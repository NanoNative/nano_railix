package example.railix.welcome;

import dev.nanonative.railix.core.flow.CompileResult;
import dev.nanonative.railix.core.flow.FlowCompiler;
import dev.nanonative.railix.core.step.StepCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.lang.management.ManagementFactory;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.jar.JarEntry;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.assertj.core.api.Assertions.assertThat;

class TrustedGreetingPackageIT {
    private static final Path PROJECT = Path.of("").toAbsolutePath().normalize();
    private static final Path ARTIFACT = PROJECT.resolve("target/trusted-step-monolith.jar");
    private static final int HTTP_PORT = 18080;
    private static final int SOCKET_PORT = 18081;
    private static final String APPLICATION_USAGE =
            "Usage: <no arguments> | cli <trigger-id> [argument...] | creator "
                    + "| lock [--check | --write <output>...]";
    private static final String STARTUP_FLOW = """
            {
              "id":"startup-package",
              "triggers":[{"id":"initialize","type":"startup","config":{}}],
              "entry":"startup",
              "inputs":{},
              "outputs":{"message":"string"},
              "steps":[
                {"id":"startup","use":"example.package-signal","config":{},"on":{"ok":"end"}}
              ],
              "connections":[{"from":"startup.message","to":"output.message"}]
            }
            """;
    private static final String SCHEDULED_FLOW = """
            {
              "id":"scheduled-package",
              "triggers":[{"id":"refresh","type":"scheduled","config":{
                "intervalMillis":60000,"initialDelayMillis":0,"maxConcurrentRuns":1
              }}],
              "entry":"scheduled",
              "inputs":{},
              "outputs":{"message":"string"},
              "steps":[
                {"id":"scheduled","use":"example.package-signal","config":{},"on":{"ok":"end"}}
              ],
              "connections":[{"from":"scheduled.message","to":"output.message"}]
            }
            """;
    private static final String COVERAGE_AGENT = ManagementFactory.getRuntimeMXBean()
            .getInputArguments()
            .stream()
            .filter(argument -> argument.startsWith("-javaagent:") && argument.contains("jacoco"))
            .findFirst()
            .orElse("");

    @Test
    void artifactContainsTheAuthoredFlowDependencyLockAndExternalStep() throws Exception {
        try (JarFile artifact = new JarFile(ARTIFACT.toFile())) {
            assertThat(List.of(
                    artifact.getEntry("railix.flow.json") != null,
                    artifact.getEntry("railix.lock.json") != null,
                    artifact.getEntry("example/railix/welcome/GreetingStep.class") != null,
                    artifact.getEntry("example/railix/welcome/TrustedGreetingMain.class") != null
            )).containsExactly(true, true, true, true);
        }
    }

    @Test
    void artifactContainsNoTestOrBuildToolClasses() throws Exception {
        try (JarFile artifact = new JarFile(ARTIFACT.toFile())) {
            assertThat(artifact.stream()
                    .map(JarEntry::getName)
                    .filter(TrustedGreetingPackageIT::testOrBuildEntry)
                    .toList()).isEmpty();
        }
    }

    @Test
    void artifactCarriesApplicationCoordinatesAsProvenance() throws Exception {
        try (JarFile artifact = new JarFile(ARTIFACT.toFile());
             InputStream coordinates = artifact.getInputStream(artifact.getJarEntry(
                     "META-INF/maven/example.railix/trusted-step-monolith/pom.properties"
             ))) {
            assertThat(new String(coordinates.readAllBytes(), StandardCharsets.UTF_8)).contains(
                    "artifactId=trusted-step-monolith",
                    "groupId=example.railix",
                    "version=1.0.0-SNAPSHOT"
            );
        }
    }

    @Test
    void artifactPrintsTheCommittedCanonicalDependencyLock(@TempDir final Path temporary) throws Exception {
        assertThat(run(temporary, "lock")).isEqualTo(new Result(
                0,
                Files.readString(PROJECT.resolve("src/main/resources/railix.lock.json")),
                ""
        ));
    }

    @Test
    void artifactChecksTheCommittedDependencyLock(@TempDir final Path temporary) throws Exception {
        assertThat(run(temporary, "lock", "--check")).isEqualTo(new Result(0, "", ""));
    }

    @Test
    void artifactRunsTheAuthoredFlowOutsideItsProject(@TempDir final Path temporary) throws Exception {
        assertThat(runWithStdin(
                temporary,
                Files.readAllBytes(PROJECT.resolve("input.json")),
                "cli",
                "command"
        )).isEqualTo(new Result(
                0,
                Files.readString(PROJECT.resolve("expected-output.json")).strip() + "\n",
                ""
        ));
    }

    @Test
    void artifactCanRunTheCliTriggerRepeatedly(@TempDir final Path temporary) throws Exception {
        final byte[] input = Files.readAllBytes(PROJECT.resolve("input.json"));

        assertThat(List.of(
                runWithStdin(temporary, input, "cli", "command"),
                runWithStdin(temporary, input, "cli", "command")
        )).containsExactly(
                new Result(0, "{\"message\":\"Welcome, railix\"}\n", ""),
                new Result(0, "{\"message\":\"Welcome, railix\"}\n", "")
        );
    }

    @Test
    void packageHarnessTerminatesALongRunningChild(@TempDir final Path temporary) throws Exception {
        final Process process = new ProcessBuilder(
                javaCommand(),
                "-jar",
                ARTIFACT.toString(),
                "creator"
        ).directory(temporary.toFile()).start();
        try (var reader = Executors.newVirtualThreadPerTaskExecutor()) {
            final Future<String> ready = reader.submit(() -> process.inputReader(StandardCharsets.UTF_8).readLine());
            assertThat(ready.get(5, TimeUnit.SECONDS)).contains("\"status\":\"creator-ready\"");
            terminate(process);
            assertThat(process.isAlive()).isFalse();
        } finally {
            terminate(process);
        }
    }

    @Test
    void artifactRejectsRemovedRunCommand(@TempDir final Path temporary) throws Exception {
        assertThat(run(temporary, "run")).isEqualTo(new Result(2, "", APPLICATION_USAGE + "\n"));
    }

    @Test
    void artifactRunsStartupTriggerWithoutArguments(@TempDir final Path temporary) throws Exception {
        assertThat(run(startupArtifact(temporary), temporary))
                .isEqualTo(new Result(0, "{\"message\":\"triggered\"}\n", ""));
    }

    @Test
    void artifactRunsScheduledTriggerUntilTermination(@TempDir final Path temporary) throws Exception {
        assertThat(observeScheduled(scheduledArtifact(temporary), temporary))
                .isEqualTo(new ScheduledObservation(
                        "{\"scheduledTriggers\":[\"refresh\"],"
                                + "\"status\":\"application-ready\"}",
                        "{\"outputs\":{\"message\":\"triggered\"},"
                                + "\"status\":\"succeeded\",\"steps\":[{"
                                + "\"outcome\":\"ok\",\"step\":\"scheduled\"}]}",
                        "",
                        true
                ));
    }

    @Test
    void artifactRunsARealHttpTrigger(@TempDir final Path temporary) throws Exception {
        assertThat(observeHttp(temporary, "/greet", "{\"name\":\"RAILIX\"}"))
                .isEqualTo(new HttpObservation(
                        200,
                        """
                        {"outputs":{"message":"Welcome, railix"},"status":"succeeded","steps":[{"outcome":"ok","step":"lowercase"},{"outcome":"ok","step":"prefix"}]}\
                        """,
                        true
                ));
    }

    @Test
    void artifactRunsARealSocketTrigger(@TempDir final Path temporary) throws Exception {
        assertThat(observeSocket(temporary, "{\"name\":\"RAILIX\"}"))
                .isEqualTo(new SocketObservation(
                        applicationReadiness(),
                        "{\"outputs\":{\"message\":\"Welcome, railix\"},"
                                + "\"status\":\"succeeded\",\"steps\":[{"
                                + "\"outcome\":\"ok\",\"step\":\"lowercase\"},{"
                                + "\"outcome\":\"ok\",\"step\":\"prefix\"}]}",
                        "",
                        true
                ));
    }

    @Test
    void artifactRunsTheExplicitFlowEventRoute(@TempDir final Path temporary) throws Exception {
        assertThat(observeHttp(
                temporary,
                "/v1/flows/trusted-greeting/events",
                "{\"name\":\"RAILIX\"}"
        )).isEqualTo(new HttpObservation(
                200,
                """
                {"outputs":{"message":"Welcome, railix"},"status":"succeeded","steps":[{"outcome":"ok","step":"lowercase"},{"outcome":"ok","step":"prefix"}]}\
                """,
                true
        ));
    }

    @Test
    void artifactRunsTheExplicitStepEventRoute(@TempDir final Path temporary) throws Exception {
        assertThat(observeHttp(
                temporary,
                "/v1/flows/trusted-greeting/steps/prefix/events",
                "{\"text\":\"railix\"}"
        )).isEqualTo(new HttpObservation(
                200,
                """
                {"outputs":{"message":"Welcome, railix"},"status":"succeeded","steps":[{"outcome":"ok","step":"prefix"}]}\
                """,
                true
        ));
    }

    @Test
    void artifactRejectsAnUnknownCliTrigger(@TempDir final Path temporary) throws Exception {
        assertThat(runWithStdin(temporary, new byte[0], "cli", "missing")).isEqualTo(new Result(
                2,
                "",
                "{\"error\":{\"code\":\"CLI_TRIGGER_UNKNOWN\","
                        + "\"message\":\"CLI trigger does not exist: missing.\"},"
                        + "\"status\":\"launcher-rejected\"}\n"
        ));
    }

    @Test
    void artifactRejectsCliArgumentsWithoutADeclaredMapping(@TempDir final Path temporary) throws Exception {
        assertThat(runWithStdin(
                temporary,
                Files.readAllBytes(PROJECT.resolve("input.json")),
                "cli",
                "command",
                "unexpected"
        )).isEqualTo(new Result(
                2,
                "",
                "{\"error\":{\"code\":\"CLI_ARGUMENTS_UNSUPPORTED\","
                        + "\"message\":\"CLI trigger does not accept arguments: command.\"},"
                        + "\"status\":\"launcher-rejected\"}\n"
        ));
    }

    @Test
    void artifactRejectsUnknownArguments(@TempDir final Path temporary) throws Exception {
        assertThat(run(temporary, "unknown")).isEqualTo(new Result(2, "", APPLICATION_USAGE + "\n"));
    }

    @Test
    void artifactRejectsAThreePartUnknownCommand(@TempDir final Path temporary) throws Exception {
        assertThat(run(temporary, "unknown", "--write", temporary.resolve("output").toString()))
                .isEqualTo(new Result(2, "", APPLICATION_USAGE + "\n"));
    }

    @Test
    void artifactRejectsAnUnknownLockOperation(@TempDir final Path temporary) throws Exception {
        assertThat(run(temporary, "lock", "--unknown", temporary.resolve("output").toString()))
                .isEqualTo(new Result(2, "", APPLICATION_USAGE + "\n"));
    }

    @Test
    void artifactRejectsMalformedInputJson(@TempDir final Path temporary) throws Exception {
        assertThat(rejection(temporary, "{", "event-rejected", "DATA_JSON_INVALID"))
                .isEqualTo(new Rejection(2, true, true, true));
    }

    @Test
    void artifactRejectsNonObjectInput(@TempDir final Path temporary) throws Exception {
        assertThat(rejection(temporary, "[]", "event-rejected", "FLOW_INPUT_OBJECT_REQUIRED"))
                .isEqualTo(new Rejection(2, true, true, true));
    }

    @Test
    void artifactRejectsInvalidUtf8Stdin(@TempDir final Path temporary) throws Exception {
        final Result result = runWithStdin(
                temporary,
                new byte[]{(byte) 0xc3, 0x28},
                "cli",
                "command"
        );

        assertThat(new Rejection(
                result.exitCode(),
                result.stdout().isEmpty(),
                result.stderr().contains("\"status\":\"event-rejected\""),
                result.stderr().contains("\"code\":\"DATA_SOURCE_UTF8_INVALID\"")
        )).isEqualTo(new Rejection(2, true, true, true));
    }

    @Test
    void artifactRejectsMissingFlowInput(@TempDir final Path temporary) throws Exception {
        assertThat(rejection(temporary, "{}", "run-rejected", "FLOW_INPUT_REQUIRED"))
                .isEqualTo(new Rejection(2, true, true, true));
    }

    @Test
    void artifactRejectsUnknownFlowInput(@TempDir final Path temporary) throws Exception {
        assertThat(rejection(
                temporary,
                "{\"name\":\"RAILIX\",\"extra\":true}",
                "run-rejected",
                "FLOW_INPUT_UNKNOWN"
        )).isEqualTo(new Rejection(2, true, true, true));
    }

    @Test
    void artifactRejectsWrongFlowInputType(@TempDir final Path temporary) throws Exception {
        assertThat(rejection(temporary, "{\"name\":1}", "run-rejected", "FLOW_INPUT_TYPE_MISMATCH"))
                .isEqualTo(new Rejection(2, true, true, true));
    }

    @Test
    void artifactRejectsAMissingDependencyLock(@TempDir final Path temporary) throws Exception {
        final Path artifact = rewriteArtifact(temporary, Optional.empty());

        assertThat(runWithStdin(
                artifact,
                temporary,
                Files.readAllBytes(PROJECT.resolve("input.json")),
                "cli",
                "command"
        )).isEqualTo(new Result(
                4,
                "",
                "{\"error\":{\"code\":\"PACKAGED_LOCK_MISSING\","
                        + "\"message\":\"Packaged dependency lock resource is missing.\"},"
                        + "\"status\":\"launcher-rejected\"}\n"
        ));
    }

    @Test
    void artifactRejectsATamperedFlowLock(@TempDir final Path temporary) throws Exception {
        final Path artifact = rewriteArtifact(temporary, Optional.of(tamperDigest(
                lock(),
                "\"flow\":\"sha256:"
        )));

        assertThat(runWithStdin(
                artifact,
                temporary,
                new byte[0],
                "cli",
                "command"
        )).isEqualTo(lockRejection(
                "STEP_LOCK_FLOW_MISMATCH",
                "Step dependency lock does not match the authored flow.",
                "flow"
        ));
    }

    @Test
    void artifactRejectsATamperedContractLock(@TempDir final Path temporary) throws Exception {
        final Path artifact = rewriteArtifact(temporary, Optional.of(tamperDigest(
                lock(),
                "\"contract\":\"sha256:"
        )));

        assertThat(runWithStdin(
                artifact,
                temporary,
                new byte[0],
                "cli",
                "command"
        )).isEqualTo(lockRejection(
                "STEP_LOCK_CONTRACT_MISMATCH",
                "Locked Step contract does not match: example.text.prefix",
                "steps.example.text.prefix.contract"
        ));
    }

    @Test
    void artifactCreatorRejectsATamperedDependencyLock(@TempDir final Path temporary) throws Exception {
        final Path artifact = rewriteArtifact(temporary, Optional.of(tamperDigest(
                lock(),
                "\"flow\":\"sha256:"
        )));

        assertThat(run(artifact, temporary, "creator")).isEqualTo(lockRejection(
                "STEP_LOCK_FLOW_MISMATCH",
                "Step dependency lock does not match the authored flow.",
                "flow"
        ));
    }

    @Test
    void artifactRegeneratesTheCanonicalLockWhenItsEmbeddedLockIsStale(
            @TempDir final Path temporary
    ) throws Exception {
        final Path artifact = rewriteArtifact(temporary, Optional.of(tamperDigest(
                lock(),
                "\"flow\":\"sha256:"
        )));

        assertThat(run(artifact, temporary, "lock")).isEqualTo(new Result(0, lock(), ""));
    }

    private static Rejection rejection(
            final Path temporary,
            final String inputSource,
            final String status,
            final String code
    ) throws Exception {
        final Result result = runWithStdin(
                temporary,
                inputSource.getBytes(StandardCharsets.UTF_8),
                "cli",
                "command"
        );
        return new Rejection(
                result.exitCode(),
                result.stdout().isEmpty(),
                result.stderr().contains("\"status\":\"" + status + "\""),
                result.stderr().contains("\"code\":\"" + code + "\"")
        );
    }

    private static boolean testOrBuildEntry(final String name) {
        return name.startsWith("org/junit/")
                || name.startsWith("org/assertj/")
                || name.startsWith("com/microsoft/playwright/")
                || name.startsWith("org/apache/maven/")
                || name.endsWith("Test.class")
                || name.endsWith("IT.class");
    }

    private static Result run(final Path directory, final String... arguments) throws Exception {
        return runWithStdin(ARTIFACT, directory, new byte[0], arguments);
    }

    private static Result runWithStdin(
            final Path directory,
            final byte[] stdin,
            final String... arguments
    ) throws Exception {
        return runWithStdin(ARTIFACT, directory, stdin, arguments);
    }

    private static Result run(
            final Path artifact,
            final Path directory,
            final String... arguments
    ) throws Exception {
        return runWithStdin(artifact, directory, new byte[0], arguments);
    }

    private static Result runWithStdin(
            final Path artifact,
            final Path directory,
            final byte[] stdin,
            final String... arguments
    ) throws Exception {
        final List<String> command = new ArrayList<>();
        command.add(javaCommand());
        if (!COVERAGE_AGENT.isBlank()) {
            command.add(COVERAGE_AGENT);
        }
        command.add("-jar");
        command.add(artifact.toString());
        command.addAll(List.of(arguments));
        final Process process = new ProcessBuilder(command).directory(directory.toFile()).start();
        try {
            try (var input = process.getOutputStream()) {
                input.write(stdin);
            }
            return observe(process);
        } finally {
            terminate(process);
        }
    }

    private static Path rewriteArtifact(
            final Path temporary,
            final Optional<String> replacementLock
    ) throws Exception {
        final Path target = temporary.resolve("trusted-step-monolith.jar");
        try (JarFile source = new JarFile(ARTIFACT.toFile());
             JarOutputStream output = new JarOutputStream(
                     Files.newOutputStream(target),
                     source.getManifest()
             )) {
            final Enumeration<JarEntry> entries = source.entries();
            while (entries.hasMoreElements()) {
                final JarEntry entry = entries.nextElement();
                if ("META-INF/MANIFEST.MF".equals(entry.getName())) {
                    continue;
                }
                if ("railix.lock.json".equals(entry.getName())) {
                    if (replacementLock.isPresent()) {
                        output.putNextEntry(new JarEntry(entry.getName()));
                        output.write(replacementLock.orElseThrow().getBytes(StandardCharsets.UTF_8));
                        output.closeEntry();
                    }
                    continue;
                }
                output.putNextEntry(new JarEntry(entry.getName()));
                if (!entry.isDirectory()) {
                    try (InputStream input = source.getInputStream(entry)) {
                        input.transferTo(output);
                    }
                }
                output.closeEntry();
            }
        }
        return target;
    }

    private static Path startupArtifact(final Path temporary) throws Exception {
        return applicationArtifact(
                temporary.resolve("startup-application.jar"),
                STARTUP_FLOW,
                StepCatalog.of(InputlessPackageApplication.definition()),
                InputlessPackageApplication.class
        );
    }

    private static Path scheduledArtifact(final Path temporary) throws Exception {
        return applicationArtifact(
                temporary.resolve("scheduled-application.jar"),
                SCHEDULED_FLOW,
                StepCatalog.of(InputlessPackageApplication.definition()),
                InputlessPackageApplication.class
        );
    }

    private static Path applicationArtifact(
            final Path target,
            final String flow,
            final StepCatalog catalog,
            final Class<?> applicationClass
    ) throws Exception {
        final CompileResult.Compiled compiled = (CompileResult.Compiled) FlowCompiler.compile(
                flow,
                catalog
        );
        try (JarFile source = new JarFile(ARTIFACT.toFile())) {
            final Manifest manifest = new Manifest(source.getManifest());
            manifest.getMainAttributes().put(
                    Attributes.Name.MAIN_CLASS,
                    applicationClass.getName()
            );
            try (JarOutputStream output = new JarOutputStream(
                    Files.newOutputStream(target),
                    manifest
            )) {
                final Enumeration<JarEntry> entries = source.entries();
                while (entries.hasMoreElements()) {
                    final JarEntry entry = entries.nextElement();
                    if ("META-INF/MANIFEST.MF".equals(entry.getName())
                            || "railix.flow.json".equals(entry.getName())
                            || "railix.lock.json".equals(entry.getName())) {
                        continue;
                    }
                    output.putNextEntry(new JarEntry(entry.getName()));
                    if (!entry.isDirectory()) {
                        try (InputStream input = source.getInputStream(entry)) {
                            input.transferTo(output);
                        }
                    }
                    output.closeEntry();
                }
                writeEntry(output, "railix.flow.json", flow.getBytes(StandardCharsets.UTF_8));
                writeEntry(output, "railix.lock.json", compiled.lock().getBytes(StandardCharsets.UTF_8));
                try (InputStream application = applicationClass.getResourceAsStream(
                        applicationClass.getSimpleName() + ".class"
                )) {
                    if (application == null) {
                        throw new AssertionError("Inputless package application class is missing.");
                    }
                    writeEntry(
                            output,
                            applicationClass.getName().replace('.', '/') + ".class",
                            application.readAllBytes()
                    );
                }
            }
        }
        return target;
    }

    private static HttpObservation observeHttp(
            final Path temporary,
            final String path,
            final String event
    ) throws Exception {
        final Process process = startArtifact(ARTIFACT, temporary);
        try (BufferedReader stdout = new BufferedReader(new InputStreamReader(
                process.getInputStream(),
                StandardCharsets.UTF_8
        )); var reader = Executors.newVirtualThreadPerTaskExecutor()) {
            final Future<String> ready = reader.submit(stdout::readLine);
            assertThat(ready.get(5, TimeUnit.SECONDS)).isEqualTo(applicationReadiness());
            final HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + HTTP_PORT + path))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(event))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            terminate(process);
            return new HttpObservation(response.statusCode(), response.body(), !process.isAlive());
        } finally {
            terminate(process);
        }
    }

    private static SocketObservation observeSocket(
            final Path temporary,
            final String event
    ) throws Exception {
        final Process process = startArtifact(ARTIFACT, temporary);
        try (BufferedReader stdout = process.inputReader(StandardCharsets.UTF_8);
             var reader = Executors.newVirtualThreadPerTaskExecutor();
             Socket socket = new Socket()) {
            final Future<String> ready = reader.submit(stdout::readLine);
            final Future<String> stderr = reader.submit(() -> new String(
                    process.getErrorStream().readAllBytes(),
                    StandardCharsets.UTF_8
            ));
            final String readiness = ready.get(5, TimeUnit.SECONDS);
            socket.connect(new InetSocketAddress(
                    InetAddress.getByAddress(new byte[]{127, 0, 0, 1}),
                    SOCKET_PORT
            ), 5_000);
            socket.setSoTimeout(5_000);
            final byte[] source = event.getBytes(StandardCharsets.UTF_8);
            final DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            output.writeInt(source.length);
            output.write(source);
            output.flush();
            final DataInputStream input = new DataInputStream(socket.getInputStream());
            final String reply = new String(
                    input.readNBytes(input.readInt()),
                    StandardCharsets.UTF_8
            );
            terminate(process);
            return new SocketObservation(
                    readiness,
                    reply,
                    stderr.get(5, TimeUnit.SECONDS),
                    !process.isAlive()
            );
        } finally {
            terminate(process);
        }
    }

    private static ScheduledObservation observeScheduled(
            final Path artifact,
            final Path temporary
    ) throws Exception {
        final Process process = startArtifact(artifact, temporary);
        try (BufferedReader stdout = process.inputReader(StandardCharsets.UTF_8);
             var reader = Executors.newVirtualThreadPerTaskExecutor()) {
            final Future<String> stderr = reader.submit(() -> new String(
                    process.getErrorStream().readAllBytes(),
                    StandardCharsets.UTF_8
            ));
            final String readiness = reader.submit(stdout::readLine).get(5, TimeUnit.SECONDS);
            final String event = reader.submit(stdout::readLine).get(5, TimeUnit.SECONDS);
            terminate(process);
            return new ScheduledObservation(
                    readiness,
                    event,
                    stderr.get(5, TimeUnit.SECONDS),
                    !process.isAlive()
            );
        } finally {
            terminate(process);
        }
    }

    private static Process startArtifact(
            final Path artifact,
            final Path directory
    ) throws Exception {
        final List<String> command = new ArrayList<>();
        command.add(javaCommand());
        if (!COVERAGE_AGENT.isBlank()) {
            command.add(COVERAGE_AGENT);
        }
        command.add("-jar");
        command.add(artifact.toString());
        return new ProcessBuilder(command).directory(directory.toFile()).start();
    }

    private static String applicationReadiness() {
        return "{\"httpUrl\":\"http://127.0.0.1:" + HTTP_PORT
                + "/\",\"socketAddress\":\"127.0.0.1:" + SOCKET_PORT
                + "\",\"status\":\"application-ready\"}";
    }

    private static void writeEntry(
            final JarOutputStream output,
            final String name,
            final byte[] content
    ) throws Exception {
        output.putNextEntry(new JarEntry(name));
        output.write(content);
        output.closeEntry();
    }

    private static String lock() throws Exception {
        return Files.readString(PROJECT.resolve("src/main/resources/railix.lock.json"));
    }

    private static String tamperDigest(final String source, final String prefix) {
        final int start = source.indexOf(prefix);
        if (start < 0) {
            throw new AssertionError("Digest field is missing: " + prefix);
        }
        final int digit = start + prefix.length();
        final char replacement = source.charAt(digit) == '0' ? '1' : '0';
        return source.substring(0, digit) + replacement + source.substring(digit + 1);
    }

    private static Result lockRejection(
            final String code,
            final String message,
            final String path
    ) {
        return new Result(
                2,
                "",
                "{\"diagnostics\":[{\"code\":\"" + code + "\",\"column\":0,\"line\":0,"
                        + "\"message\":\"" + message + "\",\"path\":\"" + path
                        + "\"}],\"status\":\"compile-rejected\"}\n"
        );
    }

    private static Result observe(final Process process) throws Exception {
        try (var readers = Executors.newVirtualThreadPerTaskExecutor()) {
            final Future<String> stdout = readers.submit(() -> new String(
                    process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8
            ));
            final Future<String> stderr = readers.submit(() -> new String(
                    process.getErrorStream().readAllBytes(),
                    StandardCharsets.UTF_8
            ));
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                terminate(process);
                throw new AssertionError("Custom monolith did not terminate within 10 seconds.");
            }
            return new Result(
                    process.exitValue(),
                    stdout.get(5, TimeUnit.SECONDS),
                    stderr.get(5, TimeUnit.SECONDS)
            );
        }
    }

    private static void terminate(final Process process) {
        if (!process.isAlive()) {
            return;
        }
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        boolean interrupted = false;
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (process.isAlive() && System.nanoTime() < deadline) {
            try {
                process.waitFor(100, TimeUnit.MILLISECONDS);
            } catch (final InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        if (process.isAlive()) {
            throw new AssertionError("Custom monolith survived forced termination.");
        }
    }

    private static String javaCommand() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private record Result(int exitCode, String stdout, String stderr) {
    }

    private record HttpObservation(int status, String body, boolean terminated) {
    }

    private record SocketObservation(String readiness, String reply, String stderr, boolean terminated) {
    }

    private record ScheduledObservation(String readiness, String reply, String stderr, boolean terminated) {
    }

    private record Rejection(
            int exitCode,
            boolean stdoutEmpty,
            boolean expectedStatus,
            boolean expectedCode
    ) {
    }
}
