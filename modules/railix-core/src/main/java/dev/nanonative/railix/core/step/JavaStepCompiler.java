package dev.nanonative.railix.core.step;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.Trees;
import dev.nanonative.railix.core.project.Diagnostic;
import dev.nanonative.railix.core.value.ValueShape;

import javax.lang.model.element.Modifier;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Build-time Java-method adaptation shared by authoring tools; absent from generated application runtimes. */
public final class JavaStepCompiler {
    private static final String PREFIX = """
            package railix.custom;
            import java.math.BigDecimal;
            import dev.nanonative.railix.core.value.RailixValue;
            import dev.nanonative.railix.core.step.StepInput;
            import dev.nanonative.railix.core.step.StepResult;
            """;

    private JavaStepCompiler() { }

    /** One compilation either produces an ordinary bundle or explains why the source was rejected. */
    public sealed interface Result permits Compiled, Rejected { }

    /**
     * Bundle output accepted through {@link StepCatalog#installSource(String, Path)}.
     * @param definition derived version-1 contract
     * @param artifact JAR in the caller's artifact store, named by its SHA-256 content digest
     * @param lock canonical dependency lock; resolve it against the artifact's parent directory
     */
    public record Compiled(StepDefinition definition, Path artifact, String lock) implements Result {
        public Compiled {
            if (definition == null || artifact == null || lock == null || lock.isBlank())
                throw new IllegalArgumentException("Compiled Java Step output must be complete.");
            artifact = artifact.toAbsolutePath().normalize();
        }
    }

    /** Source diagnostics use one-based submitted-method locations, or zero when no location applies. */
    public record Rejected(List<Diagnostic> diagnostics) implements Result {
        public Rejected {
            if (diagnostics == null || diagnostics.isEmpty())
                throw new IllegalArgumentException("Rejected Java Step compilation must contain diagnostics.");
            diagnostics = List.copyOf(diagnostics);
        }
    }

    private sealed interface MethodInspection permits InspectedMethod, InvalidMethod { }

    private record InspectedMethod(String name, List<StepDefinition.Port> receives, ValueShape resultShape,
                                   int line, int column) implements MethodInspection {
        private InspectedMethod { receives = List.copyOf(receives); }
    }

    private record InvalidMethod(List<Diagnostic> diagnostics) implements MethodInspection { }

    private record MethodSource(String submitted, String wrapped, int lineOffset) { }

    /**
     * Compiles one public static Java method into an ordinary version-1 Step bundle.
     * Supported parameters/results are String, boolean, BigDecimal and RailixValue.
     * Compilation does not invoke the method. No project document or catalog is modified.
     *
     * @param id nonblank stable Step identifier chosen by the authoring tool
     * @param name nonblank display name
     * @param methodSource one Java method, without imports or a surrounding class
     * @param runtimeApi caller-supplied runtime classpath directory or JAR; its contents determine available APIs
     * @param outputDirectory caller-owned artifact store; only this call's staging directory is removed
     * @return a content-addressed bundle and normal dependency lock, or source diagnostics
     * @throws IllegalArgumentException if any argument is Java null
     * @throws IOException if the runtime API is missing, the JDK compiler is unavailable, or artifact I/O fails
     */
    public static Result compile(final String id, final String name, final String methodSource,
                                 final Path runtimeApi, final Path outputDirectory) throws IOException {
        if (id == null || name == null || methodSource == null || runtimeApi == null || outputDirectory == null)
            throw new IllegalArgumentException("Java Step compilation arguments cannot be Java null.");
        if (id.isBlank()) return rejected("Give the Step an identifier.", 0, 0);
        if (name.isBlank()) return rejected("Give the Step a name.", 0, 0);
        if (methodSource.isBlank()) return rejected("Enter one public static Java method.", 0, 0);
        if (!Files.isDirectory(runtimeApi) && !Files.isRegularFile(runtimeApi))
            throw new IOException("Runtime API must be an existing directory or JAR.");
        final String hash = digest(id + "\n" + name + "\n" + methodSource);
        final String simpleName = "Step_" + hash.substring(0, 32);
        final String methodClass = "Method_" + hash.substring(0, 32);
        final String className = "railix.custom." + simpleName;
        final Path root = outputDirectory.toAbsolutePath().normalize();
        Files.createDirectories(root);
        final Path temporary = Files.createTempDirectory(root, ".compiling-");
        try {
            final var compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) throw new IOException("Java Step compilation requires the JDK Java compiler.");
            final Path source = temporary.resolve(simpleName + ".java");
            final MethodSource wrappedMethod = wrapMethod(methodSource, methodClass);
            Files.writeString(source, wrappedMethod.wrapped());
            final var inspection = inspectMethod(compiler, source, wrappedMethod, methodClass);
            if (inspection instanceof InvalidMethod invalid) return new Rejected(invalid.diagnostics());
            final var method = (InspectedMethod) inspection;
            Files.writeString(source, adapterSource(wrappedMethod, simpleName, methodClass, method));
            final Path classes = Files.createDirectory(temporary.resolve("classes"));
            final var compilationFailure = compileAdapter(compiler, source, classes, runtimeApi, wrappedMethod, method);
            if (compilationFailure.isPresent()) return compilationFailure.orElseThrow();
            // Define after javac so duplicate parameter names remain source diagnostics.
            final StepDefinition definition = stepDefinition(id, name, method, className);
            return writeBundle(root, temporary, classes, definition);
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (final Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static MethodSource wrapMethod(final String submitted, final String methodClass) {
        final String header = PREFIX + "final class " + methodClass + " {\n";
        return new MethodSource(submitted, header + submitted + "\n}\n", (int) header.lines().count());
    }

    private static MethodInspection inspectMethod(final JavaCompiler compiler, final Path source,
                                                   final MethodSource methodSource, final String methodClass) throws IOException {
        final var issues = new DiagnosticCollector<JavaFileObject>();
        try (var files = compiler.getStandardFileManager(issues, Locale.ROOT, StandardCharsets.UTF_8)) {
            final JavacTask task = (JavacTask) compiler.getTask(null, files, issues,
                    List.of("-proc:none"), null, files.getJavaFileObjects(source));
            final var tree = task.parse().iterator().next();
            if (hasErrors(issues)) return new InvalidMethod(diagnostics(issues, methodSource, 1, 1));
            return inspectDeclaration(tree, Trees.instance(task).getSourcePositions(), methodSource, methodClass);
        }
    }

    /** Validates parsed syntax and derives ports without invoking the compiler or accessing files. */
    private static MethodInspection inspectDeclaration(final CompilationUnitTree tree, final SourcePositions positions,
                                                       final MethodSource methodSource, final String methodClass) {
        if (tree.getTypeDecls().size() != 1 || !(tree.getTypeDecls().getFirst() instanceof ClassTree type)
                || !type.getSimpleName().contentEquals(methodClass) || type.getMembers().size() != 1
                || !(type.getMembers().getFirst() instanceof MethodTree method))
            return invalidMethod("Enter one method, without a surrounding class, imports or fields.", 0, 0);
        final long position = positions.getStartPosition(tree, method);
        final int line = (int) tree.getLineMap().getLineNumber(position) - methodSource.lineOffset();
        final int column = (int) tree.getLineMap().getColumnNumber(position);
        if (!method.getModifiers().getFlags().containsAll(Set.of(Modifier.PUBLIC, Modifier.STATIC))
                || method.getBody() == null || !method.getTypeParameters().isEmpty())
            return invalidMethod("The method must be public static, have a body and use no generic type parameters.", line, column);
        final var resultShape = method.getReturnType() == null ? Optional.<ValueShape>empty() : supportedShape(method.getReturnType());
        if (resultShape.isEmpty())
            return invalidMethod("Return String, boolean, BigDecimal or RailixValue.", line, column);
        final List<StepDefinition.Port> receives = new ArrayList<>();
        for (final var parameter : method.getParameters()) {
            final var parameterShape = supportedShape(parameter.getType());
            if (parameterShape.isEmpty())
                return invalidMethod("Parameter " + parameter.getName() + " must use String, boolean, BigDecimal or RailixValue.", line, column);
            receives.add(new StepDefinition.Port(parameter.getName().toString(), parameterShape.orElseThrow()));
        }
        return new InspectedMethod(method.getName().toString(), receives, resultShape.orElseThrow(), line, column);
    }

    private static String adapterSource(final MethodSource source, final String adapterClass,
                                        final String methodClass, final InspectedMethod method) {
        final String arguments = String.join(", ", method.receives().stream().map(JavaStepCompiler::inputExpression).toList());
        final String call = methodClass + "." + method.name() + "(" + arguments + ")";
        return source.wrapped() + """
                public final class %s {
                public static StepResult process(StepInput configuration, StepInput input) throws InterruptedException {
                return StepResult.outcome(input.primaryOutcome()).output("result", %s);
                }
                }
                """.formatted(adapterClass, outputExpression(method.resultShape(), call));
    }

    private static String inputExpression(final StepDefinition.Port port) {
        return switch (port.shape()) {
            case STRING -> "input.string(\"" + port.name() + "\")";
            case BOOLEAN -> "((RailixValue.BooleanValue) input.value(\"" + port.name() + "\")).value()";
            case NUMBER -> "((RailixValue.NumberValue) input.value(\"" + port.name() + "\")).value()";
            default -> "input.value(\"" + port.name() + "\")";
        };
    }

    private static String outputExpression(final ValueShape shape, final String call) {
        return switch (shape) {
            case STRING -> "RailixValue.string(" + call + ")";
            case BOOLEAN -> "RailixValue.bool(" + call + ")";
            case NUMBER -> "RailixValue.number(" + call + ")";
            default -> call;
        };
    }

    private static Optional<Rejected> compileAdapter(final JavaCompiler compiler, final Path source, final Path classes,
                                                     final Path runtimeApi, final MethodSource methodSource,
                                                     final InspectedMethod method) throws IOException {
        final var issues = new DiagnosticCollector<JavaFileObject>();
        try (var files = compiler.getStandardFileManager(issues, Locale.ROOT, StandardCharsets.UTF_8)) {
            final boolean success = Boolean.TRUE.equals(compiler.getTask(null, files, issues,
                    List.of("-proc:none", "-g:none", "-encoding", "UTF-8",
                            "--release", Integer.toString(Runtime.version().feature()),
                            "-classpath", runtimeApi.toAbsolutePath().normalize().toString(), "-d", classes.toString()),
                    null, files.getJavaFileObjects(source)).call());
            return success ? Optional.empty() : Optional.of(new Rejected(diagnostics(issues, methodSource, method.line(), method.column())));
        }
    }

    private static InvalidMethod invalidMethod(final String message, final int line, final int column) {
        return new InvalidMethod(List.of(new Diagnostic("CUSTOM_STEP_INVALID", message, "source", line, column)));
    }

    private static StepDefinition stepDefinition(final String id, final String name, final InspectedMethod method,
                                                  final String className) {
        final var builder = StepDefinition.named(id, "1").displayName(name);
        method.receives().forEach(port -> builder.receive(port.name(), port.shape()));
        return builder.returns("result", method.resultShape())
                .implementedBy(className, className.replace('.', '/') + ".class", List.of());
    }

    private static Compiled writeBundle(final Path root, final Path temporary, final Path classes,
                                        final StepDefinition definition) throws IOException {
        final Path jar = StepBundle.write(classes, temporary.resolve("step.jar"), List.of(definition));
        final String lock = StepCatalog.bundleLock(jar, root);
        final String hash = digest(Files.readAllBytes(jar));
        return new Compiled(definition, root.resolve(hash + ".jar"), lock);
    }

    private static Optional<ValueShape> supportedShape(final Tree type) {
        return switch (type.toString()) {
            case "String", "java.lang.String" -> Optional.of(ValueShape.STRING);
            case "boolean" -> Optional.of(ValueShape.BOOLEAN);
            case "BigDecimal", "java.math.BigDecimal" -> Optional.of(ValueShape.NUMBER);
            case "RailixValue", "dev.nanonative.railix.core.value.RailixValue" -> Optional.of(ValueShape.ANY);
            default -> Optional.empty();
        };
    }

    private static boolean hasErrors(final DiagnosticCollector<JavaFileObject> diagnostics) {
        return diagnostics.getDiagnostics().stream().anyMatch(value -> value.getKind() == javax.tools.Diagnostic.Kind.ERROR);
    }

    private static List<Diagnostic> diagnostics(final DiagnosticCollector<JavaFileObject> diagnostics,
                                                final MethodSource source, final int methodLine, final int methodColumn) {
        final long lines = source.submitted().lines().count();
        return diagnostics.getDiagnostics().stream().filter(value -> value.getKind() == javax.tools.Diagnostic.Kind.ERROR)
                .map(value -> {
                    final long line = value.getLineNumber() - source.lineOffset();
                    final boolean inMethod = line >= 1 && line <= lines;
                    return new Diagnostic("CUSTOM_STEP_COMPILE",
                            (inMethod ? "" : "Cannot adapt this method: ") + value.getMessage(Locale.ROOT), "source",
                            inMethod ? (int) line : methodLine, inMethod ? (int) value.getColumnNumber() : methodColumn);
                })
                .toList();
    }

    private static Rejected rejected(final String message, final int line, final int column) {
        return new Rejected(List.of(new Diagnostic("CUSTOM_STEP_INVALID", message, "source", line, column)));
    }

    private static String digest(final String text) { return digest(text.getBytes(StandardCharsets.UTF_8)); }
    private static String digest(final byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (final NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is required.", unavailable);
        }
    }
}
