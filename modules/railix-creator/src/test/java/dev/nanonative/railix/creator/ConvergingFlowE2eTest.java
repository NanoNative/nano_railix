package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;
import dev.nanonative.railix.core.project.ProjectCompiler;
import dev.nanonative.railix.core.project.CompileResult;
import dev.nanonative.railix.core.step.StepDefinition;
import thirdparty.production.ResourceLifecycleSteps;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(120)
final class ConvergingFlowE2eTest extends CreatorServerE2eSupport {
    @Test
    void productionUsesOnePlacementLifecycleAcrossConcurrentArrivals() throws Exception {
        final String source = project().replace("text.lowercase", "test.resource")
                .replace("[\"context\",\"payload\",\"name\"]", "[\"context\",\"payload\"]")
                .replace("\"shared.ok\"", "\"shared.next\"");
        final var file = directory.resolve("project.json");
        Files.writeString(file, source);
        final var definition = StepDefinition.named("test.resource", "1").receive("value", ValueShape.ANY)
                .returns("value", ValueShape.NUMBER)
                .input("ownership", StepDefinition.Input.options(StepDefinition.Input.option("private")).defaultOption("private"))
                .input("label", StepDefinition.Input.json(ValueShape.STRING).defaultValue(RailixValue.string("shared")))
                .input("key", StepDefinition.Input.json(ValueShape.STRING).defaultValue(RailixValue.string("client")))
                .implementation(ResourceLifecycleSteps.Handler.class);
        final var catalog = GeneratedApplicationFixture.installedCatalog(directory, List.of(definition),
                ResourceLifecycleSteps.Handler.class, ResourceLifecycleSteps.Resource.class);
        final var compiled = ProjectCompiler.compileApplication(source, catalog);
        assertThat(compiled).isInstanceOf(CompileResult.Compiled.class);
        final var jar = ApplicationBuilder.buildProduction(file, (CompileResult.Compiled) compiled).jar();
        final var probe = CreatorFirstExecutionArtifacts.compileProbe(directory.resolve("probe"), jar,
                "dev.nanonative.railix.core.project.ConvergenceProbe", """
                package dev.nanonative.railix.core.project;
                import dev.nanonative.railix.core.runtime.RunResult;
                import dev.nanonative.railix.core.value.RailixValue;
                import thirdparty.production.ResourceLifecycleSteps;
                import java.util.*;
                import java.util.concurrent.*;
                public final class ConvergenceProbe {
                    public static void main(String[] arguments) throws Exception {
                        final RuntimeApplication application = RailixApplication.runtime();
                        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
                            final List<Future<Boolean>> results = new ArrayList<>();
                            for (int index=0; index<32; index++) {
                                final boolean http=(index&1)==1;
                                results.add(workers.submit(() -> {
                                    final var response = http
                                            ? application.runSource("application.http", Map.of("request", RailixValue.object(Map.of())))
                                            : application.runSource("application.arguments", Map.of("arguments", RailixValue.array(List.of())));
                                    return response.result() instanceof RunResult.Succeeded succeeded
                                            && RailixValue.number(1).equals(succeeded.context().values().get("result"))
                                            && response.responses().keySet().equals(http?Set.of("body","headers","status"):Set.of("output","status"));
                                }));
                            }
                            for (var result : results) if (!result.get()) throw new AssertionError("Wrong independent result");
                        } finally { application.stop(); }
                        System.out.print("CONVERGENCE|" + ResourceLifecycleSteps.CREATED.size() + "|" + ResourceLifecycleSteps.CLOSED + "|" + ResourceLifecycleSteps.POSTS);
                    }
                }
                """);
        final var observed = CreatorFirstExecutionArtifacts.runProbe(probe, jar, "dev.nanonative.railix.core.project.ConvergenceProbe");
        assertThat(observed.exitCode()).as(observed.output()).isZero();
        assertThat(observed.output()).isEqualTo("CONVERGENCE|1|1|1");
    }

    @Test
    void differentSourcesKeepTheirContextsThroughOneContinuation() throws Exception {
        try (var application = GeneratedApplicationFixture.start(directory, project())) {
            for (final String trigger : List.of("command", "http", "command")) {
                final var response = application.runTest(trigger,
                        "{\"payload\":{\"arguments\":[],\"request\":{},\"name\":\"" + trigger.toUpperCase(java.util.Locale.ROOT) + "\"}}");
                assertThat(response.status()).as(response.body()).isEqualTo(200);
                assertThat(response.body()).contains("\"result\":\"" + trigger + "\"");
                final var events = application.trace(trigger,
                        "{\"payload\":{\"arguments\":[],\"request\":{},\"name\":\"TEST\"}}");
                assertThat(events.stream().filter(event -> RailixValue.string("shared").equals(event.values().get("id"))
                        && RailixValue.string("step_start").equals(event.values().get("type"))))
                        .hasSize(1);
            }
        }
    }

    @Test
    void aGroupWithConvergingArrivalsHasOneRegionAndSelectableObservationSources() throws Exception {
        final String source = project().replace("{\"id\":\"shared\",", """
                {"id":"left","use":"railix.field-manipulation","inputs":{}},
                {"id":"right","use":"railix.field-manipulation","inputs":{}},
                {"id":"shared",
                """)
                .replace("\"command.next\",\"to\":\"shared\"", "\"command.next\",\"to\":\"left\"")
                .replace("\"http.next\",\"to\":\"shared\"", "\"http.next\",\"to\":\"right\"")
                .replace("{\"from\":\"shared.ok\"", "{\"from\":\"left.next\",\"to\":\"shared\"},{\"from\":\"right.next\",\"to\":\"shared\"},{\"from\":\"shared.ok\"");
        final var file = directory.resolve("railix.project.json");
        Files.writeString(file, source);
        try (var creator = start(file)) {
            final var saved = request(creator.baseUri(), "POST", "/api/creator", """
                    {"format":2,"groups":[{"id":"g"}],"steps":{"left":{"group":"g"},"right":{"group":"g"},"shared":{"group":"g"}}}
                    """);
            assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);
            final var view = object(request(creator.baseUri(), "GET", "/api/editor?node=shared&trigger=http", "").body());
            final var editor = (RailixValue.ObjectValue) view.values().get("editor");
            final var groups = (RailixValue.ObjectValue) editor.values().get("groups");
            assertThat(((RailixValue.ObjectValue) groups.values().get("g")).values().get("regions")).isEqualTo(RailixValue.number(1));
            final var nodes = (RailixValue.ObjectValue) editor.values().get("nodes");
            assertThat(((RailixValue.ObjectValue) nodes.values().get("shared")).values().get("trigger")).isEqualTo(RailixValue.string("http"));
            final var scene = object(request(creator.baseUri(), "GET", "/api/scene", "").body());
            assertThat(((RailixValue.ArrayValue) scene.values().get("nodes")).values().stream()
                    .filter(value -> RailixValue.string("g").equals(((RailixValue.ObjectValue) value).values().get("group"))))
                    .hasSize(1);
        }
    }

    @Test
    void sharedDestinationAppearsOnceAndExposesBothArrivalSources() throws Exception {
        final var file = directory.resolve("railix.project.json");
        Files.writeString(file, project());
        try (var creator = start(file)) {
            final var response = request(creator.baseUri(), "GET", "/api/scene?focus=shared", "");
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            final var nodes = (RailixValue.ArrayValue) object(response.body()).values().get("nodes");
            assertThat(nodes.values().stream().filter(value ->
                    RailixValue.string("shared").equals(((RailixValue.ObjectValue) value).values().get("id"))))
                    .hasSize(1);
            final var editor = object(request(creator.baseUri(), "GET", "/api/editor?node=shared", "").body());
            final var index = (RailixValue.ObjectValue) editor.values().get("editor");
            final var info = (RailixValue.ObjectValue) index.values().get("nodes");
            assertThat(((RailixValue.ObjectValue) info.values().get("shared")).values().get("triggers"))
                    .isEqualTo(RailixValue.array(List.of(RailixValue.string("command"), RailixValue.string("http"))));
        }
    }

    static String project() {
        return """
                {"format":1,"id":"converging-flows","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},
                   "examples":[{"name":"command","payload":[],"context":{"payload":{"name":"CLI"}}}]},
                  {"id":"http","use":"railix.trigger.http","inputs":{"port":0},
                   "examples":[{"name":"http","payload":{},"context":{"payload":{"name":"HTTP"}}}]},
                  {"id":"shared","use":"text.lowercase","inputs":{},
                   "receives":{"value":["context","payload","name"]},
                   "returns":{"value":["context","result"]}}
                ],"links":[
                  {"from":"app.start","to":"command"},{"from":"app.start","to":"http"},
                  {"from":"command.next","to":"shared"},{"from":"http.next","to":"shared"},
                  {"from":"shared.ok","to":"end"}]}
                """;
    }
}
