package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.value.RailixValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.util.stream.IntStream;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

final class CreatorEditorE2eTest extends CreatorServerE2eSupport {
    @Test
    void themeSelectionDoesNotInvalidateSceneGeometry() throws Exception {
        final var path = directory.resolve("project.json");
        Files.writeString(path, threeStepProject());
        try (var creator = start(path)) {
            final String scene = request(creator.baseUri(), "GET", "/api/scene", "").body();
            final var changed = request(creator.baseUri(), "PATCH", "/api/creator",
                    "{\"revision\":0,\"changes\":{\"theme\":\"orbit/ice.css\"}}");
            assertThat(changed.statusCode()).as(changed.body()).isEqualTo(200);
            assertThat(request(creator.baseUri(), "GET", "/api/scene", "").body()).isEqualTo(scene);
        }
    }

    @Test
    void themeAndCreationDateSurviveMetadataAndNeighborhoodEdits() throws Exception {
        final var path = directory.resolve("project.json");
        Files.writeString(path, threeStepProject());
        try (var creator = start(path)) {
            final long pid = number(application(creator.baseUri()), "pid");
            final String before = Files.readString(path);
            final var response = request(creator.baseUri(), "POST", "/api/creator",
                    "{\"format\":2,\"created_at\":1780000000123,\"theme\":\"orbit/ice.css\",\"groups\":[],\"steps\":{}}");
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            assertThat(request(creator.baseUri(), "GET", "/api/editor?node=one", "").body())
                    .contains("\"created_at\":1780000000123", "\"theme\":\"orbit/ice.css\"");
            final var saved = request(creator.baseUri(), "PATCH", "/api/creator",
                    "{\"revision\":1,\"changes\":{\"theme\":null,\"steps\":{\"one\":{\"color\":\"#abcdef\"}}}}");
            assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);
            assertThat(Files.readString(directory.resolve("railix.creator.json")))
                    .contains("\"created_at\":1780000000123").doesNotContain("\"theme\"");
            assertThat(number(application(creator.baseUri()), "pid")).isEqualTo(pid);
            assertThat(Files.readString(path)).isEqualTo(before);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"rectangle", "ellipse", "triangle", "diamond", "hexagon", "event", "storage", "subsystem"})
    void stepAndGroupShapesPersistWithoutChangingTheApplication(final String shape) throws Exception {
        final var project = directory.resolve("project.json");
        Files.writeString(project, threeStepProject());
        try (var creator = start(project)) {
            final String before = Files.readString(project);
            final String style = "\"shape\":\"" + shape + "\",\"aspect\":1,\"roundness\":25";
            final var response = request(creator.baseUri(), "PATCH", "/api/creator",
                    "{\"revision\":0,\"changes\":{\"groups\":{\"g\":{\"id\":\"g\"," + style
                            + "}},\"steps\":{\"one\":{\"group\":\"g\"," + style + "}}}}");
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            assertThat(Files.readString(project)).isEqualTo(before);
            final var persisted = object(Files.readString(directory.resolve("railix.creator.json")));
            assertThat(child(child(persisted, "steps"), "one").values())
                    .containsAllEntriesOf(object("{" + style + "}").values());
            final var groups = (RailixValue.ArrayValue) persisted.values().get("groups");
            assertThat(((RailixValue.ObjectValue) groups.values().getFirst()).values())
                    .containsAllEntriesOf(object("{" + style + "}").values());
            assertThat(request(creator.baseUri(), "GET", "/api/scene?focus=one", "").body())
                    .contains("\"shape\":\"" + shape + "\"", "\"aspect\":1", "\"roundness\":25");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"shape\":\"star\"", "\"shape\":null", "\"aspect\":0", "\"aspect\":4.01",
            "\"aspect\":\"1\"", "\"aspect\":null", "\"roundness\":-1", "\"roundness\":50.01",
            "\"roundness\":true", "\"roundness\":null"})
    void malformedShapesAreRejectedAtTheMetadataBoundary(final String style) throws Exception {
        try (var creator = start(directory.resolve("project.json"))) {
            final var response = request(creator.baseUri(), "PATCH", "/api/creator",
                    "{\"revision\":0,\"changes\":{\"steps\":{\"app\":{" + style + "}}}}");
            assertThat(response.statusCode()).as(response.body()).isEqualTo(422);
            assertThat(response.body()).contains("CREATOR_PRESENTATION_");
        }
    }

    @Test
    void editorLoadsOnlyTheSelectedNeighborhoodAndRetainsCanonicalIndexes() throws Exception {
        final var path = directory.resolve("project.json");
        final StringBuilder nodes = new StringBuilder("{\"id\":\"app\",\"use\":\"railix.app\",\"inputs\":{}},"
                + "{\"id\":\"cli\",\"use\":\"railix.trigger.cli\",\"inputs\":{},\"examples\":[{\"name\":\"empty\",\"payload\":{\"arguments\":[]}}]}");
        final StringBuilder links = new StringBuilder("{\"from\":\"app.start\",\"to\":\"cli\"},{\"from\":\"cli.next\",\"to\":\"s0\"}");
        for (int i = 0; i < 80; i++) {
            nodes.append(",{\"id\":\"s").append(i).append("\",\"use\":\"railix.field-manipulation\",\"inputs\":{}}");
            links.append(",{\"from\":\"s").append(i).append(".next\",\"to\":\"")
                    .append(i == 79 ? "end" : "s" + (i + 1)).append("\"}");
        }
        Files.writeString(path, "{\"format\":1,\"id\":\"editor\",\"nodes\":[" + nodes + "],\"links\":[" + links + "]}");
        try (var creator = start(path)) {
            final var response = request(creator.baseUri(), "GET", "/api/editor?node=s40", "");
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            final var result = object(response.body());
            final var project = child(result, "project");
            assertThat(((RailixValue.ArrayValue) project.values().get("nodes")).values()).hasSizeLessThanOrEqualTo(7);
            assertThat(response.body()).doesNotContain("\"s70\"");
            final var info = child(child(result, "editor"), "nodes");
            assertThat(child(info, "s40").values().get("index")).isEqualTo(RailixValue.number(42));
            assertThat(child(info, "s40").values().get("trigger")).isEqualTo(RailixValue.string("cli"));
            assertThat(child(result, "workspace").values().get("step_count")).isEqualTo(RailixValue.number(82));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"?unknown=x", "?node=", "?node=app&node=app", "?offset=-1"})
    void editorRejectsInvalidQueries(final String query) throws Exception {
        try (var creator = start(directory.resolve("project.json"))) {
            assertThat(request(creator.baseUri(), "GET", "/api/editor" + query, "").statusCode()).isEqualTo(400);
        }
    }

    @Test
    void editorDoesNotSubstituteAnUnknownStep() throws Exception {
        try (var creator = start(directory.resolve("project.json"))) {
            assertThat(request(creator.baseUri(), "GET", "/api/editor?node=missing", "").statusCode()).isEqualTo(404);
        }
    }

    @Test
    void groupListingIsPagedAndSearchableWithoutLoadingMemberSteps() throws Exception {
        final var project = directory.resolve("project.json");
        Files.writeString(project, threeStepProject());
        final String groups = IntStream.range(0, 130).mapToObj(index ->
                "{\"id\":\"g" + index + "\",\"name\":\"Group " + index + "\"}").collect(Collectors.joining(","));
        Files.writeString(directory.resolve("railix.creator.json"),
                "{\"format\":2,\"groups\":[" + groups + "],\"steps\":{\"three\":{\"group\":\"g129\"}}}");
        try (var creator = start(project)) {
            final var first = object(request(creator.baseUri(), "GET", "/api/editor", "").body());
            assertThat(((RailixValue.ArrayValue) child(first, "creator").values().get("groups")).values()).hasSize(64);
            final var last = object(request(creator.baseUri(), "GET", "/api/editor?offset=128", "").body());
            assertThat(((RailixValue.ArrayValue) child(last, "creator").values().get("groups")).values()).hasSize(2);
            final var search = object(request(creator.baseUri(), "GET", "/api/editor?q=129", "").body());
            assertThat(((RailixValue.ArrayValue) child(search, "creator").values().get("groups")).values()).hasSize(1);
            assertThat(child(child(child(search, "editor"), "groups"), "g129").values().get("steps"))
                    .isEqualTo(RailixValue.number(1));
        }
    }

    @Test
    void unnamedGroupsAreSearchableByTheirDisplayedDefaultName() throws Exception {
        Files.writeString(directory.resolve("railix.creator.json"),
                "{\"format\":2,\"groups\":[{\"id\":\"unnamed\"},{\"id\":\"named\",\"name\":\"Routes\"}],\"steps\":{}}");
        try (var creator = start(directory.resolve("project.json"))) {
            final var response = request(creator.baseUri(), "GET", "/api/editor?q=GROUP", "");
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            final var result = object(response.body());
            assertThat(child(result, "editor").values().get("group_matches")).isEqualTo(RailixValue.number(1));
            assertThat(((RailixValue.ArrayValue) child(result, "creator").values().get("groups")).values())
                    .containsExactly(object("{\"id\":\"unnamed\"}"));
        }
    }

    @Test
    void repeatedFlowDeletionIdsHaveOneEffectWithinTheIngressBudget() throws Exception {
        final var project = directory.resolve("project.json");
        Files.writeString(project, threeStepProject());
        try (var creator = start(project)) {
            final String removals = "\"command\",".repeat(99_999) + "\"command\"";
            final long before = System.nanoTime();
            final var response = request(creator.baseUri(), "PATCH", "/api/project",
                    "{\"revision\":0,\"changes\":{\"remove_flows\":[" + removals + "]}}");
            System.out.printf(java.util.Locale.ROOT,
                    "RAILIX_EDITOR_REPEATED_DELETION advisory=true ids=100000 elapsed_ms=%.3f%n",
                    (System.nanoTime() - before) / 1_000_000.0);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            final var persisted = object(Files.readString(project));
            assertThat(((RailixValue.ArrayValue) persisted.values().get("nodes")).values())
                    .containsExactly(object("{\"id\":\"app\",\"use\":\"railix.app\",\"inputs\":{}}"));
            assertThat(((RailixValue.ArrayValue) persisted.values().get("links")).values()).isEmpty();
        }
    }

    @Test
    void deletingAGroupUnassignsUnloadedMembersWithoutDeletingTheirPresentation() throws Exception {
        final var project = directory.resolve("project.json");
        Files.writeString(project, threeStepProject());
        Files.writeString(directory.resolve("railix.creator.json"), """
                {"format":2,"groups":[{"id":"g"}],"steps":{"one":{"group":"g"},"three":{"group":"g","name":"Keep me"}}}
                """);
        try (var creator = start(project)) {
            final String before = Files.readString(project);
            final var response = request(creator.baseUri(), "PATCH", "/api/creator", """
                    {"revision":0,"changes":{"groups":{"g":null}}}
                    """);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            assertThat(Files.readString(project)).isEqualTo(before);
            final var steps = child(object(Files.readString(directory.resolve("railix.creator.json"))), "steps");
            assertThat(steps.values()).doesNotContainKey("one");
            assertThat(child(steps, "three").values()).containsOnlyKeys("name");
        }
    }

    @Test
    void explicitFlowDeletionRemovesTheUnloadedDescendantsAndPrunesOnlyTheirAppearance() throws Exception {
        final var project = directory.resolve("project.json");
        Files.writeString(project, threeStepProject());
        Files.writeString(directory.resolve("railix.creator.json"), """
                {"format":2,"groups":[],"steps":{"app":{"name":"Keep"},"three":{"name":"Remove"}}}
                """);
        try (var creator = start(project)) {
            final var response = request(creator.baseUri(), "PATCH", "/api/project", """
                    {"revision":0,"changes":{"remove_flows":["command"]}}
                    """);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            assertThat(((RailixValue.ArrayValue) object(Files.readString(project)).values().get("nodes")).values()).hasSize(1);
            final var metadata = request(creator.baseUri(), "PATCH", "/api/creator", """
                    {"revision":0,"changes":{"prune_removed_steps":true}}
                    """);
            assertThat(metadata.statusCode()).as(metadata.body()).isEqualTo(200);
            assertThat(child(object(Files.readString(directory.resolve("railix.creator.json"))), "steps").values())
                    .containsOnlyKeys("app");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void flowDeletionPreservesANewTriggerAddedInTheSamePatch(final boolean retainRemovedTarget) throws Exception {
        final var project = directory.resolve("project.json");
        Files.writeString(project, threeStepProject());
        try (var creator = start(project)) {
            final var response = request(creator.baseUri(), "PATCH", "/api/project", """
                    {"revision":0,"changes":{
                      "remove_flows":["command"],
                      "nodes":{"replacement":{"id":"replacement","use":"railix.trigger.cli","inputs":{},
                        "examples":[{"name":"replacement","payload":[]}]}},
                      "links":{
                        "app.start":[%s{"from":"app.start","to":"replacement"}],
                        "replacement.next":[{"from":"replacement.next","to":"end"}]
                      }
                    }}
                    """.formatted(retainRemovedTarget ? "{\"from\":\"app.start\",\"to\":\"command\"}," : ""));
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            final var persisted = object(Files.readString(project));
            assertThat(((RailixValue.ArrayValue) persisted.values().get("nodes")).values().stream()
                    .map(value -> string((RailixValue.ObjectValue) value, "id")))
                    .containsExactly("app", "replacement");
            assertThat(((RailixValue.ArrayValue) persisted.values().get("links")).values()).containsExactly(
                    object("{\"from\":\"app.start\",\"to\":\"replacement\"}"),
                    object("{\"from\":\"replacement.next\",\"to\":\"end\"}"));
            final var loaded = request(creator.baseUri(), "GET", "/api/editor?node=replacement", "");
            assertThat(loaded.statusCode()).as(loaded.body()).isEqualTo(200);
            assertThat(child(object(loaded.body()), "workspace").values().get("flow_count"))
                    .isEqualTo(RailixValue.number(1));
            assertThat(request(creator.baseUri(), "GET", "/api/editor?node=three", "").statusCode()).isEqualTo(404);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"app.start\":true}",
            "{\"app.start\":[7]}",
            "{\"app.start\":[{\"from\":\"other.next\",\"to\":\"command\"}]}",
            "{\"command.next\":true}",
            "{\"command.next\":[null]}",
            "{\"command.next\":[{\"from\":\"other.next\",\"to\":\"one\"}]}"
    })
    void flowDeletionPreservesInvalidExplicitLinkDiagnostics(final String links) throws Exception {
        final var project = directory.resolve("project.json");
        Files.writeString(project, threeStepProject());
        try (var creator = start(project)) {
            final String persisted = Files.readString(project);
            final var response = request(creator.baseUri(), "PATCH", "/api/project",
                    "{\"revision\":0,\"changes\":{\"remove_flows\":[\"command\"],\"links\":" + links + "}}");
            assertThat(response.statusCode()).as(response.body()).isEqualTo(422);
            assertThat(response.body()).contains("CREATOR_EDIT_INVALID",
                    links.contains("true") ? "Connection edits must contain a list for each source port."
                            : "Edited from must match its key:");
            assertThat(Files.readString(project)).isEqualTo(persisted);
        }
    }

    @Test
    void flowDeletionRetainsCompilerDiagnosticsForMalformedSourcePorts() throws Exception {
        final var project = directory.resolve("project.json");
        Files.writeString(project, threeStepProject());
        try (var creator = start(project)) {
            final String persisted = Files.readString(project);
            final var response = request(creator.baseUri(), "PATCH", "/api/project", """
                    {"revision":0,"changes":{"remove_flows":["command"],
                      "links":{"invalid":[{"from":"invalid","to":"end"}]}}}
                    """);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(422);
            assertThat(response.body()).contains("\"node\":\"app\"");
            assertThat(Files.readString(project)).isEqualTo(persisted);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "[\"app\"]", "[\"missing\"]", "[null]"})
    void flowDeletionRejectsValuesThatDoNotIdentifyExistingTriggers(final String value) throws Exception {
        try (var creator = start(directory.resolve("project.json"))) {
            assertThat(request(creator.baseUri(), "PATCH", "/api/project",
                    "{\"revision\":0,\"changes\":{\"remove_flows\":" + value + "}}").statusCode()).isEqualTo(422);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"nodes", "links"})
    void flowDeletionDoesNotHideMalformedEntryChanges(final String field) throws Exception {
        try (var creator = start(directory.resolve("project.json"))) {
            assertThat(request(creator.baseUri(), "PATCH", "/api/project",
                    "{\"revision\":0,\"changes\":{\"remove_flows\":[],\"" + field + "\":[]}}").statusCode()).isEqualTo(422);
        }
    }

    @Test
    void malformedLinkDiagnosticsDoNotFailWhileResolvingTheirOwner() throws Exception {
        try (var creator = start(directory.resolve("project.json"))) {
            final var response = request(creator.baseUri(), "PATCH", "/api/project", """
                    {"revision":0,"changes":{"links":{"invalid":[{"from":"invalid","to":"end"}]}}}
                    """);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(422);
            assertThat(response.body()).contains("\"node\":\"app\"");
        }
    }

    private static RailixValue.ObjectValue child(final RailixValue.ObjectValue value, final String key) {
        return (RailixValue.ObjectValue) value.values().get(key);
    }
}
