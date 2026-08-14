package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Execution(ExecutionMode.SAME_THREAD)
final class CreatorServerWorkspaceE2eTest extends CreatorServerE2eSupport {

    @Test
    void missingWorkspaceStartsWithPersistedApplicationGraph() throws Exception {
        final Path project = directory.resolve("project.json");

        try (CreatorServer creator = start(project)) {
            final HttpResponse<String> response = request(creator.baseUri(), "GET", "/api/project", "");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains(
                    "\"id\":\"app\"",
                    "\"links\":[]",
                    "\"state\":\"running\""
            ).containsPattern("\"id\":\"[a-z]+-[a-z]+-[a-z]+\"")
                    .doesNotContain("\"id\":\"command\"");
            assertThat(Files.readString(project)).contains("\"use\":\"railix.app\"");
            assertThat(Files.readString(directory.resolve("railix.creator.json")))
                    .contains("\"format\":1", "\"groups\":[]", "\"steps\":{}");
        }
    }

    @Test
    void invalidOptionalCreatorMetadataDoesNotBlockTheFunctionalApplication() throws Exception {
        final Path project = directory.resolve("railix.project.json");
        final Path metadata = directory.resolve("railix.creator.json");
        Files.writeString(project, CreatorProjects.lowercaseCli(), StandardCharsets.UTF_8);
        Files.writeString(metadata, "{", StandardCharsets.UTF_8);

        try (CreatorServer creator = start(project)) {
            final HttpResponse<String> workspace = request(creator.baseUri(), "GET", "/api/project", "");
            final HttpResponse<String> run = request(creator.baseUri(), "POST", "/api/run/command", CONTEXT);

            assertThat(workspace.statusCode()).isEqualTo(200);
            assertThat(workspace.body()).contains(
                    "\"creator\":{\"format\":1,\"groups\":[],\"steps\":{}}",
                    "\"code\":\"CREATOR_JSON_INVALID\""
            );
            assertThat(run.statusCode()).isEqualTo(200);
            assertThat(run.body()).contains("\"result\":\"hello railix\"");
            assertThat(Files.readString(metadata)).isEqualTo("{");
        }
    }

    @Test
    void nonContiguousStartupMetadataFallsBackToTheFlatRunningGraphWithoutBeingOverwritten()
            throws Exception {
        final Path project = directory.resolve("railix.project.json");
        final Path metadata = directory.resolve("railix.creator.json");
        final String invalid = """
                {"format":1,"steps":{},"groups":[{"id":"group-one","occurrences":[{
                  "id":"occurrence-one","flow":"command","parent":null,
                  "steps":{"slot-one":"one","slot-three":"three"}
                }]}]}
                """;
        Files.writeString(project, threeStepProject(), StandardCharsets.UTF_8);
        Files.writeString(metadata, invalid, StandardCharsets.UTF_8);

        try (CreatorServer creator = start(project)) {
            final HttpResponse<String> workspace = request(creator.baseUri(), "GET", "/api/project", "");
            final HttpResponse<String> run = request(
                    creator.baseUri(), "POST", "/api/run/command", "{\"payload\":{}}"
            );

            assertThat(workspace.statusCode()).isEqualTo(200);
            assertThat(workspace.body()).contains(
                    "\"creator\":{\"format\":1,\"groups\":[],\"steps\":{}}",
                    "\"code\":\"CREATOR_OCCURRENCE_RANGE_INVALID\"",
                    "\"id\":\"one\"",
                    "\"id\":\"two\"",
                    "\"id\":\"three\""
            );
            assertThat(run.statusCode()).isEqualTo(200);
            assertThat(run.body()).contains("\"status\":\"succeeded\"");
            assertThat(Files.readString(metadata)).isEqualTo(invalid);
        }
    }

    @Test
    void validCreatorSaveReplacesInvalidStartupMetadataAndClearsItsDiagnostic() throws Exception {
        final Path project = directory.resolve("railix.project.json");
        final Path metadata = directory.resolve("railix.creator.json");
        Files.writeString(project, CreatorProjects.lowercaseCli(), StandardCharsets.UTF_8);
        Files.writeString(metadata, "{", StandardCharsets.UTF_8);

        try (CreatorServer creator = start(project)) {
            final HttpResponse<String> saved = request(
                    creator.baseUri(), "POST", "/api/creator", CreatorDocument.EMPTY
            );
            final HttpResponse<String> workspace = request(creator.baseUri(), "GET", "/api/project", "");

            assertThat(saved.statusCode()).isEqualTo(200);
            assertThat(workspace.body()).contains("\"diagnostics\":[]")
                    .doesNotContain("CREATOR_JSON_INVALID");
            assertThat(Files.readString(metadata)).isEqualTo(CreatorDocument.EMPTY);
        }
    }

    @Test
    void creatorMetadataPersistsSeparatelyWithoutRestartingTheApplication() throws Exception {
        final Path project = directory.resolve("railix.project.json");
        Files.writeString(project, CreatorProjects.grouping(), StandardCharsets.UTF_8);
        final String creatorSource = """
                {"format":1,"steps":{"lowercase-text":{"name":"Normalize text"}},"groups":[{
                  "id":"group-8494a3c7-bda5-4f72-96f8-1ca7d76ac7ec",
                  "name":"Normalize and return",
                  "occurrences":[{
                    "id":"occurrence-1314a8b1-b71a-41f4-9560-f3df05dba801",
                    "flow":"command",
                    "parent":null,
                    "steps":{
                      "slot-lowercase":"lowercase-text",
                      "slot-return":"return-text"
                    }
                  }]
                }]}
                """;

        try (CreatorServer creator = start(project)) {
            final long pid = number(application(creator.baseUri()), "pid");
            final String functional = Files.readString(project);

            final HttpResponse<String> saved = request(
                    creator.baseUri(),
                    "POST",
                    "/api/creator",
                    creatorSource
            );

            assertThat(saved.statusCode()).isEqualTo(200);
            assertThat(Files.readString(project)).isEqualTo(functional)
                    .doesNotContain("presentation", "groups", "occurrences");
            assertThat(Files.readString(directory.resolve("railix.creator.json"))).contains(
                    "\"name\":\"Normalize text\"",
                    "\"name\":\"Normalize and return\"",
                    "\"slot-lowercase\":\"lowercase-text\""
            );
            assertThat(number(application(creator.baseUri()), "pid")).isEqualTo(pid);
            assertThat(request(creator.baseUri(), "GET", "/api/project", "").body())
                    .contains("\"creator\":", "\"group-8494a3c7-bda5-4f72-96f8-1ca7d76ac7ec\"");
        }
    }

    @Test
    void creatorMetadataPersistsAValidPortableStepIconWithoutRestartingTheApplication() throws Exception {
        final Path project = directory.resolve("railix.project.json");
        final Path metadata = directory.resolve("railix.creator.json");
        Files.writeString(project, CreatorProjects.grouping(), StandardCharsets.UTF_8);
        final String source = """
                {"format":1,"steps":{"lowercase-text":{"icon":{
                  "media_type":"image/svg+xml","data":"PHN2Zy8+"
                }}},"groups":[]}
                """;

        try (CreatorServer creator = start(project)) {
            final long pid = number(application(creator.baseUri()), "pid");

            final HttpResponse<String> saved = request(
                    creator.baseUri(),
                    "POST",
                    "/api/creator",
                    source
            );

            assertThat(saved.statusCode()).isEqualTo(200);
            assertThat(saved.body()).contains(
                    "\"media_type\":\"image/svg+xml\"",
                    "\"data\":\"PHN2Zy8+\""
            );
            assertThat(Files.readString(metadata, StandardCharsets.UTF_8)).contains(
                    "\"media_type\":\"image/svg+xml\"",
                    "\"data\":\"PHN2Zy8+\""
            );
            assertThat(number(application(creator.baseUri()), "pid")).isEqualTo(pid);
        }
    }

    @Test
    void creatorMetadataRejectsInvalidUtf8WithoutChangingTheRunningApplication() throws Exception {
        final Path project = directory.resolve("railix.project.json");
        final Path creatorFile = directory.resolve("railix.creator.json");
        Files.writeString(project, CreatorProjects.grouping(), StandardCharsets.UTF_8);
        try (CreatorServer creator = start(project)) {
            final long pid = number(application(creator.baseUri()), "pid");
            final String before = Files.readString(creatorFile);

            final HttpResponse<String> response = request(
                    creator.baseUri(),
                    "POST",
                    "/api/creator",
                    new byte[]{(byte) 0xc3, 0x28}
            );

            assertThat(response.statusCode()).isEqualTo(422);
            assertThat(response.body()).contains("CREATOR_UTF8_INVALID", "\"path\":\"\"");
            assertThat(Files.readString(creatorFile)).isEqualTo(before);
            assertThat(number(application(creator.baseUri()), "pid")).isEqualTo(pid);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidCreatorMetadata")
    void creatorMetadataRejectionKeepsTheRunningApplication(
            final String scenario,
            final String source,
            final String code,
            final String path
    ) throws Exception {
        assertCreatorMetadataRejected(source, code, path);
    }

    private static Stream<Arguments> invalidCreatorMetadata() {
        final String occurrence = "{\"id\":\"occurrence-one\",\"flow\":\"command\",\"parent\":null,"
                + "\"steps\":{\"slot-one\":\"lowercase-text\"}}";
        return Stream.of(
                Arguments.of("malformed JSON", "{", "CREATOR_JSON_INVALID", ""),
                Arguments.of("metadata must be an object", "[]", "CREATOR_OBJECT_REQUIRED", ""),
                Arguments.of("steps must be an object", creatorMetadata("[]", "[]"), "CREATOR_STEPS_OBJECT_REQUIRED", "steps"),
                Arguments.of("groups must be an array", creatorMetadata("{}", "{}"), "CREATOR_GROUPS_ARRAY_REQUIRED", "groups"),
                Arguments.of("Step presentation must be an object", creatorStep("true"), "CREATOR_PRESENTATION_OBJECT_REQUIRED", "steps.lowercase-text"),
                Arguments.of("presentation name must be non-blank", creatorStep("{\"name\":\" \"}"), "CREATOR_PRESENTATION_NAME_INVALID", "steps.lowercase-text.name"),
                Arguments.of("presentation color must use six hex digits", creatorStep("{\"color\":\"red\"}"), "CREATOR_PRESENTATION_COLOR_INVALID", "steps.lowercase-text.color"),
                Arguments.of("presentation icon must be an object", creatorStep("{\"icon\":true}"), "CREATOR_PRESENTATION_ICON_INVALID", "steps.lowercase-text.icon"),
                Arguments.of("presentation name must be text", creatorStep("{\"name\":1}"), "CREATOR_PRESENTATION_NAME_INVALID", "steps.lowercase-text.name"),
                Arguments.of("presentation name is bounded", creatorStep("{\"name\":\"" + "x".repeat(129) + "\"}"), "CREATOR_PRESENTATION_NAME_INVALID", "steps.lowercase-text.name"),
                Arguments.of("presentation color must be text", creatorStep("{\"color\":1}"), "CREATOR_PRESENTATION_COLOR_INVALID", "steps.lowercase-text.color"),
                Arguments.of("icon rejects unknown fields", creatorIcon("{\"media_type\":\"image/svg+xml\",\"data\":\"PHN2Zy8+\",\"noise\":true}"), "CREATOR_PRESENTATION_ICON_INVALID", "steps.lowercase-text.icon.noise"),
                Arguments.of("icon requires both fields", creatorIcon("{\"media_type\":\"image/svg+xml\"}"), "CREATOR_PRESENTATION_ICON_INVALID", "steps.lowercase-text.icon"),
                Arguments.of("icon media type must be text", creatorIcon("{\"media_type\":1,\"data\":\"PHN2Zy8+\"}"), "CREATOR_PRESENTATION_ICON_INVALID", "steps.lowercase-text.icon"),
                Arguments.of("icon data must be text", creatorIcon("{\"media_type\":\"image/svg+xml\",\"data\":1}"), "CREATOR_PRESENTATION_ICON_INVALID", "steps.lowercase-text.icon"),
                Arguments.of("icon media type must be supported", creatorIcon("{\"media_type\":\"image/jpeg\",\"data\":\"PHN2Zy8+\"}"), "CREATOR_PRESENTATION_ICON_INVALID", "steps.lowercase-text.icon"),
                Arguments.of("icon data must be Base64", creatorIcon("{\"media_type\":\"image/svg+xml\",\"data\":\"%%%\"}"), "CREATOR_PRESENTATION_ICON_INVALID", "steps.lowercase-text.icon.data"),
                Arguments.of("icon data must not be empty", creatorIcon("{\"media_type\":\"image/svg+xml\",\"data\":\"\"}"), "CREATOR_PRESENTATION_ICON_INVALID", "steps.lowercase-text.icon.data"),
                Arguments.of("icon data must match media type", creatorIcon("{\"media_type\":\"image/svg+xml\",\"data\":\"PGJhZC8+\"}"), "CREATOR_PRESENTATION_ICON_INVALID", "steps.lowercase-text.icon.data"),
                Arguments.of("icon data is bounded", creatorIcon("{\"media_type\":\"image/png\",\"data\":\"" + Base64.getEncoder().encodeToString(new byte[65_537]) + "\"}"), "CREATOR_PRESENTATION_ICON_INVALID", "steps.lowercase-text.icon.data"),
                Arguments.of("Step presentation rejects unknown fields", creatorStep("{\"noise\":true}"), "CREATOR_PRESENTATION_FIELD_UNKNOWN", "steps.lowercase-text.noise"),
                Arguments.of("group must be an object", creatorGroup("true"), "CREATOR_GROUP_OBJECT_REQUIRED", "groups[0]"),
                Arguments.of("group id must be non-blank", creatorGroup("{\"id\":\"\",\"occurrences\":[true]}"), "CREATOR_ID_INVALID", "groups[0].id"),
                Arguments.of("group color must use six hex digits", creatorGroup("{\"id\":\"group-one\",\"color\":\"red\",\"occurrences\":[]}"), "CREATOR_PRESENTATION_COLOR_INVALID", "groups[0].color"),
                Arguments.of("group occurrence must be an object", creatorOccurrence("true"), "CREATOR_OCCURRENCE_OBJECT_REQUIRED", "groups[0].occurrences[0]"),
                Arguments.of("group occurrences must be an array", creatorGroup("{\"id\":\"group-one\",\"occurrences\":true}"), "CREATOR_GROUP_OCCURRENCES_REQUIRED", "groups[0].occurrences"),
                Arguments.of("occurrence rejects unknown fields", creatorOccurrence(occurrence.replace("\"steps\":{\"slot-one\":\"lowercase-text\"}", "\"steps\":{\"slot-one\":\"lowercase-text\"},\"noise\":true")), "CREATOR_OCCURRENCE_FIELD_UNKNOWN", "groups[0].occurrences[0].noise"),
                Arguments.of("occurrence id must be non-blank", creatorOccurrence(occurrence.replace("\"occurrence-one\"", "\"\"")), "CREATOR_ID_INVALID", "groups[0].occurrences[0].id"),
                Arguments.of("occurrence flow must be an id", creatorOccurrence(occurrence.replace("\"flow\":\"command\"", "\"flow\":4")), "CREATOR_ID_INVALID", "groups[0].occurrences[0].flow"),
                Arguments.of("occurrence steps must be an object", creatorOccurrence(occurrence.replace("{\"slot-one\":\"lowercase-text\"}", "[]")), "CREATOR_OCCURRENCE_STEPS_REQUIRED", "groups[0].occurrences[0].steps"),
                Arguments.of("occurrence steps must not be empty", creatorOccurrence(occurrence.replace("{\"slot-one\":\"lowercase-text\"}", "{}")), "CREATOR_OCCURRENCE_STEPS_REQUIRED", "groups[0].occurrences[0].steps"),
                Arguments.of("occurrence parent must be null or an id", creatorOccurrence(occurrence.replace("\"parent\":null", "\"parent\":4")), "CREATOR_OCCURRENCE_PARENT_INVALID", "groups[0].occurrences[0].parent"),
                Arguments.of("occurrence parent must not be blank", creatorOccurrence(occurrence.replace("\"parent\":null", "\"parent\":\" \"")), "CREATOR_OCCURRENCE_PARENT_INVALID", "groups[0].occurrences[0].parent"),
                Arguments.of("occurrence slot must be a non-blank id", creatorOccurrence(occurrence.replace("\"slot-one\"", "\"\"")), "CREATOR_OCCURRENCE_STEP_INVALID", "groups[0].occurrences[0].steps."),
                Arguments.of("occurrence Step must be an id", creatorOccurrence(occurrence.replace("\"lowercase-text\"", "4")), "CREATOR_OCCURRENCE_STEP_INVALID", "groups[0].occurrences[0].steps.slot-one"),
                Arguments.of("occurrence rejects reserved app node", creatorOccurrence(occurrence.replace("\"lowercase-text\"", "\"app\"")), "CREATOR_OCCURRENCE_STEP_UNKNOWN", "groups[0].occurrences[0].steps.slot-one"),
                Arguments.of("occurrence rejects reserved Trigger node", creatorOccurrence(occurrence.replace("\"lowercase-text\"", "\"command\"")), "CREATOR_OCCURRENCE_STEP_UNKNOWN", "groups[0].occurrences[0].steps.slot-one"),
                Arguments.of("occurrence cannot assign one Step twice", creatorOccurrence(occurrence.replace("{\"slot-one\":\"lowercase-text\"}", "{\"slot-one\":\"lowercase-text\",\"slot-two\":\"lowercase-text\"}")), "CREATOR_OCCURRENCE_STEP_DUPLICATE", "groups[0].occurrences[0].steps.slot-two"),
                Arguments.of("metadata rejects unknown top-level fields", "{\"format\":1,\"steps\":{},\"groups\":[],\"noise\":true}", "CREATOR_FIELD_UNKNOWN", "noise"),
                Arguments.of("group rejects unimplemented global flag", creatorGroup("{\"id\":\"group-one\",\"global\":true,\"occurrences\":[]}"), "CREATOR_GROUP_FIELD_UNKNOWN", "groups[0].global"),
                Arguments.of("group requires occurrences", creatorGroup("{\"id\":\"group-one\",\"occurrences\":[]}"), "CREATOR_GROUP_OCCURRENCES_REQUIRED", "groups[0].occurrences"),
                Arguments.of("unsupported format", "{\"format\":2,\"steps\":{},\"groups\":[]}", "CREATOR_FORMAT_UNSUPPORTED", "format"),
                Arguments.of("missing format", "{\"steps\":{},\"groups\":[]}", "CREATOR_FORMAT_UNSUPPORTED", "format"),
                Arguments.of("presentation references unknown Step", "{\"format\":1,\"steps\":{\"missing\":{}},\"groups\":[]}", "CREATOR_STEP_UNKNOWN", "steps.missing"),
                Arguments.of("duplicate group ids", """
                        {"format":1,"steps":{},"groups":[
                          {"id":"group-one","occurrences":[{
                            "id":"occurrence-one","flow":"command","parent":null,
                            "steps":{"slot-one":"lowercase-text"}
                          }]},
                          {"id":"group-one","occurrences":[{
                            "id":"occurrence-two","flow":"command","parent":null,
                            "steps":{"slot-two":"return-text"}
                          }]}
                        ]}
                        """, "CREATOR_GROUP_ID_DUPLICATE", "groups[1].id"),
                Arguments.of("unknown occurrence flow", creatorOccurrence(occurrence.replace("\"flow\":\"command\"", "\"flow\":\"missing\"")), "CREATOR_OCCURRENCE_FLOW_UNKNOWN", "groups[0].occurrences[0].flow"),
                Arguments.of("unknown occurrence Step", creatorOccurrence(occurrence.replace("\"lowercase-text\"", "\"missing\"")), "CREATOR_OCCURRENCE_STEP_UNKNOWN", "groups[0].occurrences[0].steps.slot-one"),
                Arguments.of("duplicate occurrence ids", """
                        {"format":1,"steps":{},"groups":[
                          {"id":"group-one","occurrences":[{
                            "id":"same-occurrence","flow":"command","parent":null,
                            "steps":{"slot-one":"lowercase-text"}
                          }]},
                          {"id":"group-two","occurrences":[{
                            "id":"same-occurrence","flow":"command","parent":null,
                            "steps":{"slot-one":"return-text"}
                          }]}
                        ]}
                        """, "CREATOR_OCCURRENCE_ID_DUPLICATE", "groups[1].occurrences[0].id"),
                Arguments.of("shared occurrences use different slots", """
                        {"format":1,"steps":{},"groups":[{"id":"group-one","occurrences":[
                          {"id":"occurrence-one","flow":"command","parent":null,
                           "steps":{"slot-one":"lowercase-text"}},
                          {"id":"occurrence-two","flow":"command","parent":null,
                           "steps":{"slot-two":"return-text"}}
                        ]}]}
                        """, "CREATOR_OCCURRENCE_SLOTS_MISMATCH", "groups[0].occurrences[1].steps"),
                Arguments.of("unknown parent occurrence", creatorOccurrence(occurrence.replace("\"parent\":null", "\"parent\":\"missing\"")), "CREATOR_OCCURRENCE_PARENT_UNKNOWN", "groups[0].occurrences[0].parent"),
                Arguments.of("self parent occurrence", creatorOccurrence(occurrence.replace("\"parent\":null", "\"parent\":\"occurrence-one\"")), "CREATOR_OCCURRENCE_PARENT_UNKNOWN", "groups[0].occurrences[0].parent"),
                Arguments.of("parent cycle", """
                        {"format":1,"steps":{},"groups":[
                          {"id":"group-one","occurrences":[{
                            "id":"occurrence-one","flow":"command","parent":"occurrence-two",
                            "steps":{"slot-one":"lowercase-text"}
                          }]},
                          {"id":"group-two","occurrences":[{
                            "id":"occurrence-two","flow":"command","parent":"occurrence-one",
                            "steps":{"slot-two":"return-text"}
                          }]}
                        ]}
                        """, "CREATOR_OCCURRENCE_PARENT_CYCLE", "groups[0].occurrences[0].parent"),
                Arguments.of("child outside parent range", """
                        {"format":1,"steps":{},"groups":[
                          {"id":"group-one","occurrences":[{
                            "id":"occurrence-one","flow":"command","parent":null,
                            "steps":{"slot-one":"lowercase-text"}
                          }]},
                          {"id":"group-two","occurrences":[{
                            "id":"occurrence-two","flow":"command","parent":"occurrence-one",
                            "steps":{"slot-two":"return-text"}
                          }]}
                        ]}
                        """, "CREATOR_OCCURRENCE_OUTSIDE_PARENT", "groups[1].occurrences[0].steps"),
                Arguments.of("overlapping sibling groups", """
                        {"format":1,"steps":{},"groups":[
                          {"id":"group-one","occurrences":[{
                            "id":"occurrence-one","flow":"command","parent":null,
                            "steps":{"slot-one":"lowercase-text"}
                          }]},
                          {"id":"group-two","occurrences":[{
                            "id":"occurrence-two","flow":"command","parent":null,
                            "steps":{"slot-two":"lowercase-text"}
                          }]}
                        ]}
                        """, "CREATOR_OCCURRENCE_STEP_OVERLAP", "groups[1].occurrences[0].steps")
        );
    }

    private static String creatorMetadata(final String steps, final String groups) {
        return "{\"format\":1,\"steps\":" + steps + ",\"groups\":" + groups + "}";
    }

    private static String creatorStep(final String presentation) {
        return creatorMetadata("{\"lowercase-text\":" + presentation + "}", "[]");
    }

    private static String creatorIcon(final String icon) {
        return creatorStep("{\"icon\":" + icon + "}");
    }

    private static String creatorGroup(final String group) {
        return creatorMetadata("{}", "[" + group + "]");
    }

    private static String creatorOccurrence(final String occurrence) {
        return creatorGroup("{\"id\":\"group-one\",\"occurrences\":[" + occurrence + "]}");
    }

    @Test
    void creatorMetadataRejectsANonContiguousOccurrenceRange() throws Exception {
        final Path project = directory.resolve("project.json");
        Files.writeString(project, threeStepProject(), StandardCharsets.UTF_8);

        try (CreatorServer creator = start(project)) {
            final HttpResponse<String> response = request(
                    creator.baseUri(),
                    "POST",
                    "/api/creator",
                    """
                    {"format":1,"steps":{},"groups":[{"id":"group-one","occurrences":[{
                      "id":"occurrence-one","flow":"command","parent":null,
                      "steps":{"slot-one":"one","slot-three":"three"}
                    }]}]}
                    """
            );

            assertThat(response.statusCode()).isEqualTo(422);
            assertThat(response.body()).contains(
                    "CREATOR_OCCURRENCE_RANGE_INVALID",
                    "groups[0].occurrences[0].steps"
            );
        }
    }

    @Test
    void creatorMetadataAcceptsAConnectedBranchRegionWithOneEntry() throws Exception {
        final Path project = directory.resolve("project.json");
        Files.writeString(project, branchGroupProject(), StandardCharsets.UTF_8);

        try (CreatorServer creator = start(project)) {
            final HttpResponse<String> response = request(
                    creator.baseUri(),
                    "POST",
                    "/api/creator",
                    """
                    {"format":1,"steps":{},"groups":[{"id":"group-one","occurrences":[{
                      "id":"occurrence-one","flow":"command","parent":null,
                      "steps":{"slot-choice":"choice","slot-match":"matched","slot-other":"otherwise"}
                    }]}]}
                    """
            );

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains(
                    "\"id\":\"group-one\"",
                    "\"slot-choice\":\"choice\"",
                    "\"slot-match\":\"matched\"",
                    "\"slot-other\":\"otherwise\""
            );
        }
    }

    @Test
    void creatorMetadataRejectsDisconnectedBranchMembers() throws Exception {
        final Path project = directory.resolve("project.json");
        final Path metadata = directory.resolve("railix.creator.json");
        Files.writeString(project, branchGroupProject(), StandardCharsets.UTF_8);

        try (CreatorServer creator = start(project)) {
            final long pid = number(application(creator.baseUri()), "pid");
            final String originalMetadata = Files.readString(metadata, StandardCharsets.UTF_8);
            final HttpResponse<String> response = request(
                    creator.baseUri(),
                    "POST",
                    "/api/creator",
                    """
                    {"format":1,"steps":{},"groups":[{"id":"group-one","occurrences":[{
                      "id":"occurrence-one","flow":"command","parent":null,
                      "steps":{"slot-match":"matched","slot-other":"otherwise"}
                    }]}]}
                    """
            );

            assertThat(response.statusCode()).isEqualTo(422);
            assertThat(response.body()).contains("CREATOR_OCCURRENCE_RANGE_INVALID");
            assertThat(Files.readString(metadata, StandardCharsets.UTF_8)).isEqualTo(originalMetadata);
            assertThat(number(application(creator.baseUri()), "pid")).isEqualTo(pid);
        }
    }

    @Test
    void creatorMetadataAcceptsSharedOccurrencesWithTheSameBranchTopology() throws Exception {
        final Path project = directory.resolve("project.json");
        Files.writeString(project, sharedBranchGroupProject(), StandardCharsets.UTF_8);

        try (CreatorServer creator = start(project)) {
            final HttpResponse<String> response = request(
                    creator.baseUri(),
                    "POST",
                    "/api/creator",
                    sharedBranchMetadata(false)
            );

            assertThat(response.statusCode()).isEqualTo(200);
        }
    }

    @Test
    void creatorMetadataRejectsSharedOccurrencesWithDifferentBranchTopology() throws Exception {
        final Path project = directory.resolve("project.json");
        Files.writeString(project, sharedBranchGroupProject(), StandardCharsets.UTF_8);

        try (CreatorServer creator = start(project)) {
            final HttpResponse<String> response = request(
                    creator.baseUri(),
                    "POST",
                    "/api/creator",
                    sharedBranchMetadata(true)
            );

            assertThat(response.statusCode()).isEqualTo(422);
            assertThat(response.body()).contains(
                    "CREATOR_OCCURRENCE_TOPOLOGY_MISMATCH",
                    "groups[0].occurrences[1].steps"
            );
        }
    }

    @Test
    void readmeLinksTheCanonicalLowercaseProject() throws Exception {
        final Path readme = Path.of("..", "..", "README.md").toAbsolutePath().normalize();

        assertThat(Files.readString(readme, StandardCharsets.UTF_8))
                .contains("examples/lowercase-app/railix.project.json");
    }

    @Test
    void lowercaseExampleRunsThroughTheCreatorApplication() throws Exception {
        final Path project = directory.resolve("project.json");
        Files.copy(lowercaseExampleProject(), project);

        try (CreatorServer creator = start(project)) {
            final HttpResponse<String> response = request(
                    creator.baseUri(),
                    "POST",
                    "/api/run/command",
                    "{\"payload\":{\"arguments\":[\"Hello RAILIX\"]}}"
            );

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains(
                    "\"status\":\"succeeded\"",
                    "\"result\":\"hello railix\""
            );
        }
    }

    @Test
    void iconCatalogContainsBuiltInsAndExactCustomSvgAndPngBytes() throws Exception {
        final Path project = directory.resolve("project.json");
        final Path railixHome = directory.resolve("railix-home");
        final Path icons = railixHome.resolve("icons");
        Files.createDirectories(icons);
        Files.writeString(icons.resolve("bolt.svg"), "<svg/>", StandardCharsets.UTF_8);
        final String png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwC"
                + "AAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=";
        Files.write(icons.resolve("pixel.png"), Base64.getDecoder().decode(png));

        try (CreatorServer creator = start(project, railixHome)) {
            final HttpResponse<String> response = request(creator.baseUri(), "GET", "/api/icons", "");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains(
                    "\"id\":\"flow\"",
                    "\"id\":\"custom:bolt\"",
                    "\"media_type\":\"image/svg+xml\"",
                    "\"data\":\"PHN2Zy8+\"",
                    "\"id\":\"custom:pixel\"",
                    "\"media_type\":\"image/png\"",
                    "\"data\":\"" + png + "\""
            );
        }
    }

    @Test
    void invalidCustomIconIsReportedAndNotSelectable() throws Exception {
        final Path project = directory.resolve("project.json");
        final Path railixHome = directory.resolve("railix-home");
        final Path icons = railixHome.resolve("icons");
        Files.createDirectories(icons);
        Files.writeString(icons.resolve("broken.png"), "not-png", StandardCharsets.UTF_8);

        try (CreatorServer creator = start(project, railixHome)) {
            final HttpResponse<String> response = request(creator.baseUri(), "GET", "/api/icons", "");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body())
                    .contains("CREATOR_ICON_INVALID", "broken.png")
                    .doesNotContain("custom:broken");
        }
    }

    @Test
    void truncatedPngIconIsReportedAndNotSelectable() throws Exception {
        final Path project = directory.resolve("project.json");
        final Path railixHome = directory.resolve("railix-home");
        final Path icons = railixHome.resolve("icons");
        Files.createDirectories(icons);
        Files.write(icons.resolve("truncated.png"), new byte[]{
                (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
        });

        try (CreatorServer creator = start(project, railixHome)) {
            final HttpResponse<String> response = request(creator.baseUri(), "GET", "/api/icons", "");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body())
                    .contains("CREATOR_ICON_INVALID", "truncated.png")
                    .doesNotContain("custom:truncated");
        }
    }

    @Test
    void nonSvgXmlRootIsReportedAndNotSelectable() throws Exception {
        final Path project = directory.resolve("project.json");
        final Path railixHome = directory.resolve("railix-home");
        final Path icons = railixHome.resolve("icons");
        Files.createDirectories(icons);
        Files.writeString(icons.resolve("wrong.svg"), "<svgx/>", StandardCharsets.UTF_8);

        try (CreatorServer creator = start(project, railixHome)) {
            final HttpResponse<String> response = request(creator.baseUri(), "GET", "/api/icons", "");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body())
                    .contains("CREATOR_ICON_INVALID", "wrong.svg")
                    .doesNotContain("custom:wrong");
        }
    }

    @Test
    void foreignNamespaceSvgRootIsReportedAndNotSelectable() throws Exception {
        final Path project = directory.resolve("project.json");
        final Path railixHome = directory.resolve("railix-home");
        final Path icons = railixHome.resolve("icons");
        Files.createDirectories(icons);
        Files.writeString(
                icons.resolve("foreign.svg"),
                "<x:svg xmlns:x=\"urn:not-svg\"/>",
                StandardCharsets.UTF_8
        );

        try (CreatorServer creator = start(project, railixHome)) {
            final HttpResponse<String> response = request(creator.baseUri(), "GET", "/api/icons", "");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body())
                    .contains("CREATOR_ICON_INVALID", "foreign.svg")
                    .doesNotContain("custom:foreign");
        }
    }

    @Test
    void iconCatalogBoundsTheNumberOfCustomIcons() throws Exception {
        final Path project = directory.resolve("project.json");
        final Path railixHome = directory.resolve("railix-home");
        final Path icons = railixHome.resolve("icons");
        Files.createDirectories(icons);
        for (int index = 0; index < 129; index++) {
            Files.writeString(
                    icons.resolve("icon-%03d.svg".formatted(index)),
                    "<svg/>",
                    StandardCharsets.UTF_8
            );
        }

        try (CreatorServer creator = start(project, railixHome)) {
            final HttpResponse<String> response = request(creator.baseUri(), "GET", "/api/icons", "");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body())
                    .contains("CREATOR_ICON_CATALOG_LIMIT", "custom:icon-127")
                    .doesNotContain("custom:icon-128");
        }
    }

    @Test
    void iconCatalogBoundsTheCombinedCustomIconBytes() throws Exception {
        final Path project = directory.resolve("project.json");
        final Path railixHome = directory.resolve("railix-home");
        final Path icons = railixHome.resolve("icons");
        final String svg = "<svg><!--" + "x".repeat(65_000) + "--></svg>";
        Files.createDirectories(icons);
        for (int index = 0; index < 17; index++) {
            Files.writeString(
                    icons.resolve("bulk-%02d.svg".formatted(index)),
                    svg,
                    StandardCharsets.UTF_8
            );
        }

        try (CreatorServer creator = start(project, railixHome)) {
            final HttpResponse<String> response = request(creator.baseUri(), "GET", "/api/icons", "");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body())
                    .contains("CREATOR_ICON_CATALOG_LIMIT", "custom:bulk-15")
                    .doesNotContain("custom:bulk-16");
        }
    }

    @Test
    void oversizedIconIsReportedAndNotReadIntoTheCatalog() throws Exception {
        final Path project = directory.resolve("project.json");
        final Path railixHome = directory.resolve("railix-home");
        final Path icons = railixHome.resolve("icons");
        Files.createDirectories(icons);
        Files.write(icons.resolve("huge.svg"), new byte[65_537]);

        try (CreatorServer creator = start(project, railixHome)) {
            final HttpResponse<String> response = request(creator.baseUri(), "GET", "/api/icons", "");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body())
                    .contains("CREATOR_ICON_TOO_LARGE", "huge.svg")
                    .doesNotContain("custom:huge");
        }
    }

    @Test
    void iconCatalogSkipsDirectories() throws Exception {
        final Path railixHome = directory.resolve("railix-home");
        Files.createDirectories(railixHome.resolve("icons/nested.svg"));

        try (CreatorServer creator = start(directory.resolve("project.json"), railixHome)) {
            final HttpResponse<String> response = request(creator.baseUri(), "GET", "/api/icons", "");

            assertThat(response.body()).doesNotContain("nested.svg", "custom:nested");
        }
    }

    @Test
    void extensionlessIconIsReportedAndNotSelectable() throws Exception {
        assertInvalidIcon("plain", "<svg/>".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void unsafeIconNameIsReportedAndNotSelectable() throws Exception {
        assertInvalidIcon("bad name.svg", "<svg/>".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void emptyIconIsReportedAndNotSelectable() throws Exception {
        assertInvalidIcon("empty.svg", new byte[0]);
    }

}

@Execution(ExecutionMode.SAME_THREAD)
final class CreatorServerProtocolE2eTest extends CreatorServerE2eSupport {

    @Test
    void duplicateCustomIconStemIsReportedOnce() throws Exception {
        final Path railixHome = directory.resolve("railix-home");
        final Path icons = railixHome.resolve("icons");
        Files.createDirectories(icons);
        Files.writeString(icons.resolve("same.svg"), "<svg/>", StandardCharsets.UTF_8);
        Files.write(
                icons.resolve("same.png"),
                Base64.getDecoder().decode(
                        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwC"
                                + "AAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
                )
        );

        try (CreatorServer creator = start(directory.resolve("project.json"), railixHome)) {
            final HttpResponse<String> response = request(creator.baseUri(), "GET", "/api/icons", "");

            assertThat(response.body())
                    .containsOnlyOnce("\"id\":\"custom:same\"")
                    .contains("CREATOR_ICON_INVALID");
        }
    }

    @Test
    void pngWithWrongSignatureIsReportedAndNotSelectable() throws Exception {
        final byte[] png = pngHeader(1, 1);
        png[0] = 0;
        assertInvalidIcon("signature.png", png);
    }

    @Test
    void zeroWidthPngIsReportedAndNotSelectable() throws Exception {
        assertInvalidIcon("zero-width.png", pngHeader(0, 1));
    }

    @Test
    void zeroHeightPngIsReportedAndNotSelectable() throws Exception {
        assertInvalidIcon("zero-height.png", pngHeader(1, 0));
    }

    @Test
    void oversizedWidthPngIsReportedAndNotSelectable() throws Exception {
        assertInvalidIcon("wide.png", pngHeader(2_049, 1));
    }

    @Test
    void oversizedHeightPngIsReportedAndNotSelectable() throws Exception {
        assertInvalidIcon("tall.png", pngHeader(1, 2_049));
    }

    @Test
    void corruptPngBodyIsReportedAndNotSelectable() throws Exception {
        assertInvalidIcon("corrupt.png", pngHeader(1, 1));
    }

    @Test
    void namespacedSvgIconIsSelectable() throws Exception {
        final Path railixHome = directory.resolve("railix-home");
        final Path icons = railixHome.resolve("icons");
        Files.createDirectories(icons);
        Files.writeString(
                icons.resolve("namespaced.svg"),
                "<s:svg xmlns:s=\"http://www.w3.org/2000/svg\"/>",
                StandardCharsets.UTF_8
        );

        try (CreatorServer creator = start(directory.resolve("project.json"), railixHome)) {
            final HttpResponse<String> response = request(creator.baseUri(), "GET", "/api/icons", "");

            assertThat(response.body()).contains("\"id\":\"custom:namespaced\"");
        }
    }

    @Test
    void defaultNamespaceSvgIconIsSelectable() throws Exception {
        final Path railixHome = directory.resolve("railix-home");
        final Path icons = railixHome.resolve("icons");
        Files.createDirectories(icons);
        Files.writeString(
                icons.resolve("default-namespace.svg"),
                "<svg xmlns=\"http://www.w3.org/2000/svg\"/>",
                StandardCharsets.UTF_8
        );

        try (CreatorServer creator = start(directory.resolve("project.json"), railixHome)) {
            final HttpResponse<String> response = request(creator.baseUri(), "GET", "/api/icons", "");

            assertThat(response.body()).contains("\"id\":\"custom:default-namespace\"");
        }
    }

    @Test
    void malformedSvgIconIsReportedAndNotSelectable() throws Exception {
        assertInvalidIcon("malformed.svg", "<svg>".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void malformedSvgDoesNotLeakParserDiagnosticsToCreatorStderr() throws Exception {
        final PrintStream previous = System.err;
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PrintStream captured = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setErr(captured);
            assertInvalidIcon("malformed.svg", "<svg>".getBytes(StandardCharsets.UTF_8));
        } finally {
            System.setErr(previous);
        }

        assertThat(output.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void iconCatalogBoundsDiagnostics() throws Exception {
        final Path railixHome = directory.resolve("railix-home");
        final Path icons = railixHome.resolve("icons");
        Files.createDirectories(icons);
        for (int index = 0; index < 65; index++) {
            Files.write(icons.resolve("invalid-%02d.svg".formatted(index)), new byte[0]);
        }

        try (CreatorServer creator = start(directory.resolve("project.json"), railixHome)) {
            final HttpResponse<String> response = request(creator.baseUri(), "GET", "/api/icons", "");

            assertThat(response.body()).contains("invalid-63.svg").doesNotContain("invalid-64.svg");
        }
    }

    @Test
    void iconCatalogBoundsDirectoryEntries() throws Exception {
        final Path railixHome = directory.resolve("railix-home");
        final Path icons = railixHome.resolve("icons");
        Files.createDirectories(icons);
        for (int index = 0; index < 513; index++) {
            Files.writeString(icons.resolve("entry-%03d.txt".formatted(index)), "x", StandardCharsets.UTF_8);
        }

        try (CreatorServer creator = start(directory.resolve("project.json"), railixHome)) {
            final HttpResponse<String> response = request(creator.baseUri(), "GET", "/api/icons", "");

            assertThat(response.body()).contains("CREATOR_ICON_CATALOG_LIMIT");
        }
    }

    @Test
    void iconCatalogAcceptsOnlyGet() throws Exception {
        final Path project = directory.resolve("project.json");

        try (CreatorServer creator = start(project, directory.resolve("railix-home"))) {
            assertThat(request(creator.baseUri(), "POST", "/api/icons", "").statusCode()).isEqualTo(405);
        }
    }

    @Test
    void differentMissingWorkspacesReceiveDifferentGeneratedProjectNames() throws Exception {
        final String first;
        final String second;
        try (CreatorServer creator = start(directory.resolve("first/project.json"))) {
            final RailixValue.ObjectValue payload = object(
                    request(creator.baseUri(), "GET", "/api/project", "").body()
            );
            first = string((RailixValue.ObjectValue) payload.values().get("project"), "id");
        }
        try (CreatorServer creator = start(directory.resolve("second/project.json"))) {
            final RailixValue.ObjectValue payload = object(
                    request(creator.baseUri(), "GET", "/api/project", "").body()
            );
            second = string((RailixValue.ObjectValue) payload.values().get("project"), "id");
        }

        assertThat(first).matches("[a-z]+-[a-z]+-[a-z]+");
        assertThat(second).matches("[a-z]+-[a-z]+-[a-z]+").isNotEqualTo(first);
    }

    @Test
    void projectPayloadReportsWorkspacePathFlowAndStepCounts() throws Exception {
        final Path project = directory.resolve("project.json").toAbsolutePath().normalize();

        try (CreatorServer creator = start(project)) {
            final RailixValue.ObjectValue payload = object(
                    request(creator.baseUri(), "GET", "/api/project", "").body()
            );

            assertThat(payload.values().get("workspace")).isEqualTo(RailixValue.object(
                    java.util.Map.of(
                            "project_path", RailixValue.string(project.toString()),
                            "flow_count", RailixValue.number(0),
                            "step_count", RailixValue.number(1)
                    )
            ));
        }
    }

    @Test
    void applicationSnapshotReportsBuildTimeWithoutAnInstanceCounter() throws Exception {
        try (CreatorServer creator = start(directory.resolve("project.json"))) {
            final String response = request(creator.baseUri(), "GET", "/api/application", "").body();

            assertThat(response).contains("\"built_at\":").doesNotContain("\"instance\":");
        }
    }

    @Test
    void applicationSnapshotReportsTheRealProcessAndBuildPath() throws Exception {
        try (CreatorServer creator = start(directory.resolve("project.json"))) {
            final RailixValue.ObjectValue application = application(creator.baseUri());

            assertThat(number(application, "pid")).isPositive();
            assertThat(string(application, "build_path")).isNotBlank();
        }
    }

    @Test
    void catalogDescribesTheGenericRecursiveStepInputGrammar() throws Exception {
        try (CreatorServer creator = start(directory.resolve("project.json"))) {
            final HttpResponse<String> response = request(creator.baseUri(), "GET", "/api/catalog", "");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains(
                    "\"id\":\"railix.app\"",
                    "\"id\":\"railix.trigger.cli\"",
                    "\"maximum_instances\":1",
                    "\"source\":{\"name\":\"application.arguments\"",
                    "\"access\":\"write\",\"default\":[\"context\",\"payload\",\"arguments\"]",
                    "\"examples\":[{\"name\":\"no-arguments\",\"payload\":[]},"
                            + "{\"name\":\"one-argument\",\"payload\":[\"railix\"]},"
                            + "{\"name\":\"multiple-arguments\",\"payload\":[\"hello\",\"railix\"]}]",
                    "\"id\":\"railix.field-manipulation\"",
                    "\"display_name\":\"Field Manipulation\"",
                    "\"primary_outcome\":\"next\"",
                    "\"access\":\"read_write\",\"default\":[\"context\",\"payload\"]",
                    "\"default\":\"current\",\"name\":\"value\",\"options\":[",
                    "\"type\":\"candidates\"",
                    "\"name\":\"literal\",\"value_source\":{\"input\":\"literal\",\"scope\":\"owned\"}",
                    "\"name\":\"steps\",\"type\":\"steps\"",
                    "\"value_source\":{\"input\":\"value\"}",
                    "\"receives\":[{\"name\":\"value\",\"shape\":\"string\"}]",
                    "\"returns\":[{\"name\":\"value\",\"shape\":\"string\"}]",
                    "\"kind\":\"step\"",
                    "\"id\":\"text.lowercase\""
            ).doesNotContain(
                    "\"argument_path\"",
                    "\"on_missing\"",
                    "\"propagates_outcomes\"",
                    "\"primitive_pipeline\"",
                    "\"value_sources\"",
                    "\"config\"",
                    "\"kind\":\"primitive\""
            );
        }
    }

    @Test
    void catalogDescribesChoiceThroughTheGenericMatcherGroupInput() throws Exception {
        try (CreatorServer creator = start(directory.resolve("project.json"))) {
            final RailixValue.ObjectValue definition = catalogDefinition(creator.baseUri(), "railix.choice");

            assertThat(definition.values().get("kind")).isEqualTo(RailixValue.string("step"));
            assertThat(definition.values().get("primary_outcome")).isEqualTo(RailixValue.string("match"));
            assertThat(definition.values().get("outcomes")).isEqualTo(RailixValue.array(List.of(
                    RailixValue.string("match"),
                    RailixValue.string("otherwise")
            )));
            assertThat(definition.values().get("inputs")).isEqualTo(object("""
                    {"value":[{
                      "name":"conditions",
                      "type":"matcher_groups",
                      "options":[
                        {"name":"field","inputs":[{
                          "name":"field","type":"path","access":"read","required":false,
                          "default":["context","payload"]
                        }],"value_source":{"scope":"owned","input":"field"}},
                        {"name":"literal","inputs":[{
                          "name":"value","type":"json","shape":"any","required":true
                        }],"value_source":{"scope":"owned","input":"value"}}
                      ]
                    }]}
                    """).values().get("value"));
        }
    }

    @Test
    void catalogProtocolTokensUseRootLocale() throws Exception {
        final Locale previous = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        try (CreatorServer creator = start(directory.resolve("project.json"))) {
            final String response = request(creator.baseUri(), "GET", "/api/catalog", "").body();

            assertThat(response).contains(
                    "\"kind\":\"step\"",
                    "\"shape\":\"string\""
            ).doesNotContain("\"shape\":\"strıng\"");
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void catalogDescribesTextToNumberAsFallible() throws Exception {
        try (CreatorServer creator = start(directory.resolve("project.json"))) {
            final String response = request(creator.baseUri(), "GET", "/api/catalog", "").body();

            assertThat(response).contains(
                    "\"id\":\"text.to-number\"",
                    "\"outcomes\":[\"ok\",\"invalid\"]"
            );
        }
    }

    @Test
    void catalogDescribesPercentileConfigurationAndOutcomes() throws Exception {
        try (CreatorServer creator = start(directory.resolve("project.json"))) {
            final String response = request(creator.baseUri(), "GET", "/api/catalog", "").body();

            assertThat(response).contains(
                    "\"id\":\"list.percentile\"",
                    "\"inputs\":[{\"default\":95,\"maximum\":100,\"minimum\":0,"
                            + "\"name\":\"percentile\",\"required\":false,"
                            + "\"shape\":\"number\",\"type\":\"json\"}]",
                    "\"outcomes\":[\"ok\",\"empty\",\"invalid\"]"
            );
        }
    }

    @ParameterizedTest(name = "catalog binds zero-defaulted than configuration to {0}")
    @ValueSource(strings = {
            "number.greater-than",
            "number.greater-or-equal",
            "number.less-than",
            "number.less-or-equal"
    })
    void catalogDescribesDefaultedNumberComparisonConfiguration(final String primitive) throws Exception {
        try (CreatorServer creator = start(directory.resolve("project.json"))) {
            final RailixValue.ArrayValue definitions = (RailixValue.ArrayValue) object(
                    request(creator.baseUri(), "GET", "/api/catalog", "").body()
            ).values().get("steps");
            final RailixValue.ObjectValue definition = (RailixValue.ObjectValue) definitions.values().stream()
                    .filter(value -> RailixValue.string(primitive).equals(
                            ((RailixValue.ObjectValue) value).values().get("id")
                    ))
                    .findFirst()
                    .orElseThrow();

            assertThat(definition.values().get("inputs")).isEqualTo(RailixValue.array(java.util.List.of(
                    RailixValue.object(java.util.Map.of(
                            "name", RailixValue.string("than"),
                            "shape", RailixValue.string("number"),
                            "required", RailixValue.bool(false),
                            "default", RailixValue.number(0),
                            "type", RailixValue.string("json")
                    ))
            )));
        }
    }

    @Test
    void catalogDescribesDefaultedTextContainsConfiguration() throws Exception {
        try (CreatorServer creator = start(directory.resolve("project.json"))) {
            final String response = request(creator.baseUri(), "GET", "/api/catalog", "").body();

            assertThat(response).contains(
                    "\"id\":\"text.contains\"",
                    "\"inputs\":[{\"default\":\"\",\"name\":\"needle\",\"required\":false,"
                            + "\"shape\":\"string\",\"type\":\"json\"}]"
            );
        }
    }

    @ParameterizedTest(name = "catalog describes empty-string default for {0}")
    @ValueSource(strings = {"text.starts-with", "text.ends-with"})
    void catalogDescribesDefaultedTextBoundaryConfiguration(final String primitive) throws Exception {
        final String input = primitive.equals("text.starts-with") ? "prefix" : "suffix";
        try (CreatorServer creator = start(directory.resolve("project.json"))) {
            assertThat(catalogDefinition(creator.baseUri(), primitive).values().get("inputs"))
                    .isEqualTo(RailixValue.array(java.util.List.of(RailixValue.object(java.util.Map.of(
                            "name", RailixValue.string(input),
                            "shape", RailixValue.string("string"),
                            "required", RailixValue.bool(false),
                            "default", RailixValue.string(""),
                            "type", RailixValue.string("json")
                    )))));
        }
    }

    @Test
    void catalogDescribesDefaultedValueEqualsConfiguration() throws Exception {
        try (CreatorServer creator = start(directory.resolve("project.json"))) {
            assertThat(catalogDefinition(creator.baseUri(), "value.equals").values().get("inputs"))
                    .isEqualTo(RailixValue.array(java.util.List.of(RailixValue.object(java.util.Map.of(
                            "name", RailixValue.string("expected"),
                            "shape", RailixValue.string("any"),
                            "required", RailixValue.bool(false),
                            "default", RailixValue.nullValue(),
                            "type", RailixValue.string("json")
                    )))));
        }
    }

    @Test
    void catalogExposesMatcherSearchAliasesWithoutChangingItsName() throws Exception {
        try (CreatorServer creator = start(directory.resolve("project.json"))) {
            final var definition = catalogDefinition(
                    creator.baseUri(),
                    "number.greater-or-equal"
            ).values();

            assertThat(definition.get("display_name")).isEqualTo(RailixValue.string("Greater Or Equal"));
            assertThat(definition.get("search_terms")).isEqualTo(RailixValue.array(java.util.List.of(
                    RailixValue.string("gte"),
                    RailixValue.string("ge")
            )));
        }
    }

    @Test
    void catalogOmitsAbsentRefinementFields() throws Exception {
        try (CreatorServer creator = start(directory.resolve("project.json"))) {
            assertThat(catalogDefinition(creator.baseUri(), "text.lowercase").values().get("receives"))
                    .isEqualTo(RailixValue.array(java.util.List.of(RailixValue.object(java.util.Map.of(
                            "name", RailixValue.string("value"),
                            "shape", RailixValue.string("string")
                    )))));
        }
    }

    @Test
    void catalogDescribesListReverseDepthClosure() throws Exception {
        try (CreatorServer creator = start(directory.resolve("project.json"))) {
            final RailixValue.ObjectValue definition = catalogDefinition(creator.baseUri(), "list.reverse");
            final RailixValue.ArrayValue port = RailixValue.array(java.util.List.of(RailixValue.object(
                    java.util.Map.of(
                            "canonical", RailixValue.bool(true),
                            "max_depth", RailixValue.number(64),
                            "name", RailixValue.string("value"),
                            "shape", RailixValue.string("array")
                    )
            )));

            assertThat(java.util.List.of(
                    definition.values().get("receives"),
                    definition.values().get("returns")
            )).containsExactly(port, port);
        }
    }

    @Test
    void catalogDescribesNumberToTextCanonicalPorts() throws Exception {
        try (CreatorServer creator = start(directory.resolve("project.json"))) {
            final RailixValue.ObjectValue definition = catalogDefinition(creator.baseUri(), "number.to-text");

            assertThat(java.util.List.of(
                    definition.values().get("receives"),
                    definition.values().get("returns")
            )).containsExactly(
                    RailixValue.array(java.util.List.of(RailixValue.object(java.util.Map.of(
                            "canonical", RailixValue.bool(true),
                            "name", RailixValue.string("value"),
                            "shape", RailixValue.string("number")
                    )))),
                    RailixValue.array(java.util.List.of(RailixValue.object(java.util.Map.of(
                            "canonical", RailixValue.bool(true),
                            "name", RailixValue.string("value"),
                            "shape", RailixValue.string("string")
                    ))))
            );
        }
    }

    @Test
    void catalogDescribesValueWrapListDepthHeadroom() throws Exception {
        try (CreatorServer creator = start(directory.resolve("project.json"))) {
            final RailixValue.ObjectValue definition = catalogDefinition(creator.baseUri(), "value.wrap-list");

            assertThat(java.util.List.of(
                    definition.values().get("receives"),
                    definition.values().get("returns")
            )).containsExactly(
                    refinedPort("any", 63, 0),
                    refinedPort("array", 64, 0)
            );
        }
    }

    @Test
    void catalogDescribesValueToJsonByteClosure() throws Exception {
        try (CreatorServer creator = start(directory.resolve("project.json"))) {
            final RailixValue.ObjectValue definition = catalogDefinition(creator.baseUri(), "value.to-json");

            assertThat(java.util.List.of(
                    definition.values().get("receives"),
                    definition.values().get("returns")
            )).containsExactly(
                    refinedPort("any", 64, 1_048_576),
                    refinedPort("string", 0, 2_097_154)
            );
        }
    }

    @Test
    void runExampleExecutesMappedPrimitiveInChildApplication() throws Exception {
        try (CreatorServer creator = startJourney()) {
            final HttpResponse<String> response = request(
                    creator.baseUri(),
                    "POST",
                    "/api/run/command",
                    CONTEXT
            );

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo(
                    "{\"context\":{\"exit_code\":0,\"payload\":{\"arguments\":[\"Hello RAILIX\"]},"
                            + "\"result\":\"hello railix\",\"runtime\":{\"test\":true,"
                            + "\"trigger\":\"command\"}},\"status\":\"succeeded\",\"steps\":["
                            + "{\"id\":\"lowercase-text\",\"outcome\":\"ok\"}]}"
            );
        }
    }

    @Test
    void previewReturnsTheActualSourceFromTheChildApplication() throws Exception {
        try (CreatorServer creator = startJourney()) {
            final HttpResponse<String> response = request(
                    creator.baseUri(),
                    "POST",
                    "/api/preview/command/lowercase-text",
                    CONTEXT
            );

            assertThat(response.body()).contains(
                    "\"inputs\":{\"value\":\"Hello RAILIX\"}"
            );
        }
    }

    @Test
    void previewReportsTheCandidateSelectedByTheChildApplication() throws Exception {
        final Path project = directory.resolve("project.json");
        Files.writeString(project, CreatorProjects.orderedCandidates(), StandardCharsets.UTF_8);

        try (CreatorServer creator = start(project)) {
            final HttpResponse<String> response = request(
                    creator.baseUri(),
                    "POST",
                    "/api/preview/command/change",
                    "{\"payload\":{\"value\":\"existing\"}}"
            );

            assertThat(response.body()).contains(
                    "\"selected_candidates\":{\"nodes[2].inputs.value\":1}"
            );
        }
    }

    @Test
    void previewReturnsTheActualMappedPrimitiveOutputFromTheChildApplication() throws Exception {
        try (CreatorServer creator = startJourney()) {
            final HttpResponse<String> response = request(
                    creator.baseUri(),
                    "POST",
                    "/api/preview/command/lowercase-text",
                    CONTEXT
            );

            assertThat(response.body()).contains(
                    "\"result\":\"hello railix\"",
                    "\"steps\":[{\"id\":\"lowercase-text\",\"outcome\":\"ok\"}]"
            );
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("primitivePreviews")
    void previewReturnsTheActualPrimitiveOutputFromTheChildApplication(
            final String scenario,
            final String step,
            final String options,
            final String value,
            final String expected
    ) throws Exception {
        final HttpResponse<String> response = primitivePreview(step, options, value);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains(
                "\"status\":\"succeeded\",\"use\":\"" + step + "\",\"value\":" + expected
        );
    }

    private static Stream<Arguments> primitivePreviews() {
        return Stream.of(
                Arguments.of("starts with", "text.starts-with", "{\"prefix\":\"Nano\"}", "\"Nano Railix\"", "true"),
                Arguments.of("ends with", "text.ends-with", "{\"suffix\":\"Railix\"}", "\"Nano Railix\"", "true"),
                Arguments.of("value equals", "value.equals", "{\"expected\":{\"answer\":42}}", "{\"answer\":42}", "true"),
                Arguments.of("list reverse", "list.reverse", "{}", "[1,2]", "[2,1]"),
                Arguments.of("number to text", "number.to-text", "{}", "1.2300", "\"1.23\""),
                Arguments.of("wrap null in list", "value.wrap-list", "{}", "null", "[null]"),
                Arguments.of("canonical JSON text", "value.to-json", "{}", "{\"z\":2,\"a\":1}", "\"{\\\"a\\\":1,\\\"z\\\":2}\"")
        );
    }

    @Test
    void falliblePreviewCapturesTheInvalidNestedOutcomeAndCompletesTheFlow() throws Exception {
        try (CreatorServer creator = startFallibleJourney()) {
            final HttpResponse<String> response = request(
                    creator.baseUri(),
                    "POST",
                    "/api/preview/command/convert",
                    "{\"payload\":{\"value\":\"not-a-number\"}}"
            );

            assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                    .containsExactly(
                            200,
                            "{\"context\":{\"exit_code\":0,\"payload\":{\"value\":\"not-a-number\"},"
                                    + "\"result\":\"not-a-number\",\"runtime\":{\"test\":true,"
                                    + "\"trigger\":\"command\"}},"
                                    + "\"preview\":{\"input_context\":{\"exit_code\":0,"
                                    + "\"payload\":{\"value\":\"not-a-number\"},\"result\":null,"
                                    + "\"runtime\":{\"test\":true,\"trigger\":\"command\"}},"
                                    + "\"inputs\":{\"field\":\"not-a-number\","
                                    + "\"value\":\"not-a-number\"},\"selected_candidates\":{"
                                    + "\"nodes[2].inputs.value\":0},"
                                    + "\"stages\":[{\"input\":\"steps\","
                                    + "\"invocation\":\"nodes[2].inputs.steps[0]\",\"status\":\"invalid\","
                                    + "\"use\":\"text.to-number\"}],\"step\":\"convert\"},"
                                    + "\"status\":\"succeeded\",\"steps\":["
                                    + "{\"id\":\"text.to-number\",\"outcome\":\"invalid\"},"
                                    + "{\"id\":\"convert\",\"outcome\":\"next\"},"
                                    + "{\"id\":\"number-result\",\"outcome\":\"next\"}]}"
                    );
        }
    }

    @Test
    void fallibleRunFollowsNextInTheChildApplication() throws Exception {
        try (CreatorServer creator = startFallibleJourney()) {
            final HttpResponse<String> response = request(
                    creator.baseUri(),
                    "POST",
                    "/api/run/command",
                    "{\"payload\":{\"value\":\"12.5\"}}"
            );

            assertThat(response.body()).contains(
                    "\"payload\":{\"value\":12.5}",
                    "\"result\":12.5",
                    "{\"id\":\"text.to-number\",\"outcome\":\"ok\"}",
                    "{\"id\":\"convert\",\"outcome\":\"next\"}"
            );
        }
    }

    @Test
    void fallibleRunPreservesTheValueAndContinuesInTheChildApplication() throws Exception {
        try (CreatorServer creator = startFallibleJourney()) {
            final HttpResponse<String> response = request(
                    creator.baseUri(),
                    "POST",
                    "/api/run/command",
                    "{\"payload\":{\"value\":\"not-a-number\"}}"
            );

            assertThat(response.body()).contains(
                    "\"payload\":{\"value\":\"not-a-number\"}",
                    "\"result\":\"not-a-number\"",
                    "{\"id\":\"text.to-number\",\"outcome\":\"invalid\"}",
                    "{\"id\":\"convert\",\"outcome\":\"next\"}",
                    "{\"id\":\"number-result\",\"outcome\":\"next\"}"
            );
        }
    }

    @Test
    void unknownPreviewStepIsRejectedByTheChildApplication() throws Exception {
        try (CreatorServer creator = startJourney()) {
            final HttpResponse<String> response = request(
                    creator.baseUri(),
                    "POST",
                    "/api/preview/command/missing",
                    CONTEXT
            );

            assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                    .containsExactly(
                            422,
                            "{\"diagnostics\":[{\"code\":\"PREVIEW_STEP_UNKNOWN\","
                                + "\"message\":\"Step is not part of the selected Trigger branch: missing.\","
                                + "\"path\":\"step\"}],\"preview\":{\"input_context\":{},\"inputs\":{},"
                                + "\"selected_candidates\":{},\"stages\":[],"
                                + "\"step\":\"missing\"},"
                                    + "\"status\":\"rejected\",\"steps\":[]}"
                    );
        }
    }

    @Test
    void acceptedProjectChangeRollsDevelopmentApplication() throws Exception {
        try (CreatorServer creator = start(directory.resolve("project.json"))) {
            final RailixValue.ObjectValue before = application(creator.baseUri());
            final long previousPid = number(before, "pid");
            final String changed = CreatorProjects.empty("brisk-logic-vault");

            final HttpResponse<String> response = request(
                    creator.baseUri(),
                    "POST",
                    "/api/project",
                    changed
            );
            final RailixValue.ObjectValue after = application(creator.baseUri());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(number(after, "built_at")).isGreaterThanOrEqualTo(number(before, "built_at"));
            assertThat(string(after, "fingerprint")).isNotEqualTo(string(before, "fingerprint"));
            assertThat(number(after, "pid")).isNotEqualTo(previousPid);
            assertThat(awaitExit(previousPid)).isTrue();
        }
    }

    @Test
    void concurrentProjectReadsNeverMixProjectAndApplicationGenerations() throws Exception {
        final Path project = directory.resolve("project.json");
        final String first = CreatorProjects.empty("first-snapshot");
        final String second = CreatorProjects.empty("second-snapshot");
        Files.writeString(project, first, StandardCharsets.UTF_8);

        try (CreatorServer creator = start(project);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final String firstSnapshot = snapshotKey(request(
                    creator.baseUri(), "GET", "/api/project", ""
            ).body());
            final String secondSnapshot = snapshotKey(request(
                    creator.baseUri(), "POST", "/api/project", second
            ).body());
            assertThat(request(creator.baseUri(), "POST", "/api/project", first).statusCode())
                    .isEqualTo(200);

            final CountDownLatch start = new CountDownLatch(1);
            final var change = executor.submit(() -> {
                start.await();
                return request(creator.baseUri(), "POST", "/api/project", second).statusCode();
            });
            final List<java.util.concurrent.Future<String>> snapshots = new java.util.ArrayList<>();
            for (int index = 0; index < 20; index++) {
                snapshots.add(executor.submit(() -> {
                    start.await();
                    return snapshotKey(request(creator.baseUri(), "GET", "/api/project", "").body());
                }));
            }

            start.countDown();
            assertThat(change.get()).isEqualTo(200);
            for (final var snapshot : snapshots) {
                assertThat(snapshot.get()).isIn(firstSnapshot, secondSnapshot);
            }
        }
    }

    @Test
    void laterProjectMutationWinsWhenAnEarlierRequestBodyFinishesLast() throws Exception {
        final Path project = directory.resolve("ordered-project.json");
        final String first = CreatorProjects.empty("earlier-delayed");
        final String second = CreatorProjects.empty("later-complete");
        try (CreatorServer creator = start(project);
             Socket delayed = new Socket()) {
            final URI uri = creator.baseUri();
            final byte[] firstBytes = first.getBytes(StandardCharsets.UTF_8);
            delayed.connect(new InetSocketAddress(uri.getHost(), uri.getPort()));
            delayed.setSoTimeout(15_000);
            delayed.getOutputStream().write(("""
                    POST /api/project HTTP/1.1\r
                    Host: %s:%d\r
                    Content-Type: application/json\r
                    X-Railix-Creator-Token: %s\r
                    Content-Length: %d\r
                    Connection: close\r
                    \r
                    """.formatted(
                    uri.getHost(),
                    uri.getPort(),
                    tokenOrIncorrect(uri),
                    firstBytes.length
            )).getBytes(StandardCharsets.US_ASCII));
            delayed.getOutputStream().write(firstBytes, 0, 1);
            delayed.getOutputStream().flush();
            Thread.sleep(100);

            final HttpResponse<String> later = request(uri, "POST", "/api/project", second);
            delayed.getOutputStream().write(firstBytes, 1, firstBytes.length - 1);
            delayed.getOutputStream().flush();
            delayed.shutdownOutput();
            final String earlier = new String(delayed.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            assertThat(later.statusCode()).isEqualTo(200);
            assertThat(earlier).contains(" 409 ", "\"status\":\"superseded\"");
            assertThat(Files.readString(project)).contains("later-complete").doesNotContain("earlier-delayed");
            assertThat(request(uri, "GET", "/api/project", "").body())
                    .contains("later-complete")
                    .doesNotContain("earlier-delayed");
        }
    }

    @Test
    void rejectedProjectChangeKeepsRunningApplication() throws Exception {
        try (CreatorServer creator = startJourney()) {
            final RailixValue.ObjectValue before = application(creator.baseUri());

            final HttpResponse<String> rejected = request(
                    creator.baseUri(),
                    "POST",
                    "/api/project",
                    "{}"
            );
            final RailixValue.ObjectValue after = application(creator.baseUri());
            final HttpResponse<String> run = request(
                    creator.baseUri(),
                    "POST",
                    "/api/run/command",
                    CONTEXT
            );

            assertThat(rejected.statusCode()).isEqualTo(422);
            assertThat(rejected.body()).contains("\"status\":\"rejected\"", "\"diagnostics\"");
            assertThat(string(after, "fingerprint")).isEqualTo(string(before, "fingerprint"));
            assertThat(number(after, "pid")).isEqualTo(number(before, "pid"));
            assertThat(run.statusCode()).isEqualTo(200);
        }
    }

    @Test
    void acceptedProjectChangeIsPersistedCanonically() throws Exception {
        final Path project = directory.resolve("project.json");
        try (CreatorServer creator = start(project)) {
            final String changed = CreatorProjects.empty("brisk-logic-vault");

            final HttpResponse<String> response = request(
                    creator.baseUri(),
                    "POST",
                    "/api/project",
                    changed
            );
            final RailixValue.ObjectValue payload = object(response.body());

            assertThat(Files.readString(project)).isEqualTo(
                    RailixJson.write(payload.values().get("project"))
            );
        }
    }

    @Test
    void unknownTriggerIsRejectedByCompiledApplication() throws Exception {
        try (CreatorServer creator = start(directory.resolve("project.json"))) {
            final HttpResponse<String> response = request(
                    creator.baseUri(),
                    "POST",
                    "/api/run/missing",
                    CONTEXT
            );

            assertThat(response.statusCode()).isEqualTo(422);
            assertThat(response.body()).contains("\"code\":\"RUN_TRIGGER_UNKNOWN\"");
        }
    }

    @Test
    void malformedContextIsRejectedWithoutExecutingFlow() throws Exception {
        try (CreatorServer creator = start(directory.resolve("project.json"))) {
            final HttpResponse<String> response = request(
                    creator.baseUri(),
                    "POST",
                    "/api/run/command",
                    "{"
            );

            assertThat(response.statusCode()).isEqualTo(422);
            assertThat(response.body()).contains("\"status\":\"rejected\"");
        }
    }

    @Test
    void unsupportedMethodIsRejectedExplicitly() throws Exception {
        try (CreatorServer creator = start(directory.resolve("project.json"))) {
            final HttpResponse<String> response = request(
                    creator.baseUri(),
                    "PUT",
                    "/api/project",
                    CreatorProjects.empty("method-not-allowed")
            );

            assertThat(response.statusCode()).isEqualTo(405);
            assertThat(response.body()).isEqualTo("{\"status\":\"method-not-allowed\"}");
        }
    }

    @Test
    void mutationWithoutCreatorTokenIsRejectedBeforeProjectStateChanges() throws Exception {
        final Path project = directory.resolve("missing-token.json");
        try (CreatorServer creator = start(project);
             HttpClient client = HttpClient.newHttpClient()) {
            final long pid = number(application(creator.baseUri()), "pid");
            final String persisted = Files.readString(project);
            final HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(creator.baseUri().resolve("/api/project"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    CreatorProjects.empty("unauthorized-change"),
                                    StandardCharsets.UTF_8
                            ))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );

            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.body()).isEqualTo("{\"status\":\"unauthorized\"}");
            assertThat(number(application(creator.baseUri()), "pid")).isEqualTo(pid);
            assertThat(Files.readString(project)).isEqualTo(persisted);
        }
    }

    @Test
    void readWithoutCreatorTokenIsRejected() throws Exception {
        try (CreatorServer creator = start(directory.resolve("missing-read-token.json"));
             HttpClient client = HttpClient.newHttpClient()) {
            final HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(creator.baseUri().resolve("/api/project"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );

            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.body()).isEqualTo("{\"status\":\"unauthorized\"}");
        }
    }

    @Test
    void foreignHostIsRejectedBeforeCreatorDataIsRead() throws Exception {
        try (CreatorServer creator = start(directory.resolve("foreign-host.json"));
             Socket socket = new Socket()) {
            final URI uri = creator.baseUri();
            socket.connect(new InetSocketAddress(uri.getHost(), uri.getPort()));
            socket.setSoTimeout(5_000);
            socket.getOutputStream().write(("""
                    GET /api/project HTTP/1.1\r
                    Host: foreign.invalid\r
                    X-Railix-Creator-Token: %s\r
                    Connection: close\r
                    \r
                    """.formatted(tokenOrIncorrect(uri))).getBytes(StandardCharsets.US_ASCII));
            socket.shutdownOutput();

            final String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            assertThat(response).contains(" 403 ", "{\"status\":\"forbidden-host\"}");
        }
    }

    @Test
    void mutationWithIncorrectCreatorTokenIsRejected() throws Exception {
        try (CreatorServer creator = start(directory.resolve("wrong-token.json"));
             HttpClient client = HttpClient.newHttpClient()) {
            final HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(creator.baseUri().resolve("/api/project"))
                            .header("Content-Type", "application/json")
                            .header("X-Railix-Creator-Token", "incorrect")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    CreatorProjects.empty("wrong-token-change"),
                                    StandardCharsets.UTF_8
                            ))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );

            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.body()).isEqualTo("{\"status\":\"unauthorized\"}");
        }
    }

    @Test
    void mutationFromForeignBrowserOriginIsRejectedEvenWithTheCreatorToken() throws Exception {
        try (CreatorServer creator = start(directory.resolve("foreign-origin.json"));
             HttpClient client = HttpClient.newHttpClient()) {
            final HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(creator.baseUri().resolve("/api/project"))
                            .header("Content-Type", "application/json")
                            .header("Origin", "https://foreign.invalid")
                            .header("X-Railix-Creator-Token", tokenOrIncorrect(creator.baseUri()))
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    CreatorProjects.empty("foreign-origin-change"),
                                    StandardCharsets.UTF_8
                            ))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );

            assertThat(response.statusCode()).isEqualTo(403);
            assertThat(response.body()).isEqualTo("{\"status\":\"forbidden-origin\"}");
        }
    }

    @Test
    void mutationWithoutJsonMediaTypeIsRejectedBeforeReadingTheBody() throws Exception {
        try (CreatorServer creator = start(directory.resolve("content-type.json"));
             HttpClient client = HttpClient.newHttpClient()) {
            final HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(creator.baseUri().resolve("/api/project"))
                            .header("Content-Type", "text/plain")
                            .header("X-Railix-Creator-Token", tokenOrIncorrect(creator.baseUri()))
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    CreatorProjects.empty("plain-text-change"),
                                    StandardCharsets.UTF_8
                            ))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );

            assertThat(response.statusCode()).isEqualTo(415);
            assertThat(response.body()).isEqualTo("{\"status\":\"unsupported-media-type\"}");
        }
    }

    @Test
    void mutationAcceptsCaseInsensitiveJsonMediaType() throws Exception {
        try (CreatorServer creator = start(directory.resolve("case-content-type.json"))) {
            final HttpResponse<String> response = creatorMutation(creator.baseUri(), "Application/JSON");

            assertThat(response.statusCode()).isEqualTo(200);
        }
    }

    @Test
    void mutationAcceptsCaseInsensitiveUtf8Charset() throws Exception {
        try (CreatorServer creator = start(directory.resolve("charset-content-type.json"))) {
            final HttpResponse<String> response = creatorMutation(
                    creator.baseUri(),
                    "application/json; Charset=UTF-8"
            );

            assertThat(response.statusCode()).isEqualTo(200);
        }
    }

    @Test
    void mutationRejectsUnknownJsonCharset() throws Exception {
        try (CreatorServer creator = start(directory.resolve("unknown-charset.json"))) {
            final HttpResponse<String> response = creatorMutation(
                    creator.baseUri(),
                    "application/json; charset=us-ascii"
            );

            assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                    .containsExactly(415, "{\"status\":\"unsupported-media-type\"}");
        }
    }

    @Test
    void mutationRejectsExtraJsonMediaTypeParameter() throws Exception {
        try (CreatorServer creator = start(directory.resolve("extra-content-type.json"))) {
            final HttpResponse<String> response = creatorMutation(
                    creator.baseUri(),
                    "application/json; charset=utf-8; profile=creator"
            );

            assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                    .containsExactly(415, "{\"status\":\"unsupported-media-type\"}");
        }
    }

    @Test
    void stalledMutationBodyIsClosedWithinTheReadDeadlineAndCreatorRecovers() throws Exception {
        try (CreatorServer creator = start(directory.resolve("body-deadline.json"));
             Socket socket = new Socket()) {
            final URI uri = creator.baseUri();
            socket.connect(new InetSocketAddress(uri.getHost(), uri.getPort()));
            socket.setSoTimeout(8_000);
            socket.getOutputStream().write(("""
                    POST /api/project HTTP/1.1\r
                    Host: %s:%d\r
                    Content-Type: application/json\r
                    X-Railix-Creator-Token: %s\r
                    Content-Length: 100\r
                    Connection: close\r
                    \r
                    {
                    """.formatted(uri.getHost(), uri.getPort(), tokenOrIncorrect(uri)))
                    .getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            final long started = System.nanoTime();

            final String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(7));
            assertThat(response).contains(
                    " 408 ",
                    "\"code\":\"REQUEST_BODY_TIMEOUT\"",
                    "\"status\":\"rejected\""
            );
            assertThat(request(creator.baseUri(), "GET", "/api/project", "").statusCode()).isEqualTo(200);
        }
    }

    @Test
    void abandonedProjectUploadReturnsFailureAndKeepsCreatorAvailable() throws Exception {
        try (CreatorServer creator = start(directory.resolve("project.json"))) {
            final URI uri = creator.baseUri();
            final String raw;
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(uri.getHost(), uri.getPort()));
                socket.setSoTimeout(5_000);
                socket.getOutputStream().write(("""
                        POST /api/project HTTP/1.1\r
                        Host: %s:%d\r
                        Content-Type: application/json\r
                        X-Railix-Creator-Token: %s\r
                        Content-Length: 100\r
                        Connection: close\r
                        \r
                        {
                        """.formatted(uri.getHost(), uri.getPort(), tokenOrIncorrect(uri)))
                        .getBytes(StandardCharsets.UTF_8));
                socket.shutdownOutput();
                raw = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            }

            final HttpResponse<String> available = request(creator.baseUri(), "GET", "/api/project", "");
            assertThat(raw).contains(" 500 ", "\"message\":\"Creator request failed.\"");
            assertThat(available.statusCode()).isEqualTo(200);
        }
    }

    @Test
    void resetProjectUploadKeepsCreatorAvailable() throws Exception {
        try (CreatorServer creator = start(directory.resolve("project.json"))) {
            final URI uri = creator.baseUri();
            try (Socket socket = new Socket()) {
                socket.setSendBufferSize(1_024);
                socket.setSoLinger(true, 0);
                socket.connect(new InetSocketAddress(uri.getHost(), uri.getPort()));
                socket.getOutputStream().write(("""
                        POST /api/project HTTP/1.1\r
                        Host: %s:%d\r
                        Content-Type: application/json\r
                        X-Railix-Creator-Token: %s\r
                        Content-Length: 1048576\r
                        Connection: close\r
                        \r
                        """.formatted(uri.getHost(), uri.getPort(), tokenOrIncorrect(uri)))
                        .getBytes(StandardCharsets.UTF_8));
                socket.getOutputStream().write("x".repeat(1_000_000).getBytes(StandardCharsets.UTF_8));
                socket.getOutputStream().flush();
            }

            final HttpResponse<String> available = request(creator.baseUri(), "GET", "/api/project", "");
            assertThat(available.statusCode()).isEqualTo(200);
        }
    }

    @Test
    void unknownCreatorPathIsNotFound() throws Exception {
        try (CreatorServer creator = start(directory.resolve("project.json"))) {
            final HttpResponse<String> response = request(
                    creator.baseUri(),
                    "GET",
                    "/api/missing",
                    ""
            );

            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(response.body()).isEqualTo("{\"status\":\"not-found\"}");
        }
    }

    @Test
    void closingCreatorTerminatesOwnedApplicationProcess() throws Exception {
        final long pid;
        try (CreatorServer creator = start(directory.resolve("project.json"))) {
            pid = number(application(creator.baseUri()), "pid");
            assertThat(ProcessHandle.of(pid)).isPresent();
        }

        assertThat(awaitExit(pid)).isTrue();
    }

    @Test
    void invalidExistingWorkspaceCannotStartCreator() throws Exception {
        final Path project = directory.resolve("project.json");
        Files.writeString(project, "{}");

        assertThatThrownBy(() -> start(project))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Cannot open Creator project:");
    }

    @Test
    void duplicateOutcomeCannotEnterTheCreatorWorkspace() throws Exception {
        final Path project = directory.resolve("project.json");
        Files.writeString(project, threeStepProject().replace(
                "{\"from\":\"three.next\",\"to\":\"end\"}",
                "{\"from\":\"three.next\",\"to\":\"end\"},{\"from\":\"three.next\",\"to\":\"end\"}"
        ));

        assertThatThrownBy(() -> start(project))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("PROJECT_PORT_CONNECTION_DUPLICATE");
    }

    @Test
    void invalidCreatorPortCannotStartCreator() {
        assertThatThrownBy(() -> CreatorServer.start(65_536, directory.resolve("project.json")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Creator port must be from 0 through 65535.");
    }

    @Test
    void negativeCreatorPortCannotStartCreator() {
        assertThatThrownBy(() -> CreatorServer.start(-1, directory.resolve("project.json")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Creator port must be from 0 through 65535.");
    }

    @Test
    void nullCreatorProjectCannotStartCreator() {
        assertThatThrownBy(() -> start(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Creator project file cannot be Java null.");
    }

    @Test
    void nullRailixHomeCannotStartCreator() {
        assertThatThrownBy(() -> CreatorServer.start(0, directory.resolve("project.json"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Railix home cannot be Java null.");
    }

    @Test
    void oversizedExistingProjectCannotStartCreator() throws Exception {
        final Path project = directory.resolve("project.json");
        Files.writeString(project, "x".repeat(1_048_577), StandardCharsets.UTF_8);

        assertThatThrownBy(() -> start(project))
                .isInstanceOf(IOException.class)
                .hasMessage("Creator project exceeds the 1048576-byte limit.");
    }

    @Test
    void invalidUtf8ExistingProjectCannotStartCreator() throws Exception {
        final Path project = directory.resolve("project.json");
        Files.write(project, new byte[]{(byte) 0xc3, 0x28});

        assertThatThrownBy(() -> start(project))
                .isInstanceOf(IOException.class)
                .hasMessage("Creator project is not valid UTF-8.");
    }

    @Test
    void occupiedCreatorPortIsRejectedBeforeApplicationStart() throws Exception {
        try (ServerSocket socket = new ServerSocket(
                0,
                1,
                InetAddress.getByAddress(new byte[]{127, 0, 0, 1})
        )) {
            assertThatThrownBy(() -> CreatorServer.start(
                    socket.getLocalPort(),
                    directory.resolve("project.json")
            )).isInstanceOf(IOException.class);
        }
    }

    @Test
    void occupiedCreatorPortReleasesTheProjectLease() throws Exception {
        final Path project = directory.resolve("project.json");
        try (ServerSocket socket = new ServerSocket(
                0,
                1,
                InetAddress.getByAddress(new byte[]{127, 0, 0, 1})
        )) {
            assertThatThrownBy(() -> CreatorServer.start(socket.getLocalPort(), project))
                    .isInstanceOf(IOException.class);
        }

        try (CreatorServer creator = start(project)) {
            assertThat(creator.baseUri().getPort()).isPositive();
        }
    }

    @Test
    void invalidProjectParentIsRejectedBeforeApplicationStart() throws Exception {
        final Path parent = directory.resolve("not-a-directory");
        Files.writeString(parent, "occupied", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> start(parent.resolve("project.json")))
                .isInstanceOf(IOException.class);
    }

    @Test
    void waitingCreatorReturnsAfterClose() throws Exception {
        final CreatorServer creator = start(directory.resolve("project.json"));
        final CompletableFuture<CreatorServer> awaited = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                awaited.complete(creator.awaitClose());
            } catch (final InterruptedException exception) {
                awaited.completeExceptionally(exception);
            }
        });

        assertThat(awaited).isNotDone();
        creator.close();

        assertThat(awaited.get(5, TimeUnit.SECONDS)).isSameAs(creator);
    }

    @Test
    void closingCreatorTwiceIsIdempotent() throws Exception {
        final CreatorServer creator = start(directory.resolve("project.json"));
        final long pid = number(application(creator.baseUri()), "pid");

        creator.close();
        creator.close();

        assertThat(awaitExit(pid)).isTrue();
    }

    @Test
    void interruptedCreatorCloseStillTerminatesItsApplication() throws Exception {
        final CreatorServer creator = start(directory.resolve("project.json"));
        final long pid = number(application(creator.baseUri()), "pid");

        try {
            Thread.currentThread().interrupt();
            creator.close();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
            creator.close();
        }

        assertThat(awaitExit(pid)).isTrue();
    }

    @Test
    void invalidUtf8ProjectChangeIsRejectedBeforeCompilation() throws Exception {
        try (CreatorServer creator = start(directory.resolve("project.json"))) {
            final HttpResponse<String> response = request(
                    creator.baseUri(),
                    "POST",
                    "/api/project",
                    new byte[]{(byte) 0xc3, 0x28}
            );

            assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                    .containsExactly(422, """
                            {"application":%s,"diagnostics":[{"code":"PROJECT_UTF8_INVALID",\
                            "message":"Project must be valid UTF-8.","path":""}],"status":"rejected"}\
                            """.formatted(RailixJson.write(application(creator.baseUri()))));
        }
    }

    @Test
    void oversizedProjectChangeIsRejectedBeforeCompilation() throws Exception {
        try (CreatorServer creator = start(directory.resolve("project.json"))) {
            final HttpResponse<String> response = request(
                    creator.baseUri(),
                    "POST",
                    "/api/project",
                    "x".repeat(1_048_577)
            );

            assertThat(response.statusCode()).isEqualTo(413);
            assertThat(response.body()).contains("\"code\":\"REQUEST_TOO_LARGE\"");
        }
    }

    @Test
    void rollingBuildIgnoresTheAmbientJavaClasspath() throws Exception {
        try (CreatorServer creator = start(directory.resolve("project.json"))) {
            final RailixValue.ObjectValue before = application(creator.baseUri());
            final String classPath = System.getProperty("java.class.path");
            final HttpResponse<String> response;
            try {
                System.setProperty("java.class.path", directory.resolve("missing-classpath").toString());
                response = request(
                        creator.baseUri(),
                        "POST",
                        "/api/project",
                        CreatorProjects.empty("quiet-byte")
                );
            } finally {
                System.setProperty("java.class.path", classPath);
            }

            final RailixValue.ObjectValue after = application(creator.baseUri());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(string(after, "state")).isEqualTo("running");
            assertThat(string(after, "fingerprint")).isNotEqualTo(string(before, "fingerprint"));
        }
    }

    @Test
    void persistenceFailureStopsCandidateAndKeepsRunningApplication() throws Exception {
        final Path project = directory.resolve("project.json");
        try (CreatorServer creator = start(project)) {
            final RailixValue.ObjectValue before = application(creator.baseUri());
            Files.delete(project);
            Files.createDirectory(project);

            final HttpResponse<String> response = request(
                    creator.baseUri(),
                    "POST",
                    "/api/project",
                    CreatorProjects.empty("quiet-byte")
            );

            assertThat(response.statusCode()).isEqualTo(500);
            assertThat(response.body()).contains("\"message\":\"Project could not be persisted.\"");
            assertThat(application(creator.baseUri())).isEqualTo(before);
        }
    }

    @Test
    void presentationOnlyPersistenceFailureKeepsRunningApplicationWithoutRestart() throws Exception {
        final Path project = directory.resolve("project.json");
        try (CreatorServer creator = start(project)) {
            final RailixValue.ObjectValue before = application(creator.baseUri());
            final Path metadata = directory.resolve("railix.creator.json");
            Files.delete(metadata);
            Files.createDirectory(metadata);

            final HttpResponse<String> response = request(
                    creator.baseUri(),
                    "POST",
                    "/api/creator",
                    "{\"format\":1,\"groups\":[],\"steps\":{\"app\":{\"name\":\"Creator\"}}}"
            );

            assertThat(response.statusCode()).isEqualTo(500);
            assertThat(response.body()).contains("\"message\":\"Creator metadata could not be persisted.\"");
            assertThat(number(application(creator.baseUri()), "pid")).isEqualTo(number(before, "pid"));
        }
    }

    @Test
    void applicationEndpointRejectsUnsupportedMethod() throws Exception {
        try (CreatorServer creator = start(directory.resolve("project.json"))) {
            final HttpResponse<String> response = request(
                    creator.baseUri(),
                    "POST",
                    "/api/application",
                    ""
            );

            assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                    .containsExactly(405, "{\"status\":\"method-not-allowed\"}");
        }
    }

    @Test
    void catalogEndpointRejectsUnsupportedMethod() throws Exception {
        try (CreatorServer creator = start(directory.resolve("project.json"))) {
            final HttpResponse<String> response = request(
                    creator.baseUri(),
                    "POST",
                    "/api/catalog",
                    ""
            );

            assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                    .containsExactly(405, "{\"status\":\"method-not-allowed\"}");
        }
    }

    @Test
    void runEndpointRejectsUnsupportedMethod() throws Exception {
        try (CreatorServer creator = start(directory.resolve("project.json"))) {
            final HttpResponse<String> response = request(
                    creator.baseUri(),
                    "GET",
                    "/api/run/command",
                    ""
            );

            assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                    .containsExactly(405, "{\"status\":\"method-not-allowed\"}");
        }
    }

    @Test
    void previewEndpointRejectsUnsupportedMethod() throws Exception {
        try (CreatorServer creator = start(directory.resolve("project.json"))) {
            final HttpResponse<String> response = request(
                    creator.baseUri(),
                    "GET",
                    "/api/preview/command/lowercase-text",
                    ""
            );

            assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                    .containsExactly(405, "{\"status\":\"method-not-allowed\"}");
        }
    }

    @Test
    void blankRunTriggerIsNotFound() throws Exception {
        try (CreatorServer creator = start(directory.resolve("project.json"))) {
            final HttpResponse<String> response = request(
                    creator.baseUri(),
                    "POST",
                    "/api/run/",
                    CONTEXT
            );

            assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                    .containsExactly(404, "{\"status\":\"not-found\"}");
        }
    }

    @Test
    void nestedRunTriggerIsNotFound() throws Exception {
        try (CreatorServer creator = start(directory.resolve("project.json"))) {
            final HttpResponse<String> response = request(
                    creator.baseUri(),
                    "POST",
                    "/api/run/command/nested",
                    CONTEXT
            );

            assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                    .containsExactly(404, "{\"status\":\"not-found\"}");
        }
    }

    @Test
    void incompletePreviewPathIsNotFound() throws Exception {
        try (CreatorServer creator = start(directory.resolve("project.json"))) {
            final HttpResponse<String> response = request(
                    creator.baseUri(),
                    "POST",
                    "/api/preview/command",
                    CONTEXT
            );

            assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                    .containsExactly(404, "{\"status\":\"not-found\"}");
        }
    }

    @Test
    void nestedPreviewPathIsNotFound() throws Exception {
        try (CreatorServer creator = start(directory.resolve("project.json"))) {
            final HttpResponse<String> response = request(
                    creator.baseUri(),
                    "POST",
                    "/api/preview/command/lowercase-text/nested",
                    CONTEXT
            );

            assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                    .containsExactly(404, "{\"status\":\"not-found\"}");
        }
    }

    @Test
    void oversizedRunContextIsRejectedBeforeForwarding() throws Exception {
        try (CreatorServer creator = start(directory.resolve("project.json"))) {
            final HttpResponse<String> response = request(
                    creator.baseUri(),
                    "POST",
                    "/api/run/command",
                    "x".repeat(1_048_577)
            );

            assertThat(response.statusCode()).isEqualTo(413);
            assertThat(response.body()).contains("\"code\":\"REQUEST_TOO_LARGE\"");
        }
    }

    @Test
    void oversizedPreviewContextIsRejectedBeforeForwarding() throws Exception {
        try (CreatorServer creator = start(directory.resolve("project.json"))) {
            final HttpResponse<String> response = request(
                    creator.baseUri(),
                    "POST",
                    "/api/preview/command/lowercase-text",
                    "x".repeat(1_048_577)
            );

            assertThat(response.statusCode()).isEqualTo(413);
        }
    }

    @Test
    void rootServesCreatorHtml() throws Exception {
        try (CreatorServer creator = start(directory.resolve("project.json"))) {
            final HttpResponse<String> response = request(creator.baseUri(), "GET", "/", "");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type"))
                    .contains("text/html; charset=utf-8");
            assertThat(response.body()).contains("<title>Railix Creator</title>");
        }
    }

    @Test
    void explicitIndexServesCreatorHtml() throws Exception {
        try (CreatorServer creator = start(directory.resolve("project.json"))) {
            final HttpResponse<String> response = request(
                    creator.baseUri(),
                    "GET",
                    "/index.html",
                    ""
            );

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("<title>Railix Creator</title>");
        }
    }

    @Test
    void stylesheetServesCreatorCss() throws Exception {
        try (CreatorServer creator = start(directory.resolve("project.json"))) {
            final HttpResponse<String> response = request(
                    creator.baseUri(),
                    "GET",
                    "/app.css",
                    ""
            );

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type"))
                    .contains("text/css; charset=utf-8");
            assertThat(response.body()).contains(".creator-shell");
        }
    }

    @Test
    void browserScriptServesCreatorJavascript() throws Exception {
        try (CreatorServer creator = start(directory.resolve("project.json"))) {
            final HttpResponse<String> response = request(
                    creator.baseUri(),
                    "GET",
                    "/app.js",
                    ""
            );

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type"))
                    .contains("text/javascript; charset=utf-8");
            assertThat(response.body()).contains("function addCatalogStep(id)");
        }
    }

    @Test
    void browserEditorUsesOnlyTheGenericCatalogInputContract() throws Exception {
        try (CreatorServer creator = start(directory.resolve("project.json"))) {
            final HttpResponse<String> response = request(
                    creator.baseUri(),
                    "GET",
                    "/app.js",
                    ""
            );

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains(
                    "definition.inputs",
                    "input.type",
                    "valueAt(operation, locator)",
                    "setAt(selectedOperation(), locator"
            ).doesNotContain(
                    "railix.field-manipulation",
                    "primitive_pipeline",
                    "argument_path",
                    "operation.config"
            );
        }
    }

    @Test
    void staticResourceRejectsUnsupportedMethod() throws Exception {
        try (CreatorServer creator = start(directory.resolve("project.json"))) {
            final HttpResponse<String> response = request(
                    creator.baseUri(),
                    "POST",
                    "/app.css",
                    ""
            );

            assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                    .containsExactly(405, "{\"status\":\"method-not-allowed\"}");
        }
    }

    @Test
    void stoppedDevelopmentApplicationIsReported() throws Exception {
        try (CreatorServer creator = start(directory.resolve("project.json"))) {
            final long pid = number(application(creator.baseUri()), "pid");
            stop(pid);

            assertThat(string(application(creator.baseUri()), "state")).isEqualTo("stopped");
        }
    }

    @Test
    void runToStoppedDevelopmentApplicationIsUnavailable() throws Exception {
        try (CreatorServer creator = startJourney()) {
            final long pid = number(application(creator.baseUri()), "pid");
            stop(pid);

            final HttpResponse<String> response = request(
                    creator.baseUri(),
                    "POST",
                    "/api/run/command",
                    CONTEXT
            );

            assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                    .containsExactly(503, "{\"status\":\"unavailable\"}");
        }
    }

    @Test
    void previewToStoppedDevelopmentApplicationIsUnavailable() throws Exception {
        try (CreatorServer creator = startJourney()) {
            final long pid = number(application(creator.baseUri()), "pid");
            stop(pid);

            final HttpResponse<String> response = request(
                    creator.baseUri(),
                    "POST",
                    "/api/preview/command/lowercase-text",
                    CONTEXT
            );

            assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                    .containsExactly(503, "{\"status\":\"unavailable\"}");
        }
    }

}

abstract class CreatorServerE2eSupport {
    static final String CONTEXT = """
            {"payload":{"arguments":["Hello RAILIX"]}}
            """;

    @TempDir
    Path directory;

    static HttpResponse<String> request(
            final URI baseUri,
            final String method,
            final String path,
            final String body
    ) throws IOException, InterruptedException {
        return request(
                baseUri,
                method,
                path,
                body.isEmpty()
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)
        );
    }

    static HttpResponse<String> request(
            final URI baseUri,
            final String method,
            final String path,
            final byte[] body
    ) throws IOException, InterruptedException {
        return request(baseUri, method, path, HttpRequest.BodyPublishers.ofByteArray(body));
    }

    static HttpResponse<String> request(
            final URI baseUri,
            final String method,
            final String path,
            final HttpRequest.BodyPublisher body
    ) throws IOException, InterruptedException {
        final HttpRequest.Builder builder = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("X-Railix-Creator-Token", tokenOrIncorrect(baseUri))
                .method(method, body);
        final HttpRequest request = builder.build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
    }

    static HttpResponse<String> creatorMutation(
            final URI baseUri,
            final String contentType
    ) throws IOException, InterruptedException {
        final HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/api/creator"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", contentType)
                .header("X-Railix-Creator-Token", tokenOrIncorrect(baseUri))
                .POST(HttpRequest.BodyPublishers.ofString(CreatorDocument.EMPTY, StandardCharsets.UTF_8))
                .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
    }

    static String tokenOrIncorrect(final URI baseUri) {
        final String fragment = baseUri.getRawFragment();
        return fragment != null && fragment.startsWith("token=")
                ? fragment.substring("token=".length())
                : "incorrect";
    }

    static RailixValue.ObjectValue application(final URI baseUri) throws Exception {
        return (RailixValue.ObjectValue) object(
                request(baseUri, "GET", "/api/application", "").body()
        );
    }

    static RailixValue.ObjectValue object(final String source) {
        final RailixJson.Result result = RailixJson.parse(source);
        assertThat(result).isInstanceOf(RailixJson.Parsed.class);
        return (RailixValue.ObjectValue) ((RailixJson.Parsed) result).value();
    }

    static String snapshotKey(final String source) {
        final RailixValue.ObjectValue payload = object(source);
        return string((RailixValue.ObjectValue) payload.values().get("project"), "id") + ":"
                + string((RailixValue.ObjectValue) payload.values().get("application"), "fingerprint");
    }

    void assertCreatorMetadataRejected(
            final String metadata,
            final String code,
            final String path
    ) throws Exception {
        final Path project = directory.resolve("railix.project.json");
        final Path creatorFile = directory.resolve("railix.creator.json");
        Files.writeString(project, CreatorProjects.grouping(), StandardCharsets.UTF_8);
        try (CreatorServer creator = start(project)) {
            final long pid = number(application(creator.baseUri()), "pid");
            final String before = Files.exists(creatorFile) ? Files.readString(creatorFile) : "";

            final HttpResponse<String> response = request(
                    creator.baseUri(),
                    "POST",
                    "/api/creator",
                    metadata
            );

            assertThat(response.statusCode()).isEqualTo(422);
            assertThat(response.body()).contains(
                    "\"code\":\"" + code + "\"",
                    "\"path\":\"" + path + "\""
            );
            assertThat(Files.exists(creatorFile) ? Files.readString(creatorFile) : "").isEqualTo(before);
            assertThat(number(application(creator.baseUri()), "pid")).isEqualTo(pid);
        }
    }

    void assertInvalidIcon(final String file, final byte[] source) throws Exception {
        final Path railixHome = directory.resolve("railix-home");
        final Path icons = railixHome.resolve("icons");
        Files.createDirectories(icons);
        Files.write(icons.resolve(file), source);

        try (CreatorServer creator = start(directory.resolve("project.json"), railixHome)) {
            final HttpResponse<String> response = request(creator.baseUri(), "GET", "/api/icons", "");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("CREATOR_ICON_INVALID", file);
        }
    }

    static byte[] pngHeader(final int width, final int height) {
        final byte[] value = new byte[24];
        ByteBuffer.wrap(value)
                .put(new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a})
                .position(16)
                .putInt(width)
                .putInt(height);
        return value;
    }

    static RailixValue.ObjectValue catalogDefinition(
            final URI creator,
            final String primitive
    ) throws Exception {
        final RailixValue.ArrayValue definitions = (RailixValue.ArrayValue) object(
                request(creator, "GET", "/api/catalog", "").body()
        ).values().get("steps");
        return (RailixValue.ObjectValue) definitions.values().stream()
                .filter(value -> RailixValue.string(primitive).equals(
                        ((RailixValue.ObjectValue) value).values().get("id")
                ))
                .findFirst()
                .orElseThrow();
    }

    static RailixValue.ArrayValue refinedPort(
            final String shape,
            final int maxDepth,
            final int maxJsonBytes
    ) {
        final java.util.Map<String, RailixValue> values = new java.util.LinkedHashMap<>();
        values.put("canonical", RailixValue.bool(true));
        if (maxDepth > 0) {
            values.put("max_depth", RailixValue.number(maxDepth));
        }
        if (maxJsonBytes > 0) {
            values.put("max_json_bytes", RailixValue.number(maxJsonBytes));
        }
        values.put("name", RailixValue.string("value"));
        values.put("shape", RailixValue.string(shape));
        return RailixValue.array(java.util.List.of(RailixValue.object(values)));
    }

    static long number(final RailixValue.ObjectValue object, final String field) {
        return ((RailixValue.NumberValue) object.values().get(field)).value().longValueExact();
    }

    static String string(final RailixValue.ObjectValue object, final String field) {
        return ((RailixValue.StringValue) object.values().get(field)).value();
    }

    static boolean awaitExit(final long pid) throws InterruptedException {
        for (int attempt = 0; attempt < 50; attempt++) {
            if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                Thread.sleep(100);
            } else {
                return true;
            }
        }
        return false;
    }

    static void stop(final long pid) throws InterruptedException {
        final ProcessHandle process = ProcessHandle.of(pid).orElseThrow();
        process.destroy();
        if (!awaitExit(pid)) {
            process.destroyForcibly();
            assertThat(awaitExit(pid)).isTrue();
        }
    }

    CreatorServer start(final Path project) throws IOException {
        return start(project, directory.resolve("railix-home"));
    }

    static CreatorServer start(final Path project, final Path railixHome) throws IOException {
        return CreatorServer.start(0, project, railixHome);
    }

    CreatorServer startJourney() throws IOException {
        final Path project = directory.resolve("project.json");
        Files.writeString(project, CreatorProjects.lowercaseCli(), StandardCharsets.UTF_8);
        return start(project);
    }

    static Path lowercaseExampleProject() {
        return Path.of("..", "..", "examples", "lowercase-app", "railix.project.json")
                .toAbsolutePath()
                .normalize();
    }

    static String threeStepProject() {
        return """
                {"format":1,"id":"three-steps","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[{
                    "name":"example","payload":[],"context":{"payload":{}}
                  }]},
                  {"id":"one","use":"railix.field-manipulation","inputs":{}},
                  {"id":"two","use":"railix.field-manipulation","inputs":{}},
                  {"id":"three","use":"railix.field-manipulation","inputs":{}}
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"one"},
                  {"from":"one.next","to":"two"},
                  {"from":"two.next","to":"three"},
                  {"from":"three.next","to":"end"}
                ]}
                """;
    }

    static String branchGroupProject() {
        return """
                {"format":1,"id":"branch-group","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[{
                    "name":"example","payload":[],"context":{"payload":{}}
                  }]},
                  {"id":"choice","use":"railix.choice","inputs":{"conditions":[]}},
                  {"id":"matched","use":"railix.field-manipulation","inputs":{}},
                  {"id":"otherwise","use":"railix.field-manipulation","inputs":{}}
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"choice"},
                  {"from":"choice.match","to":"matched"},
                  {"from":"choice.otherwise","to":"otherwise"},
                  {"from":"matched.next","to":"end"},
                  {"from":"otherwise.next","to":"end"}
                ]}
                """;
    }

    static String sharedBranchGroupProject() {
        return """
                {"format":1,"id":"shared-branch-group","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[{
                    "name":"example","payload":[],"context":{"payload":{}}
                  }]},
                  {"id":"split","use":"railix.choice","inputs":{"conditions":[]}},
                  {"id":"choice-a","use":"railix.choice","inputs":{"conditions":[]}},
                  {"id":"a-match","use":"railix.field-manipulation","inputs":{}},
                  {"id":"a-other","use":"railix.field-manipulation","inputs":{}},
                  {"id":"choice-b","use":"railix.choice","inputs":{"conditions":[]}},
                  {"id":"b-match","use":"railix.field-manipulation","inputs":{}},
                  {"id":"b-other","use":"railix.field-manipulation","inputs":{}}
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"split"},
                  {"from":"split.match","to":"choice-a"},
                  {"from":"split.otherwise","to":"choice-b"},
                  {"from":"choice-a.match","to":"a-match"},
                  {"from":"choice-a.otherwise","to":"a-other"},
                  {"from":"a-match.next","to":"end"},
                  {"from":"a-other.next","to":"end"},
                  {"from":"choice-b.match","to":"b-match"},
                  {"from":"choice-b.otherwise","to":"b-other"},
                  {"from":"b-match.next","to":"end"},
                  {"from":"b-other.next","to":"end"}
                ]}
                """;
    }

    static String sharedBranchMetadata(final boolean swapSecondLeaves) {
        return """
                {"format":1,"steps":{},"groups":[{"id":"group-one","occurrences":[
                  {"id":"occurrence-a","flow":"command","parent":null,"steps":{
                    "slot-choice":"choice-a","slot-match":"a-match","slot-other":"a-other"}},
                  {"id":"occurrence-b","flow":"command","parent":null,"steps":{
                    "slot-choice":"choice-b","slot-match":"%s","slot-other":"%s"}}
                ]}]}
                """.formatted(
                swapSecondLeaves ? "b-other" : "b-match",
                swapSecondLeaves ? "b-match" : "b-other"
        );
    }

    CreatorServer startFallibleJourney() throws IOException {
        final Path project = directory.resolve("project.json");
        Files.writeString(project, CreatorProjects.fallibleNumber(), StandardCharsets.UTF_8);
        return start(project);
    }

    HttpResponse<String> primitivePreview(
            final String primitive,
            final String inputs,
            final String value
    ) throws Exception {
        final Path project = directory.resolve("project.json");
        Files.writeString(project, primitiveProject(primitive, inputs, value), StandardCharsets.UTF_8);
        try (CreatorServer creator = start(project)) {
            return request(
                    creator.baseUri(),
                    "POST",
                    "/api/preview/command/apply",
                    "{\"payload\":{\"value\":" + value + "}}"
            );
        }
    }

    static String primitiveProject(
            final String primitive,
            final String inputs,
            final String value
    ) {
        return """
                {
                  "format":1,
                  "id":"creator-primitive-proof",
                  "nodes":[
                    {"id":"app","use":"railix.app","inputs":{}},
                    {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[{
                      "name":"example","payload":[],"context":{"payload":{"value":%s}}
                    }]},
                    {"id":"apply","use":"railix.field-manipulation","inputs":{
                      "field":["context","result"],
                      "value":[{"option":"field","inputs":{
                        "source":["context","payload","value"]
                      }}],
                      "steps":[{"use":"%s","inputs":%s}]
                    }}
                  ],
                  "links":[
                    {"from":"app.start","to":"command"},
                    {"from":"command.next","to":"apply"},
                    {"from":"apply.next","to":"end"}
                  ]
                }
                """.formatted(value, primitive, inputs);
    }
}
