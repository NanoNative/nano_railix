package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.project.CompileResult;
import dev.nanonative.railix.core.project.ProjectCompiler;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import thirdparty.production.ProductionRuntimeConcurrencySteps;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Source lifetime and response draining through installed bundles and production JARs. */
@Timeout(60)
final class GenericTriggerLifecycleE2eTest {
    @TempDir Path directory;

    @Test
    void cliCompletionDoesNotStopAnIndependentVirtualThreadSource() throws Exception {
        try (Child child = build("normal")) {
            await(() -> text(child.output()).contains("cli-finished"));
            final int port = child.port();
            assertThat(child.process().isAlive()).isTrue();
            assertThat(exchange(port, "first")).isEqualTo("\"first\"");
            assertThat(exchange(port, "second")).isEqualTo("\"second\"");
            assertThat(exchange(port, "finish")).isEqualTo("\"finish\"");
            assertThat(child.process().waitFor(10, TimeUnit.SECONDS)).isTrue();
            assertThat(child.process().exitValue()).as(text(child.output())).isEqualTo(7);
            assertThat(Files.readString(directory.resolve("closed"))).isEqualTo("closed");
            assertThat(text(child.output()).lines().filter(line -> line.equals("\"cli-finished\"")).count()).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shutdownWaitsForTransportResponseBeforeReleasingSourceResources(final boolean command) throws Exception {
        try (Child child = build("normal", command); Socket held = new Socket("127.0.0.1", child.port())) {
            held.setSoTimeout(10000);
            held.getOutputStream().write("hold\n".getBytes(StandardCharsets.UTF_8));
            held.getOutputStream().flush();
            await(() -> Files.exists(directory.resolve("responding")));
            child.process().destroy();
            await(() -> {
                try {
                    return exchange(child.port(), "new-work").equals("rejected");
                } catch (final Exception failure) {
                    throw new IllegalStateException("Listener must remain open while draining its response.", failure);
                }
            });
            assertThat(Files.exists(directory.resolve("closed"))).isFalse();
            Files.writeString(directory.resolve("release"), "release");
            assertThat(new BufferedReader(new InputStreamReader(held.getInputStream(), StandardCharsets.UTF_8))
                    .readLine()).isEqualTo("\"hold\"");
            assertThat(child.process().waitFor(10, TimeUnit.SECONDS)).isTrue();
            assertThat(Files.readString(directory.resolve("closed"))).isEqualTo("closed");
        }
    }

    @Test
    void activationFailureCleansUpPreparedSourceResources() throws Exception {
        try (Child child = build("fail")) {
            assertThat(child.process().waitFor(10, TimeUnit.SECONDS)).isTrue();
            assertThat(child.process().exitValue()).as(text(child.output())).isEqualTo(1);
            assertThat(Files.readString(directory.resolve("closed"))).isEqualTo("closed");
            assertThat(Files.exists(child.marker())).isFalse();
        }
    }

    @Test
    void developmentLaunchStartsSourcesAndExamplesBypassTriggerProcess() throws Exception {
        final Path marker = directory.resolve("source-port");
        try (var application = GeneratedApplicationFixture.start(directory, source("normal", marker),
                List.of(definition()), ProductionRuntimeConcurrencySteps.SocketSource.class)) {
            await(() -> !text(marker).isBlank());
            final int port = Integer.parseInt(text(marker));
            final var example = application.runTest("socket", "{\"result\":\"example\"}");
            assertThat(example.status()).as(example.body()).isEqualTo(200);
            assertThat(Files.exists(directory.resolve("invoked"))).isFalse();
            assertThat(exchange(port, "live")).isEqualTo("\"live\"");
            assertThat(Files.readString(directory.resolve("invoked"))).isEqualTo("live\n");
            assertThat(Integer.parseInt(text(marker))).isEqualTo(port);
        }
        assertThat(Files.readString(directory.resolve("closed"))).isEqualTo("closed");
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "true,true"})
    void failedSourceActivationDoesNotOverwriteAnInterveningExternalFileEdit(final boolean editor, final boolean metadata) throws Exception {
        final Path marker = directory.resolve("source-port");
        GeneratedApplicationFixture.installedCatalog(directory, List.of(definition()),
                ProductionRuntimeConcurrencySteps.SocketSource.class);
        final Path project = directory.resolve("railix.project.json");
        final String accepted = source("normal", marker);
        Files.writeString(project, accepted);
        try (CreatorServer creator = CreatorServer.start(0, project, directory.resolve("railix-home"));
             var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            final var current = CreatorServerE2eSupport.object(CreatorServerE2eSupport.request(
                    creator.baseUri(), "GET", "/api/project", "").body());
            final String failedSource = source("hold-fail", marker);
            final var failedProject = CreatorServerE2eSupport.object(failedSource);
            final var changedNode = ((RailixValue.ArrayValue) failedProject.values().get("nodes")).values().stream()
                    .map(RailixValue.ObjectValue.class::cast)
                    .filter(node -> RailixValue.string("socket").equals(node.values().get("id"))).findFirst().orElseThrow();
            final String body = editor ? RailixJson.write(RailixValue.object(Map.of(
                    "revision", current.values().get("revision"), "creator_revision", current.values().get("creator_revision"),
                    "command", RailixValue.object(Map.of("kind", RailixValue.string("patch"), "project", RailixValue.object(Map.of(
                            "nodes", RailixValue.object(Map.of("socket", changedNode))))))))) : failedSource;
            final var replacement = tasks.submit(() -> CreatorServerE2eSupport.request(
                    creator.baseUri(), "POST", editor ? "/api/editor" : "/api/project", body));
            final Path changedFile = metadata ? project.resolveSibling("railix.creator.json") : project;
            final String external = metadata
                    ? Files.readString(changedFile).replace("\"steps\":{}", "\"steps\":{\"message\":{\"name\":\"Outside edit\"}}")
                    : accepted.replace("source-lifetime", "edited-outside");
            try {
                await(() -> Files.exists(directory.resolve("activating")));
                Files.writeString(changedFile, external);
            } finally {
                Files.writeString(directory.resolve("activation-release"), "release");
            }
            final var response = replacement.get(20, TimeUnit.SECONDS);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(409);
            assertThat(response.body()).contains("outside Creator", "preserved", "\"recovery\":\"restarted\"");
            assertThat(Files.readString(changedFile)).isEqualTo(external);
            assertThat(directory.resolve(".railix/run/editor-transaction.json")).doesNotExist();
        }
    }

    @Test
    void failedEditorActivationPreservesPreviouslyInvalidMetadataWithoutReportingAnOutsideEdit() throws Exception {
        final Path marker = directory.resolve("source-port");
        GeneratedApplicationFixture.installedCatalog(directory, List.of(definition()),
                ProductionRuntimeConcurrencySteps.SocketSource.class);
        final Path project = directory.resolve("railix.project.json");
        Files.writeString(project, source("normal", marker));
        final Path metadata = project.resolveSibling("railix.creator.json");
        final String invalid = "{\"format\":2,\"groups\":\"invalid\",\"steps\":{}}";
        Files.writeString(metadata, invalid);
        try (CreatorServer creator = CreatorServer.start(0, project, directory.resolve("railix-home"))) {
            final var current = CreatorServerE2eSupport.object(CreatorServerE2eSupport.request(
                    creator.baseUri(), "GET", "/api/project", "").body());
            assertThat(((RailixValue.ArrayValue) current.values().get("diagnostics")).values()).isNotEmpty();
            final var changedNode = ((RailixValue.ArrayValue) CreatorServerE2eSupport.object(source("fail", marker)).values().get("nodes"))
                    .values().stream().map(RailixValue.ObjectValue.class::cast)
                    .filter(node -> RailixValue.string("socket").equals(node.values().get("id"))).findFirst().orElseThrow();
            final String body = RailixJson.write(RailixValue.object(Map.of(
                    "revision", current.values().get("revision"), "creator_revision", current.values().get("creator_revision"),
                    "command", RailixValue.object(Map.of("kind", RailixValue.string("patch"), "project", RailixValue.object(Map.of(
                            "nodes", RailixValue.object(Map.of("socket", changedNode)))))))));
            final var response = CreatorServerE2eSupport.request(creator.baseUri(), "POST", "/api/editor", body);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(503);
            assertThat(response.body()).contains("could not be activated").doesNotContain("outside Creator", "\"conflict\"");
            assertThat(Files.readString(metadata)).isEqualTo(invalid);
            assertThat(directory.resolve(".railix/run/editor-transaction.json")).doesNotExist();
        }
    }

    @Test
    void sourceActivationDoesNotBlockReadingTheApplicationStatus() throws Exception {
        final Path marker = directory.resolve("source-port");
        GeneratedApplicationFixture.installedCatalog(directory, List.of(definition()),
                ProductionRuntimeConcurrencySteps.SocketSource.class);
        final Path project = directory.resolve("railix.project.json");
        Files.writeString(project, source("normal", marker));
        try (CreatorServer creator = CreatorServer.start(0, project, directory.resolve("railix-home"));
             var tasks = Executors.newVirtualThreadPerTaskExecutor();
             var client = java.net.http.HttpClient.newHttpClient()) {
            final var replacement = tasks.submit(() -> CreatorServerE2eSupport.request(
                    creator.baseUri(), "POST", "/api/project", source("hold", marker)));
            try {
                await(() -> Files.exists(directory.resolve("activating")));
                final var status = client.send(java.net.http.HttpRequest.newBuilder(creator.baseUri().resolve("/api/application"))
                        .timeout(Duration.ofSeconds(2))
                        .header("X-Railix-Creator-Token", creator.baseUri().getRawFragment().substring("token=".length()))
                        .GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());
                assertThat(status.statusCode()).as(status.body()).isEqualTo(200);
            } finally {
                Files.writeString(directory.resolve("activation-release"), "release");
                assertThat(replacement.get(20, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            }
        }
    }

    @Test
    void failedSourceActivationRestoresTheAcceptedProjectWithItsRunningArtifact() throws Exception {
        final Path marker = directory.resolve("source-port");
        GeneratedApplicationFixture.installedCatalog(directory, List.of(definition()),
                ProductionRuntimeConcurrencySteps.SocketSource.class);
        final Path project = directory.resolve("railix.project.json");
        final String accepted = source("normal", marker);
        Files.writeString(project, accepted);
        try (CreatorServer creator = CreatorServer.start(0, project, directory.resolve("railix-home"))) {
            final String persisted = Files.readString(project);
            final var before = CreatorServerE2eSupport.application(creator.baseUri());
            final var rejected = CreatorServerE2eSupport.request(creator.baseUri(), "POST", "/api/project", source("fail", marker));

            assertThat(rejected.statusCode()).as(rejected.body()).isEqualTo(503);
            assertThat(rejected.body()).contains("\"recovery\":\"restarted\"");
            assertThat(Files.readString(project)).isEqualTo(persisted);
            final var document = CreatorServerE2eSupport.object(CreatorServerE2eSupport.request(
                    creator.baseUri(), "GET", "/api/project", "").body());
            assertThat(document.values().get("project")).isEqualTo(((RailixJson.Parsed) RailixJson.parse(accepted)).value());
            final var after = CreatorServerE2eSupport.application(creator.baseUri());
            assertThat(after.values().get("fingerprint")).isEqualTo(before.values().get("fingerprint"));
            assertThat(after.values().get("pid")).isNotEqualTo(before.values().get("pid"));
            assertThat(after.values().get("state")).isEqualTo(RailixValue.string("running"));
            assertThat(CreatorServerE2eSupport.request(creator.baseUri(), "GET", "/api/examples", "").statusCode()).isEqualTo(200);
            assertThat(exchange(Integer.parseInt(text(marker)), "live")).isEqualTo("\"live\"");

            final var repaired = CreatorServerE2eSupport.request(creator.baseUri(), "POST", "/api/project",
                    accepted.replace("cli-finished", "repaired"));
            assertThat(repaired.statusCode()).as(repaired.body()).isEqualTo(200);
            assertThat(Files.readString(project)).contains("repaired");
        }
    }

    @Test
    void creatorClosureHonorsTheApplicationDrainBudget() throws Exception {
        final Path marker = directory.resolve("source-port");
        GeneratedApplicationFixture.installedCatalog(directory, List.of(definition()),
                ProductionRuntimeConcurrencySteps.SocketSource.class);
        final Path project = directory.resolve("railix.project.json");
        Files.writeString(project, source("normal", marker));
        try (CreatorServer creator = CreatorServer.start(0, project, directory.resolve("railix-home"));
             var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            await(() -> !text(marker).isBlank());
            final int port = Integer.parseInt(text(marker));
            try (Socket held = new Socket("127.0.0.1", port)) {
                held.setSoTimeout(15000);
                held.getOutputStream().write("hold\n".getBytes(StandardCharsets.UTF_8));
                held.getOutputStream().flush();
                await(() -> Files.exists(directory.resolve("responding")));
                final var closing = tasks.submit(creator::close);
                try {
                    assertThatThrownBy(() -> closing.get(8, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);
                    assertThat(Files.exists(directory.resolve("closed"))).isFalse();
                    Files.writeString(directory.resolve("release"), "release");
                    assertThat(new BufferedReader(new InputStreamReader(held.getInputStream(), StandardCharsets.UTF_8))
                            .readLine()).isEqualTo("\"hold\"");
                    closing.get(10, TimeUnit.SECONDS);
                    assertThat(Files.readString(directory.resolve("closed"))).isEqualTo("closed");
                } finally {
                    Files.writeString(directory.resolve("release"), "release");
                }
            }
        }
    }

    private Child build(final String mode) throws Exception {
        return build(mode, false);
    }

    private Child build(final String mode, final boolean command) throws Exception {
        final Path marker = directory.resolve("source-port");
        final var catalog = GeneratedApplicationFixture.installedCatalog(directory, List.of(definition()),
                ProductionRuntimeConcurrencySteps.SocketSource.class);
        final String source = source(mode, marker);
        final Path project = directory.resolve("railix.project.json");
        Files.writeString(project, source);
        final CompileResult result = ProjectCompiler.compileApplication(source, catalog);
        assertThat(result).isInstanceOf(CompileResult.Compiled.class);
        final var artifact = ApplicationBuilder.buildProduction(project, (CompileResult.Compiled) result);
        final Path output = directory.resolve("output");
        final var arguments = new java.util.ArrayList<String>();
        arguments.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        if (command) {
            final Path home = directory.resolve("runner-home");
            final Path store = Files.createDirectories(home.resolve(".railix/artifacts"));
            try (var files = Files.list(directory.resolve("railix-home/artifacts"))) {
                for (final Path file : files.toList()) Files.copy(file, store.resolve(file.getFileName()));
            }
            arguments.addAll(List.of("-Duser.home=" + home, "-cp", System.getProperty("java.class.path"),
                    RailixMain.class.getName(), "run"));
        } else {
            arguments.addAll(List.of("-jar", artifact.jar().toString()));
        }
        arguments.add("business-argument");
        final Process process = new ProcessBuilder(arguments).directory(directory.toFile())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        return new Child(process, marker, output);
    }

    private static StepDefinition definition() {
        return StepDefinition.named("test.socket", "1").kind(StepDefinition.Kind.TRIGGER)
                .source("test.socket").receive("value", ValueShape.STRING)
                .input("marker", StepDefinition.Input.json(ValueShape.STRING))
                .input("mode", StepDefinition.Input.json(ValueShape.STRING))
                .input("target", StepDefinition.Input.path(StepDefinition.PathAccess.WRITE)
                        .defaultPath("context", "result"))
                .exampleTarget("target").result("result", ValueShape.ANY, RailixValue.nullValue())
                .response("output", "result").implementation(ProductionRuntimeConcurrencySteps.SocketSource.class);
    }

    private static String source(final String mode, final Path marker) {
        return """
                {"format":1,"id":"source-lifetime","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"cli","use":"railix.trigger.cli","inputs":{},"examples":[{"name":"default","payload":[]}]},
                  {"id":"message","use":"railix.field-manipulation","inputs":{
                    "field":["context","result"],"value":[{"option":"literal","inputs":{"literal":"cli-finished"}}],"steps":[]}},
                  {"id":"status","use":"railix.field-manipulation","inputs":{
                    "field":["context","exit_code"],"value":[{"option":"literal","inputs":{"literal":7}}],"steps":[]}},
                  {"id":"socket","use":"test.socket","inputs":{"marker":%s,"mode":"%s"},
                   "examples":[{"name":"default","payload":"example"}]}
                ],"links":[{"from":"app.start","to":"cli"},{"from":"cli.next","to":"message"},
                  {"from":"message.next","to":"status"},{"from":"status.next","to":"end"},
                  {"from":"app.start","to":"socket"},{"from":"socket.next","to":"end"}]}
                """.formatted(RailixJson.write(RailixValue.string(marker.toString())), mode);
    }

    private static String exchange(final int port, final String value) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10000);
            socket.getOutputStream().write((value + "\n").getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).readLine();
        }
    }

    private static String text(final Path file) {
        try {
            return Files.exists(file) ? Files.readString(file) : "";
        } catch (final java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    private static void await(final BooleanSupplier condition) throws InterruptedException {
        final long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private record Child(Process process, Path marker, Path output) implements AutoCloseable {
        private int port() throws InterruptedException {
            await(() -> !text(marker).isBlank());
            return Integer.parseInt(text(marker));
        }

        @Override
        public void close() throws InterruptedException {
            final var children = process.descendants().toList();
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
            assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
            children.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        }
    }
}
