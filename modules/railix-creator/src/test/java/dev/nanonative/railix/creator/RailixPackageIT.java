package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.step.StepContractJson;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(40)
final class RailixPackageIT {
    private static final Path APP_IMAGE = Path.of("target", "app-image").toAbsolutePath().normalize();
    private static final Path EXECUTABLE = packagedExecutable(APP_IMAGE);
    private static final Path RUNTIME_JAVA = bundledRuntimeHome(APP_IMAGE).resolve("bin").resolve("java");
    private static final Path LAUNCHER = Path.of("..", "..", "railix").toAbsolutePath().normalize();
    private static final String SQL_STEP_ID = "thirdparty.sql.probe";
    private static final AtomicLong COVERAGE_CHILD = new AtomicLong();

    @TempDir
    Path directory;

    @Test
    void packagedExecutableRunsWithoutPathJavaOrJavaHome() throws Exception {
        Files.writeString(
                directory.resolve("railix.project.json"),
                argumentProject(),
                StandardCharsets.UTF_8
        );

        assertThat(runExecutable(noSystemJavaEnvironment(), "run", "Hello", "Railix"))
                .isEqualTo(new ProcessResult(0, "[\"Hello\",\"Railix\"]"));
    }

    @Test
    void concurrentPackagedRunsShareOneAtomicApplicationBuild() throws Exception {
        Files.writeString(
                directory.resolve("railix.project.json"),
                argumentProject(),
                StandardCharsets.UTF_8
        );
        final List<Process> processes = new ArrayList<>();
        for (int index = 0; index < 16; index++) {
            processes.add(startExecutable(noSystemJavaEnvironment(), "run", "concurrent"));
        }

        final List<ProcessResult> results = new ArrayList<>();
        for (final Process process : processes) {
            results.add(awaitExecutable(process));
        }

        assertThat(results).containsOnly(new ProcessResult(0, "[\"concurrent\"]"));
    }

    @Test
    void packagingRetainsOnlyTheDeployableApplicationImage() {
        assertThat(APP_IMAGE).isDirectory();
        assertThat(Path.of("target", "runtime")).doesNotExist();
        assertThat(Path.of("target", "package-input")).doesNotExist();
    }

    @Test
    void packagedExecutableStartsCreatorAndOwnedApplicationWithoutPathJava() throws Exception {
        try (PackagedCreator creator = PackagedCreator.start(
                EXECUTABLE,
                directory.resolve("project.json"),
                noSystemJavaEnvironment()
        )) {
            final HttpResponse<String> response = request(creator.uri(), "GET", "/api/project", "");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("\"state\":\"running\"", "\"use\":\"railix.app\"");
            assertThat(creator.childPids()).hasSize(1);
        }
    }

    @Test
    void packagedExecutableUsesDefaultProjectAndAvailablePort() throws Exception {
        final Path project = directory.resolve("railix.project.json").toAbsolutePath().normalize();

        try (PackagedCreator creator = PackagedCreator.startDefault(EXECUTABLE, directory)) {
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
    void rootLauncherNeverTreatsASameNamedFileAsTheProject() throws Exception {
        Files.writeString(directory.resolve("creator"), "{\"format\":1}", StandardCharsets.UTF_8);

        try (PackagedCreator creator = PackagedCreator.startDefault(LAUNCHER, directory, noSystemJavaEnvironment())) {
            final HttpResponse<String> response = request(creator.uri(), "GET", "/api/project", "");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(directory.resolve("railix.project.json")).isRegularFile();
        }
    }

    @Test
    void packagedExecutableRunsProjectContext() throws Exception {
        final Path project = directory.resolve("project.json");
        Files.writeString(project, CreatorProjects.lowercaseCli(), StandardCharsets.UTF_8);
        try (PackagedCreator creator = PackagedCreator.start(EXECUTABLE, project)) {
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
    void packagedExecutableRollsApplicationAfterAcceptedProjectChange() throws Exception {
        try (PackagedCreator creator = PackagedCreator.start(EXECUTABLE, directory.resolve("project.json"))) {
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
    void packagedExecutablePreservesApplicationAfterRejectedProjectChange() throws Exception {
        final Path project = directory.resolve("project.json");
        Files.writeString(project, CreatorProjects.lowercaseCli(), StandardCharsets.UTF_8);
        try (PackagedCreator creator = PackagedCreator.start(EXECUTABLE, project)) {
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
    void stoppingPackagedExecutableTerminatesCreatorAndApplicationProcesses() throws Exception {
        try (PackagedCreator creator = PackagedCreator.start(EXECUTABLE, directory.resolve("project.json"))) {
            final List<Long> childPids = creator.childPids();

            creator.stop();

            assertThat(childPids).isNotEmpty();
            assertThat(childPids).allMatch(RailixPackageIT::awaitExit);
        }
    }

    @Test
    void packagedRuntimeIncludesEveryStableBuildJdkModuleForDynamicSteps() throws Exception {
        assertThat(Files.isExecutable(RUNTIME_JAVA)).isTrue();
        final Process process = instrument(new ProcessBuilder(
                RUNTIME_JAVA.toString(),
                "--list-modules"
        ).redirectErrorStream(true)).start();
        final String output;
        try (var reader = process.inputReader(StandardCharsets.UTF_8)) {
            output = reader.lines()
                    .filter(line -> !line.isBlank())
                    .filter(line -> !line.startsWith("Picked up JAVA_TOOL_OPTIONS:"))
                    .collect(Collectors.joining("\n"));
        }
        assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isZero();
        final List<String> modules = output.lines()
                .map(RailixPackageIT::moduleName)
                .sorted()
                .toList();
        final List<String> expected;
        try (var jmods = Files.list(Path.of(System.getProperty("java.home"), "jmods"))) {
            expected = jmods
                    .filter(path -> path.getFileName().toString().endsWith(".jmod"))
                    .map(path -> path.getFileName().toString().replaceFirst("\\.jmod$", ""))
                    .filter(module -> !module.startsWith("jdk.incubator."))
                    .sorted()
                    .toList();
        }
        assertThat(modules).containsExactlyElementsOf(expected);
    }

    private static String moduleName(final String listedModule) {
        final int versionSeparator = listedModule.indexOf('@');
        return versionSeparator < 0 ? listedModule : listedModule.substring(0, versionSeparator);
    }

    @Test
    void packagedExecutableBuildsAndRunsLockedSqlStepBundleWithoutPathJava() throws Exception {
        final Path project = directory.resolve("project.json");
        installSqlBundle(directory);
        Files.writeString(project, sqlProject(), StandardCharsets.UTF_8);

        try (PackagedCreator creator = PackagedCreator.start(EXECUTABLE, project, isolatedEnvironment(directory))) {
            final HttpResponse<String> response = request(
                    creator.uri(),
                    "POST",
                    "/api/run/command",
                    "{\"payload\":{\"arguments\":[]}}"
            );

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("\"result\":12", "\"exit_code\":0");
        }
    }

    @Test
    void packagedExecutableUsesSilentCliDefaults() throws Exception {
        Files.writeString(
                directory.resolve("railix.project.json"),
                cliProject(),
                StandardCharsets.UTF_8
        );

        assertThat(runExecutable(Map.of(), "run")).isEqualTo(new ProcessResult(0, ""));
    }

    @Test
    void packagedExecutableRejectsAMissingProject() throws Exception {
        assertThat(runExecutable(Map.of(), "run")).isEqualTo(new ProcessResult(
                2,
                "Cannot read project: railix.project.json"
        ));
    }

    @Test
    void packagedExecutableReportsACompileRejectedProject() throws Exception {
        Files.writeString(
                directory.resolve("railix.project.json"),
                "{}",
                StandardCharsets.UTF_8
        );

        assertThat(runExecutable(Map.of(), "run")).isEqualTo(new ProcessResult(
                2,
                "PROJECT_FORMAT_UNSUPPORTED format Project format must be the number 1."
        ));
    }

    @Test
    void packagedExecutableUsesExplicitCliExitCode() throws Exception {
        Files.writeString(
                directory.resolve("railix.project.json"),
                cliProject(new Assignment("exit_code", "7")),
                StandardCharsets.UTF_8
        );

        assertThat(runExecutable(Map.of(), "run")).isEqualTo(new ProcessResult(7, ""));
    }

    @Test
    void packagedExecutableRejectsAProjectWithoutCommandLineIngress() throws Exception {
        Files.writeString(
                directory.resolve("railix.project.json"),
                """
                        {"format":1,"id":"no-cli","nodes":[
                          {"id":"app","use":"railix.app","inputs":{}}
                        ],"links":[]}
                        """,
                StandardCharsets.UTF_8
        );

        assertThat(runExecutable(Map.of(), "run")).isEqualTo(new ProcessResult(
                2,
                "RUN_SOURCE_UNKNOWN source "
                        + "Project has no Trigger for source: application.arguments."
        ));
    }

    @Test
    void packagedExecutableRejectsAnOversizedProjectBeforeParsing() throws Exception {
        Files.writeString(
                directory.resolve("railix.project.json"),
                " ".repeat(RailixData.DEFAULT_MAX_SOURCE_BYTES + 1),
                StandardCharsets.UTF_8
        );

        assertThat(runExecutable(Map.of(), "run")).isEqualTo(new ProcessResult(
                2,
                "Project exceeds the 1048576-byte limit."
        ));
    }

    @Test
    void packagedExecutableRejectsFractionalCliExitCodeWithoutPrintingAResult() throws Exception {
        Files.writeString(
                directory.resolve("railix.project.json"),
                cliProject(
                        new Assignment("result", "\"must-not-print\""),
                        new Assignment("exit_code", "1.5")
                ),
                StandardCharsets.UTF_8
        );

        assertThat(runExecutable(Map.of(), "run"))
                .isEqualTo(new ProcessResult(2, "CLI exit code must be an integer."));
    }

    @Test
    void packagedExecutableRejectsNonNumericCliExitCodeWithoutPrintingAResult() throws Exception {
        Files.writeString(
                directory.resolve("railix.project.json"),
                cliProject(
                        new Assignment("result", "\"must-not-print\""),
                        new Assignment("exit_code", "\"not-a-number\"")
                ),
                StandardCharsets.UTF_8
        );

        assertThat(runExecutable(Map.of(), "run")).isEqualTo(new ProcessResult(
                2,
                "RUN_RESULT_INCOMPATIBLE context.exit_code "
                        + "Trigger result exit_code requires number but receives string."
        ));
    }

    @Test
    void packagedExecutableRejectsNegativeCliExitCodeWithoutPrintingAResult() throws Exception {
        Files.writeString(
                directory.resolve("railix.project.json"),
                cliProject(
                        new Assignment("result", "\"must-not-print\""),
                        new Assignment("exit_code", "-1")
                ),
                StandardCharsets.UTF_8
        );

        assertThat(runExecutable(Map.of(), "run")).isEqualTo(new ProcessResult(
                2,
                "CLI exit code must be from 0 through 255."
        ));
    }

    @Test
    void packagedExecutableRejectsOutOfRangeCliExitCodeWithoutPrintingAResult() throws Exception {
        Files.writeString(
                directory.resolve("railix.project.json"),
                cliProject(
                        new Assignment("result", "\"must-not-print\""),
                        new Assignment("exit_code", "256")
                ),
                StandardCharsets.UTF_8
        );

        assertThat(runExecutable(Map.of(), "run")).isEqualTo(new ProcessResult(
                2,
                "CLI exit code must be from 0 through 255."
        ));
    }

    @Test
    void packagedExecutableRejectsTooManyCreatorArguments() throws Exception {
        assertThat(runExecutable(Map.of(), "creator", "one", "two", "three")).isEqualTo(new ProcessResult(
                2,
                "Usage: railix creator [project-file] [port]"
        ));
    }

    @Test
    void packagedExecutableRejectsAMissingCommand() throws Exception {
        assertThat(runExecutable(Map.of())).isEqualTo(new ProcessResult(
                2,
                "Usage: railix creator [project-file] [port]\n       railix run [arguments...]"
        ));
    }

    @Test
    void packagedExecutableRejectsAnUnknownCommand() throws Exception {
        assertThat(runExecutable(Map.of(), "launch"))
                .isEqualTo(new ProcessResult(2, "Unknown Railix command: launch."));
    }

    @Test
    void packagedExecutableRejectsNonNumericCreatorPort() throws Exception {
        assertThat(runExecutable(
                Map.of(),
                "creator",
                directory.resolve("project.json").toString(),
                "nope"
        )).isEqualTo(new ProcessResult(2, "Creator port must be a number."));
    }

    @Test
    void packagedExecutableRejectsOutOfRangeCreatorPort() throws Exception {
        assertThat(runExecutable(
                Map.of(),
                "creator",
                directory.resolve("project.json").toString(),
                "-1"
        )).isEqualTo(new ProcessResult(2, "Creator port must be from 0 through 65535."));
    }

    @Test
    void packagedExecutableRejectsOccupiedCreatorPortWithoutLeakingChild() throws Exception {
        try (ServerSocket socket = new ServerSocket(
                0,
                1,
                InetAddress.getByAddress(new byte[]{127, 0, 0, 1})
        )) {
            final ProcessResult result = runExecutable(
                    Map.of(),
                    "creator",
                    directory.resolve("project.json").toString(),
                    Integer.toString(socket.getLocalPort())
            );

            assertThat(result.status()).isEqualTo(2);
            assertThat(result.output()).contains("Address already in use");
        }
    }

    private ProcessResult runExecutable(
            final Map<String, String> environment,
            final String... arguments
    ) throws Exception {
        return awaitExecutable(startExecutable(environment, arguments));
    }

    private Process startExecutable(
            final Map<String, String> environment,
            final String... arguments
    ) throws IOException {
        final List<String> command = new ArrayList<>();
        command.add(EXECUTABLE.toString());
        command.addAll(Arrays.asList(arguments));
        final ProcessBuilder builder = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true);
        builder.environment().putAll(environment);
        return instrument(builder).start();
    }

    private static ProcessResult awaitExecutable(final Process process) throws Exception {
        final String output;
        try (var reader = process.inputReader(StandardCharsets.UTF_8)) {
            output = reader.lines()
                    .filter(line -> !line.startsWith("Picked up JAVA_TOOL_OPTIONS:"))
                    .collect(Collectors.joining("\n"));
        }
        assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue();
        return new ProcessResult(process.exitValue(), output.strip());
    }

    private static Map<String, String> noSystemJavaEnvironment() {
        final String fakeHome = Path.of("missing-java-home").toAbsolutePath().normalize().toString();
        return Map.of(
                "PATH", Path.of("/no-java-on-path").toString(),
                "JAVA_HOME", fakeHome,
                "JDK_HOME", fakeHome
        );
    }

    private static Map<String, String> isolatedEnvironment(final Path home) {
        final Map<String, String> environment = new LinkedHashMap<>(noSystemJavaEnvironment());
        environment.put("JAVA_TOOL_OPTIONS", "-Duser.home=" + home.toAbsolutePath().normalize());
        return Map.copyOf(environment);
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
        final HttpRequest.Builder builder = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header(
                        "X-Railix-Creator-Token",
                        baseUri.getRawFragment().substring("token=".length())
                )
                .method(method, body.isEmpty()
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        final HttpRequest request = builder.build();
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

    private static Path packagedExecutable(final Path appImageDirectory) {
        if (isMac()) {
            return appImageDirectory.resolve("railix.app").resolve("Contents").resolve("MacOS").resolve("railix");
        }
        return appImageDirectory.resolve("railix").resolve("bin").resolve("railix");
    }

    private static Path bundledRuntimeHome(final Path appImageDirectory) {
        if (isMac()) {
            return appImageDirectory.resolve("railix.app").resolve("Contents").resolve("runtime").resolve("Contents").resolve("Home");
        }
        return appImageDirectory.resolve("railix").resolve("lib").resolve("runtime");
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").startsWith("Mac");
    }

    private static void installSqlBundle(final Path workspace) throws Exception {
        final Path root = workspace.resolve("sql-bundle");
        Files.createDirectories(root);
        final RailixValue.ObjectValue contract = StepContractJson.value(StepDefinition.named(SQL_STEP_ID, "1")
                .input("target", StepDefinition.Input.path(StepDefinition.PathAccess.READ_WRITE)
                        .defaultPath("context", "result"))
                .define());
        final String className = "thirdparty.sqlprobe.SqlProbeStep";
        final String entry = className.replace('.', '/') + ".class";
        final RailixValue.ObjectValue manifestValue = RailixValue.object(Map.of(
                "format", RailixValue.number(1),
                "steps", RailixValue.array(List.of(RailixValue.object(Map.of(
                        "contract", contract,
                        "contract_digest", RailixValue.string("sha256:" + digest(
                                RailixJson.write(contract).getBytes(StandardCharsets.UTF_8)
                        )),
                        "implementation", RailixValue.string(className),
                        "implementation_entry", RailixValue.string(entry)
                ))))
        ));
        final String manifest = RailixJson.write(manifestValue);
        final Path classes = compileBundleClass(root, className, """
                package thirdparty.sqlprobe;
                import dev.nanonative.railix.core.step.StepHandler;
                import dev.nanonative.railix.core.step.StepInput;
                import dev.nanonative.railix.core.step.StepResult;
                import dev.nanonative.railix.core.value.RailixValue;
                import java.sql.JDBCType;
                public final class SqlProbeStep implements StepHandler {
                    public SqlProbeStep() {
                    }
                    @Override
                    public StepResult run(final StepInput input) {
                        return StepResult.outcome(input.primaryOutcome())
                                .write("target", RailixValue.number(
                                        JDBCType.VARCHAR.getVendorTypeNumber().longValue()
                                ));
                    }
                }
                """);
        final Path bundle = root.resolve("bundle.jar");
        jar(bundle, Map.of(
                entry, Files.readAllBytes(classes.resolve(entry)),
                "META-INF/railix/steps.json", manifest.getBytes(StandardCharsets.UTF_8)
        ));
        final String digest = digest(Files.readAllBytes(bundle));
        final Path store = workspace.resolve(".railix/artifacts");
        Files.createDirectories(store);
        Files.copy(bundle, store.resolve(digest + ".jar"));
        final RailixValue.ObjectValue manifestStep = (RailixValue.ObjectValue)
                ((RailixValue.ArrayValue) manifestValue.values().get("steps")).values().getFirst();
        final RailixValue.ObjectValue lock = RailixValue.object(Map.of(
                "format", RailixValue.number(1),
                "artifacts", RailixValue.array(List.of(RailixValue.object(Map.of(
                        "digest", RailixValue.string("sha256:" + digest),
                        "origin", RailixValue.string("test:sql-bundle"),
                        "size", RailixValue.number(Files.size(bundle))
                )))),
                "bundles", RailixValue.array(List.of(RailixValue.object(Map.of(
                        "artifact", RailixValue.string("sha256:" + digest),
                        "runtime", RailixValue.array(List.of()),
                        "steps", RailixValue.array(List.of(RailixValue.object(Map.of(
                                "contract", manifestStep.values().get("contract_digest"),
                                "id", contract.values().get("id"),
                                "implementation", RailixValue.string(className),
                                "implementation_entry", RailixValue.string(entry),
                                "version", contract.values().get("version")
                        ))))
                ))))
        ));
        Files.writeString(
                workspace.resolve("railix.dependencies.lock.json"),
                RailixJson.write(lock),
                StandardCharsets.UTF_8
        );
    }

    private static Path compileBundleClass(
            final Path root,
            final String className,
            final String source
    ) throws IOException {
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).isNotNull();
        final Path sourceFile = root.resolve("src").resolve(className.replace('.', '/') + ".java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, source, StandardCharsets.UTF_8);
        final Path classes = root.resolve("classes");
        Files.createDirectories(classes);
        try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            files.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classes));
            final boolean compiled = compiler.getTask(
                    null,
                    files,
                    null,
                    List.of("-classpath", System.getProperty("java.class.path")),
                    null,
                    files.getJavaFileObjects(sourceFile)
            ).call();
            assertThat(compiled).isTrue();
        }
        return classes;
    }

    private static void jar(final Path target, final Map<String, byte[]> entries) throws IOException {
        try (OutputStream output = Files.newOutputStream(target);
             JarOutputStream jar = new JarOutputStream(output)) {
            for (final Map.Entry<String, byte[]> entry : entries.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .toList()) {
                final JarEntry item = new JarEntry(entry.getKey());
                item.setTime(0L);
                jar.putNextEntry(item);
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
    }

    private static String digest(final byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (final NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private static String sqlProject() {
        return """
                {
                  "format":1,
                  "id":"sql-probe",
                  "nodes":[
                    {"id":"app","use":"railix.app","inputs":{}},
                    {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[{
                      "name":"default","payload":[]
                    }]},
                    {"id":"sql","use":"%s","inputs":{}}
                  ],
                  "links":[
                    {"from":"app.start","to":"command"},
                    {"from":"command.next","to":"sql"},
                    {"from":"sql.next","to":"end"}
                  ]
                }
                """.formatted(SQL_STEP_ID);
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

        private static PackagedCreator startDefault(final Path executable, final Path directory) throws Exception {
            return startDefault(executable, directory, Map.of());
        }

        private static PackagedCreator startDefault(
                final Path executable,
                final Path directory,
                final Map<String, String> environment
        ) throws Exception {
            return start(command(executable, directory, environment, "creator"));
        }

        private static PackagedCreator start(final Path executable, final Path project) throws Exception {
            return start(executable, project, Map.of());
        }

        private static PackagedCreator start(
                final Path executable,
                final Path project,
                final Map<String, String> environment
        ) throws Exception {
            return start(command(
                    executable,
                    project.toAbsolutePath().normalize().getParent(),
                    environment,
                    "creator",
                    project.toString(),
                    "0"
            ));
        }

        private static ProcessBuilder command(
                final Path executable,
                final Path directory,
                final Map<String, String> environment,
                final String... arguments
        ) {
            assertThat(Files.isExecutable(executable)).isTrue();
            final List<String> command = new ArrayList<>();
            command.add(executable.toString());
            command.addAll(Arrays.asList(arguments));
            final ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(directory.toFile())
                    .redirectErrorStream(true);
            builder.environment().putAll(environment);
            return instrument(builder);
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
            final List<String> lines = new ArrayList<>();
            try {
                String line;
                while ((line = output.readLine()) != null) {
                    lines.add(line);
                    if (line.startsWith("Railix Creator ")) {
                        final String payload = line.substring("Railix Creator ".length()).trim();
                        if (payload.startsWith("http://") || payload.startsWith("https://")) {
                            ready.complete(line);
                        } else {
                            ready.completeExceptionally(new IOException(line));
                        }
                        return;
                    }
                }
                ready.completeExceptionally(new IOException(
                        "Packaged Creator exited before readiness: " + String.join(" | ", lines)
                ));
            } catch (final IOException exception) {
                ready.completeExceptionally(exception);
            }
        }
    }

    static ProcessBuilder instrument(final ProcessBuilder builder) {
        coverageAgent().ifPresent(argument -> builder.environment().merge(
                "JAVA_TOOL_OPTIONS",
                isolatedCoverage(argument),
                (existing, added) -> existing + " " + added
        ));
        return builder;
    }

    static ProcessBuilder instrumentJava(final ProcessBuilder builder) {
        coverageAgent().ifPresent(argument -> {
            final List<String> command = new ArrayList<>(builder.command());
            command.add(1, isolatedCoverage(argument));
            builder.command(command);
        });
        return builder;
    }

    private static Optional<String> coverageAgent() {
        return ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(argument -> argument.startsWith("-javaagent:") && argument.contains("jacoco"))
                .findFirst();
    }

    private static String isolatedCoverage(final String agent) {
        final String option = "destfile=";
        final int start = agent.indexOf(option);
        if (start < 0) {
            return agent;
        }
        final int value = start + option.length();
        final int separator = agent.indexOf(',', value);
        final int end = separator < 0 ? agent.length() : separator;
        final Path current = Path.of(agent.substring(value, end));
        final Path isolated = current.resolveSibling(
                "jacoco-child-" + ProcessHandle.current().pid() + "-"
                        + COVERAGE_CHILD.incrementAndGet() + ".exec"
        );
        return (agent.substring(0, value) + isolated + agent.substring(end))
                .replace(",append=false", ",append=true");
    }

    private record ProcessResult(int status, String output) {
    }

    private record Assignment(String field, String json) {
    }

}
