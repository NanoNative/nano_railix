package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.project.CompileResult;
import dev.nanonative.railix.core.project.ProjectCompiler;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;
import dev.nanonative.railix.development.ArtifactLease;
import dev.nanonative.railix.stdlib.StandardLibrary;
import dev.nanonative.railix.stdlib.StandardStepHandlers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
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
    void applicationArtifactDoesNotPublishAnExpandedRuntimeCopy() throws Exception {
        final ApplicationBuilder.Artifact artifact = productionArtifact();

        assertThat(artifact.directory().resolve("content")).doesNotExist();
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

        try (ApplicationBuilder.DevelopmentBuild build = ApplicationBuilder.build(
                project,
                (CompileResult.Compiled) result
        )) {
            assertThat(build.jar()).isRegularFile();
        }
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
                    "DevelopmentRuntime.Trace.invoke(input, HANDLER_0)",
                    "DevelopmentRuntime.Trace.invoke(input, HANDLER_1)",
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
    void generatedApplicationContinuesWhenNestedTraceObservationStops() throws Exception {
        final String projectSource = CreatorProjects.nestedLowercase();
        final Path project = project(directory.resolve("nested-trace-backpressure"), projectSource);
        final CompileResult result = ProjectCompiler.compileApplication(projectSource, StandardLibrary.catalog());
        assertThat(result).isInstanceOf(CompileResult.Compiled.class);
        try (ApplicationBuilder.DevelopmentBuild build = ApplicationBuilder.build(
                project,
                (CompileResult.Compiled) result
        )) {
            final ApplicationBuilder.Artifact artifact = build.artifact();
        final Path probeSource = directory.resolve(
                "probe/dev/nanonative/railix/core/project/TraceBackpressureProbe.java"
        );
        final Path probeClasses = directory.resolve("probe-classes");
        Files.createDirectories(probeSource.getParent());
        Files.createDirectories(probeClasses);
        Files.writeString(probeSource, """
                package dev.nanonative.railix.core.project;

                import dev.nanonative.railix.core.runtime.RunResult;
                import dev.nanonative.railix.core.value.RailixJson;
                import dev.nanonative.railix.core.value.RailixValue;

                public final class TraceBackpressureProbe {
                    public static void main(final String[] arguments) {
                        final RailixValue.ObjectValue context = RailixValue.object(java.util.Map.of(
                                "payload", RailixValue.object(java.util.Map.of(
                                        "arguments", RailixValue.array(java.util.List.of()),
                                        "text", RailixValue.string("Hello RAILIX")
                                ))
                        ));
                        final RunResult result = RailixApplication.runtime().trace(
                                "command",
                                context,
                                true,
                                event -> !(event.values().get("type")
                                        instanceof RailixValue.StringValue type)
                                        || !type.value().equals("step_start")
                                        || !(event.values().get("invocation")
                                        instanceof RailixValue.StringValue invocation)
                                        || !invocation.value().equals("lowercase-text.inputs.steps[0]")
                        );
                        if (!(result instanceof RunResult.Succeeded succeeded)) {
                            throw new AssertionError("Generated flow did not succeed: " + result);
                        }
                        System.out.print(RailixJson.write(succeeded.context().values().get("result")));
                    }
                }
                """, StandardCharsets.UTF_8);

            tool(
                    "javac",
                    "-classpath", artifact.jar().toString(),
                    "-d", probeClasses.toString(),
                    probeSource.toString()
            );

            assertThat(tool(
                    "java",
                    "-classpath", probeClasses + java.io.File.pathSeparator + artifact.jar(),
                    "dev.nanonative.railix.core.project.TraceBackpressureProbe"
            )).isEqualTo("\"hello railix\"");
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
    void productionJarContainsNoDevelopmentOrTraceSurface() throws Exception {
        final ApplicationBuilder.Artifact artifact = productionArtifact();
        final String[] forbiddenSymbols = {
                "dev/nanonative/railix/development/",
                "dev.nanonative.railix.development",
                "RailixDevelopmentApplication",
                "DevelopmentRuntime",
                "DevelopmentRuntime$Metrics",
                "ExampleSuite",
                "/v1/metrics",
                "startFlow",
                "startStep",
                "finishFlow",
                "finishStep",
                "TraceExecution",
                "TraceSink",
                "traceExecute_",
                "trace_",
                "use_",
                "/v1/run/",
                "/v1/trace/",
                "/v1/preview/",
                "ObservationCapture",
                "WorkflowRuntime$Capture",
                "RunResult$StepExecution"
        };

        try (JarFile jar = new JarFile(artifact.jar().toFile())) {
            final var entries = jar.stream().toList();
            assertThat(entries).extracting(java.util.jar.JarEntry::getName).noneMatch(name ->
                    name.startsWith("dev/nanonative/railix/development/")
                            || name.endsWith("RailixDevelopmentApplication.class")
                            || name.endsWith("ExampleSuite.class")
                            || name.contains("ExampleSuite$")
                            || name.contains("TraceExecution")
                            || name.endsWith("WorkflowRuntime$Capture.class")
                            || name.endsWith("RunResult$StepExecution.class")
            );
            for (final var entry : entries) {
                if (entry.isDirectory()) {
                    continue;
                }
                try (var input = jar.getInputStream(entry)) {
                    assertThat(new String(input.readAllBytes(), StandardCharsets.ISO_8859_1))
                            .as("production JAR entry %s", entry.getName())
                            .doesNotContain(forbiddenSymbols);
                }
            }
        }

        assertThat(Files.readString(artifact.source(), StandardCharsets.UTF_8))
                .doesNotContain(forbiddenSymbols)
                .doesNotContain("trace(", "trace_", "use_");

        assertThat(tool(
                "javap",
                "-classpath", artifact.jar().toString(),
                "-c", "-p", "-v", "dev.nanonative.railix.core.project.RailixApplication"
        )).doesNotContain(forbiddenSymbols)
                .doesNotContain("trace(", "trace_", "use_");

        assertThat(tool(
                "javap",
                "-classpath", artifact.jar().toString(),
                "-p", "dev.nanonative.railix.core.project.WorkflowRuntime$StepCall"
        )).contains("dev.nanonative.railix.core.step.StepResult run("
                        + "dev.nanonative.railix.core.step.StepInput) throws java.lang.InterruptedException;")
                .doesNotContain("java.lang.String");
    }

    @Test
    void productionArtifactOmitsAuthoredExampleNamesAndPayloads() throws Exception {
        final String exampleName = "authored-example-name-sentinel-7d2f";
        final String examplePayload = "authored-example-payload-sentinel-4c91";
        final String source = authoredExample(exampleName, examplePayload);
        final Path project = project(directory.resolve("production-example-omission"), source);
        final ApplicationBuilder.Artifact artifact = productionArtifact(project, source);

        assertThat(Files.readString(artifact.source(), StandardCharsets.UTF_8))
                .doesNotContain(exampleName, examplePayload);
        try (JarFile jar = new JarFile(artifact.jar().toFile())) {
            for (final var entry : jar.stream().filter(item -> !item.isDirectory()).toList()) {
                try (var input = jar.getInputStream(entry)) {
                    assertThat(new String(input.readAllBytes(), StandardCharsets.ISO_8859_1))
                            .as("production JAR entry %s", entry.getName())
                            .doesNotContain(exampleName, examplePayload);
                }
            }
            assertThat(jar.getJarEntry("META-INF/railix/examples.json")).isNull();
        }
    }

    @Test
    void developmentArtifactEmbedsTheCompiledExampleManifest() throws Exception {
        final String exampleName = "development-example-name-sentinel-7d2f";
        final String examplePayload = "development-example-payload-sentinel-4c91";
        final String source = authoredExample(exampleName, examplePayload);
        final Path project = project(directory.resolve("development-example-manifest"), source);
        final CompileResult result = ProjectCompiler.compileApplication(source, StandardLibrary.catalog());
        assertThat(result).isInstanceOf(CompileResult.Compiled.class);
        try (ApplicationBuilder.DevelopmentBuild build = ApplicationBuilder.build(
                project,
                (CompileResult.Compiled) result
        )) {
            final ApplicationBuilder.Artifact artifact = build.artifact();

            try (JarFile jar = new JarFile(artifact.jar().toFile())) {
                final var entry = jar.getJarEntry("META-INF/railix/examples.json");
                assertThat(entry).isNotNull();
                try (var input = jar.getInputStream(entry)) {
                    assertThat(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                            .contains(exampleName, examplePayload);
                }
            }
        }
    }

    @Test
    void exampleChangesDoNotChangeProductionSourceOrJarBytes() throws Exception {
        final String firstSource = CreatorProjects.lowercaseCli();
        final String secondSource = firstSource.replace("Hello RAILIX", "Different Example");
        final CompileResult firstResult = ProjectCompiler.compileApplication(
                firstSource,
                StandardLibrary.catalog()
        );
        final CompileResult secondResult = ProjectCompiler.compileApplication(
                secondSource,
                StandardLibrary.catalog()
        );
        assertThat(firstResult).isInstanceOf(CompileResult.Compiled.class);
        assertThat(secondResult).isInstanceOf(CompileResult.Compiled.class);
        final CompileResult.Compiled first = (CompileResult.Compiled) firstResult;
        final CompileResult.Compiled second = (CompileResult.Compiled) secondResult;
        final Path firstProject = project(directory.resolve("production-example-first"), firstSource);
        final Path secondProject = project(directory.resolve("production-example-second"), secondSource);

        assertThat(second.productionApplicationSource()).isEqualTo(first.productionApplicationSource());
        assertThat(second.developmentResources()).isNotEqualTo(first.developmentResources());
        assertThat(digest(ApplicationBuilder.buildProduction(firstProject, first).jar()))
                .isEqualTo(digest(ApplicationBuilder.buildProduction(secondProject, second).jar()));
        try (ApplicationBuilder.DevelopmentBuild firstBuild = ApplicationBuilder.build(firstProject, first);
             ApplicationBuilder.DevelopmentBuild secondBuild = ApplicationBuilder.build(secondProject, second)) {
            assertThat(digest(firstBuild.jar())).isNotEqualTo(digest(secondBuild.jar()));
        }
    }

    @Test
    void productionExecutionCarriesNoDevelopmentModeState() throws Exception {
        final Path jar = productionArtifact().jar();

        assertThat(tool(
                "javap",
                "-classpath", jar.toString(),
                "-p", "dev.nanonative.railix.core.project.WorkflowRuntime$Execution"
        )).doesNotContain("boolean test", "DevelopmentRuntime", "Metrics", "Trace");
    }

    @Test
    void productionExecutionOmitsTheDevelopmentTestFlagFromRuntimeContext() throws Exception {
        final String source = runtimeContextProject();
        final Path project = project(directory.resolve("production-runtime-context"), source);

        assertThat(runJar(productionArtifact(project, source).jar()))
                .isEqualTo(new ProcessResult(0, "{\"trigger\":\"command\"}"));
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
            assertThat(exampleResult(creator.baseUri(), "command:0")).isEqualTo(RailixValue.string(expected));
        }
    }

    @Test
    void generatedChildKeepsAuthoredOutcomeTablesLocalToEachSwitchNode() throws Exception {
        final Path project = project(directory.resolve("switch-node-local"), nodeLocalSwitchProject());

        try (CreatorServer creator = start(project)) {
            assertThat(exampleResult(creator.baseUri(), "command:0")).isEqualTo(RailixValue.string("both"));
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
            final long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            RailixValue.ObjectValue examples;
            do {
                examples = CreatorServerE2eSupport.examples(creator.baseUri());
                if (number(examples, "completed") == 1) {
                    break;
                }
                Thread.sleep(20);
            } while (System.nanoTime() < deadline);
            assertThat(number(examples, "completed")).isEqualTo(1);

            final HttpResponse<String> response = request(
                    creator.baseUri(),
                    "GET",
                    "/api/examples/command:0/view",
                    ""
            );
            final RailixJson.Result parsed = RailixJson.parse(response.body());
            assertThat(parsed).isInstanceOf(RailixJson.Parsed.class);
            final RailixValue.ObjectValue view = (RailixValue.ObjectValue) ((RailixJson.Parsed) parsed).value();
            final RailixValue.ArrayValue nodes = (RailixValue.ArrayValue) view.values().get("nodes");
            final List<Integer> steps = nodes.values().stream()
                    .map(RailixValue.NumberValue.class::cast)
                    .map(value -> value.value().intValueExact())
                    .toList();

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(steps).containsExactlyElementsOf(java.util.stream.IntStream.range(2, 258)
                            .boxed()
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
        final String projectSource = sparseArrayWrite(1_025).replace(
                "\"name\":\"sparse\",\"payload\":[\"bounded\"]",
                "\"name\":\"sparse\",\"payload\":[\"bounded\"],\"context\":{\"payload\":{\"items\":[null]}}"
        );
        final Path project = project(directory.resolve("existing-array"), projectSource);

        try (CreatorServer creator = start(project)) {
            assertThat(number(awaitExamples(creator.baseUri(), 1), "succeeded")).isEqualTo(1);
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
            assertThat(exampleResult(creator.baseUri(), "command:0"))
                    .isEqualTo(RailixValue.string("hello railix"));
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
    void reopeningAChangedProjectRemovesThePreviousSessionArtifact() throws Exception {
        final Path project = project(directory.resolve("workspace"));
        final Path previous;
        try (CreatorServer creator = start(project)) {
            previous = path(application(creator.baseUri()), "build_path").getParent();
        }
        Files.writeString(
                project,
                CreatorProjects.lowercaseCli().replace("text.lowercase", "text.uppercase"),
                StandardCharsets.UTF_8
        );

        try (CreatorServer reopened = start(project)) {
            assertThat(path(application(reopened.baseUri()), "build_path").getParent())
                    .isNotEqualTo(previous)
                    .isDirectory();
            assertThat(previous).doesNotExist();
        }
    }

    @Test
    void developmentPruningNeverDeletesAProductionArtifact() throws Exception {
        final Path workspace = directory.resolve("production-artifact");
        final String source = CreatorProjects.lowercaseCli();
        final Path project = project(workspace, source);
        final ApplicationBuilder.Artifact production = productionArtifact(project, source);

        try (CreatorServer ignored = start(project)) {
            assertThat(production.jar()).isRegularFile();
        }
    }

    @Test
    void developmentPublicationLeaseProtectsAnArtifactBeforeItsProcessStarts() throws Exception {
        final Path workspace = directory.resolve("publication-lease");
        final String lowercase = CreatorProjects.lowercaseCli();
        final Path project = project(workspace, lowercase);
        final CompileResult result = ProjectCompiler.compileApplication(lowercase, StandardLibrary.catalog());
        assertThat(result).isInstanceOf(CompileResult.Compiled.class);
        final Path reserved;

        try (ApplicationBuilder.DevelopmentBuild publication = ApplicationBuilder.build(
                project,
                (CompileResult.Compiled) result
        )) {
            reserved = publication.artifact().directory();
            Files.writeString(
                    project,
                    lowercase.replace("text.lowercase", "text.uppercase"),
                    StandardCharsets.UTF_8
            );
            try (CreatorServer ignored = start(project)) {
                assertThat(reserved).isDirectory();
            }
        }

        try (CreatorServer ignored = start(project)) {
            assertThat(reserved).doesNotExist();
        }
    }

    @Test
    void developmentPublicationLeasePreventsRuntimeCleanupDuringProcessHandoff() throws Exception {
        final Path workspace = directory.resolve("runtime-publication-lease");
        final String source = CreatorProjects.lowercaseCli();
        final Path project = project(workspace, source);
        final CompileResult result = ProjectCompiler.compileApplication(source, StandardLibrary.catalog());
        assertThat(result).isInstanceOf(CompileResult.Compiled.class);
        final Path pendingRuntime;
        final ApplicationBuilder.Artifact artifact;

        try (ApplicationBuilder.DevelopmentBuild publication = ApplicationBuilder.build(
                project,
                (CompileResult.Compiled) result
        )) {
            artifact = publication.artifact();
            pendingRuntime = artifact.directory().resolve(".railix-runtime/pending");
            Files.createDirectories(pendingRuntime);

            ApplicationBuilder.cleanRuntime(artifact);

            assertThat(pendingRuntime).isDirectory();
        }

        ApplicationBuilder.cleanRuntime(artifact);
        assertThat(pendingRuntime).doesNotExist();
    }

    @Test
    void creatorStartupRemovesRuntimeDataLeftByAStoppedApplication() throws Exception {
        final Path workspace = directory.resolve("stale-runtime-startup");
        final String source = CreatorProjects.lowercaseCli();
        final Path project = project(workspace, source);
        final CompileResult result = ProjectCompiler.compileApplication(source, StandardLibrary.catalog());
        assertThat(result).isInstanceOf(CompileResult.Compiled.class);
        final Path staleRuntime;

        try (ApplicationBuilder.DevelopmentBuild publication = ApplicationBuilder.build(
                project,
                (CompileResult.Compiled) result
        )) {
            staleRuntime = publication.artifact().directory().resolve(".railix-runtime/stale");
        }
        Files.createDirectories(staleRuntime);
        Files.writeString(staleRuntime.resolve("trace.ndjson"), "stale", StandardCharsets.UTF_8);

        try (CreatorServer ignored = start(project)) {
            assertThat(staleRuntime).doesNotExist();
        }
    }

    @Test
    void runtimeCleanupRemovesAnUnlockedRootLeaseFile() throws Exception {
        final Path workspace = directory.resolve("stale-root-lease");
        final String source = CreatorProjects.lowercaseCli();
        final Path project = project(workspace, source);
        final CompileResult result = ProjectCompiler.compileApplication(source, StandardLibrary.catalog());
        assertThat(result).isInstanceOf(CompileResult.Compiled.class);
        final ApplicationBuilder.Artifact artifact;

        try (ApplicationBuilder.DevelopmentBuild publication = ApplicationBuilder.build(
                project,
                (CompileResult.Compiled) result
        )) {
            artifact = publication.artifact();
        }
        final Path staleLease = artifact.directory().resolve(".railix-runtime/.lease-stale.lock");
        Files.createDirectories(staleLease.getParent());
        Files.writeString(staleLease, "", StandardCharsets.UTF_8);

        ApplicationBuilder.cleanRuntime(artifact);

        assertThat(staleLease).doesNotExist();
    }

    @Test
    void liveUnrelatedPidCannotPreserveAStaleDevelopmentArtifact() throws Exception {
        final Path workspace = directory.resolve("stale-pid");
        final String lowercase = CreatorProjects.lowercaseCli();
        final Path project = project(workspace, lowercase);
        final CompileResult result = ProjectCompiler.compileApplication(lowercase, StandardLibrary.catalog());
        assertThat(result).isInstanceOf(CompileResult.Compiled.class);
        final ApplicationBuilder.Artifact stale;
        try (ApplicationBuilder.DevelopmentBuild publication = ApplicationBuilder.build(
                project,
                (CompileResult.Compiled) result
        )) {
            stale = publication.artifact();
        }
        Files.createDirectories(stale.directory().resolve(".railix-runtime")
                .resolve(Long.toString(ProcessHandle.current().pid())));
        Files.writeString(
                project,
                lowercase.replace("text.lowercase", "text.uppercase"),
                StandardCharsets.UTF_8
        );

        try (CreatorServer ignored = start(project)) {
            assertThat(stale.directory()).doesNotExist();
        }
    }

    @Test
    void liveUnrelatedPidCannotPreserveAStaleBuildStagingDirectory() throws Exception {
        final Path workspace = directory.resolve("stale-staging-pid");
        final Path project = project(workspace);
        final Path stale = workspace.resolve(".railix/build/.building-"
                + ProcessHandle.current().pid() + "-stale");
        Files.createDirectories(stale);
        Files.writeString(stale.resolve("partial"), "stale", StandardCharsets.UTF_8);

        try (CreatorServer ignored = start(project)) {
            assertThat(stale).doesNotExist();
        }
    }

    @Test
    void activeBuildStagingLeasePreventsConcurrentCleanup() throws Exception {
        final Path workspace = directory.resolve("active-staging-lease");
        final Path project = project(workspace);
        final Path staging = workspace.resolve(".railix/build/.building-active");

        try (ArtifactLease ignoredLease = ArtifactLease.acquire(staging);
             CreatorServer ignoredCreator = start(project)) {
            assertThat(staging).isDirectory();
        }

        try (CreatorServer ignored = start(project)) {
            assertThat(staging).doesNotExist();
        }
    }

    @Test
    void startingCreatorPreservesAnAlreadyRunningDevelopmentArtifact() throws Exception {
        final Path workspace = directory.resolve("running-build-root");
        final Path project = project(workspace);
        final DevelopmentRuntimeGeneratedProcess.Build running = developmentBuild(
                project,
                CreatorProjects.lowercaseCli()
        );

        try (var child = DevelopmentRuntimeGeneratedProcess.launch(running).token("running-artifact")) {
            child.awaitReady();
            final Path runningArtifact = running.artifact().directory();
            final String uppercase = CreatorProjects.lowercaseCli().replace("text.lowercase", "text.uppercase");
            Files.writeString(project, uppercase, StandardCharsets.UTF_8);

            try (CreatorServer creator = start(project)) {
                final Path creatorArtifact = path(application(creator.baseUri()), "build_path").getParent();

                assertThat(creatorArtifact).isNotEqualTo(runningArtifact).isDirectory();
                assertThat(runningArtifact).isDirectory();
                assertThat(child.process().isAlive()).isTrue();
            }
        }
    }

    @Test
    void rollingCreatorPreservesAnArtifactUsedByAnotherRunningDevelopmentApplication() throws Exception {
        final Path workspace = directory.resolve("shared-running-artifact");
        final Path project = project(workspace);
        final DevelopmentRuntimeGeneratedProcess.Build running = developmentBuild(
                project,
                CreatorProjects.lowercaseCli()
        );

        try (var child = DevelopmentRuntimeGeneratedProcess.launch(running).token("shared-artifact")) {
            child.awaitReady();
            try (CreatorServer creator = start(project)) {
                assertThat(path(application(creator.baseUri()), "build_path").getParent())
                        .isEqualTo(running.artifact().directory());

                final HttpResponse<String> response = request(
                        creator.baseUri(),
                        "POST",
                        "/api/project",
                        CreatorProjects.lowercaseCli().replace("text.lowercase", "text.uppercase")
                );

                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(running.artifact().directory()).isDirectory();
                assertThat(child.process().isAlive()).isTrue();
            }
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
            assertThat(exampleResult(creator.baseUri(), "command:0"))
                    .isEqualTo(RailixValue.string("HELLO RAILIX"));
        }
    }

    @Test
    void exampleOnlyEditPublishesANewDevelopmentArtifactAndApplication() throws Exception {
        final Path project = project(directory.resolve("workspace"));

        try (CreatorServer creator = start(project)) {
            final RailixValue.ObjectValue before = application(creator.baseUri());
            final Path previousJar = path(before, "build_path");
            final String edited = CreatorProjects.lowercaseCli().replace("Hello RAILIX", "Different Example");

            final HttpResponse<String> response = request(creator.baseUri(), "POST", "/api/project", edited);
            final RailixValue.ObjectValue after = application(creator.baseUri());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(number(after, "pid")).isNotEqualTo(number(before, "pid"));
            assertThat(string(after, "fingerprint")).isNotEqualTo(string(before, "fingerprint"));
            assertThat(path(after, "build_path")).isNotEqualTo(previousJar).isRegularFile();
            assertThat(previousJar).doesNotExist();
            assertThat(Files.readString(project, StandardCharsets.UTF_8)).contains("Different Example");
            assertThat(exampleResult(creator.baseUri(), "command:0"))
                    .isEqualTo(RailixValue.string("different example"));
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
            assertThat(exampleResult(creator.baseUri(), "command:0"))
                    .isEqualTo(RailixValue.string("hello railix"));
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
            assertThat(string(after, "state")).isEqualTo("running");
            assertThat(ProcessHandle.of(number(after, "pid")).orElseThrow().isAlive()).isTrue();
            assertThat(exampleResult(creator.baseUri(), "command:0"))
                    .isEqualTo(RailixValue.string("hello railix"));
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

    private static String authoredExample(final String name, final String payload) {
        return """
                {"format":1,"id":"production-example-omission","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[{
                    "name":%s,"payload":[%s]
                  }]}
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"end"}
                ]}
                """.formatted(
                RailixJson.write(RailixValue.string(name)),
                RailixJson.write(RailixValue.string(payload))
        );
    }

    private static String runtimeContextProject() {
        return """
                {"format":1,"id":"production-runtime-context","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[{
                    "name":"runtime","payload":[]
                  }]},
                  {"id":"return-runtime","use":"railix.field-manipulation","inputs":{
                    "field":["context","result"],
                    "value":[{"option":"field","inputs":{
                      "source":["context","runtime"]
                    }}],
                    "steps":[]
                  }}
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"return-runtime"},
                  {"from":"return-runtime.next","to":"end"}
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

    private static DevelopmentRuntimeGeneratedProcess.Build developmentBuild(
            final Path project,
            final String source
    ) throws IOException {
        final CompileResult result = ProjectCompiler.compileApplication(source, StandardLibrary.catalog());
        if (!(result instanceof CompileResult.Compiled compiled)) {
            throw new IOException("Development application did not compile: " + result);
        }
        try (ApplicationBuilder.DevelopmentBuild build = ApplicationBuilder.build(project, compiled)) {
            return new DevelopmentRuntimeGeneratedProcess.Build(project, compiled, build.artifact());
        }
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

    private static RailixValue exampleResult(final URI baseUri, final String id) throws Exception {
        awaitExamples(baseUri, 1);
        final HttpResponse<String> response = request(baseUri, "GET", "/api/examples/" + id + "/view", "");
        assertThat(response.statusCode()).isEqualTo(200);
        final RailixJson.Result parsed = RailixJson.parse(response.body());
        if (!(parsed instanceof RailixJson.Parsed(RailixValue.ObjectValue view))
                || !(view.values().get("result") instanceof RailixValue.ObjectValue result)
                || !(result.values().get("context") instanceof RailixValue.ObjectValue context)
                || !context.values().containsKey("result")) {
            throw new AssertionError("Completed Example has no result projection: " + response.body());
        }
        return context.values().get("result");
    }

    private static RailixValue.ObjectValue awaitExamples(final URI baseUri, final long completed) throws Exception {
        final long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        RailixValue.ObjectValue examples;
        do {
            examples = CreatorServerE2eSupport.examples(baseUri);
            if (number(examples, "completed") == completed) {
                return examples;
            }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Examples did not complete: " + examples);
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

    private record Build(Path source, Path jar) {
    }

    private record ProcessResult(int exitCode, String output) {
    }

    private record TriggerScale(String source, StepCatalog catalog) {
    }
}
