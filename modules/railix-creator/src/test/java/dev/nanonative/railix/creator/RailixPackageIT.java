package dev.nanonative.railix.creator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class RailixPackageIT {
    private static final Path REPOSITORY = Path.of("..", "..").toAbsolutePath().normalize();
    private static final Path LAUNCHER = REPOSITORY.resolve("railix");
    private static final Path EXAMPLE = REPOSITORY.resolve("examples/lowercase-app");
    private static final Path FILE_EXAMPLE = REPOSITORY.resolve("examples/file-read-app");
    private static final Path PERSISTENCE_EXAMPLE =
            REPOSITORY.resolve("examples/file-persistence-app");
    private static final Path HTTP_EXAMPLE = REPOSITORY.resolve("examples/http-get-app");
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Test
    void packagedLauncherRunsTheLowercaseApp() throws Exception {
        assertThat(run(List.of("run", EXAMPLE.toString()), Map.of())).isEqualTo(new Result(
                0,
                "{\"text\":\"hello railix\"}\n",
                ""
        ));
    }

    @Test
    void packagedLauncherRunsTheCommittedFileReadApp() throws Exception {
        assertThat(run(List.of("run", FILE_EXAMPLE.toString()), Map.of())).isEqualTo(new Result(
                0,
                "{\"value\":{\"active\":true,\"name\":\"Railix\"}}\n",
                ""
        ));
    }

    @Test
    void packagedLauncherRunsTheCommittedFilePersistenceAppWithoutResidue() throws Exception {
        final Path value = PERSISTENCE_EXAMPLE.resolve("value.json");
        Files.deleteIfExists(value);
        try {
            assertThat(run(List.of("run", PERSISTENCE_EXAMPLE.toString()), Map.of()))
                    .isEqualTo(new Result(
                            0,
                            "{\"value\":{\"active\":true,\"name\":\"Railix\"}}\n",
                            ""
                    ));
            assertThat(value).doesNotExist();
            try (var files = Files.list(PERSISTENCE_EXAMPLE)) {
                assertThat(files.map(path -> path.getFileName().toString())
                        .filter(name -> name.contains(".railix-"))
                        .toList()).isEmpty();
            }
        } finally {
            Files.deleteIfExists(value);
        }
    }

    @Test
    void packagedLauncherRunsTheCommittedHttpFlow(@TempDir final Path temporary) throws Exception {
        try (LocalJsonHttpServer endpoint =
                     new LocalJsonHttpServer("{\"active\":true,\"name\":\"Railix\"}")) {
            Files.copy(
                    HTTP_EXAMPLE.resolve("railix.flow.json"),
                    temporary.resolve("railix.flow.json")
            );
            Files.writeString(
                    temporary.resolve("input.json"),
                    "{\"url\":\"" + endpoint.url("/value") + "\",\"headers\":{}}"
            );

            assertThat(run(List.of("run", temporary.toString()), Map.of())).isEqualTo(new Result(
                    0,
                    Files.readString(HTTP_EXAMPLE.resolve("expected-output.json")),
                    ""
            ));
        }
    }

    @Test
    void packagedLauncherRejectsUnknownCommand() throws Exception {
        assertThat(run(List.of("unknown"), Map.of())).isEqualTo(new Result(
                2,
                "",
                "Unknown command: unknown\n"
                        + "Usage: railix run <app-directory> | railix creator [--port <1-65535>]\n"
        ));
    }

    @Test
    void packagedLauncherRejectsIncompleteHttpTrigger(@TempDir final Path temporary) throws Exception {
        assertThat(runUnsupportedTrigger(temporary, "http")).isEqualTo(new Result(
                2,
                "",
                "{\"diagnostics\":[{\"code\":\"FLOW_TRIGGER_HTTP_PORT_REQUIRED\","
                        + "\"column\":0,\"line\":0,\"message\":\"HTTP trigger config field port "
                        + "must be an integer.\",\"path\":\"triggers[0].config.port\"}],"
                        + "\"status\":\"compile-rejected\"}\n"
        ));
    }

    @Test
    void packagedLauncherRejectsIncompleteSocketTrigger(@TempDir final Path temporary) throws Exception {
        assertThat(runUnsupportedTrigger(temporary, "socket")).isEqualTo(new Result(
                2,
                "",
                "{\"diagnostics\":[{\"code\":\"FLOW_TRIGGER_SOCKET_PORT_REQUIRED\","
                        + "\"column\":0,\"line\":0,\"message\":\"Socket trigger config field port "
                        + "must be an integer.\",\"path\":\"triggers[0].config.port\"}],"
                        + "\"status\":\"compile-rejected\"}\n"
        ));
    }

    @Test
    void packagedLauncherRejectsIncompleteScheduledTrigger(@TempDir final Path temporary) throws Exception {
        assertThat(runUnsupportedTrigger(temporary, "scheduled")).isEqualTo(new Result(
                2,
                "",
                "{\"diagnostics\":[{\"code\":\"FLOW_TRIGGER_SCHEDULED_INTERVAL_REQUIRED\","
                        + "\"column\":0,\"line\":0,\"message\":\"Scheduled trigger config field "
                        + "intervalMillis must be an integer.\","
                        + "\"path\":\"triggers[0].config.intervalMillis\"}],"
                        + "\"status\":\"compile-rejected\"}\n"
        ));
    }

    @Test
    void packagedLauncherRejectsArbitraryTriggerWithoutFallback(@TempDir final Path temporary) throws Exception {
        assertThat(runUnsupportedTrigger(temporary, "custom")).isEqualTo(unsupportedTrigger("custom"));
    }

    @Test
    void packagedLauncherReportsMissingJarWithoutBuildingImplicitly() throws Exception {
        final Path missing = REPOSITORY.resolve("missing-railix.jar");

        assertThat(run(List.of("--help"), Map.of("RAILIX_JAR", missing.toString()))).isEqualTo(new Result(
                4,
                "",
                "{\"error\":{\"code\":\"PACKAGE_MISSING\","
                        + "\"message\":\"Build with mvn -q -pl modules/railix-creator -am verify\"},"
                        + "\"status\":\"launcher-rejected\"}\n"
        ));
    }

    @Test
    void packagedCreatorStartsServesHealthAndTerminates() throws Exception {
        final int port = availablePort();

        assertThat(observeCreator(port, healthRequest(port))).isEqualTo(new CreatorObservation(
                "{\"creatorUrl\":\"http://127.0.0.1:" + port + "/\",\"status\":\"creator-ready\"}",
                200,
                "{\"status\":\"ready\"}",
                true
        ));
    }

    @Test
    void packagedCreatorServesTheRealStepCatalog() throws Exception {
        final int port = availablePort();
        final CreatorObservation observation = observeCreator(port, stepsRequest(port));

        assertThat(new CreatorApiObservation(
                observation.responseStatus(),
                observation.responseBody().contains("\"id\":\"text.lowercase\""),
                observation.terminated()
        )).isEqualTo(new CreatorApiObservation(200, true, true));
    }

    @Test
    void packagedCreatorCompilesTheShippedFlow() throws Exception {
        final int port = availablePort();
        final CreatorObservation observation = observeCreator(port, compileRequest(port));

        assertThat(new CreatorApiObservation(
                observation.responseStatus(),
                observation.responseBody().contains("\"status\":\"compiled\""),
                observation.terminated()
        )).isEqualTo(new CreatorApiObservation(200, true, true));
    }

    private static Result run(final List<String> arguments, final Map<String, String> environment) throws Exception {
        final Process process = start(arguments, environment);
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Packaged Railix command did not terminate within 10 seconds.");
        }
        return new Result(
                process.exitValue(),
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8),
                new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8)
        );
    }

    private static Result runUnsupportedTrigger(final Path temporary, final String type) throws Exception {
        Files.writeString(
                temporary.resolve("railix.flow.json"),
                Files.readString(EXAMPLE.resolve("railix.flow.json")).replace(
                        "\"triggers\": []",
                        "\"triggers\":[{\"id\":\"source\",\"type\":\"" + type + "\",\"config\":{}}]"
                )
        );
        Files.writeString(temporary.resolve("input.json"), "{\"text\":\"Railix\"}");
        return run(List.of("run", temporary.toString()), Map.of());
    }

    private static Result unsupportedTrigger(final String type) {
        return new Result(
                2,
                "",
                "{\"diagnostics\":[{\"code\":\"FLOW_TRIGGER_TYPE_UNSUPPORTED\","
                        + "\"column\":0,\"line\":0,\"message\":\"Trigger type is not implemented: "
                        + type + ".\",\"path\":\"triggers[0].type\"}],\"status\":\"compile-rejected\"}\n"
        );
    }

    private static CreatorObservation observeCreator(final int port, final HttpRequest request) throws Exception {
        final Process process = start(List.of("creator", "--port", Integer.toString(port)), Map.of());
        try (BufferedReader stdout = new BufferedReader(new InputStreamReader(
                process.getInputStream(),
                StandardCharsets.UTF_8
        ))) {
            final FutureTask<String> readiness = new FutureTask<>(stdout::readLine);
            Thread.startVirtualThread(readiness);
            final String readyLine = readiness.get(5, TimeUnit.SECONDS);
            final HttpResponse<String> response = CLIENT.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );

            process.destroy();
            final boolean terminated = process.waitFor(5, TimeUnit.SECONDS)
                    || terminateForcibly(process);
            return new CreatorObservation(readyLine, response.statusCode(), response.body(), terminated);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    private static HttpRequest healthRequest(final int port) {
        return HttpRequest.newBuilder(endpoint(port, "api/health")).GET().build();
    }

    private static HttpRequest stepsRequest(final int port) {
        return HttpRequest.newBuilder(endpoint(port, "api/steps")).GET().build();
    }

    private static HttpRequest compileRequest(final int port) throws IOException {
        return HttpRequest.newBuilder(endpoint(port, "api/compile"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Files.readString(EXAMPLE.resolve("railix.flow.json"))))
                .build();
    }

    private static java.net.URI endpoint(final int port, final String path) {
        return java.net.URI.create("http://127.0.0.1:" + port + "/" + path);
    }

    private static boolean terminateForcibly(final Process process) throws InterruptedException {
        process.destroyForcibly();
        return process.waitFor(5, TimeUnit.SECONDS);
    }

    private static Process start(
            final List<String> arguments,
            final Map<String, String> environment
    ) throws IOException {
        final List<String> command = new ArrayList<>();
        command.add(LAUNCHER.toString());
        command.addAll(arguments);
        final ProcessBuilder builder = new ProcessBuilder(command).directory(REPOSITORY.toFile());
        builder.environment().putAll(environment);
        return builder.start();
    }

    private static int availablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}))) {
            return socket.getLocalPort();
        }
    }

    private record Result(int exitCode, String stdout, String stderr) {
    }

    private record CreatorObservation(String readiness, int responseStatus, String responseBody, boolean terminated) {
    }

    private record CreatorApiObservation(int responseStatus, boolean expectedBody, boolean terminated) {
    }
}
