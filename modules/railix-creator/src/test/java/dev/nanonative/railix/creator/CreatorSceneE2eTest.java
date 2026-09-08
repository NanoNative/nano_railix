package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.value.RailixValue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@Execution(ExecutionMode.SAME_THREAD)
final class CreatorSceneE2eTest extends CreatorServerE2eSupport {
    @TempDir
    static Path sceneDirectory;
    private static CreatorServer server;

    @BeforeAll
    @Timeout(120)
    static void openScene() throws Exception {
        final Path project = sceneDirectory.resolve("railix.project.json");
        Files.writeString(project, chain(2050));
        Files.writeString(sceneDirectory.resolve("railix.creator.json"), metadata("Normalize"));
        server = start(project, sceneDirectory.resolve("home"));
    }

    @AfterAll
    static void closeScene() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void overviewReplacesThousandsOfStepsWithRecursiveRegions() throws Exception {
        final RailixValue.ObjectValue scene = scene("");

        assertThat(nodes(scene)).hasSizeLessThan(30);
        assertThat(nodes(scene)).anySatisfy(node -> {
            assertThat(string(node, "kind")).isEqualTo("region");
            assertThat(number(node, "count")).isGreaterThan(100);
        });
        assertThat(string(scene, "revision")).hasSize(64);
        assertThat(decimal((RailixValue.ObjectValue) scene.values().get("bounds"), "width")).isLessThan(2000);
        assertThat(scene.values()).doesNotContainKeys("project", "creator", "examples", "metrics");
        assertThat(scene.values().get("limited")).isEqualTo(RailixValue.bool(false));
    }

    @Test
    void nearLimitPortableMetadataKeepsTheVisibleSceneWithinItsResponseBudget() throws Exception {
        final Path project = directory.resolve("railix.project.json");
        Files.writeString(project, chain(11));
        final String styles = IntStream.range(0, 11).mapToObj(index -> {
            final String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 20 20\">"
                    + " ".repeat(60_000) + "<path fill=\"#%06x\" d=\"M0 0h20v20H0z\"/></svg>".formatted(index);
            return "\"step-%04d\":{\"icon\":{\"media_type\":\"image/svg+xml\",\"data\":\"%s\"}}"
                    .formatted(index, Base64.getEncoder().encodeToString(svg.getBytes(StandardCharsets.UTF_8)));
        }).collect(java.util.stream.Collectors.joining(","));
        Files.writeString(directory.resolve("railix.creator.json"), "{\"format\":2,\"groups\":[],\"steps\":{" + styles + "}}");
        try (CreatorServer creator = start(project)) {
            final HttpResponse<String> response = request(creator.baseUri(), "GET", "/api/scene?scale=1000000", "");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body().getBytes(StandardCharsets.UTF_8).length).isBetween(800_000, 2 * 1024 * 1024);
            assertThat(((RailixValue.ObjectValue) object(response.body()).values().get("icons")).values()).hasSize(11);
            assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        }
    }

    @Test
    void deeperZoomRevealsMoreDetailInTheSameWorld() throws Exception {
        final RailixValue.ObjectValue overview = scene("?scale=1");
        final RailixValue.ObjectValue closer = scene("?scale=8");

        assertThat(nodes(closer).size()).isGreaterThan(nodes(overview).size());
        assertThat(closer.values().get("bounds")).isEqualTo(overview.values().get("bounds"));
        assertThat(string(closer, "revision")).isEqualTo(string(overview, "revision"));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 5, 8})
    void smallOrdinaryFlowShowsItsRealStepsAtTypicalFitScale(final int count) throws Exception {
        final Path project = directory.resolve("railix.project.json");
        Files.writeString(project, chain(count));
        try (CreatorServer creator = start(project)) {
            final HttpResponse<String> response = request(creator.baseUri(), "GET", "/api/scene?scale=0.6", "");
            assertThat(response.statusCode()).isEqualTo(200);
            final List<RailixValue.ObjectValue> nodes = nodes(object(response.body()));

            assertThat(nodes.stream().map(node -> string(node, "kind"))).doesNotContain("region");
            assertThat(nodes.stream().filter(node -> "step".equals(string(node, "kind")))).hasSize(count);
            assertThat(nodes.stream().map(node -> string(node, "id"))).contains("app", "command", "step-0000");
        }
    }

    @Test
    void focusedStepIsRevealedWithoutChangingItsCoordinates() throws Exception {
        final RailixValue.ObjectValue one = scene("?focus=step-1000&scale=1");
        final RailixValue.ObjectValue four = scene("?focus=step-1000&scale=4");

        assertThat(geometry(node(one, "step-1000"))).isEqualTo(geometry(node(four, "step-1000")));
        assertThat(one.values().get("focus")).isEqualTo(four.values().get("focus"));
        assertThat(nodes(four)).hasSizeLessThan(10);
    }

    @Test
    void disconnectedUsesOfOneGroupRemainDistinctOccurrences() throws Exception {
        final RailixValue.ObjectValue first = scene("?focus=group-region:normalize:step-0020&scale=0.000001");
        final RailixValue.ObjectValue second = scene("?focus=group-region:normalize:step-0100&scale=0.000001");

        assertThat(number(node(first, "group-region:normalize:step-0020"), "count")).isEqualTo(4);
        assertThat(number(node(second, "group-region:normalize:step-0100"), "count")).isEqualTo(4);
        assertThat(first.values().get("focus")).isNotEqualTo(second.values().get("focus"));
        assertThat(string(node(first, "group-region:normalize:step-0020"), "group")).isEqualTo("normalize");
    }

    @Test
    void groupManagerFocusSelectsTheFirstStableOccurrence() throws Exception {
        final RailixValue.ObjectValue alias = scene("?focus=group:normalize");
        final RailixValue.ObjectValue explicit = scene("?focus=group-region:normalize:step-0020");

        assertThat(alias.values().get("focus")).isEqualTo(explicit.values().get("focus"));
    }

    @Test
    void stepAndGroupWithIdenticalIdsHaveSeparateFocusNamespaces() throws Exception {
        final Path project = directory.resolve("railix.project.json");
        Files.writeString(project, branchGroupProject());
        Files.writeString(directory.resolve("railix.creator.json"), """
                {"format":2,"groups":[{"id":"choice"}],"steps":{
                  "choice":{"group":"choice"},"matched":{"group":"choice"}
                }}
                """);
        try (CreatorServer creator = start(project)) {
            final RailixValue.ObjectValue step = object(request(creator.baseUri(), "GET",
                    "/api/scene?focus=choice", "").body());
            final RailixValue.ObjectValue group = object(request(creator.baseUri(), "GET",
                    "/api/scene?focus=group:choice", "").body());

            assertThat(step.values().get("focus")).isNotEqualTo(group.values().get("focus"));
            assertThat(string(node(step, "choice"), "kind")).isEqualTo("step");
            assertThat(string(node(group, "group-region:choice:choice"), "group")).isEqualTo("choice");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"observations", "focus"})
    void groupAliasesDoNotOverwriteOccurrenceIdentities(final String scenario) throws Exception {
        final Path project = directory.resolve("railix.project.json");
        Files.writeString(project, chain(3).replace("step-0000", "s0")
                .replace("step-0001", "s1").replace("step-0002", "s2"));
        try (CreatorServer creator = start(project)) {
            final HttpResponse<String> saved = request(creator.baseUri(), "POST", "/api/creator", """
                    {"format":2,"groups":[{"id":"g"},{"id":"g:s0"}],"steps":{
                      "s0":{"group":"g"},"s1":{"group":"g:s0"},"s2":{"group":"g:s0"}
                    }}
                    """);
            assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);
            final String viewport = "scale=0.000001";
            final HttpResponse<String> response = request(creator.baseUri(), "GET", "/api/scene?" + viewport, "");
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            final RailixValue.ObjectValue scene = object(response.body());
            final List<RailixValue.ObjectValue> groups = nodes(scene).stream()
                    .filter(node -> node.values().containsKey("group")).toList();

            assertThat(groups.stream().map(group -> string(group, "group")))
                    .containsExactlyInAnyOrder("g", "g:s0");
            assertThat(groups).allSatisfy(group -> {
                assertThat(group.values().get("expanded")).isEqualTo(RailixValue.bool(false));
                assertThat(number(group, "count")).isEqualTo("g".equals(string(group, "group")) ? 1 : 2);
            });
            assertThat(geometry(groups.getFirst())).isNotEqualTo(geometry(groups.getLast()));
            assertThat(nodes(scene).stream().map(node -> string(node, "id"))).doesNotHaveDuplicates();

            if ("observations".equals(scenario)) {
                final HttpResponse<String> observedResponse = request(creator.baseUri(), "GET",
                        "/api/scene/observations?revision=" + string(scene, "revision") + "&" + viewport, "");
                assertThat(observedResponse.statusCode()).as(observedResponse.body()).isEqualTo(200);
                final RailixValue.ObjectValue observed = object(observedResponse.body());

                assertThat(string(observed, "revision")).isEqualTo(string(scene, "revision"));
                assertThat(nodes(observed).stream().map(node -> string(node, "id"))).doesNotHaveDuplicates()
                        .containsExactlyInAnyOrderElementsOf(nodes(scene).stream().map(node -> string(node, "id")).toList());
                for (final RailixValue.ObjectValue visible : nodes(scene)) {
                    final String id = string(visible, "id");
                    assertThat(number(node(observed, id), "count")).as("Observation count for %s", id)
                            .isEqualTo(number(visible, "count"));
                }
                return;
            }
            for (final RailixValue.ObjectValue group : groups) {
                for (final String focus : List.of(string(group, "id"), "group:" + string(group, "group"))) {
                    final HttpResponse<String> focusedResponse = request(creator.baseUri(), "GET",
                            "/api/scene?" + viewport + "&focus=" + URLEncoder.encode(focus, StandardCharsets.UTF_8), "");
                    assertThat(focusedResponse.statusCode()).as(focusedResponse.body()).isEqualTo(200);
                    assertThat(object(focusedResponse.body()).values().get("focus"))
                            .as("Focus %s must select group %s", focus, string(group, "group"))
                            .isEqualTo(RailixValue.object(geometry(group)));
                }
            }
        }
    }

    @Test
    void zoomingInsideCustomGroupKeepsItsBoundaryAndRevealsItsSteps() throws Exception {
        final RailixValue.ObjectValue scene = scene("?focus=group-region:normalize:step-0020");

        assertThat(node(scene, "group-region:normalize:step-0020").values().get("expanded"))
                .isEqualTo(RailixValue.bool(true));
        assertThat(nodes(scene).stream().map(node -> string(node, "id")))
                .contains("step-0020", "step-0021", "step-0022", "step-0023");
    }

    @Test
    void groupedChoicePreservesSeparateParallelArms() throws Exception {
        final Path project = directory.resolve("railix.project.json");
        Files.writeString(project, branchGroupProject());
        Files.writeString(directory.resolve("railix.creator.json"), """
                {"format":2,"groups":[{"id":"routing"}],"steps":{
                  "choice":{"group":"routing"},"matched":{"group":"routing"},
                  "otherwise":{"group":"routing"}
                }}
                """);
        try (CreatorServer creator = start(project)) {
            final RailixValue.ObjectValue scene = object(request(creator.baseUri(), "GET",
                    "/api/scene?focus=group:routing", "").body());
            final RailixValue.ObjectValue choice = node(scene, "choice");
            final RailixValue.ObjectValue matched = node(scene, "matched");
            final RailixValue.ObjectValue otherwise = node(scene, "otherwise");

            assertThat(decimal(matched, "x")).isEqualTo(decimal(otherwise, "x"));
            assertThat(decimal(matched, "y")).isLessThan(decimal(otherwise, "y"));
            assertThat(decimal(choice, "x")).isLessThan(decimal(matched, "x"));
            assertThat(array(scene, "links").stream().map(RailixValue.ObjectValue.class::cast)
                    .filter(link -> "choice".equals(string(link, "from")))
                    .map(link -> string(link, "outcome"))).containsExactlyInAnyOrder("match", "otherwise");
        }
    }

    @Test
    void partialBranchGroupDoesNotEncloseTheUngroupedArm() throws Exception {
        final Path project = directory.resolve("railix.project.json");
        Files.writeString(project, branchGroupProject());
        Files.writeString(directory.resolve("railix.creator.json"), """
                {"format":2,"groups":[{"id":"routing"}],"steps":{
                  "choice":{"group":"routing"},"matched":{"group":"routing"}
                }}
                """);
        try (CreatorServer creator = start(project)) {
            final RailixValue.ObjectValue scene = object(request(creator.baseUri(), "GET",
                    "/api/scene?scale=100000", "").body());
            final RailixValue.ObjectValue group = node(scene, "group-region:routing:choice");
            final RailixValue.ObjectValue otherwise = node(scene, "otherwise");

            assertThat(decimal(otherwise, "x")).isGreaterThan(decimal(group, "x") + decimal(group, "width"));
        }
    }

    @Test
    void smallBranchingFlowKeepsChoiceAndBothArmsVisibleAtFitScale() throws Exception {
        final Path project = directory.resolve("railix.project.json");
        Files.writeString(project, branchGroupProject());
        try (CreatorServer creator = start(project)) {
            final RailixValue.ObjectValue scene = object(request(creator.baseUri(), "GET",
                    "/api/scene?scale=0.6", "").body());

            assertThat(nodes(scene).stream().map(node -> string(node, "id")))
                    .contains("app", "command", "choice", "matched", "otherwise");
            assertThat(nodes(scene).stream().map(node -> string(node, "kind"))).doesNotContain("region");
            assertThat(decimal(node(scene, "matched"), "y")).isLessThan(decimal(node(scene, "otherwise"), "y"));
        }
    }

    @Test
    void viewportCullsOffscreenStepsAndRegions() throws Exception {
        final RailixValue.ObjectValue scene = scene("?x=-10000&y=-10000&width=100&height=100&scale=1");

        assertThat(nodes(scene)).isEmpty();
        assertThat(array(scene, "links")).isEmpty();
    }

    @Test
    void routesRemainVisibleWhilePanningBetweenStations() throws Exception {
        final RailixValue.ObjectValue first = node(scene("?focus=step-0000"), "step-0000");
        final RailixValue.ObjectValue second = node(scene("?focus=step-0001"), "step-0001");
        final double x = decimal(first, "x") + decimal(first, "width");
        final double gap = decimal(second, "x") - x;
        final double height = decimal(first, "height") / 10;
        final RailixValue.ObjectValue scene = scene("?x=" + (x + gap / 4)
                + "&y=" + (decimal(first, "y") + decimal(first, "height") / 2 - height / 2)
                + "&width=" + gap / 2 + "&height=" + height + "&scale=" + 1000 / gap);

        assertThat(nodes(scene)).isEmpty();
        assertThat(array(scene, "links")).anySatisfy(value -> {
            final RailixValue.ObjectValue link = (RailixValue.ObjectValue) value;
            assertThat(string(link, "from")).isEqualTo("step-0000");
            assertThat(string(link, "to")).isEqualTo("step-0001");
        });
    }

    @Test
    void adversarialWholeWorldAtFullDetailHasExplicitOutputLimits() throws Exception {
        final RailixValue.ObjectValue scene = scene("?x=-1000&y=-1000&width=10000000&height=10000000&scale=100000000");

        assertThat(nodes(scene)).hasSizeLessThanOrEqualTo(2048);
        final long segments = array(scene, "links").stream()
                .map(RailixValue.ObjectValue.class::cast)
                .mapToLong(link -> array(link, "points").size() - 1).sum();
        assertThat(segments).isLessThanOrEqualTo(4096);
        assertThat(scene.values().get("limited")).isEqualTo(RailixValue.bool(true));
        assertThat(nodes(scene).stream().map(node -> string(node, "kind")))
                .containsOnly("app", "trigger", "step", "region", "end");
        final List<String> ids = nodes(scene).stream().map(node -> string(node, "id")).toList();
        assertThat(array(scene, "links").stream().map(RailixValue.ObjectValue.class::cast)
                .flatMap(link -> java.util.stream.Stream.of(string(link, "from"), string(link, "to"))))
                .allSatisfy(id -> assertThat(ids).contains(id));
    }

    @Test
    void repeatedViewportQueriesAreDeterministic() throws Exception {
        assertThat(scene("?focus=step-1100&scale=2")).isEqualTo(scene("?focus=step-1100&scale=2"));
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 4, 8})
    void aBranchsLastStepAndTerminalShareTheirLane(final int steps) throws Exception {
        try (CreatorServer creator = start(groupedBranches(steps))) {
            final RailixValue.ObjectValue scene = object(request(creator.baseUri(), "GET",
                    "/api/scene?x=-100&y=-100&width=2000&height=1000&scale=0.1", "").body());
            for (int index = 0; index < steps; index++) {
                final RailixValue.ObjectValue last = node(scene, "group-region:reply:output-" + index);
                final RailixValue.ObjectValue terminal = node(scene, "end:output-" + index + ".next");
                assertThat(decimal(last, "y") + decimal(last, "height") / 2)
                        .as("A plain continuation to End must stay on the same horizontal lane")
                        .isCloseTo(decimal(terminal, "y") + decimal(terminal, "height") / 2,
                                org.assertj.core.data.Offset.offset(0.000001));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 4, 8})
    void branchesLeaveTheRightPortThroughOneSharedTrunk(final int steps) throws Exception {
        try (CreatorServer creator = start(groupedBranches(steps))) {
            final RailixValue.ObjectValue scene = object(request(creator.baseUri(), "GET",
                    "/api/scene?x=-100&y=-100&width=2000&height=1000&scale=0.1", "").body());
            final RailixValue.ObjectValue route = node(scene, "route");
            final List<RailixValue.ObjectValue> branches = array(scene, "links").stream()
                    .map(RailixValue.ObjectValue.class::cast)
                    .filter(link -> "route".equals(string(link, "from"))).toList();
            assertThat(branches).hasSize(steps);
            assertThat(branches).allSatisfy(link -> {
                assertThat(point(link, 0, 0)).isCloseTo(decimal(route, "x") + decimal(route, "width"),
                        org.assertj.core.data.Offset.offset(0.000001));
                assertThat(point(link, 1, 0)).isCloseTo(point(branches.getFirst(), 1, 0),
                        org.assertj.core.data.Offset.offset(0.000001));
            });
        }
    }

    private Path groupedBranches(final int steps) throws Exception {
        final Path project = directory.resolve("railix.project.json");
        final String outputs = IntStream.range(0, steps).mapToObj(index ->
                "{\"id\":\"output-" + index + "\",\"use\":\"railix.field-manipulation\",\"inputs\":{}}")
                .collect(java.util.stream.Collectors.joining(","));
        final String cases = IntStream.range(0, steps - 1).mapToObj(index ->
                "{\"outcome\":\"case-" + index + "\",\"option\":\"literal\",\"inputs\":{\"value\":1},"
                        + "\"when\":{\"transforms\":[],\"all\":[]}}")
                .collect(java.util.stream.Collectors.joining(","));
        final String links = IntStream.range(0, steps).mapToObj(index ->
                "{\"from\":\"route." + (index == steps - 1 ? "otherwise" : "case-" + index)
                        + "\",\"to\":\"output-" + index + "\"},"
                        + "{\"from\":\"output-" + index + ".next\",\"to\":\"end\"}")
                .collect(java.util.stream.Collectors.joining(","));
        Files.writeString(project, """
                {"format":1,"id":"branch-lanes","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[{"name":"example","payload":[]}]},
                  {"id":"route","use":"railix.switch","inputs":{"cases":[%s]}},%s
                ],"links":[{"from":"app.start","to":"command"},{"from":"command.next","to":"route"},%s]}
                """.formatted(cases, outputs, links));
        final String groups = IntStream.range(0, steps).mapToObj(index ->
                "\"output-" + index + "\":{\"group\":\"reply\"}")
                .collect(java.util.stream.Collectors.joining(","));
        Files.writeString(directory.resolve("railix.creator.json"),
                "{\"format\":2,\"groups\":[{\"id\":\"reply\",\"name\":\"Reply\"}],\"steps\":{" + groups + "}}");
        return project;
    }

    @Test
    void panningAnAggregateOffscreenDoesNotChangeItsRetainedRail() throws Exception {
        final RailixValue.ObjectValue overview = scene("?scale=1");
        final Map<String, RailixValue.ObjectValue> byId = nodes(overview).stream()
                .collect(java.util.stream.Collectors.toMap(node -> string(node, "id"), node -> node));
        final RailixValue.ObjectValue rail = array(overview, "links").stream()
                .map(RailixValue.ObjectValue.class::cast)
                .filter(link -> byId.containsKey(string(link, "from")))
                .filter(link -> "region".equals(string(byId.get(string(link, "from")), "kind")))
                .filter(link -> point(link, 3, 0) > point(link, 0, 0))
                .findFirst().orElseThrow();
        final double startX = point(rail, 0, 0);
        final double y = point(rail, 0, 1);
        final double span = point(rail, 3, 0) - startX;
        final double margin = span / 10;
        final RailixValue.ObjectValue before = scene("?scale=1&x=" + (startX - margin)
                + "&y=" + (y - margin) + "&width=" + span * 2 + "&height=" + margin * 2);
        final RailixValue.ObjectValue after = scene("?scale=1&x=" + (startX + margin)
                + "&y=" + (y - margin) + "&width=" + span * 2 + "&height=" + margin * 2);

        assertThat(array(before, "links")).contains(rail);
        assertThat(array(after, "links")).contains(rail);
        assertThat(nodes(before).stream().map(node -> string(node, "id"))).contains(string(rail, "from"));
        assertThat(nodes(after).stream().map(node -> string(node, "id"))).doesNotContain(string(rail, "from"));
    }

    @Test
    void metadataChangeInvalidatesTheSceneWithoutRestartingTheApplication() throws Exception {
        final RailixValue.ObjectValue before = scene("?focus=group-region:normalize:step-0020&scale=0.1");
        final long pid = number(application(server.baseUri()), "pid");
        try {
            assertThat(request(server.baseUri(), "POST", "/api/creator", metadata("Text cleanup")).statusCode())
                    .isEqualTo(200);
            final RailixValue.ObjectValue after = scene("?focus=group-region:normalize:step-0020&scale=0.1");

            assertThat(string(after, "revision")).isNotEqualTo(string(before, "revision"));
            assertThat(string(node(after, "group-region:normalize:step-0020"), "name")).isEqualTo("Text cleanup");
            assertThat(after.values().get("bounds")).isEqualTo(before.values().get("bounds"));
            assertThat(number(application(server.baseUri()), "pid")).isEqualTo(pid);
        } finally {
            assertThat(request(server.baseUri(), "POST", "/api/creator", metadata("Normalize")).statusCode())
                    .isEqualTo(200);
        }
    }

    @Test
    void acceptedFunctionalChangeInvalidatesTheScene() throws Exception {
        try (CreatorServer creator = start(directory.resolve("railix.project.json"))) {
            final String before = string(object(request(creator.baseUri(), "GET", "/api/scene", "").body()), "revision");

            assertThat(request(creator.baseUri(), "POST", "/api/project", CreatorProjects.empty("new-name")).statusCode())
                    .isEqualTo(200);
            final RailixValue.ObjectValue after = object(request(creator.baseUri(), "GET", "/api/scene", "").body());

            assertThat(string(after, "revision")).isNotEqualTo(before);
            assertThat(string(node(after, "app"), "name")).isEqualTo("new-name");
        }
    }

    @Test
    void rejectedProjectDoesNotInvalidateAcceptedGeometry() throws Exception {
        final RailixValue.ObjectValue before = scene("");

        assertThat(request(server.baseUri(), "POST", "/api/project", "{}").statusCode()).isEqualTo(422);
        assertThat(scene("")).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "scale=0", "scale=-1", "scale=NaN", "scale=Infinity", "scale=invalid",
            "x=1", "x=1&y=1&width=0&height=1", "x=1&y=1&width=1&height=-1",
            "x=NaN&y=1&width=1&height=1", "x=1&y=Infinity&width=1&height=1",
            "x=1e308&y=1&width=1e308&height=1", "scale=1&scale=2", "zoom=1", "scale",
            "focus="
    })
    void invalidViewportReturnsActionableIngressFailure(final String query) throws Exception {
        final HttpResponse<String> response = request(server.baseUri(), "GET", "/api/scene?" + query, "");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("invalid-scene-query", "message");
    }

    @Test
    void sceneEndpointRequiresCreatorAuthentication() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            final HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(server.baseUri().resolve("/api/scene")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(response.statusCode()).isEqualTo(401);
        }
    }

    @Test
    void deletedOrUnknownFocusReturnsNotFound() throws Exception {
        final HttpResponse<String> response = request(server.baseUri(), "GET", "/api/scene?focus=unknown-step", "");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("scene-focus-not-found", "Scene focus does not identify a Step or region.");
    }

    @Test
    void sceneEndpointRejectsMutationMethods() throws Exception {
        assertThat(request(server.baseUri(), "POST", "/api/scene", "{}").statusCode()).isEqualTo(405);
    }

    @Test
    void worldRendererIsServedAsJavaScript() throws Exception {
        final HttpResponse<String> response = request(server.baseUri(), "GET", "/world.js", "");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).contains("text/javascript; charset=utf-8");
    }

    private static RailixValue.ObjectValue scene(final String query) throws Exception {
        final HttpResponse<String> response = request(server.baseUri(), "GET", "/api/scene" + query, "");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return object(response.body());
    }

    private static List<RailixValue.ObjectValue> nodes(final RailixValue.ObjectValue scene) {
        return array(scene, "nodes").stream().map(RailixValue.ObjectValue.class::cast).toList();
    }

    private static List<RailixValue> array(final RailixValue.ObjectValue value, final String key) {
        return ((RailixValue.ArrayValue) value.values().get(key)).values();
    }

    private static RailixValue.ObjectValue node(final RailixValue.ObjectValue scene, final String id) {
        return nodes(scene).stream().filter(node -> id.equals(string(node, "id"))).findFirst().orElseThrow();
    }

    private static Map<String, RailixValue> geometry(final RailixValue.ObjectValue node) {
        return Map.of("x", node.values().get("x"), "y", node.values().get("y"),
                "width", node.values().get("width"), "height", node.values().get("height"));
    }

    private static double decimal(final RailixValue.ObjectValue node, final String key) {
        return ((RailixValue.NumberValue) node.values().get(key)).value().doubleValue();
    }

    private static double point(final RailixValue.ObjectValue link, final int index, final int axis) {
        return ((RailixValue.NumberValue) ((RailixValue.ArrayValue) array(link, "points").get(index))
                .values().get(axis)).value().doubleValue();
    }

    private static String chain(final int count) {
        final StringBuilder nodes = new StringBuilder("""
                {"id":"app","use":"railix.app","inputs":{}},
                {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[{"name":"empty","payload":[]}]}
                """);
        final StringBuilder links = new StringBuilder("{\"from\":\"app.start\",\"to\":\"command\"}");
        String previous = "command";
        for (int index = 0; index < count; index++) {
            final String id = "step-%04d".formatted(index);
            nodes.append(",{\"id\":\"").append(id).append("\",\"use\":\"railix.field-manipulation\",\"inputs\":{}}");
            links.append(",{\"from\":\"").append(previous).append(".next\",\"to\":\"").append(id).append("\"}");
            previous = id;
        }
        links.append(",{\"from\":\"").append(previous).append(".next\",\"to\":\"end\"}");
        return "{\"format\":1,\"id\":\"scene-world\",\"nodes\":[" + nodes + "],\"links\":[" + links + "]}";
    }

    private static String metadata(final String name) {
        final String members = IntStream.concat(IntStream.range(20, 24), IntStream.range(100, 104))
                .mapToObj(index -> "\"step-%04d\":{\"group\":\"normalize\"}".formatted(index))
                .collect(java.util.stream.Collectors.joining(","));
        return "{\"format\":2,\"groups\":[{\"id\":\"normalize\",\"name\":\"" + name
                + "\",\"color\":\"#147982\"}],\"steps\":{" + members + "}}";
    }
}
