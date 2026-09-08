package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.stdlib.StandardLibrary;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import thirdparty.conformance.DevelopmentRuntimeConformanceSteps;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@Execution(ExecutionMode.SAME_THREAD)
@Timeout(120)
final class CreatorSceneObservationsE2eTest extends CreatorServerE2eSupport {
    @TempDir
    static Path workspace;
    private static CreatorServer server;

    @BeforeAll
    static void openApplication() throws Exception {
        final Path project = workspace.resolve("railix.project.json");
        Files.writeString(project, threeStepProject().replace(
                "\"id\":\"two\",", "\"id\":\"two\",\"metrics\":false,"));
        Files.writeString(workspace.resolve("railix.creator.json"), """
                {"format":2,"groups":[{"id":"normalize"}],"steps":{
                  "one":{"group":"normalize"},"two":{"group":"normalize"},
                  "three":{"group":"normalize"}
                }}
                """);
        server = start(project, workspace.resolve("home"));
        assertThat(awaitExampleView(server.baseUri(), "command:0").statusCode()).isEqualTo(200);
    }

    @AfterAll
    static void closeApplication() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void visibleIconsAreDeduplicatedAcrossStepsAndDisconnectedGroups() throws Exception {
        final Path project = directory.resolve("railix.project.json");
        Files.writeString(project, branchGroupProject());
        final RailixValue.ObjectValue icon = portableIcon("red");
        Files.writeString(directory.resolve("railix.creator.json"), RailixJson.write(RailixValue.object(Map.of(
                "format", RailixValue.number(2),
                "groups", RailixValue.array(List.of(RailixValue.object(Map.of("id", RailixValue.string("arms"), "icon", icon)))),
                "steps", RailixValue.object(Map.of(
                        "matched", RailixValue.object(Map.of("group", RailixValue.string("arms"), "icon", icon)),
                        "otherwise", RailixValue.object(Map.of("group", RailixValue.string("arms"), "icon", icon))
                ))
        ))));
        try (CreatorServer creator = start(project)) {
            final RailixValue.ObjectValue visible = scene(creator.baseUri(), "scale=100");
            final Map<String, RailixValue> icons = ((RailixValue.ObjectValue) visible.values().get("icons")).values();

            assertThat(icons).hasSize(1);
            assertThat(icons.values()).containsExactly(icon);
            final String reference = icons.keySet().iterator().next();
            assertThat(reference).matches("[0-9a-f]{64}");
            assertThat(entries(visible, "nodes").stream().filter(node -> node.values().containsKey("icon_ref")))
                    .hasSize(4).allSatisfy(node -> assertThat(string(node, "icon_ref")).isEqualTo(reference));
            assertThat(entries(visible, "nodes")).allSatisfy(node -> assertThat(node.values()).doesNotContainKey("icon"));
            assertThat(scene(creator.baseUri(), "scale=100")).isEqualTo(visible);
        }
    }

    @Test
    void sceneOmitsIconsOwnedOnlyByOffscreenNodes() throws Exception {
        final Path project = directory.resolve("railix.project.json");
        Files.writeString(project, branchGroupProject());
        final RailixValue.ObjectValue visibleIcon = portableIcon("red");
        Files.writeString(directory.resolve("railix.creator.json"), RailixJson.write(RailixValue.object(Map.of(
                "format", RailixValue.number(2), "groups", RailixValue.array(List.of()),
                "steps", RailixValue.object(Map.of(
                        "matched", RailixValue.object(Map.of("icon", visibleIcon)),
                        "otherwise", RailixValue.object(Map.of("icon", portableIcon("blue")))
                ))
        ))));
        try (CreatorServer creator = start(project)) {
            final RailixValue.ObjectValue visible = scene(creator.baseUri(), "focus=matched&scale=100");

            assertThat(ids(visible, "nodes")).doesNotContain("otherwise");
            assertThat(((RailixValue.ObjectValue) visible.values().get("icons")).values().values()).containsExactly(visibleIcon);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void groupIconUsesItsOverrideOrItsEntryStepNotItsLexicalFirstMember(final boolean override) throws Exception {
        final Path project = directory.resolve("railix.project.json");
        Files.writeString(project, threeStepProject().replace("one", "z-entry").replace("two", "a-middle").replace("three", "b-last"));
        final RailixValue.ObjectValue firstIcon = portableIcon("red");
        final RailixValue.ObjectValue groupIcon = portableIcon("green");
        final Map<String, RailixValue> group = new LinkedHashMap<>(Map.of("id", RailixValue.string("normalize")));
        if (override) {
            group.put("icon", groupIcon);
        }
        Files.writeString(directory.resolve("railix.creator.json"), RailixJson.write(RailixValue.object(Map.of(
                "format", RailixValue.number(2), "groups", RailixValue.array(List.of(RailixValue.object(group))),
                "steps", RailixValue.object(Map.of(
                        "z-entry", RailixValue.object(Map.of("group", RailixValue.string("normalize"), "icon", firstIcon)),
                        "a-middle", RailixValue.object(Map.of("group", RailixValue.string("normalize"), "icon", portableIcon("blue"))),
                        "b-last", RailixValue.object(Map.of("group", RailixValue.string("normalize")))
                ))
        ))));
        try (CreatorServer creator = start(project)) {
            final RailixValue.ObjectValue visible = scene(creator.baseUri(), "focus=group:normalize&scale=0.000001");
            final String reference = string(entry(visible, "group-region:normalize:a-middle"), "icon_ref");

            assertThat(((RailixValue.ObjectValue) visible.values().get("icons")).values())
                    .containsExactlyEntriesOf(Map.of(reference, override ? groupIcon : firstIcon));
        }
    }

    @Test
    void triggerSceneEntryReportsItsActualExampleCountWithoutInlineIcons() throws Exception {
        final RailixValue.ObjectValue trigger = entry(scene(server.baseUri(), ""), "command");

        assertThat(number(trigger, "example_count")).isEqualTo(1);
        assertThat(trigger.values()).doesNotContainKey("icon");
    }

    @Test
    void canonicalObservationsCombineMetricsCoverageAndSelectedTraceWithoutModes() throws Exception {
        final String viewport = "scale=100";
        final String revision = string(scene(server.baseUri(), viewport), "revision");
        final HttpResponse<String> response = request(server.baseUri(), "GET",
                "/api/scene/observations?revision=" + revision + "&" + viewport + "&example=command%3A0", "");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        final RailixValue.ObjectValue observed = object(response.body());
        assertThat(observed.values()).containsKeys("coverage_revision", "example")
                .doesNotContainKeys("mode", "unvisited");
        final RailixValue.ObjectValue step = entry(observed, "one");
        assertThat(number(step, "executions")).isEqualTo(1);
        assertThat(number(step, "covered_count")).isEqualTo(1);
        assertThat(number(step, "selected_count")).isEqualTo(1);
        assertThat(entries(observed, "links")).anySatisfy(link -> {
            assertThat(string(link, "id")).isEqualTo("two.next>three");
            assertThat(string(link, "selection")).isEqualTo("reached");
            assertThat(number(link, "executions")).isEqualTo(1);
        });
        assertThat(entries(observed, "links").stream().filter(link -> string(link, "id").equals("one.next>two")
                || string(link, "id").contains(">end:")))
                .allSatisfy(link -> assertThat(link.values()).doesNotContainKey("executions"));
    }

    @Test
    void observationsDescribeOnlyVisibleIdentitiesWithoutChangingGeometry() throws Exception {
        final String viewport = "focus=group:normalize&scale=0.000001";
        final RailixValue.ObjectValue before = scene(server.baseUri(), viewport);

        final HttpResponse<String> response = observations(server.baseUri(), viewport, "");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        final RailixValue.ObjectValue observed = object(response.body());
        assertThat(ids(observed, "nodes")).containsExactlyElementsOf(ids(before, "nodes"));
        assertThat(ids(observed, "links")).containsExactlyElementsOf(ids(before, "links"));
        assertThat(string(observed, "revision")).isEqualTo(string(before, "revision"));
        assertThat(number(observed, "application_pid")).isEqualTo(number(application(server.baseUri()), "pid"));
        assertThat(observed.values()).containsKey("application_revision")
                .doesNotContainKeys("project", "creator", "initial_context", "result", "bounds", "mode", "unvisited");
        final RailixValue.ObjectValue region = entry(observed, "group-region:normalize:one");
        assertThat(number(region, "count")).isEqualTo(3);
        assertThat(number(region, "disabled_count")).isEqualTo(1);
        assertThat(scene(server.baseUri(), viewport)).isEqualTo(before);
    }

    @Test
    void baselineCoverageAggregatesRealCompletedExamplesWithoutASelection() throws Exception {
        final HttpResponse<String> response = observations(server.baseUri(),
                "focus=group:normalize&scale=0.000001", "");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        final RailixValue.ObjectValue observed = object(response.body());
        final RailixValue.ObjectValue region = entry(observed, "group-region:normalize:one");
        assertThat(number(region, "covered_count")).isEqualTo(3);
        assertThat(region.values()).doesNotContainKey("selected_count");
        assertThat(entries(observed, "links")).allSatisfy(link -> assertThat(link.values()).doesNotContainKey("selection"));
        assertThat(observed.values()).containsKey("coverage_revision");
        assertThat(response.body()).doesNotContain("initial_context", "payload", "step_start", "step_result");
    }

    @Test
    void selectedExampleAggregatesReachedRegionsFromItsRealSummary() throws Exception {
        final HttpResponse<String> response = observations(server.baseUri(),
                "focus=group:normalize&scale=0.000001", "&example=command%3A0");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        final RailixValue.ObjectValue observed = object(response.body());
        assertThat(string(observed, "example")).isEqualTo("command:0");
        assertThat(number(entry(observed, "group-region:normalize:one"), "selected_count")).isEqualTo(3);
        assertThat(number(entry(observed, "group-region:normalize:one"), "covered_count")).isEqualTo(3);
        assertThat(response.body()).doesNotContain("initial_context", "payload", "step_start", "step_result");
    }

    @Test
    void selectedExampleMarksVisibleRealConnectionsWithoutInventingEndOutcomes() throws Exception {
        final HttpResponse<String> response = observations(server.baseUri(),
                "scale=100", "&example=command%3A0");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        final List<RailixValue.ObjectValue> links = entries(object(response.body()), "links");
        assertThat(links).anySatisfy(link -> {
            assertThat(string(link, "id")).isEqualTo("one.next>two");
            assertThat(string(link, "selection")).isEqualTo("reached");
        });
        assertThat(links).anySatisfy(link -> {
            assertThat(string(link, "id")).isEqualTo("two.next>three");
            assertThat(string(link, "selection")).isEqualTo("reached");
        });
        assertThat(links.stream().filter(link -> string(link, "id").contains(">end:")))
                .allSatisfy(link -> assertThat(string(link, "selection")).isEqualTo("unknown"));
    }

    @Test
    void exampleExecutionPopulatesNormalCountersAndDisabledStepsStayDistinct() throws Exception {
        final HttpResponse<String> response = observations(server.baseUri(),
                "focus=group:normalize&scale=0.000001", "");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        final RailixValue.ObjectValue region = entry(object(response.body()), "group-region:normalize:one");
        assertThat(number(region, "count")).isEqualTo(3);
        assertThat(number(region, "disabled_count")).isEqualTo(1);
        assertThat(number(region, "executions")).isEqualTo(2);
        assertThat(number(region, "errors")).isZero();
        assertThat(number(region, "cancelled")).isZero();
        assertThat(number(region, "duration_samples")).isEqualTo(2);
        assertThat(number(region, "duration_nanos_total")).isPositive();
        assertThat(response.body()).doesNotContain("p95", "bottleneck", "payload");
    }

    @Test
    void selectedExampleDoesNotMarkTheUnvisitedBranch() throws Exception {
        final Path project = directory.resolve("railix.project.json");
        Files.writeString(project, branchGroupProject());
        try (CreatorServer creator = start(project)) {
            final HttpResponse<String> summary = awaitExampleView(creator.baseUri(), "command:0");
            assertThat(summary.statusCode()).as(summary.body()).isEqualTo(200);
            final Set<Long> reached = ((RailixValue.ArrayValue) object(summary.body()).values().get("nodes"))
                    .values().stream().map(RailixValue.NumberValue.class::cast)
                    .map(value -> value.value().longValueExact()).collect(Collectors.toSet());

            final HttpResponse<String> response = observations(creator.baseUri(),
                    "scale=10", "&example=command%3A0");

            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            final RailixValue.ObjectValue observed = object(response.body());
            assertThat(number(entry(observed, "matched"), "selected_count")).isEqualTo(reached.contains(3L) ? 1 : 0);
            assertThat(number(entry(observed, "otherwise"), "selected_count")).isEqualTo(reached.contains(4L) ? 1 : 0);
            assertThat(reached.contains(3L) ^ reached.contains(4L)).isTrue();
            for (final RailixValue.ObjectValue link : entries(observed, "links")) {
                if (string(link, "id").equals("choice.match>matched")) {
                    assertThat(string(link, "selection")).isEqualTo(reached.contains(3L) ? "reached" : "unreached");
                }
                if (string(link, "id").equals("choice.otherwise>otherwise")) {
                    assertThat(string(link, "selection")).isEqualTo(reached.contains(4L) ? "reached" : "unreached");
                }
            }
        }
    }

    @Test
    void sampledOperationalHttpExecutionsAreAggregatedWithoutAddingFlowTotalsTwice() throws Exception {
        final String source = threeStepProject().replace("\"id\":\"two\",", "\"id\":\"two\",\"metrics\":false,");
        final RailixValue.ObjectValue observed = operationalObservations(source, StandardLibrary.catalog(), 1025, 200);
        final RailixValue.ObjectValue region = entry(observed, "group-region:normalize:one");

        assertThat(number(region, "executions")).isEqualTo(2052);
        assertThat(number(region, "errors")).isZero();
        assertThat(number(region, "duration_samples")).isEqualTo(4);
        assertThat(number(region, "duration_nanos_total")).isPositive();
        assertThat(number(region, "disabled_count")).isEqualTo(1);
        assertThat(number(entry(observed, "app"), "executions")).isEqualTo(1026);
        assertThat(number(entry(observed, "command"), "executions")).isEqualTo(1026);
        assertThat(entries(observed, "links")).anySatisfy(link -> {
            assertThat(string(link, "id")).isEqualTo("command.next>group-region:normalize:one");
            assertThat(number(link, "executions")).isEqualTo(1026);
        });
        assertThat(entries(observed, "links").stream().filter(link -> string(link, "id").contains(">end:")))
                .allSatisfy(link -> assertThat(link.values()).doesNotContainKey("executions"));
    }

    @Test
    void operationalHttpFailuresContributeTheirRealStepErrors() throws Exception {
        final StepCatalog catalog = GeneratedApplicationFixture.installedCatalog(directory,
                List.of(StepDefinition.named("scene.observation.fault", "1")
                        .run(DevelopmentRuntimeConformanceSteps.Fault.class)), DevelopmentRuntimeConformanceSteps.Fault.class);
        final String source = threeStepProject()
                .replace("\"id\":\"two\",", "\"id\":\"two\",\"metrics\":false,")
                .replace("\"id\":\"three\",\"use\":\"railix.field-manipulation\"",
                        "\"id\":\"three\",\"use\":\"scene.observation.fault\"");

        final RailixValue.ObjectValue region = entry(operationalObservations(source, catalog, 3, 500), "group-region:normalize:one");

        assertThat(number(region, "executions")).isEqualTo(8);
        assertThat(number(region, "errors")).isEqualTo(4);
        assertThat(number(region, "cancelled")).isZero();
        assertThat(number(region, "duration_samples")).isEqualTo(2);
    }

    @Test
    void coverageUsesCanonicalNodeIndexesRatherThanSceneLeafOrder() throws Exception {
        final Map<String, RailixValue> source = new LinkedHashMap<>(object(threeStepProject()).values());
        final List<RailixValue> nodes = new ArrayList<>(((RailixValue.ArrayValue) source.get("nodes")).values());
        Collections.rotate(nodes, 2);
        source.put("nodes", RailixValue.array(nodes));
        final Path project = directory.resolve("railix.project.json");
        Files.writeString(project, RailixJson.write(RailixValue.object(source)));
        try (CreatorServer creator = start(project)) {
            assertThat(awaitExampleView(creator.baseUri(), "command:0").statusCode()).isEqualTo(200);
            final HttpResponse<String> response = observations(creator.baseUri(), "scale=10", "&example=command%3A0");

            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            final RailixValue.ObjectValue observed = object(response.body());
            for (final String id : List.of("one", "two", "three", "command")) {
                assertThat(number(entry(observed, id), "covered_count")).isEqualTo(1);
                assertThat(number(entry(observed, id), "selected_count")).isEqualTo(1);
            }
            assertThat(number(entry(observed, "app"), "covered_count")).isZero();
            assertThat(number(entry(observed, "app"), "selected_count")).isZero();
        }
    }

    @Test
    void disconnectedOccurrencesDoNotBorrowTheOtherOccurrencesCoverage() throws Exception {
        final Path project = directory.resolve("railix.project.json");
        Files.writeString(project, branchGroupProject());
        Files.writeString(directory.resolve("railix.creator.json"), """
                {"format":2,"groups":[{"id":"arms"}],"steps":{
                  "matched":{"group":"arms"},"otherwise":{"group":"arms"}
                }}
                """);
        try (CreatorServer creator = start(project)) {
            assertThat(awaitExampleView(creator.baseUri(), "command:0").statusCode()).isEqualTo(200);
            final List<Long> coverage = new ArrayList<>();
            for (final String arm : List.of("matched", "otherwise")) {
                final HttpResponse<String> response = observations(creator.baseUri(),
                        "focus=group-region:arms:" + arm + "&scale=0.000001", "&example=command%3A0");
                assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
                final RailixValue.ObjectValue region = entry(object(response.body()), "group-region:arms:" + arm);
                assertThat(number(region, "count")).isEqualTo(1);
                assertThat(number(region, "selected_count")).isEqualTo(number(region, "covered_count"));
                coverage.add(number(region, "covered_count"));
            }
            assertThat(coverage).containsExactlyInAnyOrder(0L, 1L);
        }
    }

    @Test
    void metadataChangeInvalidatesTheObservationRevisionWithoutReplacingTheApplication() throws Exception {
        final Path project = directory.resolve("railix.project.json");
        Files.writeString(project, threeStepProject());
        try (CreatorServer creator = start(project)) {
            final String revision = string(scene(creator.baseUri(), ""), "revision");
            final long pid = number(application(creator.baseUri()), "pid");
            assertThat(request(creator.baseUri(), "POST", "/api/creator",
                    "{\"format\":2,\"groups\":[],\"steps\":{\"one\":{\"name\":\"Renamed\"}}}").statusCode()).isEqualTo(200);

            final HttpResponse<String> response = request(creator.baseUri(), "GET",
                    "/api/scene/observations?revision=" + revision, "");

            assertThat(response.statusCode()).as(response.body()).isEqualTo(409);
            assertThat(number(application(creator.baseUri()), "pid")).isEqualTo(pid);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void largeObservationsRemainServerSideAndDoNotHitTheSingleNodeResponseLimit(final boolean selected) throws Exception {
        final Path project = directory.resolve("railix.project.json");
        final StringBuilder nodes = new StringBuilder("""
                {"id":"app","use":"railix.app","inputs":{}},
                {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[{"name":"empty","payload":[]}]}
                """);
        final StringBuilder links = new StringBuilder("{\"from\":\"app.start\",\"to\":\"command\"}");
        String previous = "command";
        for (int index = 0; index < 6000; index++) {
            final String id = "step-" + index;
            nodes.append(",{\"id\":\"").append(id).append("\",\"use\":\"railix.field-manipulation\",\"inputs\":{}}");
            links.append(",{\"from\":\"").append(previous).append(".next\",\"to\":\"").append(id).append("\"}");
            previous = id;
        }
        links.append(",{\"from\":\"").append(previous).append(".next\",\"to\":\"end\"}");
        Files.writeString(project, "{\"format\":1,\"id\":\"large-observations\",\"nodes\":[" + nodes + "],\"links\":[" + links + "]}");
        try (CreatorServer creator = start(project)) {
            assertThat(awaitExampleView(creator.baseUri(), "command:0").statusCode()).isEqualTo(200);
            final String viewport = "x=-1000&y=-1000&width=10000000&height=10000000&scale=100000000";
            final String path = "/api/scene/observations?revision="
                    + string(scene(creator.baseUri(), viewport), "revision") + "&" + viewport
                    + (selected ? "&example=command%3A0" : "");
            final List<Long> timings = new ArrayList<>();
            HttpResponse<String> response = null;
            for (int sample = 0; sample < 23; sample++) {
                final long started = System.nanoTime();
                response = request(creator.baseUri(), "GET", path, "");
                final long elapsed = System.nanoTime() - started;
                assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
                if (sample >= 3) timings.add(elapsed);
            }

            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            final RailixValue.ObjectValue observed = object(response.body());
            assertThat(entries(observed, "nodes")).hasSizeLessThanOrEqualTo(2048);
            assertThat(entries(observed, "links")).hasSizeLessThanOrEqualTo(4096 / 3);
            assertThat(observed.values().get("limited")).isEqualTo(RailixValue.bool(true));
            assertThat(entries(observed, "links").stream().filter(link -> link.values().containsKey("executions")).count())
                    .as("Capped connections must not present partial execution totals").isZero();
            assertThat(response.body().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(2 * 1024 * 1024);
            if (selected) {
                assertThat(entries(observed, "nodes")).allSatisfy(node -> {
                    assertThat(number(node, "selected_count")).isLessThanOrEqualTo(number(node, "covered_count"));
                    assertThat(number(node, "covered_count")).isEqualTo(string(node, "id").equals("app")
                            ? 0 : number(node, "count"));
                });
            }
            timings.sort(Long::compareTo);
            System.out.printf(java.util.Locale.ROOT,
                    "RAILIX_SCENE_OBSERVATIONS advisory=true selected=%s project_nodes=6002 warmup=3 samples=20 p95_ms=%.3f response_bytes=%d%n",
                    selected, timings.get(18) / 1_000_000.0, response.body().getBytes(StandardCharsets.UTF_8).length);
        }
    }

    @Test
    void staleSceneRevisionIsRejectedBeforeObservationsAreReturned() throws Exception {
        final HttpResponse<String> response = request(server.baseUri(), "GET",
                "/api/scene/observations?revision=" + "0".repeat(64), "");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(409);
        assertThat(string(object(response.body()), "status")).isEqualTo("scene-revision-conflict");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "revision=broken", "revision=current&example=", "revision=current&example=command:abc",
            "revision=current&mode=build", "revision=current&mode=live", "revision=current&mode=example",
            "revision=current&revision=current", "revision=current&unknown=1",
            "revision=current&x=0", "revision=current&scale=NaN"
    })
    void invalidObservationQueryHasADeterministicIngressFailure(final String query) throws Exception {
        final String revision = string(scene(server.baseUri(), ""), "revision");
        final HttpResponse<String> response = request(server.baseUri(), "GET",
                "/api/scene/observations?" + query.replace("current", revision), "");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(string(object(response.body()), "status")).isEqualTo("invalid-scene-query");
    }

    @Test
    void unknownSelectedExampleIsNotReportedAsUnreached() throws Exception {
        final HttpResponse<String> response = observations(server.baseUri(), "",
                "&example=missing%3A0");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(404);
    }

    @Test
    void unknownFocusRemainsNotFound() throws Exception {
        final String revision = string(scene(server.baseUri(), ""), "revision");
        final HttpResponse<String> response = request(server.baseUri(), "GET",
                "/api/scene/observations?revision=" + revision + "&focus=missing", "");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(404);
    }

    @Test
    void observationEndpointRequiresCreatorAuthentication() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            final HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                    server.baseUri().resolve("/api/scene/observations"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(401);
        }
    }

    @Test
    void observationEndpointRejectsMutationMethods() throws Exception {
        final HttpResponse<String> response = request(server.baseUri(), "POST",
                "/api/scene/observations", "{}");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(405);
    }

    @Test
    void observationResponseIsBoundedAndNotCacheable() throws Exception {
        final HttpResponse<String> response = observations(server.baseUri(), "scale=100",
                "&example=command%3A0");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        final RailixValue.ObjectValue observed = object(response.body());
        assertThat(entries(observed, "nodes")).hasSizeLessThanOrEqualTo(2048);
        assertThat(entries(observed, "links")).hasSizeLessThanOrEqualTo(4096 / 3);
        assertThat(response.body().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(2 * 1024 * 1024);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    }

    private static HttpResponse<String> observations(final URI uri, final String viewport, final String extra) throws Exception {
        final String revision = string(scene(uri, viewport), "revision");
        return request(uri, "GET", "/api/scene/observations?revision=" + revision
                + (viewport.isEmpty() ? "" : "&" + viewport) + extra, "");
    }

    private static RailixValue.ObjectValue portableIcon(final String color) {
        return RailixValue.object(Map.of("media_type", RailixValue.string("image/svg+xml"),
                "data", RailixValue.string(Base64.getEncoder().encodeToString(
                        ("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"16\" height=\"16\"><path fill=\""
                                + color + "\" d=\"M0 0h16v16H0z\"/></svg>").getBytes(StandardCharsets.UTF_8)))));
    }

    private RailixValue.ObjectValue operationalObservations(final String source, final StepCatalog catalog,
                                                            final int runs, final int expectedStatus) throws Exception {
        final DevelopmentRuntimeGeneratedProcess.Build build = DevelopmentRuntimeGeneratedProcess.build(directory, source, catalog);
        final String token = "scene-observation-test";
        try (var child = DevelopmentRuntimeGeneratedProcess.launch(build).token(token)) {
            final URI uri = child.awaitReady();
            final long deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
            while (true) {
                final HttpResponse<String> response = child.request(uri, token, "GET", "/v1/examples/status", "", null);
                assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
                final RailixValue.ObjectValue status = object(response.body());
                if (string(status, "state").equals("completed")) {
                    assertThat(number(status, "completed")).isEqualTo(number(status, "total"));
                    break;
                }
                assertThat(System.nanoTime()).as("Example suite must complete: %s", response.body()).isLessThan(deadline);
                Thread.sleep(20);
            }
            for (int run = 0; run < runs; run++) {
                final HttpResponse<String> response = child.request(uri, token, "POST", "/v1/run/command", "{}", "false");
                assertThat(response.statusCode()).as(response.body()).isEqualTo(expectedStatus);
            }
            final HttpResponse<String> response = child.request(uri, token, "GET", "/v1/metrics", "", null);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            final RailixValue.ObjectValue metrics = object(response.body());
            final CreatorScene scene = new CreatorScene(source, object("""
                    {"format":2,"groups":[{"id":"normalize"}],"steps":{
                      "one":{"group":"normalize"},"two":{"group":"normalize"},"three":{"group":"normalize"}
                    }}
                    """), catalog);
            final Map<String, String> parameters = CreatorScene.observationParameters("scale=0.000001&revision="
                    + string(scene.view(""), "revision"));
            return scene.observations(parameters, scene.observationView(parameters), Map.of("metrics", metrics),
                    0, number(metrics, "application_pid"));
        }
    }

    private static RailixValue.ObjectValue scene(final URI uri, final String viewport) throws Exception {
        final HttpResponse<String> response = request(uri, "GET",
                "/api/scene" + (viewport.isEmpty() ? "" : "?" + viewport), "");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return object(response.body());
    }

    private static List<RailixValue.ObjectValue> entries(final RailixValue.ObjectValue value, final String name) {
        return ((RailixValue.ArrayValue) value.values().get(name)).values().stream()
                .map(RailixValue.ObjectValue.class::cast).toList();
    }

    private static List<String> ids(final RailixValue.ObjectValue value, final String name) {
        return entries(value, name).stream().map(entry -> string(entry, "id")).toList();
    }

    private static RailixValue.ObjectValue entry(final RailixValue.ObjectValue value, final String id) {
        return entries(value, "nodes").stream().filter(entry -> id.equals(string(entry, "id")))
                .findFirst().orElseThrow();
    }
}
