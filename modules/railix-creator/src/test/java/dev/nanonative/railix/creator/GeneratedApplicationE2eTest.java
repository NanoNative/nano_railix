package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.project.CompileResult;
import dev.nanonative.railix.core.project.ProjectCompiler;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;
import dev.nanonative.railix.stdlib.StandardLibrary;
import dev.nanonative.railix.stdlib.StandardStepHandlers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import thirdparty.production.ProductionRuntimeSequenceStep;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Execution(ExecutionMode.SAME_THREAD)
final class GeneratedApplicationE2eTest {
    @TempDir
    Path directory;

    @Test
    void creatorPublishesDurableGeneratedSourceAndExecutableJar() throws Exception {
        final Path project = project(directory.resolve("workspace"));

        try (CreatorServer creator = start(project)) {
            final RailixValue.ObjectValue application = application(creator.baseUri());
            final Path source = path(application, "source_path");
            final Path jar = path(application, "build_path");

            assertThat(source).isRegularFile().startsWith(project.getParent());
            assertThat(jar).isRegularFile().startsWith(project.getParent());
            assertThat(source).hasFileName("RailixApplication.java");
            assertThat(jar).hasExtension("jar");
        }
    }

    @Test
    void compilerRejectsAnApplicationBeyondTheTriggerLimit() {
        final TriggerScale scale = triggerScale(513);

        assertThat(ProjectCompiler.compileApplication(scale.source(), scale.catalog()))
                .isInstanceOfSatisfying(CompileResult.Rejected.class, rejected ->
                        assertThat(rejected.diagnostics()).singleElement().satisfies(diagnostic ->
                                assertThat(diagnostic).extracting(
                                        dev.nanonative.railix.core.project.Diagnostic::code,
                                        dev.nanonative.railix.core.project.Diagnostic::message,
                                        dev.nanonative.railix.core.project.Diagnostic::path
                                ).containsExactly(
                                        "PROJECT_APPLICATION_TRIGGER_LIMIT",
                                        "Generated applications support at most 512 Triggers.",
                                        "nodes"
                                )
                        )
                );
    }

    @Test
    void developmentApplicationBuildsAtTheTriggerLimit() throws Exception {
        final TriggerScale scale = triggerScale(512);
        final Path project = project(directory.resolve("development-trigger-limit"), scale.source());
        final CompileResult result = ProjectCompiler.compileApplication(scale.source(), scale.catalog());
        assertThat(result).isInstanceOf(CompileResult.Compiled.class);

        assertThat(ApplicationBuilder.build(project, (CompileResult.Compiled) result).jar()).isRegularFile();
    }

    @Test
    void productionApplicationBuildsAtTheTriggerLimit() throws Exception {
        final TriggerScale scale = triggerScale(512);
        final Path project = project(directory.resolve("production-trigger-limit"), scale.source());
        final CompileResult result = ProjectCompiler.compileApplication(scale.source(), scale.catalog());
        assertThat(result).isInstanceOf(CompileResult.Compiled.class);

        assertThat(ApplicationBuilder.buildProduction(project, (CompileResult.Compiled) result).jar())
                .isRegularFile();
    }

    @Test
    void productionApplicationBuildsOneWidePlanPartitionWithoutOversizedInitialization() throws Exception {
        final TriggerScale scale = widePlanPartition();
        final Path project = project(directory.resolve("wide-plan-partition"), scale.source());

        assertThat(productionArtifact(project, scale.source(), scale.catalog()).jar()).isRegularFile();
    }

    @Test
    void generatedSourceCallsOnlyReachableStepImplementations() throws Exception {
        final Path project = project(directory.resolve("workspace"));

        try (CreatorServer creator = start(project)) {
            final String source = Files.readString(
                    path(application(creator.baseUri()), "source_path"),
                    StandardCharsets.UTF_8
            );

            assertThat(source).contains(
                    "new dev.nanonative.railix.stdlib.StandardStepHandlers.Cli()",
                    "new dev.nanonative.railix.stdlib.PrimitiveStepHandlers.Lowercase()",
                    "HANDLER_0.run(input)",
                    "HANDLER_1.run(input)",
                    "switch (current)"
            ).doesNotContain(
                    "CompiledProject",
                    "invoke(final String use",
                    "Unknown Step:",
                    "dev.nanonative.railix.creator",
                    "ProjectCompiler",
                    "StandardLibrary.catalog()",
                    "StandardLibrary.",
                    "PrimitiveSteps.",
                    "railix.field-manipulation"
            );
        }
    }

    @Test
    void generatedJarRunsTheCompiledCliFlowWithoutCreator() throws Exception {
        final Path project = project(directory.resolve("workspace"));
        try (CreatorServer creator = start(project)) {
            assertThat(string(application(creator.baseUri()), "state")).isEqualTo("running");
        }
        final Path jar = productionArtifact(project, Files.readString(project, StandardCharsets.UTF_8)).jar();

        final ProcessResult result = runJar(jar, "Hello RAILIX");

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).isEqualTo("\"hello railix\"");
    }

    @Test
    void generatedJarExecutesFlowWhenStepIsDeclaredBeforeItsTrigger() throws Exception {
        final String source = outOfOrderLowercase();
        final Path project = project(directory.resolve("out-of-order"), source);

        final ProcessResult result = runJar(productionArtifact(project, source).jar(), "ORDER FREE");

        assertThat(result).isEqualTo(new ProcessResult(0, "\"order free\""));
    }

    @Test
    void productionJarOmitsTheDevelopmentCapability() throws Exception {
        final ApplicationBuilder.Artifact artifact = productionArtifact();

        try (JarFile jar = new JarFile(artifact.jar().toFile())) {
            assertThat(jar.stream().map(java.util.jar.JarEntry::getName)).noneMatch(name ->
                    name.startsWith("dev/nanonative/railix/development/")
                            || name.endsWith("RailixDevelopmentApplication.class")
            );
        }
    }

    @Test
    void productionJarOmitsTheGeneratedObservationCollector() throws Exception {
        final ApplicationBuilder.Artifact artifact = productionArtifact();

        try (JarFile jar = new JarFile(artifact.jar().toFile())) {
            assertThat(jar.stream().map(java.util.jar.JarEntry::getName))
                    .noneMatch(name -> name.contains("ObservationCapture"));
        }
    }

    @Test
    void productionJarOmitsCompilerAndStepAuthoringContracts() throws Exception {
        final ApplicationBuilder.Artifact artifact = productionArtifact();

        try (JarFile jar = new JarFile(artifact.jar().toFile())) {
            assertThat(jar.stream().map(java.util.jar.JarEntry::getName)).noneMatch(name ->
                    name.startsWith("dev/nanonative/railix/core/project/ApplicationPlan")
                            || name.startsWith("dev/nanonative/railix/core/project/ProjectCompiler")
                            || name.startsWith("dev/nanonative/railix/core/step/StepDefinition")
                            || name.contains("WorkflowRuntime$Binding")
                            || name.contains("WorkflowRuntime$CallPlan")
                            || name.contains("WorkflowRuntime$JsonBinding")
                            || name.contains("WorkflowRuntime$ChoiceBinding")
                            || name.contains("WorkflowRuntime$CandidatesBinding")
                            || name.contains("WorkflowRuntime$MatcherGroupsBinding")
                            || name.contains("WorkflowRuntime$StepsBinding")
            );
        }
    }

    @Test
    void productionSourceOmitsDevelopmentExecutionAndObservationCode() throws Exception {
        final String source = Files.readString(productionArtifact().source(), StandardCharsets.UTF_8);

        assertThat(source).doesNotContain(
                "DevelopmentRuntime",
                "WorkflowRuntime.Capture",
                "public RunResult run(",
                " preview(",
                " observe(",
                "railix.development"
        );
    }

    @Test
    void productionApplicationPublicApiContainsOnlyProductionIngress() throws Exception {
        final Path jar = productionArtifact().jar();
        final String api = tool(
                "javap",
                "-classpath", jar.toString(),
                "-public", "dev.nanonative.railix.core.project.RailixApplication"
        );

        assertThat(api.lines().map(String::strip).filter(line -> line.contains("(")).toList())
                .containsExactly(
                        "public static void main(java.lang.String[]);",
                        "public java.lang.String projectId();",
                        "public dev.nanonative.railix.core.project.WorkflowRuntime$SourceResult "
                                + "runSource(java.lang.String, java.util.Map<java.lang.String, "
                                + "dev.nanonative.railix.core.value.RailixValue>);"
                );
    }

    @Test
    void productionApplicationBytecodeNeverCallsTheObservationPath() throws Exception {
        final Path jar = productionArtifact().jar();

        assertThat(tool(
                "javap",
                "-classpath", jar.toString(),
                "-c", "-p", "dev.nanonative.railix.core.project.RailixApplication"
        )).doesNotContain(
                "WorkflowRuntime$Execution.observe",
                "DevelopmentRuntime",
                "ObservationCapture"
        );
    }

    @Test
    void productionApplicationConstantPoolOmitsDevelopmentSymbols() throws Exception {
        final Path jar = productionArtifact().jar();

        assertThat(tool(
                "javap",
                "-classpath", jar.toString(),
                "-v", "dev.nanonative.railix.core.project.RailixApplication"
        )).doesNotContain(
                "DevelopmentRuntime",
                "ObservationCapture",
                "/v1/run/",
                "/v1/preview/",
                "railix.development"
        );
    }

    @Test
    void productionJarHasNoHttpServerModuleDependency() throws Exception {
        final Path jar = productionArtifact().jar();

        assertThat(tool("jdeps", "-q", "--print-module-deps", jar.toString()))
                .doesNotContain("jdk.httpserver");
    }

    @Test
    void productionJarUsesTheProductionApplicationAsItsMainClass() throws Exception {
        final ApplicationBuilder.Artifact artifact = productionArtifact();

        try (JarFile jar = new JarFile(artifact.jar().toFile())) {
            assertThat(jar.getManifest().getMainAttributes().getValue(java.util.jar.Attributes.Name.MAIN_CLASS))
                    .isEqualTo("dev.nanonative.railix.core.project.RailixApplication");
        }
    }

    @Test
    void productionJarRunsTheCompiledCliFlow() throws Exception {
        final ApplicationBuilder.Artifact artifact = productionArtifact();

        final ProcessResult result = runJar(artifact.jar(), "PRODUCTION");

        assertThat(result).isEqualTo(new ProcessResult(0, "\"production\""));
    }

    @Test
    void productionJarKeepsHttpFlagAsCliArgumentWhenThereIsNoHttpTrigger() throws Exception {
        final ApplicationBuilder.Artifact artifact = productionArtifact();

        final ProcessResult result = runJar(artifact.jar(), "--railix-http");

        assertThat(result).isEqualTo(new ProcessResult(0, "\"--railix-http\""));
    }

    @Test
    void productionJarServesTheCompiledHttpFlow() throws Exception {
        final String source = httpEchoProject();
        final Path project = project(directory.resolve("http-echo"), source);
        final ApplicationBuilder.Artifact artifact = productionArtifact(project, source);

        try (HttpProcess server = startHttpJar(artifact.jar())) {
            final HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(server.uri().resolve("/echo?source=test"))
                            .timeout(Duration.ofSeconds(20))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"Hello HTTP\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("{\"message\":\"Hello HTTP\"}");
            assertThat(response.headers().allValues("x-railix-test"))
                    .containsExactly("first", "second");

            final HttpResponse<String> invalidJson = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(server.uri().resolve("/echo"))
                            .timeout(Duration.ofSeconds(20))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            assertThat(invalidJson.statusCode()).isEqualTo(400);
            assertThat(invalidJson.body()).contains("HTTP_JSON_INVALID");

            final HttpResponse<String> oversized = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(server.uri().resolve("/echo"))
                            .timeout(Duration.ofSeconds(20))
                            .POST(HttpRequest.BodyPublishers.ofString("x".repeat(1024 * 1024 + 1)))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            assertThat(oversized.statusCode()).isEqualTo(413);
            assertThat(oversized.body()).contains("HTTP_BODY_TOO_LARGE");

            final HttpResponse<String> invalidUtf8 = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(server.uri().resolve("/echo"))
                            .timeout(Duration.ofSeconds(20))
                            .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[] {(byte) 0xc3, 0x28}))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            assertThat(invalidUtf8.statusCode()).isEqualTo(400);
            assertThat(invalidUtf8.body()).contains("HTTP_BODY_UTF8_INVALID");
        }
    }

    @ParameterizedTest(name = "production JAR routes Switch case: {0}")
    @MethodSource("switchRoutes")
    void productionJarRoutesTheFirstAcceptedSwitchCaseOrOtherwise(
            final String scenario,
            final String cases,
            final String expected
    ) throws Exception {
        final String source = switchProject(cases);
        final Path project = project(directory.resolve("production-switch-" + scenario), source);

        final ProcessResult result = runJar(productionArtifact(project, source).jar(), "go");

        assertThat(result).isEqualTo(new ProcessResult(0, RailixJson.write(RailixValue.string(expected))));
    }

    @ParameterizedTest(name = "generated child routes Switch case: {0}")
    @MethodSource("switchRoutes")
    void generatedChildRoutesTheFirstAcceptedCaseOrOtherwise(
            final String scenario,
            final String cases,
            final String expected
    ) throws Exception {
        final Path project = project(directory.resolve("switch-" + scenario), switchProject(cases));

        try (CreatorServer creator = start(project)) {
            assertThat(runResult(creator.baseUri(), "go")).isEqualTo(RailixValue.string(expected));
        }
    }

    @Test
    void generatedChildKeepsAuthoredOutcomeTablesLocalToEachSwitchNode() throws Exception {
        final Path project = project(directory.resolve("switch-node-local"), nodeLocalSwitchProject());

        try (CreatorServer creator = start(project)) {
            assertThat(runResult(creator.baseUri(), "go")).isEqualTo(RailixValue.string("both"));
        }
    }

    @ParameterizedTest(name = "generated JAR preserves {0}")
    @MethodSource("javaStringEscapes")
    void generatedJarPreservesCanonicalJsonStringLiterals(
            final String scenario,
            final String value
    ) throws Exception {
        final String projectSource = literalResult(value);
        final Path project = project(directory.resolve(scenario), projectSource);

        final ProcessResult result = runJar(productionArtifact(project, projectSource).jar());

        assertThat(result).isEqualTo(new ProcessResult(0, RailixJson.write(RailixValue.string(value))));
    }

    @Test
    void generatedJarRunsA129StepFlowAcrossBoundedCodePartitions() throws Exception {
        final String projectSource = lowercaseChain(129);
        final Path project = project(directory.resolve("workspace"), projectSource);
        final Path jar = productionArtifact(project, projectSource).jar();

        final ProcessResult result = runJar(jar, "CROSS CHUNK");

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).isEqualTo("\"cross chunk\"");
    }

    @Test
    void generatedJarExecutesEveryMaximumFlowNodeExactlyOnceInAuthoredOrder() throws Exception {
        final String projectSource = ordinalChain(16_381);
        final Path project = project(directory.resolve("maximum-application"), projectSource);
        final StepCatalog catalog = GeneratedApplicationFixture.installedCatalog(
                project.getParent(),
                List.of(sequenceDefinition()),
                ProductionRuntimeSequenceStep.class
        );

        final Path jar = productionArtifact(project, projectSource, catalog).jar();
        final ProcessResult result = runJar(jar, "0");

        assertThat(result).isEqualTo(new ProcessResult(0, "16381"));
    }

    @Test
    void applicationAboveThe16384NodeLimitIsRejectedBeforeBuild() {
        final CompileResult result = ProjectCompiler.compileApplication(
                lowercaseChain(16_383),
                StandardLibrary.catalog()
        );

        assertThat(result).isInstanceOfSatisfying(CompileResult.Rejected.class, rejected ->
                assertThat(rejected.diagnostics()).singleElement().satisfies(diagnostic ->
                        assertThat(diagnostic).extracting(
                                dev.nanonative.railix.core.project.Diagnostic::code,
                                dev.nanonative.railix.core.project.Diagnostic::message,
                                dev.nanonative.railix.core.project.Diagnostic::path
                        ).containsExactly(
                                "PROJECT_APPLICATION_NODE_LIMIT",
                                "Generated applications support at most 16384 nodes.",
                                "nodes"
                        )
                )
        );
    }

    @Test
    void generatedApplicationExecutes256NodesInAuthoredOrder() throws Exception {
        final Path project = project(directory.resolve("workspace"), linearProject(256));

        try (CreatorServer creator = start(project)) {
            final HttpResponse<String> response = request(
                    creator.baseUri(),
                    "POST",
                    "/api/run/command",
                    "{\"payload\":{}}"
            );
            final RailixJson.Parsed parsed = (RailixJson.Parsed) RailixJson.parse(response.body());
            final RailixValue.ObjectValue body = (RailixValue.ObjectValue) parsed.value();
            final List<RailixValue> steps = ((RailixValue.ArrayValue) body.values().get("steps")).values();

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(steps.stream().map(GeneratedApplicationE2eTest::stepId))
                    .containsExactlyElementsOf(java.util.stream.IntStream.range(0, 256)
                            .mapToObj(index -> "step-" + index)
                            .toList());
        }
    }

    @Test
    void generatedJarRejectsASparseArrayWriteWithoutAllocatingTheGap() throws Exception {
        final String projectSource = sparseArrayWrite(100_000);
        final Path project = project(directory.resolve("workspace"), projectSource);
        final Path jar = productionArtifact(project, projectSource).jar();

        final ProcessResult result = runJar(jar, "bounded");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output()).contains("RUN_ARRAY_TARGET_SPARSE nodes[2].returns.value");
    }

    @Test
    void generatedJarAcceptsTheMaximumBoundedSparseArrayGap() throws Exception {
        final String projectSource = sparseArrayWrite(1_024);
        final Path project = project(directory.resolve("bounded-gap"), projectSource);
        final ProcessResult result = runJar(
                productionArtifact(project, projectSource).jar(),
                "bounded"
        );

        assertThat(result).extracting(ProcessResult::exitCode, ProcessResult::output)
                .containsExactly(0, "");
    }

    @Test
    void generatedJarRejectsOnePastTheMaximumSparseArrayGap() throws Exception {
        final String projectSource = sparseArrayWrite(1_025);
        final Path project = project(directory.resolve("unbounded-gap"), projectSource);
        final ProcessResult result = runJar(
                productionArtifact(project, projectSource).jar(),
                "unbounded"
        );

        assertThat(result.exitCode() + ":" + result.output())
                .startsWith("2:RUN_ARRAY_TARGET_SPARSE nodes[2].returns.value");
    }

    @Test
    void existingArraySizeIsSubtractedFromTheSparseGap() throws Exception {
        final String projectSource = sparseArrayWrite(1_025);
        final Path project = project(directory.resolve("existing-array"), projectSource);

        try (CreatorServer creator = start(project)) {
            final HttpResponse<String> response = request(
                    creator.baseUri(),
                    "POST",
                    "/api/run/command",
                    "{\"payload\":{\"arguments\":[\"bounded\"],\"items\":[null]}}"
            );

            assertThat(response.statusCode()).isEqualTo(200);
        }
    }

    @Test
    void generatedJarRunsAfterProjectAndCreatorDocumentsAreDeleted() throws Exception {
        final Path project = project(directory.resolve("workspace"));
        try (CreatorServer creator = start(project)) {
            assertThat(string(application(creator.baseUri()), "state")).isEqualTo("running");
        }
        final Path jar = productionArtifact(project, Files.readString(project, StandardCharsets.UTF_8)).jar();
        Files.delete(project);
        Files.deleteIfExists(project.resolveSibling("railix.creator.json"));

        final ProcessResult result = runJar(jar, "Independent");

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).isEqualTo("\"independent\"");
    }

    @Test
    void generatedJarExcludesCreatorCompilerAndWebUi() throws Exception {
        final Path project = project(directory.resolve("workspace"));

        try (CreatorServer creator = start(project);
             JarFile jar = new JarFile(path(application(creator.baseUri()), "build_path").toFile())) {
            final List<String> entries = jar.stream().map(entry -> entry.getName()).toList();

            assertThat(entries).contains("dev/nanonative/railix/core/project/RailixApplication.class");
            assertThat(entries).contains(
                    "dev/nanonative/railix/stdlib/StandardStepHandlers$Cli.class",
                    "dev/nanonative/railix/stdlib/PrimitiveStepHandlers$Lowercase.class"
            );
            assertThat(entries).noneMatch(entry ->
                    entry.endsWith("CompiledProject.class")
                            || entry.contains("CompiledProject$")
                            || entry.contains("ProjectCompiler")
                            || entry.contains("CompileResult")
                            || entry.endsWith("StepCatalog.class")
                            || entry.contains("StepCatalog$")
                            || entry.endsWith("StepContractJson.class")
                            || entry.startsWith("dev/nanonative/railix/creator/")
                            || entry.endsWith("StandardLibrary.class")
                            || entry.endsWith("PrimitiveSteps.class")
                            || entry.endsWith("StandardStepHandlers$FieldManipulation.class")
                            || entry.endsWith("PrimitiveStepHandlers$Uppercase.class")
                            || entry.startsWith("dev/nanonative/railix/creator/web/")
            );
        }
    }

    @Test
    void runningApplicationPidIdentifiesThePublishedJar() throws Exception {
        final Path project = project(directory.resolve("workspace"));

        try (CreatorServer creator = start(project)) {
            final RailixValue.ObjectValue application = application(creator.baseUri());
            final long pid = number(application, "pid");
            final Path jar = path(application, "build_path");

            assertThat(ProcessHandle.of(pid)).isPresent();
            assertThat(ProcessHandle.of(pid).orElseThrow().info().arguments())
                    .hasValueSatisfying(arguments -> assertThat(arguments).containsSequence("-jar", jar.toString()));
        }
    }

    @Test
    void equivalentProjectsProduceIdenticalGeneratedSourceAndJarBytes() throws Exception {
        final Build first = build(directory.resolve("first"));
        final Build second = build(directory.resolve("second"));

        assertThat(Files.readAllBytes(first.source())).isEqualTo(Files.readAllBytes(second.source()));
        assertThat(digest(first.jar())).isEqualTo(digest(second.jar()));
    }

    @Test
    void unchangedProjectReusesTheRunningArtifactAndPid() throws Exception {
        final Path project = project(directory.resolve("workspace"));

        try (CreatorServer creator = start(project)) {
            final RailixValue.ObjectValue before = application(creator.baseUri());

            final HttpResponse<String> response = request(
                    creator.baseUri(),
                    "POST",
                    "/api/project",
                    Files.readString(project, StandardCharsets.UTF_8)
            );
            final RailixValue.ObjectValue after = application(creator.baseUri());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(number(after, "pid")).isEqualTo(number(before, "pid"));
            assertThat(path(after, "build_path")).isEqualTo(path(before, "build_path"));
            assertThat(string(after, "fingerprint")).isEqualTo(string(before, "fingerprint"));
        }
    }

    @Test
    void alteredCachedJarIsRebuiltBeforeItCanRun() throws Exception {
        final Path project = project(directory.resolve("workspace"));
        final Path jar;
        final String expectedDigest;
        try (CreatorServer creator = start(project)) {
            jar = path(application(creator.baseUri()), "build_path");
            expectedDigest = digest(jar);
        }
        Files.write(jar, new byte[]{0x42}, StandardOpenOption.APPEND);
        assertThat(digest(jar)).isNotEqualTo(expectedDigest);

        try (CreatorServer creator = start(project)) {
            final Path rebuilt = path(application(creator.baseUri()), "build_path");
            assertThat(rebuilt).isEqualTo(jar);
            assertThat(digest(rebuilt)).isEqualTo(expectedDigest);
            assertThat(runResult(creator.baseUri(), "INTEGRITY")).isEqualTo(RailixValue.string("integrity"));
        }
    }

    @Test
    void creatorBuildDoesNotCreateATemporaryProjectDocument() throws Exception {
        final Set<Path> before = temporaryProjects();
        final Path project = project(directory.resolve("workspace"));

        try (CreatorServer ignored = start(project)) {
            assertThat(temporaryProjects()).isEqualTo(before);
        }
    }

    @Test
    void applicationFingerprintIsThePublishedJarDigest() throws Exception {
        final Path project = project(directory.resolve("workspace"));

        try (CreatorServer creator = start(project)) {
            final RailixValue.ObjectValue application = application(creator.baseUri());

            assertThat(string(application, "fingerprint"))
                    .isEqualTo("sha256:" + digest(path(application, "build_path")));
        }
    }

    @Test
    void secondCreatorCannotOwnTheSameProject() throws Exception {
        final Path project = project(directory.resolve("workspace"));

        try (CreatorServer creator = start(project)) {
            assertThatThrownBy(() -> start(project))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("Project is already open in another Creator");
            assertThat(string(application(creator.baseUri()), "state")).isEqualTo("running");
        }
    }

    @Test
    void projectCanBeOpenedAgainAfterCreatorCloses() throws Exception {
        final Path project = project(directory.resolve("workspace"));
        start(project).close();

        try (CreatorServer reopened = start(project)) {
            assertThat(string(application(reopened.baseUri()), "state")).isEqualTo("running");
        }
    }

    @Test
    void functionalEditPublishesNewJarAndPidThenRemovesPreviousArtifact() throws Exception {
        final Path project = project(directory.resolve("workspace"));

        try (CreatorServer creator = start(project)) {
            final RailixValue.ObjectValue before = application(creator.baseUri());
            final Path previousJar = path(before, "build_path");
            final String uppercase = CreatorProjects.lowercaseCli().replace("text.lowercase", "text.uppercase");

            final HttpResponse<String> response = request(creator.baseUri(), "POST", "/api/project", uppercase);
            final RailixValue.ObjectValue after = application(creator.baseUri());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(number(after, "pid")).isNotEqualTo(number(before, "pid"));
            assertThat(path(after, "build_path")).isNotEqualTo(previousJar).isRegularFile();
            assertThat(previousJar).doesNotExist();
            assertThat(runResult(creator.baseUri(), "Hello Railix"))
                    .isEqualTo(RailixValue.string("HELLO RAILIX"));
        }
    }

    @Test
    void exampleOnlyEditPersistsWithoutRestartingTheApplication() throws Exception {
        final Path project = project(directory.resolve("workspace"));

        try (CreatorServer creator = start(project)) {
            final RailixValue.ObjectValue before = application(creator.baseUri());
            final String edited = CreatorProjects.lowercaseCli().replace("Hello RAILIX", "Different Example");

            final HttpResponse<String> response = request(creator.baseUri(), "POST", "/api/project", edited);
            final RailixValue.ObjectValue after = application(creator.baseUri());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(number(after, "pid")).isEqualTo(number(before, "pid"));
            assertThat(path(after, "build_path")).isEqualTo(path(before, "build_path"));
            assertThat(Files.readString(project, StandardCharsets.UTF_8)).contains("Different Example");
        }
    }

    @Test
    void invalidEditKeepsTheRunningApplicationAndArtifact() throws Exception {
        final Path project = project(directory.resolve("workspace"));

        try (CreatorServer creator = start(project)) {
            final RailixValue.ObjectValue before = application(creator.baseUri());

            final HttpResponse<String> response = request(
                    creator.baseUri(),
                    "POST",
                    "/api/project",
                    "{\"format\":1,\"id\":\"broken\",\"nodes\":[],\"links\":[]}"
            );
            final RailixValue.ObjectValue after = application(creator.baseUri());

            assertThat(response.statusCode()).isEqualTo(422);
            assertThat(number(after, "pid")).isEqualTo(number(before, "pid"));
            assertThat(path(after, "build_path")).isEqualTo(path(before, "build_path")).isRegularFile();
            assertThat(runResult(creator.baseUri(), "Still Works"))
                    .isEqualTo(RailixValue.string("still works"));
        }
    }

    @Test
    void unchangedProjectRestartsAStoppedApplicationFromTheSameArtifact() throws Exception {
        final Path project = project(directory.resolve("workspace"));

        try (CreatorServer creator = start(project)) {
            final RailixValue.ObjectValue before = application(creator.baseUri());
            final ProcessHandle process = ProcessHandle.of(number(before, "pid")).orElseThrow();
            process.destroyForcibly();
            assertThat(process.onExit().get(10, TimeUnit.SECONDS).isAlive()).isFalse();

            final HttpResponse<String> response = request(
                    creator.baseUri(),
                    "POST",
                    "/api/project",
                    Files.readString(project, StandardCharsets.UTF_8)
            );
            final RailixValue.ObjectValue after = application(creator.baseUri());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(number(after, "pid")).isNotEqualTo(number(before, "pid"));
            assertThat(path(after, "build_path")).isEqualTo(path(before, "build_path"));
        }
    }

    @Test
    void creatorRemovesAbandonedBuildStagingBeforeBuilding() throws Exception {
        final Path project = project(directory.resolve("workspace"));
        final Path stale = project.getParent().resolve(".railix/build/.building-abandoned");
        Files.createDirectories(stale);
        Files.writeString(stale.resolve("partial"), "incomplete", StandardCharsets.UTF_8);

        try (CreatorServer ignored = start(project)) {
            assertThat(stale).doesNotExist();
        }
    }

    @Test
    void repeatedRollingBuildsRetainOnlyTheRunningArtifact() throws Exception {
        final Path project = project(directory.resolve("workspace"));
        final String lowercase = CreatorProjects.lowercaseCli();
        final String uppercase = lowercase.replace("text.lowercase", "text.uppercase");

        try (CreatorServer creator = start(project)) {
            for (final String source : List.of(uppercase, lowercase, uppercase, lowercase)) {
                assertThat(request(creator.baseUri(), "POST", "/api/project", source).statusCode()).isEqualTo(200);
            }
            try (var builds = Files.list(project.getParent().resolve(".railix/build"))) {
                assertThat(builds.filter(Files::isDirectory).toList()).hasSize(1);
            }
        }
    }

    private Build build(final Path workspace) throws Exception {
        final Path project = project(workspace);
        try (CreatorServer creator = start(project)) {
            final RailixValue.ObjectValue application = application(creator.baseUri());
            return new Build(
                    path(application, "source_path"),
                    path(application, "build_path")
            );
        }
    }

    private static Path project(final Path workspace) throws IOException {
        return project(workspace, CreatorProjects.lowercaseCli());
    }

    private static Path project(final Path workspace, final String source) throws IOException {
        Files.createDirectories(workspace);
        final Path project = workspace.resolve("railix.project.json");
        Files.writeString(project, source, StandardCharsets.UTF_8);
        return project;
    }

    private static Stream<Arguments> switchRoutes() {
        return Stream.of(
                Arguments.of("first-match", switchCases("go", "go"), "first"),
                Arguments.of("later-match", switchCases("other", "go"), "second"),
                Arguments.of("otherwise", switchCases("other", "still-other"), "otherwise")
        );
    }

    private static String switchCases(final String firstExpected, final String secondExpected) {
        return """
                [{"outcome":"case-first","option":"field","inputs":{
                    "field":["context","payload","arguments",0]},"when":{"transforms":[],"all":[[
                    {"use":"value.equals","inputs":{"expected":"%s"}}]]}},
                 {"outcome":"case-second","option":"field","inputs":{
                    "field":["context","payload","arguments",0]},"when":{"transforms":[],"all":[[
                    {"use":"value.equals","inputs":{"expected":"%s"}}]]}}]
                """.formatted(firstExpected, secondExpected);
    }

    private static String switchProject(final String cases) {
        return """
                {"format":1,"id":"switch-generated","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[
                    {"name":"route","payload":["go"]}]},
                  {"id":"switch","use":"railix.switch","inputs":{"cases":%s}},
                  {"id":"first","use":"railix.field-manipulation","inputs":{
                    "field":["context","result"],"value":[{"option":"literal","inputs":{
                    "literal":"first"},"when":{"transforms":[],"all":[]}}],"steps":[]}},
                  {"id":"second","use":"railix.field-manipulation","inputs":{
                    "field":["context","result"],"value":[{"option":"literal","inputs":{
                    "literal":"second"},"when":{"transforms":[],"all":[]}}],"steps":[]}},
                  {"id":"fallback","use":"railix.field-manipulation","inputs":{
                    "field":["context","result"],"value":[{"option":"literal","inputs":{
                    "literal":"otherwise"},"when":{"transforms":[],"all":[]}}],"steps":[]}}
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"switch"},
                  {"from":"switch.case-first","to":"first"},
                  {"from":"switch.case-second","to":"second"},
                  {"from":"switch.otherwise","to":"fallback"},
                  {"from":"first.next","to":"end"},
                  {"from":"second.next","to":"end"},
                  {"from":"fallback.next","to":"end"}
                ]}
                """.formatted(cases);
    }

    private static String nodeLocalSwitchProject() {
        return """
                {"format":1,"id":"node-local-switch","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[
                    {"name":"route","payload":["go"]}]},
                  {"id":"switch-one","use":"railix.switch","inputs":{"cases":[{
                    "outcome":"case-one","option":"field","inputs":{
                    "field":["context","payload","arguments",0]},"when":{"transforms":[],"all":[[
                    {"use":"value.equals","inputs":{"expected":"go"}}]]}}]}},
                  {"id":"switch-two","use":"railix.switch","inputs":{"cases":[{
                    "outcome":"case-two","option":"field","inputs":{
                    "field":["context","payload","arguments",0]},"when":{"transforms":[],"all":[[
                    {"use":"value.equals","inputs":{"expected":"go"}}]]}}]}},
                  {"id":"success","use":"railix.field-manipulation","inputs":{
                    "field":["context","result"],"value":[{"option":"literal","inputs":{
                    "literal":"both"},"when":{"transforms":[],"all":[]}}],"steps":[]}},
                  {"id":"first-fallback","use":"railix.field-manipulation","inputs":{
                    "field":["context","result"],"value":[{"option":"literal","inputs":{
                    "literal":"first-fallback"},"when":{"transforms":[],"all":[]}}],"steps":[]}},
                  {"id":"second-fallback","use":"railix.field-manipulation","inputs":{
                    "field":["context","result"],"value":[{"option":"literal","inputs":{
                    "literal":"second-fallback"},"when":{"transforms":[],"all":[]}}],"steps":[]}}
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"switch-one"},
                  {"from":"switch-one.case-one","to":"switch-two"},
                  {"from":"switch-one.otherwise","to":"first-fallback"},
                  {"from":"switch-two.case-two","to":"success"},
                  {"from":"switch-two.otherwise","to":"second-fallback"},
                  {"from":"success.next","to":"end"},
                  {"from":"first-fallback.next","to":"end"},
                  {"from":"second-fallback.next","to":"end"}
                ]}
                """;
    }

    private static String lowercaseChain(final int steps) {
        final StringBuilder nodes = new StringBuilder("""
                {"format":1,"id":"partitioned-cli","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{
                    "target":["context","payload","arguments"]
                  },"examples":[{"name":"cross-chunk","payload":["CROSS CHUNK"]}]}
                """);
        final StringBuilder links = new StringBuilder("""
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"lowercase-0"}
                """);
        for (int index = 0; index < steps; index++) {
            nodes.append("""
                    ,{"id":"lowercase-%d","use":"text.lowercase","inputs":{},
                      "receives":{"value":[%s]},
                      "returns":{"value":["context","result"]}}
                    """.formatted(
                    index,
                    index == 0
                            ? "\"context\",\"payload\",\"arguments\",0"
                            : "\"context\",\"result\""
            ));
            links.append("""
                    ,{"from":"lowercase-%d.ok","to":%s}
                    """.formatted(
                    index,
                    index + 1 == steps ? "\"end\"" : "\"lowercase-" + (index + 1) + "\""
            ));
        }
        return nodes.append(links).append("]}").toString();
    }

    private static TriggerScale triggerScale(final int count) {
        final StepDefinition[] definitions = new StepDefinition[count + 1];
        definitions[0] = StepDefinition.named("railix.app", "1")
                .kind(StepDefinition.Kind.APP)
                .define();
        final StringBuilder nodes = new StringBuilder("""
                {"format":1,"id":"trigger-scale","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}}
                """);
        final StringBuilder links = new StringBuilder("],\"links\":[");
        for (int index = 0; index < count; index++) {
            final String suffix = Integer.toString(index);
            final String definition = "example.trigger-" + suffix;
            final String node = "trigger-" + suffix;
            definitions[index + 1] = StepDefinition.named(definition, "1")
                    .kind(StepDefinition.Kind.TRIGGER)
                    .source("application.source-" + suffix)
                    .run(StandardStepHandlers.Cli.class);
            nodes.append(",{")
                    .append("\"id\":\"").append(node)
                    .append("\",\"use\":\"").append(definition)
                    .append("\",\"inputs\":{},\"examples\":[{\"name\":\"default\",\"payload\":[]}]}");
            if (index > 0) {
                links.append(',');
            }
            links.append("{\"from\":\"app.start\",\"to\":\"").append(node).append("\"},")
                    .append("{\"from\":\"").append(node).append(".next\",\"to\":\"end\"}");
        }
        return new TriggerScale(nodes.append(links).append("]}").toString(), StepCatalog.of(definitions));
    }

    private static String outOfOrderLowercase() {
        return """
                {"format":1,"id":"out-of-order","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"lowercase","use":"text.lowercase","inputs":{},
                    "receives":{"value":["context","payload","arguments",0]},
                    "returns":{"value":["context","result"]}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{
                    "target":["context","payload","arguments"]
                  },"examples":[{"name":"order","payload":["ORDER FREE"]}]}
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"lowercase"},
                  {"from":"lowercase.ok","to":"end"}
                ]}
                """;
    }

    private static String ordinalChain(final int steps) {
        final StringBuilder nodes = new StringBuilder("""
                {"format":1,"id":"maximum-ordinal-cli","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[{
                    "name":"maximum","payload":["0"]
                  }]},
                  {"id":"number","use":"text.to-number","inputs":{},
                    "receives":{"value":["context","payload","arguments",0]},
                    "returns":{"value":["context","ordinal"]}}
                """);
        final StringBuilder links = new StringBuilder("""
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"number"},
                  {"from":"number.ok","to":"sequence-0"},
                  {"from":"number.invalid","to":"end"}
                """);
        for (int index = 0; index < steps; index++) {
            nodes.append("""
                    ,{"id":"sequence-%d","use":"production.sequence","inputs":{"expected":%d},
                      "receives":{"value":["context","ordinal"]},
                      "returns":{"value":[%s]}}
                    """.formatted(
                    index,
                    index,
                    index + 1 == steps ? "\"context\",\"result\"" : "\"context\",\"ordinal\""
            ));
            links.append("""
                    ,{"from":"sequence-%d.ok","to":%s},
                    {"from":"sequence-%d.invalid","to":"end"}
                    """.formatted(
                    index,
                    index + 1 == steps ? "\"end\"" : "\"sequence-" + (index + 1) + "\"",
                    index
            ));
        }
        return nodes.append(links).append("]}").toString();
    }

    private static StepDefinition sequenceDefinition() {
        return StepDefinition.named("production.sequence", "1")
                .primaryOutcome("ok")
                .receive("value", ValueShape.NUMBER)
                .returns("value", ValueShape.NUMBER)
                .input("expected", StepDefinition.Input.json(ValueShape.NUMBER)
                        .defaultValue(RailixValue.number(0)))
                .outcome("invalid")
                .run(ProductionRuntimeSequenceStep.class);
    }

    private static String sparseArrayWrite(final int index) {
        return """
                {"format":1,"id":"sparse-array-write","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[{
                    "name":"sparse","payload":["bounded"]
                  }]},
                  {"id":"lowercase","use":"text.lowercase","inputs":{},
                    "receives":{"value":["context","payload","arguments",0]},
                    "returns":{"value":["context","payload","items",%d]}}
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"lowercase"},
                  {"from":"lowercase.ok","to":"end"}
                ]}
                """.formatted(index);
    }

    private static String literalResult(final String value) {
        return """
                {"format":1,"id":"literal-result","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[{
                    "name":"literal","payload":[]
                  }]},
                  {"id":"literal","use":"railix.field-manipulation","inputs":{
                    "field":["context","result"],
                    "value":[{"option":"literal","inputs":{"literal":%s}}],
                    "steps":[]
                  }}
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"literal"},
                  {"from":"literal.next","to":"end"}
                ]}
                """.formatted(RailixJson.write(RailixValue.string(value)));
    }

    private static String httpEchoProject() {
        return """
                {"format":1,"id":"http-echo","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"request","use":"railix.trigger.http","inputs":{},"examples":[{
                    "name":"post-json","payload":{
                      "method":"POST","path":"/echo","query":"","headers":{},"body":{"message":"Hello HTTP"}
                    }
                  }]},
	                  {"id":"headers","use":"railix.field-manipulation","inputs":{
	                    "field":["context","headers"],
	                    "value":[{"option":"literal","inputs":{"literal":{"X-Railix-Test":["first","second"]}}}],
	                    "steps":[]
	                  }},
	                  {"id":"echo","use":"railix.field-manipulation","inputs":{
	                    "field":["context","body"],
	                    "value":[{"option":"field","inputs":{"source":["context","payload","request","body"]}}],
	                    "steps":[]
	                  }}
	                ],"links":[
	                  {"from":"app.start","to":"request"},
	                  {"from":"request.next","to":"headers"},
	                  {"from":"headers.next","to":"echo"},
	                  {"from":"echo.next","to":"end"}
	                ]}
                """;
    }

    private static Stream<Arguments> javaStringEscapes() {
        return Stream.of(
                Arguments.of("backslash", "\\"),
                Arguments.of("quote", "\""),
                Arguments.of("backspace", "\b"),
                Arguments.of("form-feed", "\f"),
                Arguments.of("newline", "\n"),
                Arguments.of("carriage-return", "\r"),
                Arguments.of("tab", "\t"),
                Arguments.of("control", "\u0000"),
                Arguments.of("non-ascii", "\u00e4")
        );
    }

    private static String linearProject(final int steps) {
        final StringBuilder project = new StringBuilder("""
                {"format":1,"id":"indexed-plan","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[{
                    "name":"example","payload":[],"context":{"payload":{}}
                  }]}
                """);
        for (int index = 0; index < steps; index++) {
            project.append(",{\"id\":\"step-").append(index)
                    .append("\",\"use\":\"railix.field-manipulation\",\"inputs\":{}}");
        }
        project.append("],\"links\":[{\"from\":\"app.start\",\"to\":\"command\"},")
                .append("{\"from\":\"command.next\",\"to\":\"")
                .append(steps == 0 ? "end" : "step-0").append("\"}");
        for (int index = 0; index < steps; index++) {
            project.append(",{\"from\":\"step-").append(index).append(".next\",\"to\":\"")
                    .append(index + 1 == steps ? "end" : "step-" + (index + 1)).append("\"}");
        }
        return project.append("]}").toString();
    }

    private static TriggerScale widePlanPartition() {
        final StepDefinition.Builder wide = StepDefinition.named("partition.wide", "1");
        for (int index = 0; index < 100; index++) {
            wide.input(
                    "value_" + index,
                    StepDefinition.Input.json(ValueShape.ANY).defaultValue(RailixValue.number(index))
            );
        }
        final StepCatalog catalog = StepCatalog.of(
                StepDefinition.named("railix.app", "1").kind(StepDefinition.Kind.APP).define(),
                StepDefinition.named("partition.trigger", "1")
                        .kind(StepDefinition.Kind.TRIGGER)
                        .source("application.arguments")
                        .run(StandardStepHandlers.Cli.class),
                wide.run(StandardStepHandlers.FieldManipulation.class)
        );
        final StringBuilder nodes = new StringBuilder("""
                {"format":1,"id":"wide-plan-partition","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"trigger","use":"partition.trigger","inputs":{},
                   "examples":[{"name":"default","payload":[]}]}
                """);
        final StringBuilder links = new StringBuilder("""
                ],"links":[
                  {"from":"app.start","to":"trigger"},
                  {"from":"trigger.next","to":"wide-0"}
                """);
        for (int index = 0; index < 126; index++) {
            nodes.append(",{\"id\":\"wide-").append(index)
                    .append("\",\"use\":\"partition.wide\",\"inputs\":{}}");
            links.append(",{\"from\":\"wide-").append(index).append(".next\",\"to\":")
                    .append(index == 125 ? "\"end\"}" : "\"wide-" + (index + 1) + "\"}");
        }
        return new TriggerScale(nodes.append(links).append("]}").toString(), catalog);
    }

    private CreatorServer start(final Path project) throws IOException {
        return CreatorServer.start(0, project, directory.resolve("railix-home"));
    }

    private ApplicationBuilder.Artifact productionArtifact() throws IOException {
        final String source = CreatorProjects.lowercaseCli();
        final Path project = project(directory.resolve("production"), source);
        return productionArtifact(project, source);
    }

    private static ApplicationBuilder.Artifact productionArtifact(
            final Path project,
            final String source
    ) throws IOException {
        return productionArtifact(project, source, StandardLibrary.catalog());
    }

    private static ApplicationBuilder.Artifact productionArtifact(
            final Path project,
            final String source,
            final StepCatalog catalog
    ) throws IOException {
        final CompileResult result = ProjectCompiler.compileApplication(source, catalog);
        assertThat(result).isInstanceOf(CompileResult.Compiled.class);
        return ApplicationBuilder.buildProduction(project, (CompileResult.Compiled) result);
    }

    private static RailixValue runResult(final URI baseUri, final String argument) throws Exception {
        final HttpResponse<String> response = request(
                baseUri,
                "POST",
                "/api/run/command",
                "{\"payload\":{\"arguments\":[" + RailixJson.write(RailixValue.string(argument)) + "]}}"
        );
        assertThat(response.statusCode()).isEqualTo(200);
        final RailixJson.Result parsed = RailixJson.parse(response.body());
        assertThat(parsed).isInstanceOf(RailixJson.Parsed.class);
        return ((RailixValue.ObjectValue) ((RailixValue.ObjectValue) ((RailixJson.Parsed) parsed).value())
                .values().get("context")).values().get("result");
    }

    private static RailixValue.ObjectValue application(final URI baseUri) throws Exception {
        final RailixJson.Result parsed = RailixJson.parse(request(baseUri, "GET", "/api/application", "").body());
        assertThat(parsed).isInstanceOf(RailixJson.Parsed.class);
        return (RailixValue.ObjectValue) ((RailixJson.Parsed) parsed).value();
    }

    private static HttpResponse<String> request(
            final URI baseUri,
            final String method,
            final String path,
            final String body
    ) throws IOException, InterruptedException {
        final HttpRequest.Builder builder = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(Duration.ofSeconds(20))
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

    private static ProcessResult runJar(final Path jar, final String... arguments) throws Exception {
        final List<String> command = new java.util.ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-jar");
        command.add(jar.toString());
        command.addAll(List.of(arguments));
        final Process process = RailixPackageIT.instrumentJava(new ProcessBuilder(command))
                .redirectErrorStream(true)
                .start();
        process.getOutputStream().close();
        try (var reader = Executors.newVirtualThreadPerTaskExecutor()) {
            final var output = reader.submit(() ->
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip());
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Generated application did not finish within 20 seconds.");
            }
            return new ProcessResult(process.exitValue(), output.get(5, TimeUnit.SECONDS));
        }
    }

    private static HttpProcess startHttpJar(final Path jar) throws Exception {
        final List<String> command = new java.util.ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-jar");
        command.add(jar.toString());
        command.add("--railix-http");
        command.add("127.0.0.1");
        command.add("0");
        final Process process = RailixPackageIT.instrumentJava(new ProcessBuilder(command))
                .redirectErrorStream(true)
                .start();
        process.getOutputStream().close();
        final BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(),
                StandardCharsets.UTF_8
        ));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final String line = executor.submit(reader::readLine).get(20, TimeUnit.SECONDS);
            if (line == null || !line.startsWith("Railix HTTP ")) {
                process.destroyForcibly();
                throw new IOException("Generated HTTP application did not report readiness: " + line);
            }
            return new HttpProcess(process, URI.create(line.substring("Railix HTTP ".length())));
        } catch (final Exception failure) {
            process.destroyForcibly();
            throw failure;
        }
    }

    private static String tool(final String name, final String... arguments) throws Exception {
        final List<String> command = new java.util.ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", name).toString());
        command.addAll(List.of(arguments));
        final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        final String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException(name + " did not finish within 20 seconds.");
        }
        if (process.exitValue() != 0) {
            throw new IOException(name + " failed with exit " + process.exitValue() + ": " + output);
        }
        return output;
    }

    private static Set<Path> temporaryProjects() throws IOException {
        try (var files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return files.filter(path -> path.getFileName().toString().startsWith("railix-development-"))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }

    private static String digest(final Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    private static Path path(final RailixValue.ObjectValue value, final String name) {
        return Path.of(string(value, name)).toAbsolutePath().normalize();
    }

    private static String string(final RailixValue.ObjectValue value, final String name) {
        return ((RailixValue.StringValue) value.values().get(name)).value();
    }

    private static long number(final RailixValue.ObjectValue value, final String name) {
        return ((RailixValue.NumberValue) value.values().get(name)).value().longValueExact();
    }

    private static String stepId(final RailixValue value) {
        return ((RailixValue.StringValue) ((RailixValue.ObjectValue) value).values().get("id")).value();
    }

    private record Build(Path source, Path jar) {
    }

    private record ProcessResult(int exitCode, String output) {
    }

    private record HttpProcess(Process process, URI uri) implements AutoCloseable {
        @Override
        public void close() {
            process.destroyForcibly();
        }
    }

    private record TriggerScale(String source, StepCatalog catalog) {
    }
}
