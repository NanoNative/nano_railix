package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.RailixJson;
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
    void regionMembersAreReadInBoundedPagesWithoutChangingTheApplication() throws Exception {
        final RailixValue.ObjectValue overview = scene("");
        final RailixValue.ObjectValue region = nodes(overview).stream()
                .filter(node -> string(node, "kind").equals("region") && number(node, "count") > 512).findFirst().orElseThrow();
        final String path = "/api/scene/observations?revision=" + string(overview, "revision")
                + "&members=" + URLEncoder.encode(string(region, "id"), StandardCharsets.UTF_8)
                + "&metrics=duration_nanos_total,duration_samples";
        final HttpResponse<String> first = request(server.baseUri(), "GET", path, "");
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        final RailixValue.ObjectValue page = object(first.body());
        assertThat(((RailixValue.ObjectValue) page.values().get("groups")).values()).hasSize(512);
        assertThat(number(page, "next")).isPositive();
        final HttpResponse<String> second = request(server.baseUri(), "GET", path + "&cursor=" + number(page, "next"), "");
        assertThat(second.statusCode()).as(second.body()).isEqualTo(200);
        assertThat(number(object(second.body()), "application_pid")).isEqualTo(number(page, "application_pid"));
        assertThat(((RailixValue.ObjectValue) object(second.body()).values().get("groups")).values().keySet())
                .doesNotContainAnyElementsOf(((RailixValue.ObjectValue) page.values().get("groups")).values().keySet());
    }

    @Test
    void enteredLongChainKeepsOneForwardLaneAndProvidesItsEntry() throws Exception {
        final Path project = directory.resolve("railix.project.json");
        final String source = chain(128);
        Files.writeString(project, source);
        try (CreatorServer creator = start(project)) {
            final String accepted = Files.readString(project);
            final var overview = object(request(creator.baseUri(), "GET", "/api/scene", "").body());
            final var region = nodes(overview).stream().filter(value -> number(value, "count") == 128).findFirst().orElseThrow();
            final String scope = URLEncoder.encode(string(region, "id"), StandardCharsets.UTF_8);
            final String query = "/api/scene?inside=" + scope + "&focus=" + scope;
            final var entered = object(request(creator.baseUri(), "GET", query, "").body());
            final var steps = nodes(entered).stream().filter(value -> "step".equals(string(value, "kind"))).toList();
            assertThat(steps).hasSize(128);
            assertThat(steps.stream().map(CreatorSceneE2eTest::centerY).distinct().count()).isEqualTo(1);
            assertThat(array(entered, "links")).hasSize(127);
            final var entry = (RailixValue.ObjectValue) entered.values().get("entry");
            assertThat(string(entry, "id")).isEqualTo("step-0000");
            assertThat(geometry(entry)).isEqualTo(geometry(node(entered, "step-0000")));
            final var zoomed = object(request(creator.baseUri(), "GET", query + "&scale=1000", "").body());
            for (int index = 0; index < 128; index++) {
                final var current = node(entered, "step-%04d".formatted(index));
                assertThat(geometry(current)).isEqualTo(geometry(node(zoomed, string(current, "id"))));
                if (index == 0) continue;
                final var previous = node(entered, "step-%04d".formatted(index - 1));
                assertThat(decimal(current, "x")).isGreaterThan(decimal(previous, "x") + decimal(previous, "width"));
            }
            for (final var value : array(entered, "links")) {
                final var link = (RailixValue.ObjectValue) value;
                assertThat(point(link, 3, 0)).isGreaterThan(point(link, 0, 0));
                assertThat(point(link, 3, 1)).isEqualTo(point(link, 0, 1));
            }
            assertThat(Files.readString(project)).isEqualTo(accepted);
        }
    }

    @Test
    void unequalSubtreesStillHaveSymmetricChoiceSocketsAndBeltLengths() throws Exception {
        final Path project = directory.resolve("railix.project.json");
        final String source = branchGroupProject()
                .replace("\"id\":\"matched\",\"use\":\"railix.field-manipulation\"", "\"id\":\"matched\",\"use\":\"railix.choice\"")
                .replace("{\"from\":\"matched.next\",\"to\":\"end\"}",
                        "{\"from\":\"matched.match\",\"to\":\"end\"},{\"from\":\"matched.otherwise\",\"to\":\"end\"}");
        Files.writeString(project, source);
        try (CreatorServer creator = start(project)) {
            final var scene = object(request(creator.baseUri(), "GET", "/api/scene?scale=1", "").body());
            final var choice = node(scene, "choice");
            assertThat(centerY(choice) - centerY(node(scene, "matched")))
                    .isCloseTo(centerY(node(scene, "otherwise")) - centerY(choice), org.assertj.core.data.Offset.offset(.000001));
            final var links = array(scene, "links").stream().map(RailixValue.ObjectValue.class::cast)
                    .filter(link -> "choice".equals(string(link, "from"))).toList();
            assertThat(links).hasSize(2);
            for (final var link : links) {
                assertThat(point(link, 0, 0)).isCloseTo(decimal(choice, "x") + decimal(choice, "width") / 2,
                        org.assertj.core.data.Offset.offset(.000001));
                assertThat(Math.abs(point(link, 0, 1) - centerY(choice))).isCloseTo(decimal(choice, "height") / 2,
                        org.assertj.core.data.Offset.offset(.000001));
            }
            final double[] lengths = links.stream().mapToDouble(link -> IntStream.range(1, array(link, "points").size())
                    .mapToDouble(index -> Math.abs(point(link, index, 0) - point(link, index - 1, 0))
                            + Math.abs(point(link, index, 1) - point(link, index - 1, 1))).sum()).toArray();
            assertThat(lengths[0]).isCloseTo(lengths[1], org.assertj.core.data.Offset.offset(.000001));
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

    @Test
    void automaticDetailLevelsDoNotStackTechnicalPartitionBorders() throws Exception {
        final List<RailixValue.ObjectValue> visible = nodes(scene("?scale=64"));
        final var boundaries = visible.stream().filter(node -> node.values().get("expanded") == RailixValue.bool(true)
                && !node.values().containsKey("group")).toList();
        assertThat(boundaries).isNotEmpty();
        for (final var boundary : boundaries) {
            assertThat(boundary.values()).containsKey("group_count");
            final var ancestors = (RailixValue.ArrayValue) boundary.values().get("regions");
            assertThat(boundaries).noneMatch(other -> ancestors.values().contains(other.values().get("id")));
        }
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
    void focusedStepRemainsVisibleAfterTheFocusParameterIsRemoved() throws Exception {
        final RailixValue.ObjectValue focused = scene("?focus=step-0021&scale=1");
        final RailixValue.ObjectValue box = (RailixValue.ObjectValue) focused.values().get("focus");
        final double scale = Math.max(.000001, decimal(focused, "focus_min_scale"));
        final RailixValue.ObjectValue settled = scene("?x=" + decimal(box, "x") + "&y=" + decimal(box, "y")
                + "&width=" + decimal(box, "width") + "&height=" + decimal(box, "height") + "&scale=" + scale);
        assertThat(nodes(settled)).anySatisfy(node -> assertThat(string(node, "id")).isEqualTo("step-0021"));
    }

    @Test
    void focusedStepIsRevealedWithoutChangingItsCoordinates() throws Exception {
        final RailixValue.ObjectValue one = scene("?focus=step-1000&scale=1");
        final RailixValue.ObjectValue four = scene("?focus=step-1000&scale=4");

        assertThat(geometry(node(one, "step-1000"))).isEqualTo(geometry(node(four, "step-1000")));
        assertThat(one.values().get("focus")).isEqualTo(four.values().get("focus"));
        assertThat(nodes(four).stream().filter(node -> !RailixValue.bool(true).equals(node.values().get("expanded"))))
                .hasSizeLessThan(10);
    }

    @Test
    void focusedStepIdentifiesItsContainingRegionsNotOtherUsesOfTheSameGroup() throws Exception {
        final RailixValue.ObjectValue selected = node(scene("?focus=step-0021&scale=10"), "step-0021");
        final var regions = (RailixValue.ArrayValue) selected.values().get("regions");
        assertThat(regions).isNotNull();
        assertThat(regions.values()).contains(RailixValue.string("group-region:normalize:step-0020"))
                .doesNotContain(RailixValue.string("group-region:normalize:step-0100"), RailixValue.string("world"));
        assertThat(regions.values()).doesNotHaveDuplicates();
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
        final String focus = "?focus=group-region:normalize:step-0020";
        final RailixValue.ObjectValue scene = scene(focus + "&scale=" + decimal(scene(focus), "focus_min_scale"));

        assertThat(node(scene, "group-region:normalize:step-0020").values().get("expanded"))
                .isEqualTo(RailixValue.bool(true));
        assertThat(number(node(scene, "group-region:normalize:step-0020"), "group_count")).isZero();
        assertThat(nodes(scene).stream().map(node -> string(node, "id")))
                .contains("step-0020", "step-0021", "step-0022", "step-0023");
    }

    @Test
    void tallBranchGroupEntersAtItsOriginRatherThanItsTopmostBranch() throws Exception {
        final String nodes = IntStream.range(0, 15).mapToObj(index ->
                "{\"id\":\"branch-" + index + "\",\"use\":\"railix.choice\",\"inputs\":{\"conditions\":[]}}")
                .collect(java.util.stream.Collectors.joining(","));
        final String links = IntStream.range(0, 15).mapToObj(index ->
                "{\"from\":\"branch-" + index + ".match\",\"to\":\"" + (index < 7 ? "branch-" + (index * 2 + 1) : "end") + "\"},"
                + "{\"from\":\"branch-" + index + ".otherwise\",\"to\":\"" + (index < 7 ? "branch-" + (index * 2 + 2) : "end") + "\"}")
                .collect(java.util.stream.Collectors.joining(","));
        final String styles = IntStream.range(0, 15).mapToObj(index -> "\"branch-" + index + "\":{\"group\":\"routing\"}")
                .collect(java.util.stream.Collectors.joining(","));
        final Path project = directory.resolve("railix.project.json");
        Files.writeString(project, "{\"format\":1,\"id\":\"branches\",\"nodes\":["
                + "{\"id\":\"app\",\"use\":\"railix.app\",\"inputs\":{}},"
                + "{\"id\":\"command\",\"use\":\"railix.trigger.cli\",\"inputs\":{},\"examples\":[{\"name\":\"branch\",\"payload\":[],\"context\":{}}]}," + nodes
                + "],\"links\":[{\"from\":\"app.start\",\"to\":\"command\"},{\"from\":\"command.next\",\"to\":\"branch-0\"}," + links + "]}");
        Files.writeString(directory.resolve("railix.creator.json"), "{\"format\":2,\"groups\":[{\"id\":\"routing\"}],\"steps\":{" + styles + "}}");
        try (CreatorServer creator = start(project)) {
            final var scene = object(request(creator.baseUri(), "GET",
                    "/api/scene?inside=group-region:routing:branch-0&focus=group-region:routing:branch-0", "").body());
            assertThat(string((RailixValue.ObjectValue) scene.values().get("entry"), "id")).isEqualTo("branch-0");
        }
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
            final RailixValue.ObjectValue focused = object(request(creator.baseUri(), "GET",
                    "/api/scene?focus=group:routing", "").body());
            final RailixValue.ObjectValue scene = object(request(creator.baseUri(), "GET",
                    "/api/scene?focus=group:routing&scale=" + decimal(focused, "focus_min_scale"), "").body());
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
    void groupedChainBeforeBranchingKeepsItsTriggerOnTheCollapsedGroupsConnectionAxis() throws Exception {
        final Path project = groupedBranches(4);
        final String corridorNodes = IntStream.range(0, 12).mapToObj(index ->
                "{\"id\":\"lane-" + index + "\",\"use\":\"railix.field-manipulation\",\"inputs\":{}},")
                .collect(java.util.stream.Collectors.joining());
        final String corridorLinks = IntStream.range(0, 12).mapToObj(index ->
                "{\"from\":\"lane-" + index + ".next\",\"to\":\""
                        + (index == 11 ? "route" : "lane-" + (index + 1)) + "\"},")
                .collect(java.util.stream.Collectors.joining());
        final String source = Files.readString(project)
                .replace("\"nodes\":[", "\"nodes\":[" + corridorNodes)
                .replace("\"links\":[", "\"links\":[" + corridorLinks)
                .replace("{\"id\":\"route\"", "{\"id\":\"normalize\",\"use\":\"railix.field-manipulation\",\"inputs\":{}},"
                        + "{\"id\":\"guard\",\"use\":\"railix.choice\",\"inputs\":{}},"
                        + "{\"id\":\"limit\",\"use\":\"railix.choice\",\"inputs\":{}},"
                        + "{\"id\":\"disabled\",\"use\":\"railix.field-manipulation\",\"inputs\":{}},"
                        + "{\"id\":\"rejected\",\"use\":\"railix.field-manipulation\",\"inputs\":{}},"
                        + "{\"id\":\"route\"")
                .replace("{\"from\":\"command.next\",\"to\":\"route\"}",
                        "{\"from\":\"command.next\",\"to\":\"normalize\"},"
                                + "{\"from\":\"normalize.next\",\"to\":\"guard\"},"
                                + "{\"from\":\"guard.match\",\"to\":\"limit\"},"
                                + "{\"from\":\"guard.otherwise\",\"to\":\"disabled\"},"
                                + "{\"from\":\"limit.match\",\"to\":\"lane-0\"},"
                                + "{\"from\":\"limit.otherwise\",\"to\":\"rejected\"},"
                                + "{\"from\":\"disabled.next\",\"to\":\"end\"},"
                                + "{\"from\":\"rejected.next\",\"to\":\"end\"}");
        Files.writeString(project, source);
        Files.writeString(directory.resolve("railix.creator.json"), """
                {"format":2,"groups":[{"id":"normalization"},{"id":"routing"},{"id":"reply"}],"steps":{
                  "normalize":{"group":"normalization"},
                  "route":{"group":"routing"},
                  "disabled":{"group":"reply"},"rejected":{"group":"reply"},
                  "output-0":{"group":"reply"},"output-1":{"group":"reply"},
                  "output-2":{"group":"reply"},"output-3":{"group":"reply"}
                }}
                """);
        try (CreatorServer creator = start(project)) {
            final RailixValue.ObjectValue scene = object(request(creator.baseUri(), "GET",
                    "/api/scene?x=-100&y=-100&width=2000&height=1000&scale=0.1", "").body());
            final RailixValue.ObjectValue trigger = node(scene, "command");
            final RailixValue.ObjectValue group = node(scene, "group-region:normalization:normalize");

            assertThat(group.values().get("expanded")).isEqualTo(RailixValue.bool(false));
            assertThat(centerY(trigger)).isCloseTo(centerY(group), org.assertj.core.data.Offset.offset(0.000001));
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

        assertThat(nodes(scene).stream().filter(node -> !RailixValue.bool(true).equals(node.values().get("expanded"))))
                .isEmpty();
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
    void denseOverviewKeepsEveryDisplayedStationConnected() throws Exception {
        final RailixValue.ObjectValue viewport = scene("?x=-1000&y=-1000&width=10000000&height=10000000&scale=100000000");
        assertConnected(viewport);
    }

    private static void assertConnected(final RailixValue.ObjectValue viewport) {
        final var reached = new java.util.HashSet<String>();
        nodes(viewport).stream().filter(node -> string(node, "kind").equals("app"))
                .forEach(node -> reached.add(string(node, "id")));
        boolean changed;
        do {
            changed = false;
            for (final RailixValue value : array(viewport, "links")) {
                final var link = (RailixValue.ObjectValue) value;
                if (reached.contains(string(link, "from"))) changed |= reached.add(string(link, "to"));
            }
        } while (changed);
        assertThat(nodes(viewport).stream()
                .filter(node -> !RailixValue.bool(true).equals(node.values().get("expanded")))
                .map(node -> string(node, "id"))).allMatch(reached::contains);
    }

    @Test
    @Timeout(120)
    void wideBranchesCoarsenWithoutOmittingAnyStationOrConnection() throws Exception {
        final int count = 5000;
        final CreatorScene indexed = new CreatorScene(Files.readString(groupedBranches(count)),
                object("{\"format\":2,\"groups\":[],\"steps\":{}}"), StandardLibrary.catalog());
        for (final String query : List.of("", "x=-1000&y=-1000&width=10000000&height=10000000&scale=100000000")) {
            final RailixValue.ObjectValue visible = indexed.view(query);
            assertConnected(visible);
            assertThat(nodes(visible).stream().filter(node -> !RailixValue.bool(true).equals(node.values().get("expanded")))
                    .mapToLong(node -> number(node, "count")).sum()).isEqualTo(count + 3);
            assertThat(array(visible, "links").size() * 3).isLessThanOrEqualTo(CreatorScene.MAX_SEGMENTS);
            assertThat(nodes(visible)).hasSizeLessThanOrEqualTo(CreatorScene.MAX_NODES);
        }
    }

    @Test
    void coarsenedSceneRegionsExposeBoundedIndividualCounters() throws Exception {
        final CreatorScene indexed = new CreatorScene(Files.readString(groupedBranches(5000)),
                object("{\"format\":2,\"groups\":[],\"steps\":{}}"), StandardLibrary.catalog());
        final List<RailixValue.ObjectValue> regions = nodes(indexed.view("")).stream()
                .filter(node -> string(node, "kind").equals("region")).toList();
        assertThat(regions).isNotEmpty();
        for (final var region : regions) {
            final var page = indexed.memberPage(Map.of("members", string(region, "id"), "metrics", "duration_samples"));
            final var query = (RailixValue.ObjectValue) page.values().get("query");
            assertThat(((RailixValue.ObjectValue) query.values().get("steps")).values()).hasSizeLessThanOrEqualTo(512);
            assertThat(number(page, "count")).isEqualTo(number(region, "count"));
        }
    }

    @Test
    void repeatedViewportQueriesAreDeterministic() throws Exception {
        assertThat(scene("?focus=step-1100&scale=2")).isEqualTo(scene("?focus=step-1100&scale=2"));
    }

    @Test
    void stationScaleIsDerivedIndependentlyOfTheRequestedViewport() throws Exception {
        final RailixValue.ObjectValue overview = nodes(scene("?focus=command&scale=1")).stream()
                .filter(node -> string(node, "id").equals("command")).findFirst().orElseThrow();
        final RailixValue.ObjectValue close = nodes(scene("?focus=command&scale=100")).stream()
                .filter(node -> string(node, "id").equals("command")).findFirst().orElseThrow();
        assertThat(decimal(overview, "station_scale")).isPositive().isEqualTo(decimal(close, "station_scale"));
    }

    @Test
    @Timeout(180)
    void largeDerivedSceneKeepsViewportAndObservationQueriesCompact() {
        final int count = Integer.getInteger("railix.scene.scale", 20_000);
        final long started = System.nanoTime();
        final CreatorScene indexed = new CreatorScene(chain(count), object("{\"format\":2,\"groups\":[],\"steps\":{}}"),
                StandardLibrary.catalog());
        final long indexedAt = System.nanoTime();
        final var times = new java.util.ArrayList<Long>();
        int queryBytes = 0;
        for (int iteration = 0; iteration < 23; iteration++) {
            final long before = System.nanoTime();
            final RailixValue.ObjectValue visible = indexed.view("");
            assertThat(nodes(visible).stream().filter(node -> !RailixValue.bool(true).equals(node.values().get("expanded")))
                    .mapToLong(node -> number(node, "count")).sum()).isEqualTo(count + 2);
            assertThat(nodes(visible)).hasSizeLessThan(30);
            final var parameters = CreatorScene.observationParameters("revision=" + string(visible, "revision") + "&example=command:0");
            final var observation = indexed.observationView(parameters);
            queryBytes = 0;
            for (final String read : List.of("metrics", "examples")) {
                for (final RailixValue.ObjectValue query : observation.queries(read)) {
                    final int bytes = RailixJson.write(query).getBytes(StandardCharsets.UTF_8).length;
                    assertThat(bytes).isLessThan(1_048_576);
                    long members = 0;
                    for (final String kind : List.of("steps", "groups")) {
                        if (query.values().get(kind) instanceof RailixValue.ObjectValue groups) {
                            for (final RailixValue group : groups.values().values()) {
                                for (final RailixValue range : ((RailixValue.ArrayValue) group).values()) {
                                    final var interval = ((RailixValue.ArrayValue) range).values();
                                    members += ((RailixValue.NumberValue) interval.getLast()).value().longValueExact()
                                            - ((RailixValue.NumberValue) interval.getFirst()).value().longValueExact() + 1;
                                }
                            }
                        }
                    }
                    assertThat(members).isLessThanOrEqualTo(1_048_576);
                    queryBytes += bytes;
                }
            }
            if (iteration >= 3) times.add(System.nanoTime() - before);
        }
        times.sort(Long::compareTo);
        assertThat(queryBytes).isLessThan(4096);
        System.out.printf(java.util.Locale.ROOT,
                "RAILIX_SCENE_SCALE advisory=true steps=%d index_ms=%.3f viewport_query_p95_ms=%.3f query_bytes=%d%n",
                count, (indexedAt - started) / 1_000_000.0, times.get(18) / 1_000_000.0, queryBytes);
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 4, 8})
    void aBranchsLastStepAndTerminalShareTheirLane(final int steps) throws Exception {
        try (CreatorServer creator = start(groupedBranches(steps))) {
            final RailixValue.ObjectValue scene = object(request(creator.baseUri(), "GET",
                    "/api/scene?scale=0.1", "").body());
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
    void branchesUseLateralSocketsAccordingToTheirDestinationLane(final int steps) throws Exception {
        try (CreatorServer creator = start(groupedBranches(steps))) {
            final RailixValue.ObjectValue scene = object(request(creator.baseUri(), "GET",
                    "/api/scene?scale=0.1", "").body());
            final RailixValue.ObjectValue route = node(scene, "route");
            final List<RailixValue.ObjectValue> branches = array(scene, "links").stream()
                    .map(RailixValue.ObjectValue.class::cast)
                    .filter(link -> "route".equals(string(link, "from"))).toList();
            assertThat(branches).hasSize(steps);
            assertThat(branches).allSatisfy(link -> {
                final var target = node(scene, string(link, "to"));
                final double delta = centerY(target) - centerY(route);
                if (Math.abs(delta) < .000001) {
                    assertThat(point(link, 0, 0)).isCloseTo(decimal(route, "x") + decimal(route, "width"),
                            org.assertj.core.data.Offset.offset(.000001));
                } else {
                    assertThat(point(link, 0, 0)).isCloseTo(decimal(route, "x") + decimal(route, "width") / 2,
                            org.assertj.core.data.Offset.offset(.000001));
                    assertThat(point(link, 0, 1)).isCloseTo(centerY(route) + Math.copySign(decimal(route, "height") / 2, delta),
                            org.assertj.core.data.Offset.offset(.000001));
                }
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
    void overviewDoesNotCompressFlowStationsRelativeToAppAndTrigger() throws Exception {
        try (CreatorServer creator = start(groupedBranches(8))) {
            final var scene = object(request(creator.baseUri(), "GET", "/api/scene?scale=0.1", "").body());
            assertThat(nodes(scene)).hasSize(19);
            assertThat(nodes(scene)).allSatisfy(station ->
                    assertThat(decimal(station, "station_scale")).as(string(station, "id"))
                            .isCloseTo(1, org.assertj.core.data.Offset.offset(.000001)));
        }
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

    private static double centerY(final RailixValue.ObjectValue node) {
        return decimal(node, "y") + decimal(node, "height") / 2;
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
