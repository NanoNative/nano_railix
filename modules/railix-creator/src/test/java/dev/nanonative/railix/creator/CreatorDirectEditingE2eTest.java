package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

final class CreatorDirectEditingE2eTest extends CreatorServerE2eSupport {
    @Test
    void groupSelectionPreviewsWithoutWritingAndUndoRestoresTheWholeAction() throws Exception {
        final var file = directory.resolve("railix.project.json");
        Files.writeString(file, threeStepProject());
        try (var server = start(file)) {
            final String source = Files.readString(file);
            final String metadata = Files.readString(directory.resolve("railix.creator.json"));
            final String command = "{\"kind\":\"group\",\"id\":\"g\",\"name\":\"Normalize\",\"start\":\"two\",\"end\":\"one\"}";
            final var current = object(request(server.baseUri(), "GET", "/api/editor", "").body());
            final var preview = request(server.baseUri(), "POST", "/api/editor", RailixJson.write(RailixValue.object(Map.of(
                    "revision", current.values().get("revision"), "creator_revision", current.values().get("creator_revision"),
                    "command", object(command), "preview", RailixValue.bool(true)))));
            assertThat(preview.statusCode()).as(preview.body()).isEqualTo(200);
            assertThat(preview.body()).contains("one", "two").doesNotContain("three");
            assertThat(Files.readString(directory.resolve("railix.creator.json"))).isEqualTo(metadata);
            assertThat(action(server.baseUri(), command).values()).containsKey("history");
            assertThat(Files.readString(file)).isEqualTo(source);
            assertThat(Files.readString(directory.resolve("railix.creator.json"))).contains("\"group\":\"g\"");
            action(server.baseUri(), "{\"kind\":\"undo\"}");
            assertThat(Files.readString(directory.resolve("railix.creator.json"))).isEqualTo(metadata);
            action(server.baseUri(), "{\"kind\":\"redo\"}");
            assertThat(Files.readString(directory.resolve("railix.creator.json"))).contains("Normalize");
        }
    }

    @Test
    void reusePropagatesProcessingAndDetachPreservesIndependentContinuations() throws Exception {
        final var file = directory.resolve("railix.project.json");
        Files.writeString(file, threeStepProject());
        try (var server = start(file)) {
            final String original = Files.readString(file);
            final var reused = action(server.baseUri(), "{\"kind\":\"reuse\",\"node\":\"one\",\"from\":\"three.next\",\"id\":\"reuse-one\"}");
            final String copy = ((RailixValue.StringValue) reused.values().get("selection")).value();
            final String copied = Files.readString(file);
            action(server.baseUri(), """
                    {"kind":"patch","project":{"nodes":{"one":{"id":"one","use":"railix.field-manipulation","inputs":{
                      "field":["context","result"],"value":[{"option":"literal","inputs":{"literal":"shared"}}]}}}}}
                    """);
            final var nodes = (RailixValue.ArrayValue) object(Files.readString(file)).values().get("nodes");
            final var shared = nodes.values().stream().map(value -> (RailixValue.ObjectValue) value)
                    .filter(value -> List.of("one", copy).contains(((RailixValue.StringValue) value.values().get("id")).value())).toList();
            assertThat(shared).hasSize(2);
            assertThat(shared.get(0).values().get("inputs")).isEqualTo(shared.get(1).values().get("inputs"));
            final String beforeDetach = Files.readString(file);
            action(server.baseUri(), "{\"kind\":\"detach\",\"node\":\"" + copy + "\"}");
            assertThat(Files.readString(file)).isEqualTo(beforeDetach);
            assertThat(Files.readString(directory.resolve("railix.creator.json"))).doesNotContain("\"reuse\"");
            action(server.baseUri(), "{\"kind\":\"undo\"}");
            assertThat(Files.readString(directory.resolve("railix.creator.json"))).contains("\"reuse\"");
            action(server.baseUri(), "{\"kind\":\"undo\"}");
            assertThat(Files.readString(file)).isEqualTo(copied);
            action(server.baseUri(), "{\"kind\":\"undo\"}");
            assertThat(Files.readString(file)).isEqualTo(original);
        }
    }

    @Test
    void replacingALinkedStepWithAChoiceKeepsEachPrimaryContinuation() throws Exception {
        final var file = directory.resolve("railix.project.json");
        Files.writeString(file, threeStepProject());
        try (var server = start(file)) {
            final var reused = action(server.baseUri(), "{\"kind\":\"reuse\",\"node\":\"one\",\"from\":\"three.next\",\"id\":\"reuse-one\"}");
            final String copy = ((RailixValue.StringValue) reused.values().get("selection")).value();
            final String third = ((RailixValue.StringValue) action(server.baseUri(), "{\"kind\":\"reuse\",\"node\":\"one\",\"from\":\"" + copy
                    + ".next\",\"id\":\"reuse-two\"}").values().get("selection")).value();
            action(server.baseUri(), """
                    {"kind":"patch","project":{"nodes":{"one":{"id":"one","use":"railix.choice","inputs":{"conditions":[]}}},
                     "links":{"one.next":null,"one.match":[{"from":"one.match","to":"two"}],"one.otherwise":[{"from":"one.otherwise","to":"end"}]}}}
                    """);
            final var links = (RailixValue.ArrayValue) object(Files.readString(file)).values().get("links");
            assertThat(links.values()).contains(object("{\"from\":\"one.match\",\"to\":\"two\"}"),
                    object("{\"from\":\"" + copy + ".match\",\"to\":\"" + third + "\"}"),
                    object("{\"from\":\"" + third + ".match\",\"to\":\"end\"}"),
                    object("{\"from\":\"" + copy + ".otherwise\",\"to\":\"end\"}"));
        }
    }

    @Test
    void overlappingLinkedSelectionsAreRejectedBeforeAnyWrite() throws Exception {
        final var file = directory.resolve("railix.project.json");
        Files.writeString(file, threeStepProject());
        try (var server = start(file)) {
            action(server.baseUri(), "{\"kind\":\"group\",\"id\":\"g\",\"nodes\":[\"one\",\"two\"]}");
            final var placed = action(server.baseUri(), "{\"kind\":\"reuse\",\"node\":\"one\",\"from\":\"three.next\",\"id\":\"linked-one\"}");
            final String copy = ((RailixValue.StringValue) placed.values().get("selection")).value();
            final String source = Files.readString(file), metadata = Files.readString(directory.resolve("railix.creator.json"));
            final var current = object(request(server.baseUri(), "GET", "/api/editor", "").body());
            final var response = request(server.baseUri(), "POST", "/api/editor", RailixJson.write(RailixValue.object(Map.of(
                    "revision", current.values().get("revision"), "creator_revision", current.values().get("creator_revision"),
                    "command", object("{\"kind\":\"reuse\",\"group\":\"g\",\"from\":\"" + copy + ".next\",\"id\":\"overlap\"}")))));
            assertThat(response.statusCode()).as(response.body()).isEqualTo(422);
            assertThat(response.body()).contains("another linked selection", "Copy");
            assertThat(Files.readString(file)).isEqualTo(source);
            assertThat(Files.readString(directory.resolve("railix.creator.json"))).isEqualTo(metadata);
        }
    }

    @Test
    void copiedGroupKeepsExternalWorkOutsideItsMembers() throws Exception {
        final var file = directory.resolve("railix.project.json");
        Files.writeString(file, threeStepProject());
        try (var server = start(file)) {
            action(server.baseUri(), "{\"kind\":\"group\",\"id\":\"g\",\"nodes\":[\"one\",\"two\"]}");
            action(server.baseUri(), "{\"kind\":\"copy\",\"group\":\"g\",\"from\":\"three.next\",\"id\":\"copy-group\"}");
            final var project = object(Files.readString(file));
            assertThat(((RailixValue.ArrayValue) project.values().get("nodes")).values()).hasSize(7);
            assertThat(Files.readString(directory.resolve("railix.creator.json"))).doesNotContain("\"reuse\"");
            final var links = (RailixValue.ArrayValue) project.values().get("links");
            assertThat(links.values()).contains(object("{\"from\":\"one.next\",\"to\":\"two\"}"),
                    object("{\"from\":\"two.next\",\"to\":\"three\"}"));
        }
    }

    @Test
    void insertionInsideLinkedGroupUpdatesBothUsesAndUndoRestoresTheirBoundaries() throws Exception {
        final var file = directory.resolve("railix.project.json");
        Files.writeString(file, threeStepProject());
        try (var server = start(file)) {
            action(server.baseUri(), "{\"kind\":\"group\",\"id\":\"g\",\"nodes\":[\"one\",\"two\"]}");
            action(server.baseUri(), "{\"kind\":\"reuse\",\"group\":\"g\",\"from\":\"three.next\",\"id\":\"linked-group\"}");
            final String before = Files.readString(file);
            final String metadata = Files.readString(directory.resolve("railix.creator.json"));
            action(server.baseUri(), """
                    {"kind":"patch","project":{"nodes":{"inserted":{"id":"inserted","use":"railix.field-manipulation","inputs":{}}},
                      "links":{"one.next":[{"from":"one.next","to":"inserted"}],"inserted.next":[{"from":"inserted.next","to":"two"}]}},
                      "creator":{"steps":{"inserted":{"group":"g"}}}}
                    """);
            final var updated = object(Files.readString(file));
            assertThat(((RailixValue.ArrayValue) updated.values().get("nodes")).values()).hasSize(9);
            final var uses = (RailixValue.ArrayValue) ((RailixValue.ObjectValue) ((RailixValue.ArrayValue)
                    object(Files.readString(directory.resolve("railix.creator.json"))).values().get("reuse")).values().getFirst()).values().get("uses");
            for (final var use : uses.values()) assertThat(((RailixValue.ObjectValue) ((RailixValue.ObjectValue) use).values().get("members")).values()).hasSize(3);
            action(server.baseUri(), "{\"kind\":\"undo\"}");
            assertThat(Files.readString(file)).isEqualTo(before);
            assertThat(Files.readString(directory.resolve("railix.creator.json"))).isEqualTo(metadata);
        }
    }

    @Test
    void removingAVisualGroupKeepsItsLinkedProcessingEditableAndUndoable() throws Exception {
        final var file=directory.resolve("railix.project.json");
        Files.writeString(file,threeStepProject());
        final var metadata=directory.resolve("railix.creator.json");
        try(var server=start(file)) {
            action(server.baseUri(),"{\"kind\":\"group\",\"id\":\"g\",\"nodes\":[\"one\",\"two\"]}");
            action(server.baseUri(),"{\"kind\":\"reuse\",\"group\":\"g\",\"from\":\"three.next\",\"id\":\"linked-group\"}");
            final String source=Files.readString(file), grouped=Files.readString(metadata);
            final var groups=(RailixValue.ArrayValue)object(grouped).values().get("groups");
            final String copyGroup=groups.values().stream().map(value->(RailixValue.ObjectValue)value)
                    .map(value->((RailixValue.StringValue)value.values().get("id")).value()).filter(id->!id.equals("g")).findFirst().orElseThrow();
            action(server.baseUri(),"{\"kind\":\"patch\",\"creator\":{\"groups\":{\""+copyGroup+"\":null}}}");
            assertThat(Files.readString(file)).isEqualTo(source);
            final String ungrouped=Files.readString(metadata);
            action(server.baseUri(),"""
                    {"kind":"patch","project":{"nodes":{"inserted":{"id":"inserted","use":"railix.field-manipulation","inputs":{}}},
                      "links":{"one.next":[{"from":"one.next","to":"inserted"}],"inserted.next":[{"from":"inserted.next","to":"two"}]}},
                      "creator":{"steps":{"inserted":{"group":"g"}}}}
                    """);
            final var uses=(RailixValue.ArrayValue)((RailixValue.ObjectValue)((RailixValue.ArrayValue)object(Files.readString(metadata))
                    .values().get("reuse")).values().getFirst()).values().get("uses");
            assertThat(uses.values()).hasSize(2);
            for(final var value:uses.values()) {
                final var use=(RailixValue.ObjectValue)value;
                assertThat(((RailixValue.ObjectValue)use.values().get("members")).values()).hasSize(3);
                assertThat(use.values().get("group")).isNotEqualTo(RailixValue.string(copyGroup));
            }
            action(server.baseUri(),"{\"kind\":\"undo\"}");
            assertThat(Files.readString(metadata)).isEqualTo(ungrouped);
            assertThat(Files.readString(file)).isEqualTo(source);
            action(server.baseUri(),"{\"kind\":\"undo\"}");
            assertThat(Files.readString(metadata)).isEqualTo(grouped);
        }
    }

    @Test
    void removingAGroupAndInsertingIntoItsLinkedUseIsOneUndoableAction() throws Exception {
        final var file = directory.resolve("railix.project.json");
        Files.writeString(file, threeStepProject());
        final var metadata = directory.resolve("railix.creator.json");
        try (var server = start(file)) {
            action(server.baseUri(), "{\"kind\":\"group\",\"id\":\"g\",\"nodes\":[\"one\",\"two\"]}");
            action(server.baseUri(), "{\"kind\":\"reuse\",\"group\":\"g\",\"from\":\"three.next\",\"id\":\"linked-group\"}");
            final String source = Files.readString(file), grouped = Files.readString(metadata);
            final var groups = (RailixValue.ArrayValue) object(grouped).values().get("groups");
            final String copyGroup = groups.values().stream().map(value -> (RailixValue.ObjectValue) value)
                    .map(value -> ((RailixValue.StringValue) value.values().get("id")).value()).filter(id -> !id.equals("g")).findFirst().orElseThrow();
            action(server.baseUri(), """
                    {"kind":"patch","project":{"nodes":{"inserted":{"id":"inserted","use":"railix.field-manipulation","inputs":{}}},
                      "links":{"one.next":[{"from":"one.next","to":"inserted"}],"inserted.next":[{"from":"inserted.next","to":"two"}]}},
                      "creator":{"groups":{"%s":null},"steps":{"inserted":{"group":"g"}}}}
                    """.formatted(copyGroup));
            final var uses = (RailixValue.ArrayValue) ((RailixValue.ObjectValue) ((RailixValue.ArrayValue)
                    object(Files.readString(metadata)).values().get("reuse")).values().getFirst()).values().get("uses");
            for (final var value : uses.values()) {
                final var use = (RailixValue.ObjectValue) value;
                assertThat(((RailixValue.ObjectValue) use.values().get("members")).values()).hasSize(3);
                assertThat(use.values().get("group")).isNotEqualTo(RailixValue.string(copyGroup));
            }
            action(server.baseUri(), "{\"kind\":\"undo\"}");
            assertThat(Files.readString(file)).isEqualTo(source);
            assertThat(Files.readString(metadata)).isEqualTo(grouped);
        }
    }

    @Test
    void deletingAMemberAfterRemovingItsGroupKeepsBothUsesLinked() throws Exception {
        final var file = directory.resolve("railix.project.json");
        Files.writeString(file, threeStepProject());
        final var metadata = directory.resolve("railix.creator.json");
        try (var server = start(file)) {
            action(server.baseUri(), "{\"kind\":\"group\",\"id\":\"g\",\"nodes\":[\"one\",\"two\"]}");
            action(server.baseUri(), "{\"kind\":\"reuse\",\"group\":\"g\",\"from\":\"three.next\",\"id\":\"linked-group\"}");
            final var family = (RailixValue.ObjectValue) ((RailixValue.ArrayValue) object(Files.readString(metadata)).values().get("reuse")).values().getFirst();
            final var copy = (RailixValue.ObjectValue) ((RailixValue.ArrayValue) family.values().get("uses")).values().get(1);
            final String copyGroup = ((RailixValue.StringValue) copy.values().get("group")).value();
            final String copyTwo = ((RailixValue.StringValue) ((RailixValue.ObjectValue) copy.values().get("members")).values().get("two")).value();
            action(server.baseUri(), "{\"kind\":\"patch\",\"creator\":{\"groups\":{\"" + copyGroup + "\":null}}}");
            final String source = Files.readString(file), ungrouped = Files.readString(metadata);
            action(server.baseUri(), "{\"kind\":\"delete\",\"node\":\"" + copyTwo + "\"}");
            final var nodes = (RailixValue.ArrayValue) object(Files.readString(file)).values().get("nodes");
            assertThat(nodes.values()).noneMatch(value -> ((RailixValue.ObjectValue) value).values().get("id").equals(RailixValue.string("two")));
            final var uses = (RailixValue.ArrayValue) ((RailixValue.ObjectValue) ((RailixValue.ArrayValue)
                    object(Files.readString(metadata)).values().get("reuse")).values().getFirst()).values().get("uses");
            assertThat(uses.values()).hasSize(2);
            for (final var use : uses.values()) assertThat(((RailixValue.ObjectValue) ((RailixValue.ObjectValue) use).values().get("members")).values()).hasSize(1);
            action(server.baseUri(), "{\"kind\":\"undo\"}");
            assertThat(Files.readString(file)).isEqualTo(source);
            assertThat(Files.readString(metadata)).isEqualTo(ungrouped);
        }
    }

    @Test
    void anUnrelatedInsertionDoesNotBecomePartOfAnUngroupedSharedDeletion() throws Exception {
        final var file = directory.resolve("railix.project.json");
        Files.writeString(file, threeStepProject());
        final var metadata = directory.resolve("railix.creator.json");
        try (var server = start(file)) {
            action(server.baseUri(), "{\"kind\":\"group\",\"id\":\"g\",\"nodes\":[\"one\",\"two\"]}");
            action(server.baseUri(), "{\"kind\":\"reuse\",\"group\":\"g\",\"from\":\"three.next\",\"id\":\"linked-group\"}");
            final var family = (RailixValue.ObjectValue) ((RailixValue.ArrayValue) object(Files.readString(metadata)).values().get("reuse")).values().getFirst();
            final var copy = (RailixValue.ObjectValue) ((RailixValue.ArrayValue) family.values().get("uses")).values().get(1);
            final var members = (RailixValue.ObjectValue) copy.values().get("members");
            final String copyGroup = ((RailixValue.StringValue) copy.values().get("group")).value();
            final String copyOne = ((RailixValue.StringValue) members.values().get("one")).value();
            final String copyTwo = ((RailixValue.StringValue) members.values().get("two")).value();
            action(server.baseUri(), "{\"kind\":\"patch\",\"creator\":{\"groups\":{\"" + copyGroup + "\":null}}}");
            final String source = Files.readString(file), ungrouped = Files.readString(metadata);
            action(server.baseUri(), """
                    {"kind":"patch","project":{"nodes":{"%s":null,
                      "side":{"id":"side","use":"railix.field-manipulation","inputs":{}}},
                      "links":{"three.next":[{"from":"three.next","to":"side"}],
                      "side.next":[{"from":"side.next","to":"%s"}],
                      "%s.next":[{"from":"%s.next","to":"end"}],"%s.next":null}}}
                    """.formatted(copyTwo, copyOne, copyOne, copyOne, copyTwo));
            final var project = object(Files.readString(file));
            assertThat(((RailixValue.ArrayValue) project.values().get("nodes")).values()).hasSize(6);
            assertThat(((RailixValue.ArrayValue) project.values().get("links")).values()).contains(
                    object("{\"from\":\"one.next\",\"to\":\"three\"}"), object("{\"from\":\"three.next\",\"to\":\"side\"}"));
            action(server.baseUri(), "{\"kind\":\"undo\"}");
            assertThat(Files.readString(file)).isEqualTo(source);
            assertThat(Files.readString(metadata)).isEqualTo(ungrouped);
        }
    }

    @Test
    void removingOneOfTwoInternalChoiceConnectionsPropagatesToEveryLinkedUse() throws Exception {
        final var file=directory.resolve("railix.project.json");
        Files.writeString(file,threeStepProject());
        try(var server=start(file)) {
            action(server.baseUri(),"""
                    {"kind":"patch","project":{"nodes":{"one":{"id":"one","use":"railix.choice","inputs":{"conditions":[]}}},
                      "links":{"one.next":null,"one.match":[{"from":"one.match","to":"two"}],
                      "one.otherwise":[{"from":"one.otherwise","to":"two"}]}}}
                    """);
            action(server.baseUri(),"{\"kind\":\"group\",\"id\":\"g\",\"nodes\":[\"one\",\"two\"]}");
            final var placed=action(server.baseUri(),"{\"kind\":\"reuse\",\"group\":\"g\",\"from\":\"three.next\",\"id\":\"linked-group\"}");
            final String copy=((RailixValue.StringValue)placed.values().get("selection")).value();
            final String before=Files.readString(file);
            action(server.baseUri(),"{\"kind\":\"connect\",\"from\":\"one.match\",\"to\":\"end\"}");
            final var links=(RailixValue.ArrayValue)object(Files.readString(file)).values().get("links");
            assertThat(links.values()).contains(object("{\"from\":\"one.match\",\"to\":\"end\"}"),
                    object("{\"from\":\""+copy+".match\",\"to\":\"end\"}"));
            action(server.baseUri(),"{\"kind\":\"undo\"}");
            assertThat(Files.readString(file)).isEqualTo(before);
        }
    }

    @Test
    void deletingAWholeUseAndEditingAnotherIsOneCompleteAction() throws Exception {
        final var file=directory.resolve("railix.project.json");
        Files.writeString(file,threeStepProject());
        final var metadata=directory.resolve("railix.creator.json");
        try(var server=start(file)) {
            action(server.baseUri(),"{\"kind\":\"group\",\"id\":\"g\",\"nodes\":[\"one\",\"two\"]}");
            action(server.baseUri(),"{\"kind\":\"reuse\",\"group\":\"g\",\"from\":\"three.next\",\"id\":\"linked-group\"}");
            final String source=Files.readString(file), oldMetadata=Files.readString(metadata);
            final var uses=(RailixValue.ArrayValue)((RailixValue.ObjectValue)((RailixValue.ArrayValue)object(oldMetadata)
                    .values().get("reuse")).values().getFirst()).values().get("uses");
            final var copied=(RailixValue.ObjectValue)((RailixValue.ObjectValue)uses.values().get(1)).values().get("members");
            final String one=((RailixValue.StringValue)copied.values().get("one")).value();
            final String two=((RailixValue.StringValue)copied.values().get("two")).value();
            action(server.baseUri(),"""
                    {"kind":"patch","project":{"nodes":{"%s":null,"%s":null,
                      "inserted":{"id":"inserted","use":"railix.field-manipulation","inputs":{}}},
                      "links":{"%s.next":null,"%s.next":null,"three.next":[{"from":"three.next","to":"end"}],
                      "one.next":[{"from":"one.next","to":"inserted"}],"inserted.next":[{"from":"inserted.next","to":"two"}]}},
                      "creator":{"steps":{"inserted":{"group":"g"}}}}
                    """.formatted(one,two,one,two));
            assertThat(((RailixValue.ArrayValue)object(Files.readString(file)).values().get("nodes")).values()).hasSize(6);
            assertThat(object(Files.readString(metadata)).values()).doesNotContainKey("reuse");
            action(server.baseUri(),"{\"kind\":\"undo\"}");
            assertThat(Files.readString(file)).isEqualTo(source);
            assertThat(Files.readString(metadata)).isEqualTo(oldMetadata);
        }
    }

    @Test
    void deletingALinkedEntranceBypassesItInEachUseWithoutMixingTheExits() throws Exception {
        final var file = directory.resolve("railix.project.json");
        Files.writeString(file, threeStepProject());
        try (var server = start(file)) {
            action(server.baseUri(), "{\"kind\":\"group\",\"id\":\"g\",\"nodes\":[\"one\",\"two\"]}");
            action(server.baseUri(), "{\"kind\":\"reuse\",\"group\":\"g\",\"from\":\"three.next\",\"id\":\"linked-group\"}");
            final String before = Files.readString(file);
            action(server.baseUri(), "{\"kind\":\"delete\",\"node\":\"one\"}");
            final var project = object(Files.readString(file));
            assertThat(((RailixValue.ArrayValue) project.values().get("nodes")).values()).hasSize(5);
            assertThat(((RailixValue.ArrayValue) project.values().get("links")).values())
                    .contains(object("{\"from\":\"command.next\",\"to\":\"two\"}"), object("{\"from\":\"two.next\",\"to\":\"three\"}"));
            action(server.baseUri(), "{\"kind\":\"undo\"}");
            assertThat(Files.readString(file)).isEqualTo(before);
        }
    }

    @Test
    void externalFileEditsBlockActionsRatherThanBeingOverwritten() throws Exception {
        final var file = directory.resolve("railix.project.json");
        Files.writeString(file, threeStepProject());
        try (var server = start(file)) {
            final String external = Files.readString(file).replace("three-steps", "edited-outside");
            Files.writeString(file, external);
            final var response = request(server.baseUri(), "POST", "/api/editor", """
                    {"revision":0,"creator_revision":0,"command":{"kind":"group","id":"g","node":"one"}}
                    """);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(409);
            assertThat(Files.readString(file)).isEqualTo(external);
        }
    }

    @Test
    void rejectedEditsIdentifyTheRealStepOutsideTheBrowsersArrayOrder() throws Exception {
        final var file = directory.resolve("railix.project.json");
        Files.writeString(file, threeStepProject());
        try (var server = start(file)) {
            final var response = request(server.baseUri(), "POST", "/api/editor", """
                    {"revision":0,"creator_revision":0,"command":{"kind":"patch","project":{"nodes":{
                     "one":{"id":"one","use":"not.installed","inputs":{}}}}}}
                    """);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(422);
            final var diagnostics = (RailixValue.ArrayValue) object(response.body()).values().get("diagnostics");
            assertThat(((RailixValue.ObjectValue) diagnostics.values().getFirst()).values().get("node")).isEqualTo(RailixValue.string("one"));
        }
    }

    @Test
    void invalidConnectionAndStaleRevisionPreserveAcceptedFiles() throws Exception {
        final var file = directory.resolve("railix.project.json");
        Files.writeString(file, threeStepProject());
        try (var server = start(file)) {
            final String source = Files.readString(file);
            final var cycle = request(server.baseUri(), "POST", "/api/editor", """
                    {"revision":0,"creator_revision":0,"command":{"kind":"connect","from":"three.next","to":"one"}}
                    """);
            assertThat(cycle.statusCode()).as(cycle.body()).isEqualTo(422);
            assertThat(cycle.body()).contains("PROJECT_GRAPH_CYCLE");
            assertThat(Files.readString(file)).isEqualTo(source);
            action(server.baseUri(), "{\"kind\":\"group\",\"id\":\"g\",\"node\":\"one\"}");
            final String metadata = Files.readString(directory.resolve("railix.creator.json"));
            final var stale = request(server.baseUri(), "POST", "/api/editor", """
                    {"revision":0,"creator_revision":0,"command":{"kind":"undo"}}
                    """);
            assertThat(stale.statusCode()).isEqualTo(409);
            assertThat(Files.readString(directory.resolve("railix.creator.json"))).isEqualTo(metadata);
        }
    }

    @Test
    void aDelayedProjectImportDoesNotMistakeAnActiveEditorTransactionForRecovery() throws Exception {
        final var file = directory.resolve("railix.project.json");
        Files.writeString(file, threeStepProject());
        try (var server = start(file);
             var delayed = new java.net.Socket();
             var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            final String original = Files.readString(file);
            final URI uri = server.baseUri();
            final byte[] body = CreatorProjects.empty("delayed-import").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            delayed.connect(new java.net.InetSocketAddress(uri.getHost(), uri.getPort()));
            delayed.setSoTimeout(15_000);
            delayed.getOutputStream().write(("""
                    POST /api/project HTTP/1.1\r
                    Host: %s:%d\r
                    Content-Type: application/json\r
                    X-Railix-Creator-Token: %s\r
                    Content-Length: %d\r
                    Connection: close\r
                    \r
                    """.formatted(uri.getHost(), uri.getPort(), tokenOrIncorrect(uri), body.length))
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            delayed.getOutputStream().write(body, 0, 1);
            delayed.getOutputStream().flush();
            Thread.sleep(100);
            final var editing = executor.submit(() -> action(uri, "{\"kind\":\"delete\",\"node\":\"one\"}"));
            final var journal = directory.resolve(".railix/run/editor-transaction.json");
            final long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while (!Files.exists(journal) && !editing.isDone() && System.nanoTime() < deadline) Thread.sleep(2);
            assertThat(Files.exists(journal)).as("The editor must be inside its real transaction").isTrue();
            delayed.getOutputStream().write(body, 1, body.length - 1);
            delayed.getOutputStream().flush();
            delayed.shutdownOutput();
            final String response = new String(delayed.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            editing.get(15, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(response).contains(" 409 ", "\"status\":\"superseded\"").doesNotContain("restart-required");
            assertThat(Files.exists(journal)).isFalse();
            assertThat(Files.readString(file)).doesNotContain("delayed-import");
            action(uri, "{\"kind\":\"undo\"}");
            assertThat(Files.readString(file)).isEqualTo(original);
        }
    }

    @Test
    void interruptedEditorCommitRecoversBothDocumentsOnStartup() throws Exception {
        final var file = directory.resolve("railix.project.json");
        Files.writeString(file, threeStepProject());
        final String source, metadata;
        try (var server = start(file)) {
            source = Files.readString(file);
            metadata = Files.readString(directory.resolve("railix.creator.json"));
        }
        Files.writeString(directory.resolve(".railix/run/editor-transaction.json"), RailixJson.write(RailixValue.object(Map.of(
                "project", RailixValue.string(source), "creator", RailixValue.string(metadata)))));
        Files.writeString(file, "interrupted project write");
        Files.writeString(directory.resolve("railix.creator.json"), "interrupted metadata write");
        try (var server = start(file)) {
            assertThat(Files.readString(file)).isEqualTo(source);
            assertThat(Files.readString(directory.resolve("railix.creator.json"))).isEqualTo(metadata);
            assertThat(Files.exists(directory.resolve(".railix/run/editor-transaction.json"))).isFalse();
            assertThat(request(server.baseUri(), "GET", "/api/editor", "").statusCode()).isEqualTo(200);
        }
    }

    private static RailixValue.ObjectValue action(final URI server, final String command) throws Exception {
        final var current = object(request(server, "GET", "/api/editor", "").body());
        final var response = request(server, "POST", "/api/editor", RailixJson.write(RailixValue.object(Map.of(
                "revision", current.values().get("revision"), "creator_revision", current.values().get("creator_revision"),
                "command", object(command)))));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return object(response.body());
    }
}
