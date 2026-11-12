package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.runtime.ObservationServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Timeout(90)
final class CustomStepsE2eTest extends CreatorServerE2eSupport {
    static final String METHOD = """
            public static String normalize(String text) {
                return text.strip().toLowerCase(java.util.Locale.ROOT);
            }
            """;

    @Test
    void requestValidationPreservesItsResponsesAndDoesNotPublishDrafts() throws Exception {
        final Path project = directory.resolve("railix.project.json");
        try (var creator = start(project)) {
            final String catalog = request(creator.baseUri(), "GET", "/api/catalog", "").body();
            for (final String invalid : List.of("not JSON", "[]", "{}",
                    "{\"name\":1,\"source\":\"method\"}", "{\"name\":\"Test\",\"source\":false}",
                    "{\"name\":\"Test\",\"source\":\"method\",\"extra\":true}")) {
                final var rejected = request(creator.baseUri(), "POST", "/api/custom-steps", invalid);
                assertThat(rejected.statusCode()).as(rejected.body()).isEqualTo(422);
                assertThat(rejected.body()).isEqualTo("{\"message\":\"A custom Step needs a name and Java method source.\"}");
            }
            final var encoding = request(creator.baseUri(), "POST", "/api/custom-steps", new byte[]{(byte) 0xc3, 0x28});
            assertThat(encoding.statusCode()).as(encoding.body()).isEqualTo(422);
            assertThat(encoding.body()).isEqualTo("{\"message\":\"Java source must be UTF-8.\"}");
            final var blank = request(creator.baseUri(), "POST", "/api/custom-steps", method(" ", METHOD));
            assertThat(blank.statusCode()).as(blank.body()).isEqualTo(422);
            assertThat(blank.body()).contains("\"status\":\"uncompiled\"", "CUSTOM_STEP_INVALID");
            final var oversized = request(creator.baseUri(), "POST", "/api/custom-steps",
                    " ".repeat(1_048_576) + method("Too large", METHOD));
            assertThat(oversized.statusCode()).as(oversized.body()).isEqualTo(413);
            assertThat(oversized.body()).contains("REQUEST_TOO_LARGE");
            assertThat(request(creator.baseUri(), "GET", "/api/custom-steps", "").statusCode()).isEqualTo(405);
            assertThat(request(creator.baseUri(), "GET", "/api/catalog", "").body()).isEqualTo(catalog);
            assertThat(project.resolveSibling(CustomSteps.FILE)).doesNotExist();

            final var accepted = request(creator.baseUri(), "POST", "/api/custom-steps", method("  Normalize  ", METHOD));
            assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(200);
            assertThat(accepted.body()).contains("\"display_name\":\"Normalize\"");
        }
    }

    @Test
    void onlyCompiledMethodsEnterTheCatalogWithoutRestartingTheApplication() throws Exception {
        final Path project = directory.resolve("railix.project.json");
        try (var creator = start(project)) {
            final long pid = number(application(creator.baseUri()), "pid");
            final String catalog = request(creator.baseUri(), "GET", "/api/catalog", "").body();
            final var invalid = request(creator.baseUri(), "POST", "/api/custom-steps", method("Normalize", """
                    public static String normalize(String text) {
                        return missing;
                    }
                    """));
            assertThat(invalid.statusCode()).as(invalid.body()).isEqualTo(422);
            assertThat(invalid.body()).contains("CUSTOM_STEP_COMPILE", "\"line\":2", "missing");
            assertThat(request(creator.baseUri(), "GET", "/api/catalog", "").body()).isEqualTo(catalog);
            assertThat(project.resolveSibling(CustomSteps.FILE)).doesNotExist();
            final var premature = request(creator.baseUri(), "POST", "/api/project", project());
            assertThat(premature.statusCode()).as(premature.body()).isEqualTo(422);
            assertThat(premature.body()).contains("custom.normalize");

            final var compiled = request(creator.baseUri(), "POST", "/api/custom-steps", method("Normalize", METHOD));
            assertThat(compiled.statusCode()).as(compiled.body()).isEqualTo(200);
            assertThat(compiled.body()).contains("custom.normalize", "\"shape\":\"string\"", "\"name\":\"text\"", "\"name\":\"result\"");
            final long revision = number((RailixValue.ObjectValue) object(compiled.body()).values().get("catalog"), "revision");
            assertThat(revision).isEqualTo(number(object(catalog), "revision") + 1);
            assertThat(number(object(request(creator.baseUri(), "GET", "/api/editor", "").body()), "catalog_revision")).isEqualTo(revision);
            final String saved = Files.readString(project.resolveSibling(CustomSteps.FILE));
            assertThat(saved).contains("toLowerCase");
            final var duplicate = request(creator.baseUri(), "POST", "/api/custom-steps", method("Normalize", METHOD));
            assertThat(duplicate.statusCode()).as(duplicate.body()).isEqualTo(422);
            assertThat(Files.readString(project.resolveSibling(CustomSteps.FILE))).isEqualTo(saved);
            assertThat(number(application(creator.baseUri()), "pid")).isEqualTo(pid);
        }
        try (var reopened = start(project)) {
            assertThat(request(reopened.baseUri(), "GET", "/api/catalog", "").body()).contains("custom.normalize");
        }
    }

    @Test
    void accumulatedSourceAcceptsTheDocumentLimitAndRejectsTheNextAddition() throws Exception {
        final Path project = directory.resolve("railix.project.json");
        final Path source = project.resolveSibling(CustomSteps.FILE);
        final String next = method("Next", "public static boolean next() { return true; }");
        final int additionBytes = 1 + next.getBytes(StandardCharsets.UTF_8).length;
        final String prefix = "{\"format\":1,\"steps\":[{\"name\":\"Seed\",\"source\":\"public static boolean seed() { return true; } /*";
        final String suffix = "*/\"}]}";
        final int paddingBytes = ProjectFiles.MAX_DOCUMENT_BYTES - additionBytes
                - prefix.getBytes(StandardCharsets.UTF_8).length - suffix.getBytes(StandardCharsets.UTF_8).length;
        // Multibyte source makes a character-count check insufficient.
        Files.writeString(source, prefix + "é".repeat(paddingBytes / 2) + " ".repeat(paddingBytes % 2) + suffix);
        assertThat(Files.size(source)).isEqualTo(ProjectFiles.MAX_DOCUMENT_BYTES - additionBytes);

        try (var creator = start(project)) {
            final var accepted = request(creator.baseUri(), "POST", "/api/custom-steps", next);
            assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(200);
            assertThat(Files.size(source)).isEqualTo(ProjectFiles.MAX_DOCUMENT_BYTES);
            final String catalog = request(creator.baseUri(), "GET", "/api/catalog", "").body();
            final var digest = MessageDigest.getInstance("SHA-256");
            final byte[] saved = digest.digest(Files.readAllBytes(source));

            final var rejected = request(creator.baseUri(), "POST", "/api/custom-steps", method("Overflow", METHOD));
            assertThat(rejected.statusCode()).as(rejected.body()).isEqualTo(422);
            assertThat(rejected.body()).contains("CUSTOM_STEP_INVALID", "document");
            assertThat(digest.digest(Files.readAllBytes(source))).isEqualTo(saved);
            assertThat(request(creator.baseUri(), "GET", "/api/catalog", "").body()).isEqualTo(catalog);
        }
        try (var reopened = start(project)) {
            assertThat(request(reopened.baseUri(), "GET", "/api/catalog", "").body())
                    .contains("custom.seed", "custom.next").doesNotContain("custom.overflow");
        }
    }

    @Test
    void concurrentPublicationsKeepEveryAcceptedStep() throws Exception {
        final Path project = directory.resolve("railix.project.json");
        try (var creator = start(project)) {
            final var distinct = publishTogether(creator.baseUri(), List.of(method("First", METHOD), method("Second", METHOD)));
            assertThat(distinct).allSatisfy(response -> assertThat(response.statusCode()).as(response.body()).isEqualTo(200));

            final String shared = method("Shared", METHOD);
            final var duplicate = publishTogether(creator.baseUri(), List.of(shared, shared));
            assertThat(duplicate).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(200, 422);
            final var catalog = object(request(creator.baseUri(), "GET", "/api/catalog", "").body());
            assertThat(number(catalog, "revision")).isEqualTo(3);
            final var steps = (RailixValue.ArrayValue) object(Files.readString(project.resolveSibling(CustomSteps.FILE))).values().get("steps");
            assertThat(steps.values()).hasSize(3);
        }
        try (var reopened = start(project)) {
            assertThat(request(reopened.baseUri(), "GET", "/api/catalog", "").body())
                    .contains("custom.first", "custom.second", "custom.shared");
        }
    }

    @ParameterizedTest
    @MethodSource("typedMethods")
    void supportedValuesKeepTheirTypesThroughACompiledStep(final String method, final String input,
                                                         final String expected) throws Exception {
        final Path project = directory.resolve("railix.project.json");
        try (var creator = start(project)) {
            final var compiled = request(creator.baseUri(), "POST", "/api/custom-steps", method("Typed", method));
            assertThat(compiled.statusCode()).as(compiled.body()).isEqualTo(200);
            final var placed = request(creator.baseUri(), "POST", "/api/project", """
                    {"format":1,"id":"typed-step","nodes":[
                      {"id":"app","use":"railix.app","inputs":{}},
                      {"id":"command","use":"railix.trigger.cli","inputs":{},
                        "examples":[{"id":"typed-case","name":"typed","payload":[],"context":{"value":%s}}]},
                      {"id":"typed","use":"custom.typed","inputs":{},"receives":{"value":["context","value"]},
                        "returns":{"result":["context","result"]}}
                    ],"links":[{"from":"app.start","to":"command"},{"from":"command.next","to":"typed"},
                      {"from":"typed.next","to":"end"}]}
                    """.formatted(input));
            assertThat(placed.statusCode()).as(placed.body()).isEqualTo(200);
            final var view = successfulExampleView(creator.baseUri(), "typed-case");
            final var result = (RailixValue.ObjectValue) view.values().get("result");
            final var context = (RailixValue.ObjectValue) result.values().get("context");
            assertThat(context.values().get("result")).isEqualTo(((RailixJson.Parsed) RailixJson.parse(expected)).value());
        }
    }

    static Stream<Arguments> typedMethods() {
        return Stream.of(
                Arguments.of("public static boolean transform(boolean value) { return !value; }", "true", "false"),
                Arguments.of("public static BigDecimal transform(BigDecimal value) { return value.add(new BigDecimal(\"0.1\")); }",
                        "9007199254740993.1", "9007199254740993.2"),
                Arguments.of("public static RailixValue transform(RailixValue value) { return value; }", "null", "null"),
                Arguments.of("public static RailixValue transform(RailixValue value) { return value; }",
                        "{\"items\":[true,1,\"text\"]}", "{\"items\":[true,1,\"text\"]}"));
    }

    @Test
    void multipleMethodsKeepTheirArgumentsAcrossReopeningRepeatableBuildsAndSourceRestoration() throws Exception {
        final Path project = directory.resolve("source/railix.project.json");
        Files.createDirectories(project.getParent());
        final String expected = "hello railix:ready (2 args)";
        try (var creator = start(project)) {
            final var compiled = request(creator.baseUri(), "POST", "/api/custom-steps", method("Normalize", """
                    public static String normalize(String text, String suffix) {
                        return text.strip().toLowerCase(java.util.Locale.ROOT) + suffix;
                    }
                    """));
            assertThat(compiled.statusCode()).as(compiled.body()).isEqualTo(200);
            final var second = request(creator.baseUri(), "POST", "/api/custom-steps", method("Format", """
                    public static String format(String text, RailixValue arguments) {
                        return text + " (" + ((RailixValue.ArrayValue) arguments).values().size() + " args)";
                    }
                    """));
            assertThat(second.statusCode()).as(second.body()).isEqualTo(200);
            final var placed = request(creator.baseUri(), "POST", "/api/project", """
                    {"format":1,"id":"custom-methods","nodes":[
                      {"id":"app","use":"railix.app","inputs":{}},
                      {"id":"command","use":"railix.trigger.cli","inputs":{},
                        "examples":[{"id":"arguments-case","name":"arguments","payload":["  Hello RAILIX  ",":ready"]}]},
                      {"id":"normalize","use":"custom.normalize","inputs":{},
                        "receives":{"text":["context","payload","arguments",0],"suffix":["context","payload","arguments",1]},
                        "returns":{"result":["context","normalized"]}},
                      {"id":"format","use":"custom.format","inputs":{},
                        "receives":{"text":["context","normalized"],"arguments":["context","payload","arguments"]},
                        "returns":{"result":["context","result"]}}
                    ],"links":[{"from":"app.start","to":"command"},{"from":"command.next","to":"normalize"},
                      {"from":"normalize.next","to":"format"},{"from":"format.next","to":"end"}]}
                    """);
            assertThat(placed.statusCode()).as(placed.body()).isEqualTo(200);
        }
        try (var reopened = start(project)) {
            assertThat(request(reopened.baseUri(), "GET", "/api/catalog", "").body()).contains("custom.normalize", "custom.format");
            final var result = (RailixValue.ObjectValue) successfulExampleView(reopened.baseUri(), "arguments-case").values().get("result");
            assertThat(((RailixValue.ObjectValue) result.values().get("context")).values().get("result"))
                    .isEqualTo(RailixValue.string(expected));
        }
        final var source = ProjectFiles.build(project, directory.resolve("railix-home"), true);
        final var first = ApplicationBuilder.buildProduction(project, source, false);
        final var sha256 = MessageDigest.getInstance("SHA-256");
        final byte[] original = sha256.digest(Files.readAllBytes(first.jar()));
        final var repeated = ApplicationBuilder.buildProduction(project,
                ProjectFiles.build(project, directory.resolve("railix-home"), true), false);
        assertThat(repeated.jar()).isEqualTo(first.jar());
        assertThat(sha256.digest(Files.readAllBytes(repeated.jar()))).as("Executable JAR bytes are repeatable").isEqualTo(original);
        try (var jar = new JarFile(first.jar().toFile())) {
            assertThat(jar.getJarEntry("META-INF/railix/project/" + CustomSteps.FILE)).isNotNull();
            assertThat(jar.stream().map(entry -> entry.getName())).anyMatch(name -> name.startsWith("railix/custom/"))
                    .noneMatch(name -> name.contains("CustomSteps") || name.contains("JavaStepCompiler")
                            || name.contains("StepDefinition") || name.startsWith("dev/nanonative/railix/development/"));
        }
        run(first.jar(), expected, "  Hello RAILIX  ", ":ready");
        final Path restored = directory.resolve("restored/railix.project.json");
        final var recovered = ProjectFiles.fromApplication(first.jar(), restored, directory.resolve("fresh-home"));
        assertThat(recovered.project()).isEqualTo(restored);
        assertThat(Files.readString(restored.resolveSibling(CustomSteps.FILE))).isEqualTo(Files.readString(project.resolveSibling(CustomSteps.FILE)));
        run(ApplicationBuilder.buildProduction(restored,
                ProjectFiles.build(restored, directory.resolve("fresh-home"), false), false).jar(), expected, "  Hello RAILIX  ", ":ready");
    }

    @Test
    void compiledStepCanBeInspectedFromAnEmbeddedObservedBuild() throws Exception {
        final Path project = directory.resolve("source/railix.project.json");
        final Path home = directory.resolve("home/.railix");
        Files.createDirectories(project.getParent());
        try (var creator = start(project, home)) {
            assertThat(request(creator.baseUri(), "POST", "/api/custom-steps", method("Normalize", METHOD)).statusCode()).isEqualTo(200);
            final String httpProject = project()
                    .replace("\"use\":\"railix.trigger.cli\",\"inputs\":{\"target\":[\"context\",\"payload\",\"arguments\"]}",
                            "\"use\":\"railix.trigger.http\",\"inputs\":{\"port\":0}")
                    .replace("\"payload\":[\"  Hello RAILIX  \"]", "\"payload\":{},\"context\":{\"text\":\"  Hello RAILIX  \"}")
                    .replace("[\"context\",\"payload\",\"arguments\",0]", "[\"context\",\"text\"]");
            final var placed = request(creator.baseUri(), "POST", "/api/project", httpProject);
            assertThat(placed.statusCode()).as(placed.body()).isEqualTo(200);
        }
        final Path artifact = Files.copy(ApplicationBuilder.buildProduction(project,
                ProjectFiles.build(project, home, true)).jar(), directory.resolve("running.jar"));
        final Process application = LiveApplicationE2eTest.start(artifact, home.getParent(), directory.resolve("application.log"));
        try {
            LiveApplicationE2eTest.await(() -> assertThat(ObservationServer.connections(home)).hasSize(1));
            final var connection = ObservationServer.connections(home).getFirst();
            // A different draft forces reconstruction from the running artifact's embedded source.
            try (var creator = start(directory.resolve("draft/railix.project.json"), home)) {
                final var attached = request(creator.baseUri(), "POST", "/api/live",
                        "{\"instance\":\"" + connection.instance() + "\"}");
                assertThat(attached.statusCode()).as(attached.body()).isEqualTo(200);
                final var confirmed = request(creator.baseUri(), "POST", "/api/live",
                        "{\"instance\":\"" + connection.instance() + "\",\"confirm\":true}");
                assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
                final var catalog = LiveApplicationE2eTest.readLive(creator.baseUri(), "/api/live/catalog", connection.instance());
                assertThat(catalog.statusCode()).as(catalog.body()).isEqualTo(200);
                assertThat(catalog.body()).contains("custom.normalize");
                final var editor = LiveApplicationE2eTest.readLive(creator.baseUri(), "/api/live/editor?node=normalize", connection.instance());
                assertThat(editor.statusCode()).as(editor.body()).isEqualTo(200);
                assertThat(editor.body()).contains("normalize");
            }
            assertThat(application.isAlive()).isTrue();
        } finally { ProjectSourceArtifactsE2eTest.stop(application); }
    }

    @Test
    void authoringApiReferencesStayOutOfTheRuntimeCatalog() throws Exception {
        final Path project = directory.resolve("railix.project.json");
        try (var creator = start(project)) {
            final String before = request(creator.baseUri(), "GET", "/api/catalog", "").body();
            final var rejected = request(creator.baseUri(), "POST", "/api/custom-steps", method("Authoring", """
                    public static String inspect() {
                        return dev.nanonative.railix.core.step.StepDefinition.named("example", "1").define().id();
                    }
                    """));
            assertThat(rejected.statusCode()).as(rejected.body()).isEqualTo(422);
            assertThat(rejected.body()).contains("StepDefinition", "\"line\":2");
            assertThat(request(creator.baseUri(), "GET", "/api/catalog", "").body()).isEqualTo(before);
            assertThat(project.resolveSibling(CustomSteps.FILE)).doesNotExist();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"pre", "post"})
    void methodNamesDoNotDeclareStepLifecycleHooks(final String name) throws Exception {
        final Path project = directory.resolve("railix.project.json");
        try (var creator = start(project)) {
            final var compiled = request(creator.baseUri(), "POST", "/api/custom-steps",
                    method("Normalize", METHOD.replace("normalize(", name + "(")));
            assertThat(compiled.statusCode()).as(compiled.body()).isEqualTo(200);
            final var placed = request(creator.baseUri(), "POST", "/api/project", project());
            assertThat(placed.statusCode()).as(placed.body()).isEqualTo(200);
            assertThat(awaitExampleView(creator.baseUri(), "normalize-case").body()).contains("hello railix");
        }
        run(ApplicationBuilder.buildProduction(project,
                ProjectFiles.build(project, directory.resolve("railix-home"), false), false).jar(), "hello railix", "  Hello RAILIX  ");
    }

    @Test
    void failedSourcePublicationLeavesTheCatalogUnchanged() throws Exception {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"), "Requires POSIX directory permissions");
        final Path project = directory.resolve("railix.project.json");
        try (var creator = start(project)) {
            final String catalog = request(creator.baseUri(), "GET", "/api/catalog", "").body();
            final var permissions = Files.getPosixFilePermissions(directory);
            final var readOnly = EnumSet.copyOf(permissions);
            readOnly.removeAll(List.of(PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_WRITE, PosixFilePermission.OTHERS_WRITE));
            try {
                Files.setPosixFilePermissions(directory, readOnly);
                assumeFalse(Files.isWritable(directory), "This user can bypass directory write permissions");
                final var rejected = request(creator.baseUri(), "POST", "/api/custom-steps", method("Normalize", METHOD));
                assertThat(rejected.statusCode()).as(rejected.body()).isEqualTo(500);
                try (var artifacts = Files.list(directory.resolve(".railix/custom-steps/artifacts"))) {
                    assertThat(artifacts.anyMatch(path -> path.toString().endsWith(".jar"))).as("Compilation finished before publication failed").isTrue();
                }
                assertThat(project.resolveSibling(CustomSteps.FILE)).doesNotExist();
                assertThat(request(creator.baseUri(), "GET", "/api/catalog", "").body()).isEqualTo(catalog);
            } finally { Files.setPosixFilePermissions(directory, permissions); }
        }
    }

    @Test
    void compilationReportsAdapterDiagnosticsWithoutExecutingTheMethod() throws Exception {
        final Path project = directory.resolve("railix.project.json");
        final Path marker = directory.resolve("method-ran");
        final String source = "\npublic static String check() throws java.io.IOException {\n"
                + "java.nio.file.Files.writeString(java.nio.file.Path.of(" + RailixJson.write(RailixValue.string(marker.toString()))
                + "), \"ran\"); return \"ok\";\n}";
        try (var creator = start(project)) {
            // A checked exception needs handling inside the method; compilation must report it without executing code.
            final var checked = request(creator.baseUri(), "POST", "/api/custom-steps", method("Checked", source));
            assertThat(checked.statusCode()).as(checked.body()).isEqualTo(422);
            assertThat(checked.body()).contains("Cannot adapt this method:");
            final var diagnostics = (RailixValue.ArrayValue) object(checked.body()).values().get("diagnostics");
            assertThat(diagnostics.values()).isNotEmpty().allSatisfy(value -> {
                final var issue = (RailixValue.ObjectValue) value;
                assertThat(number(issue, "line")).isEqualTo(2);
                assertThat(number(issue, "column")).isEqualTo(1);
            });
            assertThat(marker).doesNotExist();
            final var safeCompilation = request(creator.baseUri(), "POST", "/api/custom-steps", method("Never invoked", """
                    public static String check() {
                        throw new AssertionError("Compilation must not run this method");
                    }
                    """));
            assertThat(safeCompilation.statusCode()).as(safeCompilation.body()).isEqualTo(200);
            assertThat(request(creator.baseUri(), "GET", "/api/catalog", "").body()).doesNotContain("custom.checked");
        }
    }

    private static String method(final String name, final String java) {
        return RailixJson.write(RailixValue.object(Map.of("name", RailixValue.string(name), "source", RailixValue.string(java))));
    }

    private static List<HttpResponse<String>> publishTogether(final URI base, final List<String> bodies) throws Exception {
        final var ready = new CountDownLatch(bodies.size());
        final var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final List<Future<HttpResponse<String>>> pending = new ArrayList<>();
            for (final String body : bodies) pending.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                return request(base, "POST", "/api/custom-steps", body);
            }));
            try { assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); }
            finally { start.countDown(); }
            final List<HttpResponse<String>> responses = new ArrayList<>();
            for (final var request : pending) responses.add(request.get(25, TimeUnit.SECONDS));
            return List.copyOf(responses);
        }
    }

    static String project() {
        return """
                {"format":1,"id":"custom-method","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{"target":["context","payload","arguments"]},
                    "examples":[{"id":"normalize-case","name":"normalize","payload":["  Hello RAILIX  "]}]},
                  {"id":"normalize","use":"custom.normalize","inputs":{},
                    "receives":{"text":["context","payload","arguments",0]},"returns":{"result":["context","result"]}}
                ],"links":[{"from":"app.start","to":"command"},{"from":"command.next","to":"normalize"},
                  {"from":"normalize.next","to":"end"}]}
                """;
    }

    private static void run(final Path jar, final String expected, final String... arguments) throws Exception {
        final var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar", jar.toString()));
        command.addAll(List.of(arguments));
        final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            assertThat(process.waitFor(20, TimeUnit.SECONDS)).isTrue();
            final String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            assertThat(process.exitValue()).as(output).isZero();
            assertThat(output).isEqualTo(RailixJson.write(RailixValue.string(expected)));
        } finally { if (process.isAlive()) process.destroyForcibly().waitFor(); }
    }
}
