package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.project.CompileResult;
import dev.nanonative.railix.core.project.ProjectCompiler;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import thirdparty.production.ResourceLifecycleSteps;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Optional resource ownership through installed Steps, generated production JARs and development traces. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(90)
final class ResourceLifecycleE2eTest {
    private Path directory;
    private Path jar;
    private Path probe;

    @BeforeAll
    void compilePublicBoundaryProbe(@TempDir final Path workspace) throws Exception {
        directory = workspace;
        final Path project = directory.resolve("railix.project.json");
        Files.writeString(project, source());
        final var catalog = GeneratedApplicationFixture.installedCatalog(directory, definitions(),
                ResourceLifecycleSteps.Handler.class, ResourceLifecycleSteps.Resource.class);
        final var result = ProjectCompiler.compileApplication(source(), catalog);
        assertThat(result).isInstanceOf(CompileResult.Compiled.class);
        jar = ApplicationBuilder.buildProduction(project, (CompileResult.Compiled) result).jar();
        probe = GeneratedApplicationFixture.compileProbe(directory.resolve("probe"), jar, "ResourceProbe", probeSource());
    }

    @ParameterizedTest
    @ValueSource(strings = {"normal", "pre-error", "pre-fatal", "post-error", "post-fatal", "close-error",
            "close-fatal", "repeated-fatal", "pre-close-fatal", "duplicate", "recursive-registration",
            "null-factory", "null-close", "null-result", "factory-error",
            "blank-name", "null-type", "null-key", "missing", "wrong-type", "config-access",
            "invalid-result", "late-register", "post-register"})
    void managesPrivateAndSharedResourcesIncludingPartialFailures(final String scenario) throws Exception {
        final Path output = directory.resolve(scenario + ".log");
        final Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Drailix.resource.test=" + scenario, "-cp", jar + File.pathSeparator + probe,
                "dev.nanonative.railix.core.project.ResourceProbe")
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertThat(child.waitFor(30, TimeUnit.SECONDS)).as(scenario).isTrue();
            assertThat(child.exitValue()).as(Files.readString(output)).isZero();
            assertThat(Files.readString(output)).isEqualTo("RESOURCES|" + scenario + "|created=5|closed=5\n");
        } finally {
            child.destroyForcibly();
            assertThat(child.waitFor(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void examplesAndNestedTraceCallsUseTheSamePreparedResources() throws Exception {
        try (var app = GeneratedApplicationFixture.start(directory.resolve("development"), source(), definitions(),
                ResourceLifecycleSteps.Handler.class, ResourceLifecycleSteps.Resource.class)) {
            final var first = app.runTest("cli", "{}");
            final var second = app.runTest("cli", "{}");
            assertThat(first.status()).as(first.body()).isEqualTo(200);
            assertThat(second.body()).isEqualTo(first.body());
            final var traced = app.trace("cli", "{}").getLast();
            assertThat(traced.values().get("type")).isEqualTo(RailixValue.string("result"));
            assertThat(traced.values().get("status")).isEqualTo(RailixValue.string("succeeded"));
            assertThat(traced.values().get("context"))
                    .isEqualTo(CreatorServerE2eSupport.object(first.body()).values().get("context"));
            final var live = app.run("cli", "{}");
            assertThat(live.status()).as(live.body()).isEqualTo(200);
        }
    }

    private static List<StepDefinition> definitions() {
        return List.of(definition("test.resource"), definition("test.other-resource"));
    }

    private static StepDefinition definition(final String id) {
        return StepDefinition.named(id, "1").receive("value", ValueShape.ANY).returns("value", ValueShape.NUMBER)
                .input("ownership", StepDefinition.Input.options(StepDefinition.Input.option("private"),
                        StepDefinition.Input.option("shared")).defaultOption("private"))
                .input("key", StepDefinition.Input.json(ValueShape.STRING))
                .input("label", StepDefinition.Input.json(ValueShape.STRING))
                .implementation(ResourceLifecycleSteps.Handler.class);
    }

    private static String source() {
        return """
                {"format":1,"id":"resource-lifecycle","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"cli","use":"railix.trigger.cli","inputs":{},"examples":[{"name":"default","payload":[]}]},
                  %s,%s,%s,%s,%s,%s,
                  {"id":"nested","use":"railix.field-manipulation","inputs":{
                    "field":["context","nested"],
                    "value":[{"option":"literal","inputs":{"literal":null}}],
                    "steps":[{"use":"test.resource","inputs":{
                      "label":"nested","key":"one","ownership":{"option":"shared","inputs":{}}}}]}}
                ],"links":[{"from":"app.start","to":"cli"},{"from":"cli.next","to":"a"},
                  {"from":"a.next","to":"b"},{"from":"b.next","to":"c"},{"from":"c.next","to":"d"},
                  {"from":"d.next","to":"e"},{"from":"e.next","to":"f"},{"from":"f.next","to":"nested"},
                  {"from":"nested.next","to":"end"}]}
                """.formatted(node("a", "shared", "one"), node("b", "shared", "one"),
                node("c", "shared", "two"), node("d", "private", "one"), node("e", "private", "one"),
                node("f", "shared", "one").replace("test.resource", "test.other-resource"));
    }

    private static String node(final String label, final String ownership, final String key) {
        return """
                {"id":"%s","use":"test.resource","inputs":{"label":"%s","key":"%s",
                  "ownership":{"option":"%s","inputs":{}}},
                  "receives":{"value":["context","result"]},"returns":{"value":["context","%s"]}}
                """.formatted(label, label, key, ownership, label);
    }

    private static String probeSource() {
        return """
                package dev.nanonative.railix.core.project;

                import dev.nanonative.railix.core.runtime.RunResult;
                import dev.nanonative.railix.core.value.RailixValue;
                import thirdparty.production.ResourceLifecycleSteps;
                import java.util.*;
                import java.util.concurrent.*;

                public final class ResourceProbe {
                    public static void main(String[] args) throws Exception {
                        var app = RailixApplication.runtime();
                        String mode = ResourceLifecycleSteps.mode();
                        boolean failedStart = !Set.of("normal", "late-register", "post-register", "post-error",
                                "post-fatal", "close-error", "close-fatal", "repeated-fatal").contains(mode);
                        try {
                            var start = app.start();
                            check((start instanceof RunResult.Succeeded) != failedStart, "start: " + start);
                            if (!failedStart) {
                                check(app.start() == start, "PRE was not cached");
                                try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                                    var calls = new ArrayList<Future<?>>();
                                    for (int i = 0; i < 128; i++) calls.add(pool.submit(() -> {
                                        var result = app.runSource("application.arguments", Map.of("arguments", RailixValue.array(List.of()))).result();
                                        if (mode.equals("late-register")) {
                                            check(result instanceof RunResult.Failed, "late registration accepted");
                                            return;
                                        }
                                        check(result instanceof RunResult.Succeeded, "run: " + result);
                                        var values = ((RunResult.Succeeded) result).context().values();
                                        check(values.get("a").equals(values.get("b")), "shared placements differ");
                                        check(values.get("a").equals(values.get("nested")), "nested resource differs");
                                        check(new HashSet<>(List.of(values.get("a"), values.get("c"), values.get("d"),
                                                values.get("e"), values.get("f"))).size() == 5, "private/key/definition isolation failed");
                                    }));
                                    for (var call : calls) call.get(10, TimeUnit.SECONDS);
                                }
                                if (!mode.equals("late-register")) {
                                    check(ResourceLifecycleSteps.CREATED.stream().mapToInt(ResourceLifecycleSteps.Resource::calls)
                                            .sum() == 128 * 7, "calls lost under concurrency");
                                }
                            }
                            var stopped = app.stop();
                            boolean failedStop = Set.of("post-error", "post-register", "close-error").contains(mode);
                            check((stopped instanceof RunResult.Succeeded) != failedStop, "stop: " + stopped);
                            check(app.stop() == stopped, "cleanup result was not cached");
                        } catch (AssertionError fatal) {
                            if (!mode.endsWith("-fatal") || !fatal.getMessage().startsWith("Requested fatal")) throw fatal;
                        } finally {
                            app.stop();
                        }
                        check(ResourceLifecycleSteps.CREATED.size() == 5, "factory reuse failed");
                        check(ResourceLifecycleSteps.CLOSED.get() == 5, "cleanup omitted or repeated");
                        check(ResourceLifecycleSteps.POSTS.get() == 7, "POST skipped an owner");
                        check(!(app.runSource("application.arguments", Map.of("arguments", RailixValue.array(List.of()))).result()
                                instanceof RunResult.Succeeded), "work admitted after close");
                        System.out.println("RESOURCES|" + mode + "|created=5|closed=5");
                    }

                    private static void check(boolean condition, String message) {
                        if (!condition) throw new AssertionError(message);
                    }
                }
                """;
    }
}
