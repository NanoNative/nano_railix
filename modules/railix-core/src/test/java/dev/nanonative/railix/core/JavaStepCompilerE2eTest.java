package dev.nanonative.railix.core;

import dev.nanonative.railix.core.step.JavaStepCompiler;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.value.ValueShape;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(60)
final class JavaStepCompilerE2eTest {
    private static final String METHOD = """
            public static String describe(String text, boolean enabled, BigDecimal amount, RailixValue metadata) {
                return text.strip().toLowerCase(java.util.Locale.ROOT) + ":" + enabled + ":" + amount.toPlainString()
                    + ":" + ((RailixValue.StringValue) ((RailixValue.ObjectValue) metadata).values().get("tag")).value();
            }
            """;

    @TempDir
    Path directory;

    @Test
    void anotherAuthoringToolCompilesAndExecutesAnOrdinaryBundleWithoutCreator() throws Exception {
        final Path api = runtimeApi();
        final Path client = Files.createDirectory(directory.resolve("client"));
        final Path store = Files.createDirectory(directory.resolve("bundles"));
        final Path method = Files.writeString(directory.resolve("method.java"), METHOD);
        Files.writeString(store.resolve("keep.txt"), "caller-owned");
        compileClient(client, api.toString(), "OtherAuthoringTool", """
                import java.nio.file.*;
                import dev.nanonative.railix.core.step.*;
                public final class OtherAuthoringTool {
                    public static void main(String[] args) throws Exception {
                        var result = JavaStepCompiler.compile("external.describe", "Describe", Files.readString(Path.of(args[2])),
                            Path.of(args[0]), Path.of(args[1]));
                        if (!(result instanceof JavaStepCompiler.Compiled bundle)) throw new AssertionError(result);
                        Files.writeString(Path.of(args[1], "dependencies.json"), bundle.lock());
                        System.out.print(bundle.definition().id());
                    }
                }
                """);
        // The child process receives only core and this independent client, without Creator or stdlib.
        assertThat(run(api + File.pathSeparator + client, "OtherAuthoringTool", api.toString(), store.toString(), method.toString()))
                .isEqualTo("external.describe");
        final var catalog = StepCatalog.of().install(store.resolve("dependencies.json"), store);
        final var definition = catalog.find("external.describe").orElseThrow();
        assertThat(definition.receives()).extracting(port -> port.name())
                .containsExactly("text", "enabled", "amount", "metadata");
        assertThat(definition.receives()).extracting(port -> port.shape())
                .containsExactly(ValueShape.STRING, ValueShape.BOOLEAN, ValueShape.NUMBER, ValueShape.ANY);
        assertThat(definition.returns()).singleElement().satisfies(port -> {
            assertThat(port.name()).isEqualTo("result");
            assertThat(port.shape()).isEqualTo(ValueShape.STRING);
        });
        final var implementation = catalog.implementation(definition.id()).orElseThrow();
        final Path artifact = implementation.artifacts().getFirst().path();
        final byte[] original = Files.readAllBytes(artifact);
        final var repeated = (JavaStepCompiler.Compiled) JavaStepCompiler.compile(
                definition.id(), definition.displayName(), METHOD, api, directory.resolve("repeat"));
        assertThat(Files.readAllBytes(repeated.artifact())).isEqualTo(original);
        compileClient(client, api + File.pathSeparator + artifact, "RunStep", """
                import java.util.Map;
                import java.math.BigDecimal;
                import dev.nanonative.railix.core.step.*;
                import dev.nanonative.railix.core.value.*;
                public final class RunStep {
                    public static void main(String[] args) throws Exception {
                        var configuration = new StepInput(Map.of(), Map.of(), Map.of(), Map.of(), "next");
                        var input = new StepInput(Map.of("text", RailixValue.string(" HELLO "),
                            "enabled", RailixValue.bool(true), "amount", RailixValue.number(new BigDecimal("9007199254740993.1")),
                            "metadata", RailixValue.object(Map.of("tag", RailixValue.string("external")))),
                            Map.of(), Map.of(), Map.of(), "next");
                        var result = %s.process(configuration, input);
                        System.out.print(RailixJson.write(result.outputs().get("result")));
                    }
                }
                """.formatted(implementation.className()));
        assertThat(run(api + File.pathSeparator + artifact + File.pathSeparator + client, "RunStep"))
                .isEqualTo("\"hello:true:9007199254740993.1:external\"");
        assertThat(Files.readString(store.resolve("keep.txt"))).isEqualTo("caller-owned");
        try (var files = Files.list(store)) {
            assertThat(files.map(path -> path.getFileName().toString()))
                    .containsExactlyInAnyOrder("keep.txt", "dependencies.json", artifact.getFileName().toString());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "public static String convert(String text) { return missing; }",
            "public String convert(String text) { return text; }",
            "public static Object convert(String text) { return text; }",
            "public static String convert(Object value) { return value.toString(); }",
            "public static String convert(String text, String text) { return text; }",
            "public static <T> String convert(String text) { return text; }",
            "static { System.exit(1); } public static String convert() { return \"bad\"; }",
            "public static String convert(String text) {"
    })
    void rejectedMethodsLeaveTheCallerWorkspaceIntact(final String source) throws Exception {
        final Path output = Files.createDirectory(directory.resolve("output"));
        Files.writeString(output.resolve("keep.txt"), "caller-owned");
        assertThat(JavaStepCompiler.compile("external.invalid", "Invalid", source, runtimeApi(), output))
                .isInstanceOfSatisfying(JavaStepCompiler.Rejected.class, result ->
                        assertThat(result.diagnostics()).isNotEmpty());
        try (var files = Files.list(output)) {
            assertThat(files.map(path -> path.getFileName().toString())).containsExactly("keep.txt");
        }
    }

    @Test
    void fullyQualifiedTypesKeepTheSamePortShapes() throws Exception {
        final String source = METHOD
                .replace("String describe(String text", "java.lang.String describe(java.lang.String text")
                .replace("BigDecimal amount", "java.math.BigDecimal amount")
                .replace("RailixValue metadata", "dev.nanonative.railix.core.value.RailixValue metadata");
        final var compiled = (JavaStepCompiler.Compiled) JavaStepCompiler.compile(
                "external.qualified", "Qualified", source, runtimeApi(), directory.resolve("bundles"));
        final var definition = StepCatalog.of().installSource(compiled.lock(), compiled.artifact().getParent())
                .find("external.qualified").orElseThrow();
        assertThat(definition.receives()).extracting(port -> port.shape())
                .containsExactly(ValueShape.STRING, ValueShape.BOOLEAN, ValueShape.NUMBER, ValueShape.ANY);
        assertThat(definition.returns()).singleElement().satisfies(port -> assertThat(port.shape()).isEqualTo(ValueShape.STRING));
    }

    @Test
    void adapterDiagnosticsReferToTheSubmittedMethodDeclaration() throws Exception {
        assertThat(JavaStepCompiler.compile("external.checked", "Checked",
                "\npublic static String checked() throws java.io.IOException { return \"ok\"; }",
                runtimeApi(), directory.resolve("output")))
                .isInstanceOfSatisfying(JavaStepCompiler.Rejected.class, rejected ->
                        assertThat(rejected.diagnostics()).isNotEmpty().allSatisfy(issue -> {
                            assertThat(issue.line()).isEqualTo(2);
                            assertThat(issue.column()).isEqualTo(1);
                            assertThat(issue.message()).startsWith("Cannot adapt this method:");
                        }));
    }

    @Test
    void blankIdentityNameAndSourceAreRejectedBeforeCreatingArtifacts() throws Exception {
        final Path output = directory.resolve("output");
        for (final var input : List.of(List.of(" ", "Name", METHOD), List.of("external.valid", " ", METHOD),
                List.of("external.valid", "Name", " "))) {
            assertThat(JavaStepCompiler.compile(input.get(0), input.get(1), input.get(2), runtimeApi(), output))
                    .isInstanceOf(JavaStepCompiler.Rejected.class);
        }
        assertThat(output).doesNotExist();
    }

    @Test
    void missingRuntimeApiIsAnIoFailureWithoutChangingTheOutputDirectory() {
        final Path output = directory.resolve("output");
        assertThatThrownBy(() -> JavaStepCompiler.compile("external.valid", "Valid", METHOD,
                directory.resolve("missing-api.jar"), output)).isInstanceOf(IOException.class);
        assertThat(output).doesNotExist();
    }

    private static Path runtimeApi() throws Exception {
        return Path.of(JavaStepCompiler.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    private static void compileClient(final Path directory, final String classpath, final String name,
                                      final String source) throws IOException {
        final Path file = Files.writeString(directory.resolve(name + ".java"), source);
        final var errors = new ByteArrayOutputStream();
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, errors,
                "-proc:none", "-classpath", classpath, "-d", directory.toString(), file.toString()))
                .as(errors.toString(StandardCharsets.UTF_8)).isZero();
    }

    private static String run(final String classpath, final String main, final String... arguments) throws Exception {
        final var command = new java.util.ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", classpath, main));
        command.addAll(List.of(arguments));
        final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            assertThat(process.waitFor(25, TimeUnit.SECONDS)).isTrue();
            final String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(process.exitValue()).as(output).isZero();
            return output;
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor();
        }
    }
}
