package dev.nanonative.railix.core.step;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LineMap;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import dev.nanonative.railix.core.project.Diagnostic;
import dev.nanonative.railix.core.util.Sha256;
import dev.nanonative.railix.core.value.ValueShape;

import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Build-time Java-method adaptation shared by authoring tools; absent from generated application runtimes. */
public final class JavaStepCompiler {
    private static final List<String> DYNAMIC_PACKAGES = List.of("java.lang.reflect.", "java.lang.invoke.", "java.lang.foreign.",
            "java.lang.instrument.", "javax.tools.", "javax.script.");
    private static final Set<String> DYNAMIC_TYPES = Set.of("java.lang.ClassLoader", "java.util.ResourceBundle", "java.util.ServiceLoader",
            "java.io.ObjectInputStream", "java.io.ObjectOutputStream", "java.io.ObjectStreamClass", "java.io.ObjectStreamField",
            "sun.misc.Unsafe", "jdk.internal.misc.Unsafe");
    private static final Set<String> DYNAMIC_CLASS_MEMBERS = Set.of("forName", "newInstance", "arrayType", "getClassLoader",
            "getResource", "getResourceAsStream", "getConstructor", "getConstructors", "getMethod", "getMethods", "getField", "getFields",
            "getClasses", "getRecordComponents", "getPermittedSubclasses", "getEnclosingClass", "getEnclosingMethod", "getEnclosingConstructor",
            "getNestHost", "getNestMembers", "getSigners", "getTypeParameters", "isAnnotationPresent");
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

    /** Authoring-time restrictions on resolved references in the submitted source, not called library internals. */
    public enum SourcePolicy {
        /** Accept the Java APIs supplied by the caller's runtime classpath and JDK. */
        UNRESTRICTED,
        /** Reject known reflection, loading, resource, serialization and native-access APIs. Not Native Image certification. */
        REJECT_DYNAMIC_APIS
    }

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
                                   int line, int column, LineMap lines) implements MethodInspection {
        private InspectedMethod { receives = List.copyOf(receives); }
    }

    private record InvalidMethod(List<Diagnostic> diagnostics) implements MethodInspection { }

    private record MethodSource(String submitted, String wrapped, int lineOffset) { }

    /**
     * Compiles one public static Java method into an ordinary version-1 Step bundle.
     * Supported parameters/results are String, boolean, BigDecimal and RailixValue.
     * Compilation does not invoke the method. No project document or catalog is modified.
     * This overload uses {@link SourcePolicy#UNRESTRICTED}.
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
        return compile(id, name, methodSource, runtimeApi, outputDirectory, SourcePolicy.UNRESTRICTED);
    }

    /**
     * Compiles a method with an explicit source-API policy; other behavior is defined by
     * {@link #compile(String, String, String, Path, Path)}. Policy rejection returns located diagnostics
     * before a bundle is published. The policy does not inspect called library implementations.
     *
     * @param id nonblank stable Step identifier chosen by the authoring tool
     * @param name nonblank display name
     * @param methodSource one Java method, without imports or a surrounding class
     * @param runtimeApi caller-supplied runtime classpath directory or JAR
     * @param outputDirectory caller-owned artifact store
     * @param policy restrictions applied to resolved references in the submitted source
     * @return an ordinary Step bundle or source diagnostics
     * @throws IllegalArgumentException if any argument is Java null
     * @throws IOException if the runtime API/compiler is unavailable or artifact I/O fails
     */
    public static Result compile(final String id, final String name, final String methodSource,
                                 final Path runtimeApi, final Path outputDirectory, final SourcePolicy policy) throws IOException {
        if (id == null || name == null || methodSource == null || runtimeApi == null || outputDirectory == null || policy == null)
            throw new IllegalArgumentException("Java Step compilation arguments cannot be Java null.");
        if (id.isBlank()) return rejected("Give the Step an identifier.", 0, 0);
        if (name.isBlank()) return rejected("Give the Step a name.", 0, 0);
        if (methodSource.isBlank()) return rejected("Enter one public static Java method.", 0, 0);
        if (!Files.isDirectory(runtimeApi) && !Files.isRegularFile(runtimeApi))
            throw new IOException("Runtime API must be an existing directory or JAR.");
        final String hash = Sha256.hexDigest(id + "\n" + name + "\n" + methodSource);
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
            final var compilationFailure = compileAdapter(compiler, source, classes, runtimeApi, wrappedMethod, method, policy);
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

    /** Checks the Railix method contract and derives ports from javac's parsed AST. */
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
        return new InspectedMethod(method.getName().toString(), receives, resultShape.orElseThrow(), line, column, tree.getLineMap());
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
                                                     final InspectedMethod method, final SourcePolicy policy) throws IOException {
        final var issues = new DiagnosticCollector<JavaFileObject>();
        try (var files = compiler.getStandardFileManager(issues, Locale.ROOT, StandardCharsets.UTF_8)) {
            final JavacTask task = (JavacTask) compiler.getTask(null, files, issues,
                    List.of("-proc:none", "-g:none", "-encoding", "UTF-8",
                            "--release", Integer.toString(Runtime.version().feature()),
                            "-classpath", runtimeApi.toAbsolutePath().normalize().toString(), "-d", classes.toString()),
                    null, files.getJavaFileObjects(source));
            final var units = task.parse();
            task.analyze();
            if (hasErrors(issues)) return Optional.of(new Rejected(diagnostics(issues, methodSource, method.line(), method.column())));
            if (policy == SourcePolicy.REJECT_DYNAMIC_APIS) {
                for (final CompilationUnitTree unit : units) {
                    final var inspection = new DynamicApiInspection(unit, Trees.instance(task), methodSource, method.lines());
                    inspection.scan(unit, null);
                    if (!inspection.diagnostics.isEmpty()) return Optional.of(new Rejected(inspection.diagnostics));
                }
            }
            task.generate();
            return hasErrors(issues) ? Optional.of(new Rejected(diagnostics(issues, methodSource, method.line(), method.column())))
                    : Optional.empty();
        }
    }

    private static final class DynamicApiInspection extends TreePathScanner<Void, Void> {
        private final CompilationUnitTree unit;
        private final Trees trees;
        private final MethodSource source;
        private final LineMap lines;
        private final List<Diagnostic> diagnostics = new ArrayList<>();

        private DynamicApiInspection(final CompilationUnitTree unit, final Trees trees, final MethodSource source, final LineMap lines) {
            this.unit = unit;
            this.trees = trees;
            this.source = source;
            // The initial parse retains locations; the adapter's -g:none parse may omit its line map.
            this.lines = lines;
        }

        @Override
        public Void scan(final Tree tree, final Void unused) {
            return diagnostics.isEmpty() ? super.scan(tree, unused) : null;
        }

        @Override
        public Void visitIdentifier(final IdentifierTree tree, final Void unused) {
            inspect(tree);
            return super.visitIdentifier(tree, unused);
        }

        @Override
        public Void visitMemberSelect(final MemberSelectTree tree, final Void unused) {
            inspect(tree);
            return super.visitMemberSelect(tree, unused);
        }

        @Override
        public Void visitMemberReference(final MemberReferenceTree tree, final Void unused) {
            inspect(tree);
            return super.visitMemberReference(tree, unused);
        }

        @Override
        public Void visitMethod(final MethodTree tree, final Void unused) {
            if (tree.getModifiers().getFlags().contains(Modifier.NATIVE)) reject(tree, "native method declaration");
            return super.visitMethod(tree, unused);
        }

        private void inspect(final Tree tree) {
            final Element element = trees.getElement(getCurrentPath());
            Element owner = element;
            while (owner != null && !(owner instanceof TypeElement)) owner = owner.getEnclosingElement();
            if (!(owner instanceof TypeElement type)) return;
            if (dynamicType(type) || element instanceof ExecutableElement && dynamicMember(type, element.getSimpleName().toString())) {
                reject(tree, type.getQualifiedName() + (element instanceof TypeElement ? "" : "." + element.getSimpleName()));
            }
        }

        private void reject(final Tree tree, final String api) {
            final long position = trees.getSourcePositions().getStartPosition(unit, tree);
            if (position < 0 || !diagnostics.isEmpty()) return;
            final int line = (int) lines.getLineNumber(position) - source.lineOffset();
            if (line < 1 || line > source.submitted().lines().count()) return;
            diagnostics.add(new Diagnostic("CUSTOM_STEP_DYNAMIC_API", "Dynamic API " + api + " is not supported in this custom Step.",
                    "source", line, (int) lines.getColumnNumber(position)));
        }
    }

    private static boolean dynamicType(final TypeElement type) {
        final String name = type.getQualifiedName().toString();
        if (DYNAMIC_PACKAGES.stream().anyMatch(name::startsWith) || DYNAMIC_TYPES.contains(name)) return true;
        return type.getSuperclass() instanceof DeclaredType superclass && superclass.asElement() instanceof TypeElement parent
                && dynamicType(parent);
    }

    private static boolean dynamicMember(final TypeElement type, final String name) {
        return switch (type.getQualifiedName().toString()) {
            case "java.lang.Class" -> name.startsWith("getDeclared") || name.startsWith("getGeneric")
                    || name.startsWith("getAnnotation") || name.startsWith("getAnnotated")
                    || DYNAMIC_CLASS_MEMBERS.contains(name);
            case "java.lang.Module" -> name.equals("getResourceAsStream");
            case "java.lang.System", "java.lang.Runtime" -> name.equals("load") || name.equals("loadLibrary");
            default -> false;
        };
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
        final String hash = Sha256.hexDigest(jar);
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
}
