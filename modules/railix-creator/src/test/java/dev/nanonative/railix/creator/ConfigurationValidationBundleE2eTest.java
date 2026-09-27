package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.project.CompileResult;
import dev.nanonative.railix.core.project.ProjectCompiler;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepContractJson;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import thirdparty.conformance.ValidationProcess;
import thirdparty.conformance.ValidationSteps;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(60)
final class ConfigurationValidationBundleE2eTest extends CreatorServerE2eSupport {
    @Test
    void installedProviderValidatesDefaultsAndRepeatedPlacementsInOneWorker() throws Exception {
        final var catalog = install();
        assertThat(ProjectCompiler.compileApplication(source("{}"), catalog)).isInstanceOf(CompileResult.Compiled.class);
        assertThat(ProjectCompiler.compileApplication(source("{\"value\":\"valid\"}"), catalog))
                .isInstanceOf(CompileResult.Compiled.class);
        assertThat(StepContractJson.write(catalog.find("external.validation").orElseThrow()))
                .contains("\"validation\":\"String\"", "\"validation\":\"Integer\"");
        final var rejected = rejected(ProjectCompiler.compileApplication(source("{\"value\":\"invalid\"}"), catalog));
        assertThat(rejected.diagnostics()).hasSize(2).allSatisfy(issue -> {
            assertThat(issue.code()).isEqualTo("PROJECT_INPUT_VALIDATION_INVALID");
            assertThat(issue.message()).isEqualTo("Choose a supported mode.");
        });
        assertThat(rejected.diagnostics()).extracting(issue -> issue.path())
                .containsExactly("nodes[2].inputs.value", "nodes[3].inputs.value");
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "blank", "throw", "exit", "hang", "oversized"})
    void failingAndHangingCallbacksFailClosedAndDoNotBreakLaterBuilds(final String mode) throws Exception {
        final var catalog = install();
        final var result = rejected(ProjectCompiler.compileApplication(source("{\"value\":\"" + mode + "\"}"), catalog));
        assertThat(result.diagnostics()).allSatisfy(issue -> {
            assertThat(issue.code()).isEqualTo("PROJECT_INPUT_VALIDATION_FAILED");
            assertThat(issue.message()).doesNotContain("private details");
        });
        assertThat(ProjectCompiler.compileApplication(source("{}"), catalog)).isInstanceOf(CompileResult.Compiled.class);
    }

    @Test
    void workerHasExitedWhenCompilationReturns() throws Exception {
        final var result = rejected(ProjectCompiler.compileApplication(source("{\"value\":\"pid\"}"), install()));
        final String message = result.diagnostics().getFirst().message();
        assertThat(message).startsWith("worker:");
        final long pid = Long.parseLong(message.substring("worker:".length()));
        assertThat(pid).isNotEqualTo(ProcessHandle.current().pid());
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    }

    @Test
    void cancellingCompilationTerminatesItsWorkerAndPreservesInterruption() throws Exception {
        final var catalog = install();
        final Path marker = directory.resolve("worker.pid");
        final String inputs = RailixJson.write(RailixValue.object(java.util.Map.of(
                "value", RailixValue.string("await:" + marker))));
        final var result = new java.util.concurrent.atomic.AtomicReference<CompileResult>();
        final var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        final Thread compiler = Thread.ofVirtual().start(() -> {
            result.set(ProjectCompiler.compileApplication(source(inputs), catalog));
            interrupted.set(Thread.currentThread().isInterrupted());
        });
        try {
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!Files.exists(marker) && System.nanoTime() < deadline) Thread.sleep(10);
            assertThat(marker).exists();
            compiler.interrupt();
            compiler.join(5_000);
            assertThat(compiler.isAlive()).isFalse();
            assertThat(rejected(result.get()).diagnostics()).anySatisfy(issue ->
                    assertThat(issue.message()).contains("interrupted"));
            assertThat(interrupted).isTrue();
            final long pid = Long.parseLong(Files.readString(marker));
            assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
        } finally {
            compiler.interrupt();
            compiler.join(5_000);
        }
    }

    @Test
    void ownedAndNestedInputsUseTheSameCallbackContract() throws Exception {
        final var catalog = install();
        final String owned = "{\"options\":{\"option\":\"custom\",\"inputs\":{\"port\":-1}}}";
        final var result = rejected(ProjectCompiler.compileApplication(source(owned), catalog));
        assertThat(result.diagnostics().getFirst().path()).isEqualTo("nodes[2].inputs.options.inputs.port");
        assertThat(result.diagnostics().getFirst().message()).isEqualTo("Choose a usable port.");
        final String nested = source("{}").replace("\"use\":\"external.validation\",\"inputs\":{}",
                """
                "use":"railix.field-manipulation","inputs":{"field":["context","payload"],
                  "steps":[{"use":"external.validated-value","inputs":{"value":"invalid"}}]}
                """.strip());
        assertThat(rejected(ProjectCompiler.compileApplication(nested, catalog)).diagnostics().getFirst().path())
                .isEqualTo("nodes[2].inputs.steps[0].inputs.value");
    }

    @Test
    void productionApplicationRunsWithoutValidationInfrastructure() throws Exception {
        final Path marker = directory.resolve("lifecycle.txt");
        final var result = ProjectCompiler.compileApplication(source(RailixJson.write(RailixValue.object(
                java.util.Map.of("marker", RailixValue.string(marker.toString()))))), install());
        assertThat(result).isInstanceOf(CompileResult.Compiled.class);
        assertThat(marker).doesNotExist();
        final var compiled = (CompileResult.Compiled) result;
        final Path project = directory.resolve("project.json");
        Files.writeString(project, compiled.source());
        final Path jar = ApplicationBuilder.buildProduction(project, compiled).jar();
        try (var archive = new JarFile(jar.toFile())) {
            assertThat(archive.getJarEntry("dev/nanonative/railix/core/step/StepValidation.class")).isNull();
            assertThat(archive.getJarEntry("dev/nanonative/railix/core/step/StepDefinition.class")).isNull();
        }
        final Path log = directory.resolve("run.log");
        final var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", jar.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).as(Files.readString(log)).isZero();
            assertThat(Files.readAllLines(marker)).containsExactly("pre", "pre", "process", "process", "post", "post");
        } finally {
            process.destroyForcibly();
            assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void aProviderWithoutTheDeclaredMethodIsRejectedBeforeStartingAWorker() throws Exception {
        final var catalog = GeneratedApplicationFixture.installedCatalog(directory, ValidationProcess.class,
                ValidationSteps.definitions(), ValidationSteps.class, ValidationProcess.class);
        assertThat(rejected(ProjectCompiler.compileApplication(source("{}"), catalog)).diagnostics().getFirst().message())
                .contains("public static List<StepDefinition> definitions()");
    }

    @Test
    void aProviderCannotSilentlyReplaceTheInstalledContract() throws Exception {
        final var catalog = GeneratedApplicationFixture.installedCatalog(directory, ValidationSteps.EmptyProvider.class,
                ValidationSteps.definitions(), ValidationSteps.class, ValidationSteps.EmptyProvider.class, ValidationProcess.class);
        assertThat(rejected(ProjectCompiler.compileApplication(source("{}"), catalog)).diagnostics())
                .allSatisfy(issue -> assertThat(issue.message()).contains("does not match the installed Step contract"));
    }

    @Test
    void creatorRejectsInvalidEditsWithTheCompilerMessageAndKeepsThePreviousProject() throws Exception {
        install();
        final Path project = directory.resolve("project.json");
        Files.writeString(project, source("{}"));
        try (var creator = start(project)) {
            final String before = Files.readString(project);
            final var response = request(creator.baseUri(), "POST", "/api/project", source("{\"value\":\"invalid\"}"));
            assertThat(response.statusCode()).as(response.body()).isEqualTo(422);
            assertThat(response.body()).contains("Choose a supported mode.", "nodes[2].inputs.value");
            assertThat(Files.readString(project)).isEqualTo(before);
        }
    }

    private StepCatalog install() throws Exception {
        return GeneratedApplicationFixture.installedCatalog(directory, ValidationSteps.class, ValidationSteps.definitions(),
                ValidationSteps.class, ValidationProcess.class);
    }

    private static CompileResult.Rejected rejected(final CompileResult result) {
        assertThat(result).isInstanceOf(CompileResult.Rejected.class);
        return (CompileResult.Rejected) result;
    }

    private static String source(final String inputs) {
        return """
                {"format":1,"id":"validation-bundle","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"cli","use":"railix.trigger.cli","inputs":{},"examples":[{"name":"default","payload":[]}]},
                  {"id":"first","use":"external.validation","inputs":%s},
                  {"id":"second","use":"external.validation","inputs":%s}],
                 "links":[{"from":"app.start","to":"cli"},{"from":"cli.next","to":"first"},
                          {"from":"first.next","to":"second"},{"from":"second.next","to":"end"}]}
                """.formatted(inputs, inputs);
    }
}
