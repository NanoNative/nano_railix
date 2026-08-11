package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(40)
final class RailixPackageIT {
    private static final Path JAR = Path.of("target", "railix.jar").toAbsolutePath().normalize();
    private static final Path LAUNCHER = Path.of("..", "..", "railix").toAbsolutePath().normalize();

    @TempDir
    Path directory;

    @Test
    void executableJarStartsCreatorAndOwnedApplication() throws Exception {
        try (PackagedCreator creator = PackagedCreator.start(JAR, directory.resolve("project.json"))) {
            final HttpResponse<String> response = request(creator.uri(), "GET", "/api/project", "");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("\"state\":\"running\"", "\"use\":\"railix.app\"");
            assertThat(creator.childPids()).hasSize(1);
        }
    }

    @Test
    void executableJarUsesDefaultProjectAndAvailablePort() throws Exception {
        final Path project = directory.resolve("railix.project.json").toAbsolutePath().normalize();

        try (PackagedCreator creator = PackagedCreator.startDefault(JAR, directory)) {
            final HttpResponse<String> response = request(creator.uri(), "GET", "/api/project", "");
            final RailixValue.ObjectValue payload = object(response.body());
            final RailixValue.ObjectValue workspace =
                    (RailixValue.ObjectValue) payload.values().get("workspace");
            final String reportedProject =
                    ((RailixValue.StringValue) workspace.values().get("project_path")).value();

            assertThat(creator.uri().getPort()).isPositive();
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("\"use\":\"railix.app\"");
            assertThat(project).isRegularFile();
            assertThat(Path.of(reportedProject).toRealPath()).isEqualTo(project.toRealPath());
        }
    }

    @Test
    void creatorCommandNeverTreatsASameNamedFileAsTheProject() throws Exception {
        Files.writeString(directory.resolve("creator"), "{\"format\":1}", StandardCharsets.UTF_8);

        try (PackagedCreator creator = PackagedCreator.startLauncherDefault(LAUNCHER, directory)) {
            final HttpResponse<String> response = request(creator.uri(), "GET", "/api/project", "");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(directory.resolve("railix.project.json")).isRegularFile();
        }
    }

    @Test
    void executableJarRunsProjectContext() throws Exception {
        final Path project = directory.resolve("project.json");
        Files.writeString(project, CreatorProjects.lowercaseCli(), StandardCharsets.UTF_8);
        try (PackagedCreator creator = PackagedCreator.start(JAR, project)) {
            final HttpResponse<String> response = request(
                    creator.uri(),
                    "POST",
                    "/api/run/command",
                    "{\"payload\":{\"arguments\":[\"Hello RAILIX\"]}}"
            );

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains(
                    "\"payload\":{\"arguments\":[\"Hello RAILIX\"]}",
                    "\"result\":\"hello railix\"",
                    "\"exit_code\":0"
            );
        }
    }

    @Test
    void executableJarRollsApplicationAfterAcceptedProjectChange() throws Exception {
        try (PackagedCreator creator = PackagedCreator.start(JAR, directory.resolve("project.json"))) {
            final RailixValue.ObjectValue before = object(
                    request(creator.uri(), "GET", "/api/application", "").body()
            );
            final long previousPid = number(before, "pid");

            final HttpResponse<String> response = request(
                    creator.uri(),
                    "POST",
                    "/api/project",
                    CreatorProjects.empty("brisk-logic-vault")
            );
            final RailixValue.ObjectValue after = object(
                    request(creator.uri(), "GET", "/api/application", "").body()
            );

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(number(after, "built_at")).isGreaterThanOrEqualTo(number(before, "built_at"));
            assertThat(after.values().get("fingerprint")).isNotEqualTo(before.values().get("fingerprint"));
            assertThat(number(after, "pid")).isNotEqualTo(previousPid);
            assertThat(awaitExit(previousPid)).isTrue();
        }
    }

    @Test
    void executableJarPreservesApplicationAfterRejectedProjectChange() throws Exception {
        final Path project = directory.resolve("project.json");
        Files.writeString(project, CreatorProjects.lowercaseCli(), StandardCharsets.UTF_8);
        try (PackagedCreator creator = PackagedCreator.start(JAR, project)) {
            final String before = request(creator.uri(), "GET", "/api/application", "").body();

            final HttpResponse<String> rejected = request(
                    creator.uri(),
                    "POST",
                    "/api/project",
                    "{}"
            );
            final String after = request(creator.uri(), "GET", "/api/application", "").body();
            final HttpResponse<String> run = request(
                    creator.uri(),
                    "POST",
                    "/api/run/command",
                    "{\"payload\":{\"arguments\":[\"Still RAILIX\"]}}"
            );

            assertThat(rejected.statusCode()).isEqualTo(422);
            assertThat(after).isEqualTo(before);
            assertThat(run.statusCode()).isEqualTo(200);
            assertThat(run.body()).contains("\"result\":\"still railix\"");
        }
    }

    @Test
    void stoppingExecutableJarTerminatesCreatorAndApplicationProcesses() throws Exception {
        try (PackagedCreator creator = PackagedCreator.start(JAR, directory.resolve("project.json"))) {
            final List<Long> childPids = creator.childPids();

            creator.stop();

            assertThat(childPids).isNotEmpty();
            assertThat(childPids).allMatch(RailixPackageIT::awaitExit);
        }
    }

    @Test
    void executableJarRunsCommandLineArgumentsThroughTheDeclaredTrigger() throws Exception {
        Files.writeString(
                directory.resolve("railix.project.json"),
                argumentProject(),
                StandardCharsets.UTF_8
        );

        assertThat(runJar(List.of(), "run", "Hello", "Railix"))
                .isEqualTo(new ProcessResult(0, "[\"Hello\",\"Railix\"]"));
    }

    @Test
    void executableJarUsesSilentCliDefaults() throws Exception {
        Files.writeString(
                directory.resolve("railix.project.json"),
                cliProject(),
                StandardCharsets.UTF_8
        );

        assertThat(runJar(List.of(), "run")).isEqualTo(new ProcessResult(0, ""));
    }

    @Test
    void executableJarRejectsAMissingProject() throws Exception {
        assertThat(runJar(List.of(), "run")).isEqualTo(new ProcessResult(
                2,
                "Cannot read project: railix.project.json"
        ));
    }

    @Test
    void executableJarReportsACompileRejectedProject() throws Exception {
        Files.writeString(
                directory.resolve("railix.project.json"),
                "{}",
                StandardCharsets.UTF_8
        );

        assertThat(runJar(List.of(), "run")).isEqualTo(new ProcessResult(
                2,
                "PROJECT_FORMAT_UNSUPPORTED format Project format must be the number 1."
        ));
    }

    @Test
    void executableJarUsesExplicitCliExitCode() throws Exception {
        Files.writeString(
                directory.resolve("railix.project.json"),
                cliProject(new Assignment("exit_code", "7")),
                StandardCharsets.UTF_8
        );

        assertThat(runJar(List.of(), "run")).isEqualTo(new ProcessResult(7, ""));
    }

    @Test
    void executableJarRejectsAProjectWithoutCommandLineIngress() throws Exception {
        Files.writeString(
                directory.resolve("railix.project.json"),
                """
                        {"format":1,"id":"no-cli","nodes":[
                          {"id":"app","use":"railix.app","inputs":{}}
                        ],"links":[]}
                        """,
                StandardCharsets.UTF_8
        );

        assertThat(runJar(List.of(), "run")).isEqualTo(new ProcessResult(
                2,
                "RUN_SOURCE_UNKNOWN source "
                        + "Project has no Trigger for source: application.arguments."
        ));
    }

    @Test
    void executableJarRejectsAnOversizedProjectBeforeParsing() throws Exception {
        Files.writeString(
                directory.resolve("railix.project.json"),
                " ".repeat(RailixData.DEFAULT_MAX_SOURCE_BYTES + 1),
                StandardCharsets.UTF_8
        );

        assertThat(runJar(List.of(), "run")).isEqualTo(new ProcessResult(
                2,
                "Project exceeds the 1048576-byte limit."
        ));
    }

    @Test
    void executableJarRejectsFractionalCliExitCodeWithoutPrintingAResult() throws Exception {
        Files.writeString(
                directory.resolve("railix.project.json"),
                cliProject(
                        new Assignment("result", "\"must-not-print\""),
                        new Assignment("exit_code", "1.5")
                ),
                StandardCharsets.UTF_8
        );

        assertThat(runJar(List.of(), "run"))
                .isEqualTo(new ProcessResult(2, "CLI exit code must be an integer."));
    }

    @Test
    void executableJarRejectsNonNumericCliExitCodeWithoutPrintingAResult() throws Exception {
        Files.writeString(
                directory.resolve("railix.project.json"),
                cliProject(
                        new Assignment("result", "\"must-not-print\""),
                        new Assignment("exit_code", "\"not-a-number\"")
                ),
                StandardCharsets.UTF_8
        );

        assertThat(runJar(List.of(), "run")).isEqualTo(new ProcessResult(
                2,
                "RUN_RESULT_INCOMPATIBLE context.exit_code "
                        + "Trigger result exit_code requires number but receives string."
        ));
    }

    @Test
    void executableJarRejectsNegativeCliExitCodeWithoutPrintingAResult() throws Exception {
        Files.writeString(
                directory.resolve("railix.project.json"),
                cliProject(
                        new Assignment("result", "\"must-not-print\""),
                        new Assignment("exit_code", "-1")
                ),
                StandardCharsets.UTF_8
        );

        assertThat(runJar(List.of(), "run")).isEqualTo(new ProcessResult(
                2,
                "CLI exit code must be from 0 through 255."
        ));
    }

    @Test
    void executableJarRejectsOutOfRangeCliExitCodeWithoutPrintingAResult() throws Exception {
        Files.writeString(
                directory.resolve("railix.project.json"),
                cliProject(
                        new Assignment("result", "\"must-not-print\""),
                        new Assignment("exit_code", "256")
                ),
                StandardCharsets.UTF_8
        );

        assertThat(runJar(List.of(), "run")).isEqualTo(new ProcessResult(
                2,
                "CLI exit code must be from 0 through 255."
        ));
    }

    @Test
    void executableJarRejectsTooManyCreatorArguments() throws Exception {
        assertThat(runJar(List.of(), "creator", "one", "two", "three")).isEqualTo(new ProcessResult(
                2,
                "Usage: railix creator [project-file] [port]"
        ));
    }

    @Test
    void executableJarRejectsAMissingCommand() throws Exception {
        assertThat(runJar(List.of())).isEqualTo(new ProcessResult(
                2,
                "Usage: railix creator [project-file] [port]\n       railix run [arguments...]"
        ));
    }

    @Test
    void executableJarRejectsAnUnknownCommand() throws Exception {
        assertThat(runJar(List.of(), "launch"))
                .isEqualTo(new ProcessResult(2, "Unknown Railix command: launch."));
    }

    @Test
    void executableJarRejectsNonNumericCreatorPort() throws Exception {
        assertThat(runJar(
                List.of(),
                "creator",
                directory.resolve("project.json").toString(),
                "nope"
        )).isEqualTo(new ProcessResult(2, "Creator port must be a number."));
    }

    @Test
    void executableJarRejectsOutOfRangeCreatorPort() throws Exception {
        assertThat(runJar(
                List.of(),
                "creator",
                directory.resolve("project.json").toString(),
                "-1"
        )).isEqualTo(new ProcessResult(2, "Creator port must be from 0 through 65535."));
    }

    @Test
    void executableJarRejectsIncompleteApplicationCommand() throws Exception {
        assertThat(runJar(List.of(), "application")).isEqualTo(new ProcessResult(
                2,
                "Invalid Creator application command."
        ));
    }

    @Test
    void executableJarRejectsOccupiedCreatorPortWithoutLeakingChild() throws Exception {
        try (ServerSocket socket = new ServerSocket(
                0,
                1,
                InetAddress.getByAddress(new byte[]{127, 0, 0, 1})
        )) {
            final ProcessResult result = runJar(
                    List.of(),
                    "creator",
                    directory.resolve("project.json").toString(),
                    Integer.toString(socket.getLocalPort())
            );

            assertThat(result.status()).isEqualTo(2);
            assertThat(result.output()).contains("Address already in use");
        }
    }

    private ProcessResult runJar(
            final List<String> options,
            final String... arguments
    ) throws Exception {
        final List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(options);
        command.add("-jar");
        command.add(JAR.toString());
        command.addAll(Arrays.asList(arguments));
        final Process process = instrument(new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true))
                .start();
        final String output;
        try (var reader = process.inputReader(StandardCharsets.UTF_8)) {
            output = reader.lines()
                    .filter(line -> !line.startsWith("Picked up JAVA_TOOL_OPTIONS:"))
                    .collect(java.util.stream.Collectors.joining("\n"));
        }
        assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue();
        return new ProcessResult(process.exitValue(), output.strip());
    }

    private static String argumentProject() {
        return """
                {
                  "format":1,
                  "id":"cli-arguments",
                  "nodes":[
                    {"id":"app","use":"railix.app","inputs":{}},
                    {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[{
                      "name":"no-arguments","payload":[]
                    }]},
                    {"id":"return-arguments","use":"railix.field-manipulation","inputs":{
                      "field":["context","result"],
                      "value":[{"option":"field","inputs":{
                        "source":["context","payload","arguments"]
                      }}],
                      "steps":[]
                    }}
                  ],
                  "links":[
                    {"from":"app.start","to":"command"},
                    {"from":"command.next","to":"return-arguments"},
                    {"from":"return-arguments.next","to":"end"}
                  ]
                }
                """;
    }

    private static String cliProject(final Assignment... assignments) {
        final StringBuilder nodes = new StringBuilder();
        final StringBuilder links = new StringBuilder();
        String previous = "command";
        for (int index = 0; index < assignments.length; index++) {
            final Assignment assignment = assignments[index];
            final String id = "assign-" + index;
            nodes.append("""
                    ,{"id":"%s","use":"railix.field-manipulation","inputs":{
                      "field":["context","%s"],
                      "value":[{"option":"literal","inputs":{"literal":%s}}],"steps":[]
                    }}""".formatted(id, assignment.field(), assignment.json()));
            links.append("""
                    ,{"from":"%s.next","to":"%s"}""".formatted(previous, id));
            previous = id;
        }
        return """
                {
                  "format":1,
                  "id":"cli-result",
                  "nodes":[
                    {"id":"app","use":"railix.app","inputs":{}},
                    {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[{
                      "name":"no-arguments","payload":[]
                    }]}%s
                  ],
                  "links":[
                    {"from":"app.start","to":"command"}%s
                  ]
                }
                """.formatted(
                nodes,
                links.append("""
                        ,{"from":"%s.next","to":"end"}""".formatted(previous))
        );
    }

    private static HttpResponse<String> request(
            final URI baseUri,
            final String method,
            final String path,
            final String body
    ) throws IOException, InterruptedException {
        final HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .method(method, body.isEmpty()
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
    }

    private static boolean awaitExit(final long pid) {
        for (int attempt = 0; attempt < 50; attempt++) {
            if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                try {
                    Thread.sleep(100);
                } catch (final InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            } else {
                return true;
            }
        }
        return false;
    }

    private static RailixValue.ObjectValue object(final String source) {
        return (RailixValue.ObjectValue) ((RailixJson.Parsed) RailixJson.parse(source)).value();
    }

    private static long number(final RailixValue.ObjectValue object, final String field) {
        return ((RailixValue.NumberValue) object.values().get(field)).value().longValueExact();
    }

    private static final class PackagedCreator implements AutoCloseable {
        private final Process process;
        private final BufferedReader output;
        private final URI uri;
        private final List<ProcessHandle> children;
        private boolean stopped;

        private PackagedCreator(final Process process, final BufferedReader output, final URI uri) {
            this.process = process;
            this.output = output;
            this.uri = uri;
            children = process.descendants().toList();
        }

        private static PackagedCreator startDefault(final Path jar, final Path directory) throws Exception {
            assertThat(Files.isRegularFile(jar)).isTrue();
            return start(instrument(new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-jar",
                    jar.toString(),
                    "creator"
            ).directory(directory.toFile()).redirectErrorStream(true)));
        }

        private static PackagedCreator startLauncherDefault(
                final Path launcher,
                final Path directory
        ) throws Exception {
            return start(instrument(new ProcessBuilder(
                    launcher.toString(),
                    "creator"
            ).directory(directory.toFile()).redirectErrorStream(true)));
        }

        private static PackagedCreator start(final Path jar, final Path project) throws Exception {
            assertThat(Files.isRegularFile(jar)).isTrue();
            return start(instrument(new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-jar",
                    jar.toString(),
                    "creator",
                    project.toString(),
                    "0"
            ).redirectErrorStream(true)));
        }

        private static PackagedCreator start(final ProcessBuilder builder) throws Exception {
            final Process process = builder.start();
            final BufferedReader output = new BufferedReader(new InputStreamReader(
                    process.getInputStream(),
                    StandardCharsets.UTF_8
            ));
            final CompletableFuture<String> ready = new CompletableFuture<>();
            Thread.ofVirtual().start(() -> readiness(output, ready));
            try {
                final String line = ready.get(15, TimeUnit.SECONDS);
                return new PackagedCreator(
                        process,
                        output,
                        URI.create(line.substring("Railix Creator ".length()))
                );
            } catch (final Exception exception) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                output.close();
                throw exception;
            }
        }

        private URI uri() {
            return uri;
        }

        private List<Long> childPids() {
            return children.stream().map(ProcessHandle::pid).toList();
        }

        private void stop() throws Exception {
            if (stopped) {
                return;
            }
            stopped = true;
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
            }
            output.close();
        }

        @Override
        public void close() throws Exception {
            final List<ProcessHandle> owned = Stream.concat(
                    children.stream(),
                    process.descendants()
            ).distinct().toList();
            stop();
            owned.stream()
                    .filter(ProcessHandle::isAlive)
                    .forEach(ProcessHandle::destroyForcibly);
        }

        private static void readiness(
                final BufferedReader output,
                final CompletableFuture<String> ready
        ) {
            try {
                String line;
                while ((line = output.readLine()) != null) {
                    if (line.startsWith("Railix Creator ")) {
                        ready.complete(line);
                        return;
                    }
                }
                ready.completeExceptionally(new IOException("Packaged Creator exited before readiness."));
            } catch (final IOException exception) {
                ready.completeExceptionally(exception);
            }
        }
    }

    static ProcessBuilder instrument(final ProcessBuilder builder) {
        final Optional<String> agent = ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(argument -> argument.startsWith("-javaagent:") && argument.contains("jacoco"))
                .findFirst();
        agent.ifPresent(argument -> builder.environment().merge(
                "JAVA_TOOL_OPTIONS",
                argument,
                (existing, added) -> existing + " " + added
        ));
        return builder;
    }

    private record ProcessResult(int status, String output) {
    }

    private record Assignment(String field, String json) {
    }
}
