package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixData;
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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
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
                    .contains("\"format\":2", "\"groups\":[]", "\"steps\":{}");
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
            final HttpResponse<String> example = awaitExampleView(creator.baseUri(), "command:0");

            assertThat(workspace.statusCode()).isEqualTo(200);
            assertThat(workspace.body()).contains(
                    "\"creator\":{\"format\":2,\"groups\":[],\"steps\":{}}",
                    "\"code\":\"CREATOR_JSON_INVALID\""
            );
            assertThat(example.statusCode()).isEqualTo(200);
            assertThat(example.body()).contains("\"result\":\"hello railix\"");
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
            final HttpResponse<String> example = awaitExampleView(creator.baseUri(), "command:0");

            assertThat(workspace.statusCode()).isEqualTo(200);
            assertThat(workspace.body()).contains(
                    "\"creator\":{\"format\":2,\"groups\":[],\"steps\":{}}",
                    "\"code\":\"CREATOR_OCCURRENCE_RANGE_INVALID\"",
                    "\"id\":\"one\"",
                    "\"id\":\"two\"",
                    "\"id\":\"three\""
            );
            assertThat(example.statusCode()).isEqualTo(200);
            assertThat(example.body()).contains("\"status\":\"succeeded\"");
            assertThat(Files.readString(metadata)).isEqualTo(invalid);
        }
    }

    @Test
    void validFormatOneMetadataMigratesToFormatTwoWithoutRestartingTheApplication() throws Exception {
        final Path project = directory.resolve("railix.project.json");
        final Path metadata = directory.resolve("railix.creator.json");
        Files.writeString(project, CreatorProjects.grouping(), StandardCharsets.UTF_8);
        Files.writeString(metadata, """
                {"format":1,"steps":{"lowercase-text":{"name":"Normalize text"}},"groups":[{
                  "id":"normalize","name":"Normalize","color":"#147982",
                  "occurrences":[{
                    "id":"normalize-one","flow":"command","parent":null,
                    "steps":{"lowercase":"lowercase-text","result":"return-text"}
                  }]
                }]}
                """, StandardCharsets.UTF_8);

        try (CreatorServer creator = start(project)) {
            final long pid = number(application(creator.baseUri()), "pid");
            final String migrated = Files.readString(metadata);

            assertThat(migrated).contains(
                    "\"format\":2",
                    "\"id\":\"normalize\"",
                    "\"group\":\"normalize\"",
                    "\"name\":\"Normalize text\""
            ).doesNotContain("occurrences", "normalize-one", "lowercase\":");
            assertThat(number(application(creator.baseUri()), "pid")).isEqualTo(pid);
        }
    }

    @Test
    void deepestLegacyGroupWinsOverlappingStepMembershipDuringMigration() throws Exception {
        final Path project = directory.resolve("railix.project.json");
        final Path metadata = directory.resolve("railix.creator.json");
        Files.writeString(project, CreatorProjects.grouping(), StandardCharsets.UTF_8);
        Files.writeString(metadata, """
                {"format":1,"steps":{},"groups":[
                  {"id":"outer","name":"Outer","occurrences":[{
                    "id":"outer-one","flow":"command","parent":null,
                    "steps":{"lowercase":"lowercase-text","result":"return-text"}
                  }]},
                  {"id":"inner","name":"Inner","occurrences":[{
                    "id":"inner-one","flow":"command","parent":"outer-one",
                    "steps":{"lowercase":"lowercase-text"}
                  }]}
                ]}
                """, StandardCharsets.UTF_8);

        try (CreatorServer ignored = start(project)) {
            final String migrated = Files.readString(metadata);

            assertThat(migrated).contains(
                    "\"id\":\"outer\"",
                    "\"id\":\"inner\"",
                    "\"lowercase-text\":{\"group\":\"inner\"}",
                    "\"return-text\":{\"group\":\"outer\"}"
            ).doesNotContain("occurrences", "outer-one", "inner-one");
        }
    }

    @Test
    void legacyGroupMigrationIsIndependentOfParentDeclarationOrder() throws Exception {
        final Path project = directory.resolve("railix.project.json");
        final Path metadata = directory.resolve("railix.creator.json");
        Files.writeString(project, CreatorProjects.grouping(), StandardCharsets.UTF_8);
        Files.writeString(metadata, """
                {"format":1,"steps":{},"groups":[
                  {"id":"inner","occurrences":[{
                    "id":"inner-one","flow":"command","parent":"outer-one",
                    "steps":{"lowercase":"lowercase-text"}
                  }]},
                  {"id":"outer","occurrences":[{
                    "id":"outer-one","flow":"command","parent":null,
                    "steps":{"lowercase":"lowercase-text","result":"return-text"}
                  }]}
                ]}
                """, StandardCharsets.UTF_8);

        try (CreatorServer ignored = start(project)) {
            assertThat(Files.readString(metadata)).contains(
                    "\"lowercase-text\":{\"group\":\"inner\"}",
                    "\"return-text\":{\"group\":\"outer\"}"
            ).doesNotContain("occurrences", "inner-one", "outer-one");
        }
    }

    @Test
    void deepLegacyHierarchyMigratesIterativelyToItsDeepestGroup() throws Exception {
        final Path project = directory.resolve("railix.project.json");
        final Path metadata = directory.resolve("railix.creator.json");
        Files.writeString(project, CreatorProjects.grouping(), StandardCharsets.UTF_8);
        Files.writeString(metadata, deepLegacyMetadata(512), StandardCharsets.UTF_8);

        try (CreatorServer ignored = start(project)) {
            assertThat(Files.readString(metadata)).contains(
                    "\"id\":\"group-511\"",
                    "\"lowercase-text\":{\"group\":\"group-511\"}"
            ).doesNotContain("occurrences", "occurrence-511");
        }
    }

    private static String deepLegacyMetadata(final int depth) {
        final StringBuilder source = new StringBuilder("{\"format\":1,\"steps\":{},\"groups\":[");
        for (int index = 0; index < depth; index++) {
            if (index > 0) {
                source.append(',');
            }
            source.append("{\"id\":\"group-").append(index)
                    .append("\",\"occurrences\":[{\"id\":\"occurrence-").append(index)
                    .append("\",\"flow\":\"command\",\"parent\":")
                    .append(index == 0 ? "null" : "\"occurrence-" + (index - 1) + "\"")
                    .append(",\"steps\":{\"lowercase\":\"lowercase-text\"}}]}");
        }
        return source.append("]}").toString();
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
                {"format":2,"steps":{"lowercase-text":{
                  "name":"Normalize text","outcomes":{"next":"Continue"},
                  "group":"group-8494a3c7-bda5-4f72-96f8-1ca7d76ac7ec"
                },"return-text":{"group":"group-8494a3c7-bda5-4f72-96f8-1ca7d76ac7ec"}},"groups":[{
                  "id":"group-8494a3c7-bda5-4f72-96f8-1ca7d76ac7ec",
                  "name":"Normalize and return",
                  "boundary":"dashed"
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
                    .doesNotContain("presentation", "groups", "occurrences", "Continue");
            assertThat(Files.readString(directory.resolve("railix.creator.json"))).contains(
                    "\"name\":\"Normalize text\"",
                    "\"outcomes\":{\"next\":\"Continue\"}",
                    "\"name\":\"Normalize and return\"",
                    "\"group\":\"group-8494a3c7-bda5-4f72-96f8-1ca7d76ac7ec\"",
                    "\"boundary\":\"dashed\""
            ).doesNotContain("occurrences", "slot-lowercase");
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

    @Test
    void creatorMetadataEndpointRejectsUnsupportedMethods() throws Exception {
        try (CreatorServer creator = start(directory.resolve("creator-method.json"))) {
            final HttpResponse<String> response = request(
                    creator.baseUri(),
                    "GET",
                    "/api/creator",
                    ""
            );

            assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                    .containsExactly(405, "{\"status\":\"method-not-allowed\"}");
        }
    }

    @Test
    void oversizedCreatorMetadataIsRejectedWithoutStoppingTheApplication() throws Exception {
        try (CreatorServer creator = start(directory.resolve("creator-oversized.json"))) {
            final long pid = number(application(creator.baseUri()), "pid");
            final HttpResponse<String> response = request(
                    creator.baseUri(),
                    "POST",
                    "/api/creator",
                    "x".repeat(1_048_577)
            );

            assertThat(response.statusCode()).isEqualTo(413);
            assertThat(response.body()).contains("\"code\":\"REQUEST_TOO_LARGE\"");
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
                Arguments.of("current steps must be an object", "{\"format\":2,\"steps\":[],\"groups\":[]}", "CREATOR_STEPS_OBJECT_REQUIRED", "steps"),
                Arguments.of("current groups must be an array", "{\"format\":2,\"steps\":{},\"groups\":{}}", "CREATOR_GROUPS_ARRAY_REQUIRED", "groups"),
                Arguments.of("Step presentation must be an object", creatorStep("true"), "CREATOR_PRESENTATION_OBJECT_REQUIRED", "steps.lowercase-text"),
                Arguments.of("presentation name must be non-blank", creatorStep("{\"name\":\" \"}"), "CREATOR_PRESENTATION_NAME_INVALID", "steps.lowercase-text.name"),
                Arguments.of("presentation color must use six hex digits", creatorStep("{\"color\":\"red\"}"), "CREATOR_PRESENTATION_COLOR_INVALID", "steps.lowercase-text.color"),
                Arguments.of("presentation icon must be an object", creatorStep("{\"icon\":true}"), "CREATOR_PRESENTATION_ICON_INVALID", "steps.lowercase-text.icon"),
                Arguments.of("presentation name must be text", creatorStep("{\"name\":1}"), "CREATOR_PRESENTATION_NAME_INVALID", "steps.lowercase-text.name"),
                Arguments.of("presentation name is bounded", creatorStep("{\"name\":\"" + "x".repeat(129) + "\"}"), "CREATOR_PRESENTATION_NAME_INVALID", "steps.lowercase-text.name"),
                Arguments.of("presentation color must be text", creatorStep("{\"color\":1}"), "CREATOR_PRESENTATION_COLOR_INVALID", "steps.lowercase-text.color"),
                Arguments.of("presentation outcomes must be an object", creatorStep("{\"outcomes\":[]}"), "CREATOR_PRESENTATION_OUTCOMES_INVALID", "steps.lowercase-text.outcomes"),
                Arguments.of("presentation outcome must be connected", creatorStep("{\"outcomes\":{\"missing\":\"Missing\"}}"), "CREATOR_PRESENTATION_OUTCOME_UNKNOWN", "steps.lowercase-text.outcomes.missing"),
                Arguments.of("presentation outcome label must be text", creatorStep("{\"outcomes\":{\"next\":1}}"), "CREATOR_PRESENTATION_OUTCOME_LABEL_INVALID", "steps.lowercase-text.outcomes.next"),
                Arguments.of("presentation outcome label must be non-blank", creatorStep("{\"outcomes\":{\"next\":\" \"}}"), "CREATOR_PRESENTATION_OUTCOME_LABEL_INVALID", "steps.lowercase-text.outcomes.next"),
                Arguments.of("presentation outcome label is bounded", creatorStep("{\"outcomes\":{\"next\":\"" + "x".repeat(129) + "\"}}"), "CREATOR_PRESENTATION_OUTCOME_LABEL_INVALID", "steps.lowercase-text.outcomes.next"),
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
                Arguments.of("legacy Step presentation rejects format-two group assignment", "{\"format\":1,\"steps\":{\"lowercase-text\":{\"group\":\"group-one\"}},\"groups\":[]}", "CREATOR_PRESENTATION_FIELD_UNKNOWN", "steps.lowercase-text.group"),
                Arguments.of("current group rejects unknown fields", "{\"format\":2,\"steps\":{},\"groups\":[{\"id\":\"group-one\",\"noise\":true}]}", "CREATOR_GROUP_FIELD_UNKNOWN", "groups[0].noise"),
                Arguments.of("current group boundary is explicit", "{\"format\":2,\"steps\":{},\"groups\":[{\"id\":\"group-one\",\"boundary\":\"double\"}]}", "CREATOR_GROUP_BOUNDARY_INVALID", "groups[0].boundary"),
                Arguments.of("current Step group must be an id", "{\"format\":2,\"steps\":{\"lowercase-text\":{\"group\":4}},\"groups\":[]}", "CREATOR_STEP_GROUP_INVALID", "steps.lowercase-text.group"),
                Arguments.of("current Step group must exist", "{\"format\":2,\"steps\":{\"lowercase-text\":{\"group\":\"missing\"}},\"groups\":[]}", "CREATOR_STEP_GROUP_UNKNOWN", "steps.lowercase-text.group"),
                Arguments.of("Trigger cannot join a current group", "{\"format\":2,\"steps\":{\"command\":{\"group\":\"group-one\"}},\"groups\":[{\"id\":\"group-one\"}]}", "CREATOR_STEP_GROUP_UNSUPPORTED", "steps.command.group"),
                Arguments.of("current duplicate group ids", "{\"format\":2,\"steps\":{},\"groups\":[{\"id\":\"group-one\"},{\"id\":\"group-one\"}]}", "CREATOR_GROUP_ID_DUPLICATE", "groups[1].id"),
                Arguments.of("current group must be an object", "{\"format\":2,\"steps\":{},\"groups\":[true]}", "CREATOR_GROUP_OBJECT_REQUIRED", "groups[0]"),
                Arguments.of("current group id must be non-blank", "{\"format\":2,\"steps\":{},\"groups\":[{\"id\":\"\"}]}", "CREATOR_ID_INVALID", "groups[0].id"),
                Arguments.of("current group color must use six hex digits", "{\"format\":2,\"steps\":{},\"groups\":[{\"id\":\"group-one\",\"color\":\"red\"}]}", "CREATOR_PRESENTATION_COLOR_INVALID", "groups[0].color"),
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
                Arguments.of("unsupported format", "{\"format\":3,\"steps\":{},\"groups\":[]}", "CREATOR_FORMAT_UNSUPPORTED", "format"),
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
    void connectedLegacyBranchRegionMigratesToFlatStepAssignments() throws Exception {
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
                    "\"format\":2",
                    "\"id\":\"group-one\"",
                    "\"choice\":{\"group\":\"group-one\"}",
                    "\"matched\":{\"group\":\"group-one\"}",
                    "\"otherwise\":{\"group\":\"group-one\"}"
            ).doesNotContain("occurrences", "slot-choice", "slot-match", "slot-other");
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
    void lowercaseExampleRunsInTheGeneratedApplicationAndIsVisibleThroughCreator() throws Exception {
        final Path project = directory.resolve("project.json");
        Files.copy(lowercaseExampleProject(), project);

        try (CreatorServer creator = start(project)) {
            final HttpResponse<String> response = awaitExampleView(creator.baseUri(), "command:0");

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
    void mmlOverridesLegacyScoresRegardlessOfDirectoryListingOrder() throws Exception {
        final Path home = directory.resolve("sound-home");
        final Path sounds = Files.createDirectories(home.resolve("sounds"));
        for (int index = 0; index < 8; index++) {
            Files.writeString(sounds.resolve("custom-" + index + ".json"),
                    "name: Legacy " + index + "\ntempo: 120\nsine .2 .01 .1 | c4");
            Files.writeString(sounds.resolve("custom-" + index + ".mml"),
                    "name: Current " + index + "\ntempo: 120\nsine .2 .01 .1 | c4");
        }
        try (CreatorServer creator = start(directory.resolve("project.json"), home)) {
            final var listing = request(creator.baseUri(), "GET", "/api/sounds", "");
            assertThat(listing.statusCode()).isEqualTo(200);
            for (int index = 0; index < 8; index++) {
                assertThat(listing.body()).contains("Current " + index).doesNotContain("Legacy " + index);
            }
        }
    }

    @Test
    void soundEditsAcrossCreatorsAndExternalFilesRejectStaleContent() throws Exception {
        final Path home = directory.resolve("shared-sound-home");
        final String score = "name: First\ntempo: 120\nsine .2 .01 .1 | c4";
        try (CreatorServer first = start(directory.resolve("first/project.json"), home);
             CreatorServer second = start(directory.resolve("second/project.json"), home);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var revision = object(request(first.baseUri(), "GET", "/api/sounds", "").body()).values().get("revision");
            assertThat(object(request(second.baseUri(), "GET", "/api/sounds", "").body()).values().get("revision"))
                    .isEqualTo(revision);
            final String body = RailixJson.write(RailixValue.object(java.util.Map.of(
                    "action", RailixValue.string("save"), "id", RailixValue.string("sounds/shared.mml"),
                    "revision", revision, "content", RailixValue.string(score))));
            final var gate = new CountDownLatch(1);
            final var left = executor.submit(() -> {
                gate.await();
                return request(first.baseUri(), "POST", "/api/sounds", body);
            });
            final var right = executor.submit(() -> {
                gate.await();
                return request(second.baseUri(), "POST", "/api/sounds", body.replace("First", "Second"));
            });
            gate.countDown();
            final var a = left.get(10, TimeUnit.SECONDS);
            final var b = right.get(10, TimeUnit.SECONDS);
            assertThat(List.of(a.statusCode(), b.statusCode())).containsExactlyInAnyOrder(200, 409);
            final String winner = a.statusCode() == 200 ? score : score.replace("First", "Second");
            final Path file = home.resolve("sounds/shared.mml");
            assertThat(Files.readString(file)).isEqualTo(winner);
            final var current = object(request(second.baseUri(), "GET", "/api/sounds", "").body()).values().get("revision");
            final String stale = body.replace("\"revision\":" + RailixJson.write(revision),
                    "\"revision\":" + RailixJson.write(current));
            final String external = score.replace("First", "External");
            Files.writeString(file, external);
            assertThat(request(first.baseUri(), "POST", "/api/sounds", stale).statusCode()).isEqualTo(409);
            assertThat(Files.readString(file)).isEqualTo(external);
        }
    }

    @Test
    void concurrentSettingsWritesDoNotLoseAnAcceptedChange() throws Exception {
        final Path home = directory.resolve("shared-home");
        try (CreatorServer first = start(directory.resolve("first/project.json"), home);
             CreatorServer second = start(directory.resolve("second/project.json"), home);
             var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            final var snapshot = object(request(first.baseUri(), "GET", "/api/settings", "").body());
            final String source = RailixJson.write(snapshot.values().get("values"));
            final String prefix = "{\"revision\":\"" + string(snapshot, "revision") + "\",\"values\":";
            final var gate = new java.util.concurrent.CountDownLatch(1);
            final var left = executor.submit(() -> {
                gate.await();
                return request(first.baseUri(), "POST", "/api/settings", prefix + source.replace("\"theme\":\"\"", "\"theme\":\"first.css\"") + "}");
            });
            final var right = executor.submit(() -> {
                gate.await();
                return request(second.baseUri(), "POST", "/api/settings", prefix + source.replace("\"theme\":\"\"", "\"theme\":\"second.css\"") + "}");
            });
            gate.countDown();
            final var a = left.get(10, java.util.concurrent.TimeUnit.SECONDS);
            final var b = right.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(List.of(a.statusCode(), b.statusCode())).containsExactlyInAnyOrder(200, 409);
            final var winner = object(a.statusCode() == 200 ? a.body() : b.body());
            assertThat(object(request(second.baseUri(), "GET", "/api/settings", "").body()).values().get("values"))
                    .isEqualTo(winner.values().get("values"));
        }
    }
    @Test
    void creatorWideCatalogsAndSettingsPersistWithoutChangingTheApplicationOrMetadata() throws Exception {
        final Path home = directory.resolve("home");
        final Path project = directory.resolve("project.json");
        final Path metadata = directory.resolve("railix.creator.json");
        Files.createDirectories(home.resolve("themes"));
        Files.createDirectories(home.resolve("sounds/music"));
        Files.writeString(home.resolve("themes/local.css"), "body { color: #123; }");
        Files.writeString(home.resolve("sounds/idle.json"), """
                {"version":1,"name":"Local idle","tempo":120,"tracks":[{"waveform":"sine","volume":0.3,"attack":0.01,"release":0.1,"notes":[{"note":60,"duration":0.5}]}]}
                """);
        Files.writeString(home.resolve("sounds/music/custom.json"), """
                {"version":1,"name":"Custom music","tempo":120,"tracks":[{"waveform":"sine","volume":0.3,"attack":0.01,"release":0.1,"notes":[{"note":60,"duration":0.5}]}]}
                """);

        final String revision;
        final long pid;
        try (CreatorServer creator = start(project, home)) {
            pid = number(application(creator.baseUri()), "pid");
            final String originalMetadata = Files.readString(metadata);
            final RailixValue.ObjectValue themes = object(request(creator.baseUri(), "GET", "/api/themes", "").body());
            final RailixValue.ObjectValue sounds = object(request(creator.baseUri(), "GET", "/api/sounds", "").body());
            final RailixValue.ObjectValue settings = object(request(creator.baseUri(), "GET", "/api/settings", "").body());
            revision = string(settings, "revision");

            assertThat(RailixJson.write(themes.values().get("themes"))).contains(
                    "\"id\":\"\"", "\"name\":\"Railix Foundry\"", "\"builtin\":true", "\"url\":\"/api/themes/files/foundry/theme.css\"",
                    "\"id\":\"local.css\"", "\"builtin\":false", "\"url\":\"/api/themes/files/local.css\""
            );
            assertThat(RailixJson.write(sounds.values().get("scores"))).contains(
                    "\"id\":\"sounds/idle.mml\"", "\"key\":\"builtin:sounds/idle.mml\"", "\"key\":\"local:sounds/idle.mml\"",
                    "\"key\":\"builtin:music/orbital.mml\"", "\"key\":\"local:music/custom.mml\""
            );
            assertThat(RailixJson.write(sounds.values().get("events"))).contains(
                    "\"id\":\"idle\"", "\"default\":\"builtin:sounds/idle.mml\"");

            final String values = """
                    {"theme":"missing.css","reduced_motion":true,"effects":false,"effects_volume":0.5,"music_volume":0.4,"music":"track:local:music/missing.json","sounds":{"idle":"local:idle.json"}}
                    """;
            final HttpResponse<String> saved = request(creator.baseUri(), "POST", "/api/settings",
                    "{\"revision\":\"" + revision + "\",\"values\":" + values + "}");

            assertThat(saved.statusCode()).isEqualTo(200);
            assertThat(Files.readString(home.resolve("creator.settings.json"))).contains("missing.css", "local:sounds/idle.mml");
            assertThat(Files.readString(metadata)).isEqualTo(originalMetadata);
            assertThat(number(application(creator.baseUri()), "pid")).isEqualTo(pid);
            assertThat(request(creator.baseUri(), "POST", "/api/settings",
                    "{\"revision\":\"" + revision + "\",\"values\":" + values + "}").statusCode()).isEqualTo(409);
        }

        try (CreatorServer reopened = start(project, home)) {
            final RailixValue.ObjectValue values = (RailixValue.ObjectValue) object(
                    request(reopened.baseUri(), "GET", "/api/settings", "").body()
            ).values().get("values");
            assertThat(string(values, "theme")).isEqualTo("missing.css");
            assertThat(string(values, "music")).isEqualTo("track:local:music/missing.mml");
        }
    }

    @Test
    void settingsRejectInvalidOrUnauthenticatedRequestsAndSymlinkedPersistence() throws Exception {
        final Path home = directory.resolve("home");
        Files.createDirectories(home);
        final String defaults = "{\"theme\":\"\",\"reduced_motion\":false,\"effects\":true,\"effects_volume\":0.35,\"music_volume\":0.25,\"music\":\"\",\"sounds\":{}}";
        try (CreatorServer creator = start(directory.resolve("project.json"), home);
             HttpClient client = HttpClient.newHttpClient()) {
            final RailixValue.ObjectValue settings = object(request(creator.baseUri(), "GET", "/api/settings", "").body());
            final long pid = number(application(creator.baseUri()), "pid");
            final String request = "{\"revision\":\"" + string(settings, "revision") + "\",\"values\":" + defaults + "}";
            final HttpResponse<String> unauthenticated = client.send(HttpRequest.newBuilder(creator.baseUri().resolve("/api/settings"))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(request))
                    .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            final HttpResponse<String> invalid = request(creator.baseUri(), "POST", "/api/settings",
                    "{\"revision\":\"" + string(settings, "revision") + "\",\"values\":{}}");

            assertThat(unauthenticated.statusCode()).isEqualTo(401);
            assertThat(invalid.statusCode()).isEqualTo(400);
            assertThat(number(application(creator.baseUri()), "pid")).isEqualTo(pid);
        }
        Files.writeString(home.resolve("creator.settings.json"), "{");
        try (CreatorServer creator = start(directory.resolve("project.json"), home)) {
            final RailixValue.ObjectValue settings = object(request(creator.baseUri(), "GET", "/api/settings", "").body());
            assertThat(RailixJson.write(settings.values().get("diagnostics"))).contains("invalid-settings");
            assertThat(string((RailixValue.ObjectValue) settings.values().get("values"), "theme")).isEmpty();
        }
        Files.delete(home.resolve("creator.settings.json"));
        Files.createSymbolicLink(home.resolve("creator.settings.json"), directory.resolve("outside.json"));
        try (CreatorServer creator = start(directory.resolve("project.json"), home)) {
            final RailixValue.ObjectValue settings = object(request(creator.baseUri(), "GET", "/api/settings", "").body());
            assertThat(request(creator.baseUri(), "POST", "/api/settings", "{\"revision\":\""
                    + string(settings, "revision") + "\",\"values\":" + defaults + "}").statusCode()).isEqualTo(400);
        }
    }

    @Test
    void soundsListBundledScoresAndIgnoreBinaryOrInvalidEntries() throws Exception {
        final Path home = directory.resolve("home");
        try (CreatorServer creator = start(directory.resolve("project.json"), home)) {
            Files.writeString(home.resolve("sounds/legacy.wav"), "not a score");
            Files.writeString(home.resolve("sounds/broken.json"), "{");

            final var response = request(creator.baseUri(), "GET", "/api/sounds", "");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("Wrong Database", "Temporary Forever", "Circuit Drive", "\"version\":2", "sounds/broken.mml")
                    .doesNotContain("legacy.wav");
        }
    }

    @Test
    void soundsUseSeparateRootsAndNormalizeLegacyScoresWithoutWritingThem() throws Exception {
        final Path home = directory.resolve("home");
        final String legacy = "name: Legacy\ntempo: 120\nsine .3 .01 .1 | o4 c4";
        Files.createDirectories(home.resolve("sounds/music"));
        Files.writeString(home.resolve("sounds/legacy.json"), legacy);
        Files.writeString(home.resolve("sounds/music/legacy.json"), legacy.replace("Legacy", "Legacy music"));

        try (CreatorServer creator = start(directory.resolve("project.json"), home)) {
            final HttpResponse<String> listing = request(creator.baseUri(), "GET", "/api/sounds", "");
            final HttpResponse<String> installed = soundMutation(creator.baseUri(), "{\"action\":\"install-defaults\"}");

            assertThat(listing.body()).contains("\"id\":\"sounds/legacy.mml\"", "\"id\":\"music/legacy.mml\"");
            assertThat(installed.statusCode()).isEqualTo(200);
            assertThat(home.resolve("sounds/legacy.json")).exists();
            assertThat(home.resolve("sounds/music/legacy.json")).exists();
            assertThat(home.resolve("sounds/idle.mml")).exists();
            assertThat(home.resolve("music/orbital.mml")).exists();
        }
    }

    @Test
    void soundsRequireAuthenticatedValidatedCrudAndDoNotOverwriteDefaults() throws Exception {
        final Path home = directory.resolve("home");
        final String score = """
                {"version":1,"name":"Shift","tempo":120,"tracks":[{"waveform":"sine","volume":0.3,"attack":0.01,"release":0.1,"notes":[{"note":60,"duration":0.5}]}]}
        """;
        try (HttpClient client = HttpClient.newHttpClient(); CreatorServer creator = start(directory.resolve("project.json"), home)) {
            final var grouped = soundMutation(creator.baseUri(), "{\"action\":\"save\",\"id\":\"music/foundry/shift.mml\",\"score\":" + score + "}");
            final var unauthenticated = client.send(HttpRequest.newBuilder(creator.baseUri().resolve("/api/sounds"))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"action\":\"install-defaults\"}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            final var saved = soundMutation(creator.baseUri(), "{\"action\":\"save\",\"id\":\"music/foundry/shift.mml\",\"score\":" + score + "}");
            final var invalid = soundMutation(creator.baseUri(), "{\"action\":\"save\",\"id\":\"sounds/bad.mml\",\"score\":{}}");

            assertThat(unauthenticated.statusCode()).isEqualTo(401);
            assertThat(grouped.statusCode()).isEqualTo(200);
            assertThat(saved.statusCode()).isEqualTo(200);
            assertThat(Files.readString(home.resolve("music/foundry/shift.mml"))).contains("Shift");
            assertThat(invalid.statusCode()).isEqualTo(400);
            assertThat(Files.exists(home.resolve("sounds/bad.mml"))).isFalse();
        }
    }

    @Test
    void mmlToneParametersRoundTripAndRejectInvalidOrDuplicateControls() throws Exception {
        final String controls = "decay=.18 sustain=.2 cutoff=1800 detune=7 drive=2 pan=-.3 echo=.2";
        final String source = "name: Pluck\ntempo: 108\nsawtooth .2 .004 .12 " + controls + " | o4 a4 e4";
        try (CreatorServer creator = start(directory.resolve("project.json"))) {
            final String body = RailixJson.write(RailixValue.object(java.util.Map.of("action", RailixValue.string("preview"),
                    "content", RailixValue.string(source))));
            final var accepted = request(creator.baseUri(), "POST", "/api/sounds", body);
            assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(200);
            assertThat(accepted.body()).contains("\"decay\":0.18", "\"sustain\":0.2", "\"cutoff\":1800",
                    "\"detune\":7", "\"drive\":2", "\"pan\":-0.3", "\"echo\":0.2");
            for (final String invalid : List.of("decay=-1", "sustain=2", "cutoff=NaN", "cutoff=Infinity",
                    "cutoff=0", "cutoff=16001", "cutoff=800 cutoff=1200", "unknown=1", "detune=-1",
                    "detune=31", "drive=9", "pan=1.1", "echo=.6", "pan=NaN", "echo=.1 echo=.2")) {
                final String invalidBody = body.replace(controls, invalid);
                assertThat(request(creator.baseUri(), "POST", "/api/sounds", invalidBody).statusCode()).as(invalid).isEqualTo(400);
            }
            for (final String percussion : List.of("snare", "hat")) {
                final var invalid = request(creator.baseUri(), "POST", "/api/sounds", body.replace("sawtooth", percussion));
                assertThat(invalid.statusCode()).isEqualTo(400);
                assertThat(invalid.body()).contains("detune requires a pitched instrument");
            }
        }
    }

    @Test
    void longMmlScoresKeepRepeatsCompactWhenSavedAndLoaded() throws Exception {
        final Path home = directory.resolve("home");
        final String source = "name: Long arrangement\ntempo: 120\nsawtooth .2 .01 .1 | o4 /: /: c8 e8 g8 e8 :/16 :/16";
        try (CreatorServer creator = start(directory.resolve("project.json"), home)) {
            final String body = RailixJson.write(RailixValue.object(java.util.Map.of("action", RailixValue.string("save"),
                    "id", RailixValue.string("music/long.mml"), "content", RailixValue.string(source))));
            final var saved = soundMutation(creator.baseUri(), body);
            assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);
            assertThat(Files.readString(home.resolve("music/long.mml"))).isEqualTo(source);
            final var preview = request(creator.baseUri(), "POST", "/api/sounds",
                    "{\"action\":\"preview\",\"content\":" + RailixJson.write(RailixValue.string(source)) + "}");
            assertThat(preview.statusCode()).as(preview.body()).isEqualTo(200);
            assertThat(preview.body()).contains("\"repeat\":16").hasSizeLessThan(1200);
        }
    }

    @Test
    void soundsAcceptBoundedMmlAndRejectCopyCollisions() throws Exception {
        final Path home = directory.resolve("home");
        final String mml = "name: Bell\ntempo: 100\nsine .2 .01 .1 | o4 l8 /: c e g e :/4";
        try (CreatorServer creator = start(directory.resolve("project.json"), home)) {
            final String request = "{\"action\":\"copy\",\"id\":\"music/bell.mml\",\"content\":\""
                    + mml.replace("\\", "\\\\").replace("\n", "\\n") + "\"}";
            assertThat(soundMutation(creator.baseUri(), request).statusCode()).isEqualTo(200);
            assertThat(Files.readString(home.resolve("music/bell.mml"))).isEqualTo(mml);
            assertThat(soundMutation(creator.baseUri(), request).statusCode()).isEqualTo(400);
            final var emptyRepeat = request(creator.baseUri(), "POST", "/api/sounds", """
                    {"action":"preview","content":"name: Empty\\ntempo: 100\\nsine .2 .01 .1 | /: :/16"}
                    """);
            final var lateHeader = request(creator.baseUri(), "POST", "/api/sounds", """
                    {"action":"preview","content":"name: Late\\nsine .2 .01 .1 | t120 c\\ntempo: 120"}
                    """);
            assertThat(emptyRepeat).extracting(HttpResponse::statusCode, HttpResponse::body)
                    .containsExactly(400, "{\"message\":\"MML repeat bodies require at least one note or rest.\",\"status\":\"invalid-sound\"}");
            assertThat(lateHeader).extracting(HttpResponse::statusCode, HttpResponse::body)
                    .containsExactly(400, "{\"message\":\"MML headers must appear before instrument lines.\",\"status\":\"invalid-sound\"}");
        }
    }

    @Test
    void soundMutationsRejectTraversalLinksAndMissingFilesAndInstallWithoutOverwriting() throws Exception {
        final Path home = directory.resolve("home");
        final String idle = """
                {"version":1,"name":"Local idle","tempo":120,"tracks":[{"waveform":"sine","volume":0.3,"attack":0.01,"release":0.1,"notes":[{"note":60,"duration":0.5}]}]}
                """;
        try (CreatorServer creator = start(directory.resolve("project.json"), home)) {
            Files.writeString(home.resolve("sounds/idle.json"), idle);
            final Path outside = Files.createDirectories(directory.resolve("outside"));
            Files.createSymbolicLink(home.resolve("music/foreign"), outside);

            final var traversal = soundMutation(creator.baseUri(), "{\"action\":\"save\",\"id\":\"music/../escape.mml\",\"score\":" + idle + "}");
            final var linked = soundMutation(creator.baseUri(), "{\"action\":\"save\",\"id\":\"music/foreign/escape.mml\",\"score\":" + idle + "}");
            final var missing = soundMutation(creator.baseUri(), "{\"action\":\"delete\",\"id\":\"sounds/missing.mml\"}");
            final var missingGroup = soundMutation(creator.baseUri(), "{\"action\":\"delete\",\"id\":\"music/absent/missing.mml\"}");
            assertThat(missingGroup.statusCode()).isEqualTo(404);
            assertThat(Files.exists(home.resolve("music/absent"))).isFalse();
            final var defaults = soundMutation(creator.baseUri(), "{\"action\":\"install-defaults\"}");

            assertThat(traversal.statusCode()).isEqualTo(400);
            assertThat(linked.statusCode()).isEqualTo(400);
            assertThat(missing.statusCode()).isEqualTo(404);
            assertThat(defaults.statusCode()).isEqualTo(200);
            assertThat(Files.readString(home.resolve("sounds/idle.json"))).isEqualTo(idle);
            assertThat(Files.exists(home.resolve("music/orbital.mml"))).isTrue();
            assertThat(Files.exists(home.resolve("music/quiet/lantern.mml"))).isTrue();
        }
    }

    @Test
    void soundMutationsRejectStaleRevisionsWithoutWriting() throws Exception {
        final Path home = directory.resolve("home");
        final String score = """
                {"version":1,"name":"Stale","tempo":120,"tracks":[{"waveform":"sine","volume":0.3,"attack":0.01,"release":0.1,"notes":[{"note":60,"duration":0.5}]}]}
                """;
        try (CreatorServer creator = start(directory.resolve("project.json"), home)) {
            final var revision = object(request(creator.baseUri(), "GET", "/api/sounds", "").body()).values().get("revision");
            assertThat(soundMutation(creator.baseUri(), "{\"action\":\"install-defaults\"}").statusCode()).isEqualTo(200);

            final var stale = request(creator.baseUri(), "POST", "/api/sounds", "{\"action\":\"save\",\"revision\":"
                    + RailixJson.write(revision) + ",\"id\":\"sounds/stale.mml\",\"score\":" + score + "}");

            assertThat(stale.statusCode()).isEqualTo(409);
            assertThat(Files.exists(home.resolve("sounds/stale.mml"))).isFalse();
        }
    }

    @Test
    void soundMutationsRejectEmptyOrNearZeroDurationTracks() throws Exception {
        final Path home = directory.resolve("home");
        final String empty = """
                {"version":1,"name":"Empty","tempo":120,"tracks":[{"waveform":"sine","volume":0.3,"attack":0.01,"release":0.1,"notes":[]}]}
                """;
        final String tiny = """
                {"version":1,"name":"Tiny","tempo":120,"tracks":[{"waveform":"sine","volume":0.3,"attack":0.01,"release":0.1,"notes":[{"note":60,"duration":0.01}]}]}
                """;
        try (CreatorServer creator = start(directory.resolve("project.json"), home)) {
            final var emptyResponse = soundMutation(creator.baseUri(), "{\"action\":\"save\",\"id\":\"sounds/empty.mml\",\"score\":" + empty + "}");
            final var tinyResponse = soundMutation(creator.baseUri(), "{\"action\":\"save\",\"id\":\"sounds/tiny.mml\",\"score\":" + tiny + "}");

            assertThat(emptyResponse.statusCode()).isEqualTo(400);
            assertThat(tinyResponse.statusCode()).isEqualTo(400);
            assertThat(Files.exists(home.resolve("sounds/empty.mml"))).isFalse();
            assertThat(Files.exists(home.resolve("sounds/tiny.mml"))).isFalse();
        }
    }


    @Test
    void themesNewProjectRecordsCreationTimeAndRetainsItOnReload() throws Exception {
        final Path project = directory.resolve("project.json");
        final long before = System.currentTimeMillis();
        final long created;
        try (CreatorServer creator = start(project)) {
            final HttpResponse<String> response = request(creator.baseUri(), "GET", "/api/project", "");
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(((RailixValue.ObjectValue) object(response.body()).values().get("creator")).values())
                    .containsKey("created_at");
            created = number((RailixValue.ObjectValue) object(response.body()).values().get("creator"), "created_at");
            assertThat(created).isBetween(before, System.currentTimeMillis());
            assertThat(number(object(Files.readString(directory.resolve("railix.creator.json"))), "created_at"))
                    .isEqualTo(created);
        }
        try (CreatorServer creator = start(project)) {
            final HttpResponse<String> response = request(creator.baseUri(), "GET", "/api/project", "");
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(number((RailixValue.ObjectValue) object(response.body()).values().get("creator"), "created_at"))
                    .isEqualTo(created);
            assertThat(number(object(Files.readString(directory.resolve("railix.creator.json"))), "created_at"))
                    .isEqualTo(created);
        }
    }

    @Test
    void themesExistingProjectWithoutMetadataKeepsCreationTimeUnknown() throws Exception {
        final Path project = directory.resolve("project.json");
        Files.writeString(project, CreatorProjects.empty("existing-project"));
        for (int opening = 0; opening < 2; opening++) {
            try (CreatorServer creator = start(project)) {
                final HttpResponse<String> response = request(creator.baseUri(), "GET", "/api/project", "");
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(((RailixValue.ObjectValue) object(response.body()).values().get("creator")).values())
                        .doesNotContainKey("created_at");
                assertThat(object(Files.readString(directory.resolve("railix.creator.json"))).values())
                        .doesNotContainKey("created_at");
            }
        }
    }

    @Test
    void themesHtmlPolicyRestrictsForeignResourcesAndPreservesLocalAssets() throws Exception {
        final String policy = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
                + "img-src 'self' data: blob:; font-src 'self' data:; media-src 'none'; connect-src 'self'; object-src 'none'; "
                + "base-uri 'none'; form-action 'self'; frame-src 'none'";
        try (CreatorServer creator = start(directory.resolve("project.json"))) {
            for (final String path : List.of("/", "/index.html")) {
                final HttpResponse<String> response = request(creator.baseUri(), "GET", path, "");
                assertThat(response.statusCode()).as(path).isEqualTo(200);
                assertThat(response.headers().firstValue("Content-Security-Policy")).contains(policy);
            }
            for (final String path : List.of("/app.css", "/themes/railix.css", "/themes/classic.css", "/app.js", "/world.js", "/audio.js", "/api/themes")) {
                final HttpResponse<String> response = request(creator.baseUri(), "GET", path, "");
                assertThat(response.statusCode()).as(path).isEqualTo(200);
                assertThat(response.headers().firstValue("Content-Security-Policy")).isEmpty();
            }
        }
    }

    @Test
    void themesDiscoverRecursiveRelativeIdsAndRediscoverAddedFilesWithoutRebuilding() throws Exception {
        final Path home = directory.resolve("railix-home");
        final Path project = directory.resolve("project.json");
        try (CreatorServer creator = start(project, home)) {
            final Path themes = home.resolve("themes");
            assertThat(themes).isDirectory();
            final long pid = number(application(creator.baseUri()), "pid");
            final String persisted = Files.readString(project);
            final String metadata = Files.readString(directory.resolve("railix.creator.json"));
            final HttpResponse<String> empty = request(creator.baseUri(), "GET", "/api/themes", "");
            assertThat(empty.statusCode()).isEqualTo(200);
            assertThat(string(object(empty.body()), "directory")).isEqualTo(themes.toRealPath().toString());
            assertThat(RailixJson.write(object(empty.body()).values().get("themes"))).contains(
                    "\"id\":\"\"", "\"url\":\"/api/themes/files/foundry/theme.css\"", "\"defaultVariant\":\"canvas\"",
                    "\"id\":\"classic/theme.css\"", "\"url\":\"/api/themes/files/classic/theme.css\""
            );

            Files.createDirectories(themes.resolve("nested"));
            Files.createDirectories(themes.resolve("directory.css"));
            Files.writeString(themes.resolve("nested/dusk.css"), "body { color: blue; }");
            Files.writeString(themes.resolve("dusk.css"), "body { color: red; }");
            Files.writeString(themes.resolve("ignored.txt"), "not a stylesheet");
            final HttpResponse<String> discovered = request(creator.baseUri(), "GET", "/api/themes", "");
            assertThat(discovered.statusCode()).isEqualTo(200);
            assertThat(RailixJson.write(object(discovered.body()).values().get("themes"))).contains(
                    "\"id\":\"\"", "\"id\":\"dusk.css\"", "\"id\":\"nested/dusk.css\"",
                    "\"url\":\"/api/themes/files/nested/dusk.css\""
            );
            assertThat(number(application(creator.baseUri()), "pid")).isEqualTo(pid);
            assertThat(Files.readString(project)).isEqualTo(persisted);
            assertThat(Files.readString(directory.resolve("railix.creator.json"))).isEqualTo(metadata);
        }
    }

    @Test
    void themesDiscoverDeepSubfoldersWithoutRetainingAncestorHandles() throws Exception {
        final Path home = directory.resolve("railix-home");
        final Path themes = Files.createDirectories(home.resolve("themes"));
        final int depth = 192;
        Path deepest = themes;
        try {
            for (int level = 0; level < depth; level++) {
                deepest = Files.createDirectory(deepest.resolve("d"));
            }
            Files.writeString(deepest.resolve("deep.css"), "body { color: teal; }");
            final String id = "d/".repeat(depth) + "deep.css";
            try (CreatorServer creator = start(directory.resolve("project.json"), home)) {
                final long pid = number(application(creator.baseUri()), "pid");
                for (int read = 0; read < 2; read++) {
                    final HttpResponse<String> listing = request(creator.baseUri(), "GET", "/api/themes", "");
                    assertThat(listing.statusCode()).as(listing.body()).isEqualTo(200);
                    assertThat(RailixJson.write(object(listing.body()).values().get("themes"))).contains(
                            "\"id\":\"\"", "\"id\":\"" + id + "\"");
                    final HttpResponse<String> stylesheet = request(creator.baseUri(), "GET",
                            "/api/themes?file=" + URLEncoder.encode(id, StandardCharsets.UTF_8), "");
                    assertThat(stylesheet.statusCode()).as(stylesheet.body()).isEqualTo(200);
                    assertThat(stylesheet.body()).isEqualTo("body { color: teal; }");
                }
                assertThat(number(application(creator.baseUri()), "pid")).isEqualTo(pid);
            }
        } finally {
            Files.deleteIfExists(deepest.resolve("deep.css"));
            // Keep fixture cleanup within the same descriptor budget as the HTTP regression.
            while (!deepest.equals(themes)) {
                Files.delete(deepest);
                deepest = deepest.getParent();
            }
        }
    }

    @Test
    void themesDiscoveryExcludesNoncanonicalFileAndDirectoryNames() throws Exception {
        final Path home = directory.resolve("railix-home");
        final Path themes = Files.createDirectories(home.resolve("themes"));
        Files.writeString(themes.resolve(".hidden.css"), "body {}");
        Files.writeString(Files.createDirectory(themes.resolve(" ")).resolve("valid.css"), "body {}");
        Files.writeString(Files.createDirectory(themes.resolve("a..b")).resolve("valid.css"), "body {}");
        for (final String name : List.of("line\nbreak", "tab\tstop", "delete" + (char) 127,
                "control" + (char) 133, "back\\slash")) {
            Files.writeString(themes.resolve(name + ".css"), "invalid-name-content");
            Files.writeString(Files.createDirectory(themes.resolve(name)).resolve("nested.css"), "invalid-name-content");
        }
        try (CreatorServer creator = start(directory.resolve("project.json"), home)) {
            final HttpResponse<String> listing = request(creator.baseUri(), "GET", "/api/themes", "");
            assertThat(listing.statusCode()).as(listing.body()).isEqualTo(200);
            assertThat(RailixJson.write(object(listing.body()).values().get("themes"))).contains(
                    "\"id\":\"\"", "\"id\":\" /valid.css\"", "\"id\":\".hidden.css\"", "\"id\":\"a..b/valid.css\"");
        }
    }

    @Test
    void themesRejectNoncanonicalExistingFileAndParentNames() throws Exception {
        final Path home = directory.resolve("railix-home");
        final Path themes = Files.createDirectories(home.resolve("themes"));
        try (CreatorServer creator = start(directory.resolve("project.json"), home)) {
            for (final String name : List.of("line\nbreak", "tab\tstop", "delete" + (char) 127,
                    "control" + (char) 133, "back\\slash")) {
                Files.writeString(themes.resolve(name + ".css"), "invalid-name-content");
                Files.writeString(Files.createDirectory(themes.resolve(name)).resolve("nested.css"), "invalid-name-content");
                for (final String id : List.of(name + ".css", name + "/nested.css")) {
                    final HttpResponse<String> response = request(creator.baseUri(), "GET",
                            "/api/themes?file=" + URLEncoder.encode(id, StandardCharsets.UTF_8), "");
                    assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
                    assertThat(response.body()).contains("invalid-theme", "canonical").doesNotContain("invalid-name-content");
                }
            }
        }
    }

    @Test
    void themesReadEncodedCssFreshWithoutRebuilding() throws Exception {
        final Path home = directory.resolve("railix-home");
        final Path themes = Files.createDirectories(home.resolve("themes/nested"));
        final Path file = themes.resolve("night + day.css");
        final String original = "body { --label: '\u00e9'; color: #234; }\n";
        Files.writeString(file, original);
        try (CreatorServer creator = start(directory.resolve("project.json"), home)) {
            final long pid = number(application(creator.baseUri()), "pid");
            final String path = "/api/themes?file=" + URLEncoder.encode("nested/night + day.css", StandardCharsets.UTF_8);
            final HttpResponse<String> first = request(creator.baseUri(), "GET", path, "");
            assertThat(first.statusCode()).isEqualTo(200);
            assertThat(first.headers().firstValue("Content-Type")).contains("text/css; charset=utf-8");
            assertThat(first.headers().firstValue("Cache-Control")).contains("no-store");
            assertThat(first.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
            assertThat(first.body()).isEqualTo(original);
            Files.writeString(file, "body { color: #abc; }\n");
            assertThat(request(creator.baseUri(), "GET", path, "").body()).isEqualTo("body { color: #abc; }\n");
            Files.writeString(file, "");
            final HttpResponse<String> empty = request(creator.baseUri(), "GET", path, "");
            assertThat(empty.statusCode()).isEqualTo(200);
            assertThat(empty.body()).isEmpty();
            assertThat(number(application(creator.baseUri()), "pid")).isEqualTo(pid);
        }
    }

    @Test
    void themesRejectMissingFilesAndNoncanonicalPaths() throws Exception {
        final Path home = directory.resolve("railix-home");
        final Path themes = Files.createDirectories(home.resolve("themes"));
        Files.writeString(themes.resolve("present.css"), "body {}");
        Files.createDirectory(themes.resolve("folder.css"));
        Files.writeString(home.resolve("outside.css"), "outside-content");
        try (CreatorServer creator = start(directory.resolve("project.json"), home)) {
            for (final String file : List.of("absent.css", "folder.css", "absent/child.css")) {
                final HttpResponse<String> response = request(creator.baseUri(), "GET", "/api/themes?file=" + file, "");
                assertThat(response.statusCode()).as(file).isEqualTo(404);
                assertThat(response.body()).contains("theme-not-found");
            }
            for (final String file : List.of("", "../outside.css", "nested/../../outside.css", "./present.css",
                    "nested/../present.css", "/outside.css", "nested//present.css", "present.css/", "present.txt",
                    "nested\\present.css", "present.css" + (char) 0, "a/".repeat(2048) + "present.css")) {
                final HttpResponse<String> response = request(creator.baseUri(), "GET",
                        "/api/themes?file=" + URLEncoder.encode(file, StandardCharsets.UTF_8), "");
                assertThat(response.statusCode()).as(file).isEqualTo(400);
                assertThat(response.body()).contains("invalid-theme").doesNotContain("outside-content");
            }
            for (final String query : List.of("file=present.css&file=present.css", "unknown=present.css")) {
                final HttpResponse<String> response = request(creator.baseUri(), "GET", "/api/themes?" + query, "");
                assertThat(response.statusCode()).as(query).isEqualTo(400);
                assertThat(response.body()).contains("invalid-theme");
            }
        }
    }

    @Test
    void themesSkipSymlinksAndRefuseReadsThroughLinkedFilesOrParents() throws Exception {
        final Path home = directory.resolve("railix-home");
        final Path themes = Files.createDirectories(home.resolve("themes"));
        final Path outside = Files.createDirectory(directory.resolve("outside"));
        Files.writeString(outside.resolve("secret.css"), "outside-content");
        Files.writeString(themes.resolve("safe.css"), "body {}");
        Files.createSymbolicLink(themes.resolve("linked.css"), outside.resolve("secret.css"));
        Files.createSymbolicLink(themes.resolve("nested"), outside);
        Files.createSymbolicLink(themes.resolve("internal.css"), themes.resolve("safe.css"));
        Files.createSymbolicLink(themes.resolve("dangling.css"), outside.resolve("absent.css"));
        try (CreatorServer creator = start(directory.resolve("project.json"), home)) {
            final HttpResponse<String> listing = request(creator.baseUri(), "GET", "/api/themes", "");
            assertThat(listing.statusCode()).isEqualTo(200);
            assertThat(RailixJson.write(object(listing.body()).values().get("themes")))
                    .contains("\"id\":\"\"", "\"id\":\"safe.css\"");
            for (final String file : List.of("linked.css", "nested/secret.css", "internal.css", "dangling.css")) {
                final HttpResponse<String> response = request(creator.baseUri(), "GET", "/api/themes?file=" + file, "");
                assertThat(response.statusCode()).as(file).isEqualTo(400);
                assertThat(response.body()).contains("invalid-theme", "symbolic").doesNotContain("outside-content");
            }
        }
    }

    @Test
    void themesRefuseDirectoryReplacedWithSymlink() throws Exception {
        final Path home = directory.resolve("railix-home");
        final Path themes = Files.createDirectories(home.resolve("themes"));
        final Path outside = Files.createDirectory(directory.resolve("outside"));
        Files.writeString(outside.resolve("secret.css"), "outside-content");
        try (CreatorServer creator = start(directory.resolve("project.json"), home)) {
            Files.move(themes, home.resolve("original-themes"));
            Files.createSymbolicLink(themes, outside);
            for (final String path : List.of("/api/themes", "/api/themes?file=secret.css")) {
                final HttpResponse<String> response = request(creator.baseUri(), "GET", path, "");
                assertThat(response.statusCode()).as(path).isEqualTo(400);
                assertThat(response.body()).contains("invalid-theme", "symbolic").doesNotContain("outside-content");
            }
        }
    }

    @Test
    void themesRejectOversizedStylesheetWithExplicitDiagnostic() throws Exception {
        final Path home = directory.resolve("railix-home");
        final Path themes = Files.createDirectories(home.resolve("themes"));
        Files.write(themes.resolve("large.css"), new byte[RailixData.DEFAULT_MAX_SOURCE_BYTES + 1]);
        try (CreatorServer creator = start(directory.resolve("project.json"), home)) {
            final HttpResponse<String> response = request(creator.baseUri(), "GET", "/api/themes?file=large.css", "");
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.body()).contains("theme-too-large", "Stylesheet", "byte");
        }
    }

    @Test
    void themesRequireAuthenticationAndRejectUnsupportedMutations() throws Exception {
        final Path home = directory.resolve("railix-home");
        final Path themes = Files.createDirectories(home.resolve("themes"));
        final Path file = themes.resolve("safe.css");
        Files.writeString(file, "body {}");
        try (CreatorServer creator = start(directory.resolve("project.json"), home);
             HttpClient client = HttpClient.newHttpClient()) {
            final long pid = number(application(creator.baseUri()), "pid");
            for (final String path : List.of("/api/themes", "/api/themes?file=safe.css")) {
                final HttpResponse<String> unauthenticated = client.send(
                        HttpRequest.newBuilder(creator.baseUri().resolve(path)).GET().build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                assertThat(unauthenticated.statusCode()).as(path).isEqualTo(401);
                assertThat(unauthenticated.body()).contains("unauthorized");
                assertThat(request(creator.baseUri(), "POST", path, "body { color: red; }").statusCode()).isEqualTo(400);
                for (final String method : List.of("PUT", "PATCH", "DELETE")) {
                    final HttpResponse<String> rejected = request(creator.baseUri(), method, path, "body { color: red; }");
                    assertThat(rejected.statusCode()).as(method + " " + path).isEqualTo(405);
                    assertThat(rejected.body()).contains("method-not-allowed");
                }
            }
            assertThat(Files.readString(file)).isEqualTo("body {}");
            assertThat(number(application(creator.baseUri()), "pid")).isEqualTo(pid);
        }
    }

    @Test
    void themesExposeEmbeddedVariantsAndServeOnlyAllowlistedAssets() throws Exception {
        try (CreatorServer creator = start(directory.resolve("project.json"))) {
            final HttpResponse<String> listing = request(creator.baseUri(), "GET", "/api/themes", "");
            final HttpResponse<String> variant = request(creator.baseUri(), "GET",
                    "/api/themes/files/foundry/variants/hq/style.css", "");
            final HttpResponse<String> missing = request(creator.baseUri(), "GET",
                    "/api/themes/files/not-embedded.css", "");

            assertThat(listing.statusCode()).isEqualTo(200);
            assertThat(listing.body()).contains("\"id\":\"\"", "\"name\":\"Renderer Canvas\"", "\"name\":\"Renderer CSS\"", "\"defaultVariant\":\"canvas\"")
                    .doesNotContain("\"id\":\"standard\"", "\"id\":\"canvas-hq\"");
            assertThat(variant.statusCode()).isEqualTo(200);
            assertThat(variant.headers().firstValue("Content-Type")).contains("text/css; charset=utf-8");
            assertThat(missing.statusCode()).isEqualTo(404);
        }
    }

    @Test
    void themesUseLocalDescriptorAssetsWithoutFollowingLinksAndDiagnoseMalformedDescriptors() throws Exception {
        final Path home = directory.resolve("home");
        final Path themes = Files.createDirectories(home.resolve("themes/studio"));
        Files.writeString(themes.resolve("theme.css"), "body { color: black; }");
        Files.writeString(themes.resolve("theme.json"), """
                {"name":"Studio","variants":[{"id":"night","name":"Night","description":"After hours","stylesheet":"night.css"}],"files":["icons/logo.svg"]}
                """);
        Files.writeString(themes.resolve("night.css"), "body { color: navy; }");
        Files.createDirectories(themes.resolve("icons"));
        Files.writeString(themes.resolve("icons/logo.svg"), "<svg xmlns=\"http://www.w3.org/2000/svg\"/>");
        Files.writeString(themes.resolve("icons/logo+accent.svg"), "<svg xmlns=\"http://www.w3.org/2000/svg\"/>");
        Files.writeString(themes.resolve("broken.css"), "body {}");
        Files.writeString(themes.resolve("broken.json"), "{");
        try (CreatorServer creator = start(directory.resolve("project.json"), home)) {
            final HttpResponse<String> listing = request(creator.baseUri(), "GET", "/api/themes", "");
            final HttpResponse<String> svg = request(creator.baseUri(), "GET",
                    "/api/themes/files/studio%2Ficons%2Flogo.svg", "");

            assertThat(listing.body()).contains("\"id\":\"studio/theme.css\"", "\"id\":\"night\"", "broken.json");
            assertThat(listing.body()).doesNotContain("\"id\":\"studio/night.css\"");
            assertThat(svg.statusCode()).isEqualTo(200);
            assertThat(svg.headers().firstValue("Content-Type")).contains("image/svg+xml");
            assertThat(svg.headers().firstValue("Content-Security-Policy").orElseThrow()).contains("default-src 'none'");
            assertThat(request(creator.baseUri(), "GET", "/api/themes/files/studio/icons/logo+accent.svg", "").statusCode()).isEqualTo(200);
            assertThat(request(creator.baseUri(), "GET", "/api/themes/files/studio%2F..%2Ftheme.css", "").statusCode()).isEqualTo(400);
        }
    }

    @Test
    void themesMergeLocalDescriptorsIntoEmbeddedThemesWithoutChangingTheirIds() throws Exception {
        final Path home = directory.resolve("home");
        final Path themes = Files.createDirectories(home.resolve("themes"));
        Files.createDirectories(themes.resolve("foundry"));
        Files.createDirectories(themes.resolve("classic"));
        Files.writeString(themes.resolve("foundry/theme.css"), "body { color: foundry-local; }");
        Files.writeString(themes.resolve("classic/theme.css"), "body { color: local; }");
        Files.writeString(themes.resolve("classic/theme.json"), """
                {"name":"Local Classic","defaultVariant":"line","variants":[{"id":"line","name":"Line art","stylesheet":"classic-line.css"}],"files":[]}
                """);
        Files.writeString(themes.resolve("classic/classic-line.css"), "body { outline: 1px solid; }");
        Files.createDirectories(themes.resolve("studio"));
        Files.writeString(themes.resolve("studio/night light.css"), "body { color: midnightblue; }");
        try (CreatorServer creator = start(directory.resolve("project.json"), home)) {
            final HttpResponse<String> listing = request(creator.baseUri(), "GET", "/api/themes", "");

            assertThat(listing.body()).contains("\"id\":\"classic/theme.css\"", "\"name\":\"Local Classic\"",
                    "\"builtin\":false", "\"installable\":true", "\"defaultVariant\":\"line\"");
            assertThat(listing.body()).contains("\"builtin\":false,\"defaultVariant\":\"canvas\",\"id\":\"\"", "\"stylesheet\":\"foundry/theme.css\"")
                    .containsOnlyOnce("\"id\":\"\"");
            assertThat(listing.body()).doesNotContain("\"id\":\"classic/classic-line.css\"");
            assertThat(listing.body()).contains("\"url\":\"/api/themes/files/studio/night%20light.css\"");
        }
    }

    @Test
    void themesAssetEndpointRequiresAuthenticationAndRejectsLinkedOrOversizedFiles() throws Exception {
        final Path home = directory.resolve("home");
        final Path themes = Files.createDirectories(home.resolve("themes"));
        final Path outside = directory.resolve("outside.css");
        Files.writeString(outside, "outside-content");
        Files.createSymbolicLink(themes.resolve("linked.css"), outside);
        Files.write(themes.resolve("large.png"), new byte[RailixData.DEFAULT_MAX_SOURCE_BYTES + 1]);
        try (CreatorServer creator = start(directory.resolve("project.json"), home);
             HttpClient client = HttpClient.newHttpClient()) {
            final HttpResponse<String> listing = request(creator.baseUri(), "GET", "/api/themes", "");
            final String cookie = listing.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
            final HttpResponse<String> unauthenticated = client.send(
                    HttpRequest.newBuilder(creator.baseUri().resolve("/api/themes/files/foundry/theme.css")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            final HttpResponse<String> asset = client.send(
                    HttpRequest.newBuilder(creator.baseUri().resolve("/api/themes/files/foundry/theme.css")).header("Cookie", cookie).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            final HttpResponse<String> wrongCookie = client.send(
                    HttpRequest.newBuilder(creator.baseUri().resolve("/api/themes/files/foundry/theme.css")).header("Cookie", cookie + "x").GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            final HttpResponse<String> crossSite = client.send(
                    HttpRequest.newBuilder(creator.baseUri().resolve("/api/themes/files/foundry/theme.css"))
                            .header("Cookie", cookie).header("Sec-Fetch-Site", "cross-site").GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            final HttpResponse<String> project = client.send(
                    HttpRequest.newBuilder(creator.baseUri().resolve("/api/project")).header("Cookie", cookie).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            final HttpResponse<String> mutation = client.send(
                    HttpRequest.newBuilder(creator.baseUri().resolve("/api/themes")).header("Cookie", cookie)
                            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"action\":\"install\",\"id\":\"\"}"))
                            .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            assertThat(unauthenticated.statusCode()).isEqualTo(401);
            assertThat(listing.headers().firstValue("Set-Cookie").orElseThrow()).contains("HttpOnly", "SameSite=Strict", "Path=/api/themes/files/");
            assertThat(asset.statusCode()).isEqualTo(200);
            assertThat(wrongCookie.statusCode()).isEqualTo(401);
            assertThat(crossSite.statusCode()).isEqualTo(403);
            assertThat(project.statusCode()).isEqualTo(401);
            assertThat(mutation.statusCode()).isEqualTo(401);
            assertThat(request(creator.baseUri(), "GET", "/api/themes/files/linked.css", "").statusCode()).isEqualTo(400);
            assertThat(request(creator.baseUri(), "GET", "/api/themes/files/large.png", "").statusCode()).isEqualTo(400);
        }
    }

    @Test
    void themesInstallEmbeddedCatalogWithoutReplacingLocalFilesAndNormalizeLegacySettings() throws Exception {
        final Path home = directory.resolve("home");
        final Path themes = Files.createDirectories(home.resolve("themes"));
        Files.createDirectories(themes.resolve("foundry"));
        Files.writeString(themes.resolve("foundry/theme.css"), "/* local */");
        Files.writeString(home.resolve("creator.settings.json"), """
                {"theme":"classic.css","reduced_motion":false,"effects":true,"effects_volume":0.35,"music_volume":0.25,"music":"","sounds":{}}
                """);
        try (CreatorServer creator = start(directory.resolve("project.json"), home)) {
            final RailixValue.ObjectValue settings = object(request(creator.baseUri(), "GET", "/api/settings", "").body());
            final HttpResponse<String> installed = request(creator.baseUri(), "POST", "/api/themes",
                    "{\"action\":\"install\",\"id\":\"\"}");
            final HttpResponse<String> retried = request(creator.baseUri(), "POST", "/api/themes",
                    "{\"action\":\"install\",\"id\":\"\"}");

            assertThat(string((RailixValue.ObjectValue) settings.values().get("values"), "theme_variant")).isEmpty();
            assertThat(string((RailixValue.ObjectValue) settings.values().get("values"), "theme")).isEqualTo("classic/theme.css");
            assertThat(installed.statusCode()).isEqualTo(200);
            assertThat(installed.body()).contains("\"status\":\"theme-installed\"", "\"skipped\"");
            assertThat(retried.statusCode()).isEqualTo(200);
            assertThat(retried.body()).contains("\"installed\":0");
            assertThat(Files.readString(themes.resolve("foundry/theme.css"))).isEqualTo("/* local */");
            assertThat(Files.readString(themes.resolve("foundry/theme.json"))).contains("Renderer CSS", "Renderer Canvas");
            final String root="/dev/nanonative/railix/creator/assets/themes/foundry/";
            try(var descriptor=CreatorServer.class.getResourceAsStream(root+"theme.json")) {
                assertThat(Files.readAllBytes(themes.resolve("foundry/theme.json"))).isEqualTo(descriptor.readAllBytes());
            }
            final var descriptor=object(Files.readString(themes.resolve("foundry/theme.json")));
            final List<String> files=new ArrayList<>();
            ((RailixValue.ArrayValue)descriptor.values().get("files")).values().forEach(file->files.add(((RailixValue.StringValue)file).value()));
            for(final var value:((RailixValue.ArrayValue)descriptor.values().get("variants")).values()) {
                final var variant=(RailixValue.ObjectValue)value;
                files.add(string(variant,"stylesheet"));
                if(variant.values().containsKey("atlas"))files.add(string(variant,"atlas"));
            }
            for(final String file:files)try(var resource=CreatorServer.class.getResourceAsStream(root+file)) {
                assertThat(Files.readAllBytes(themes.resolve("foundry/"+file))).as(file).isEqualTo(resource.readAllBytes());
            }
        }
    }

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
    void applicationMetricsEndpointForwardsOnlyApplicationAndProcessSeries() throws Exception {
        try (CreatorServer creator = start(directory.resolve("project.json"))) {
            final HttpResponse<String> response = request(creator.baseUri(), "GET", "/api/metrics", "");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body())
                    .contains("\"application\":{\"metrics\":", "\"process\":", "\"metric_counter_bytes\":")
                    .contains("\"flows\":[]", "\"steps\":[]")
                    .doesNotContain("payload", "context");
        }
    }

    @Test
    void selectedNodeMetricsEndpointForwardsOnlyTheMatchingFlowAndStep() throws Exception {
        final Path project = directory.resolve("project.json");
        Files.writeString(project, CreatorProjects.lowercaseCli(), StandardCharsets.UTF_8);

        try (CreatorServer creator = start(project)) {
            final HttpResponse<String> response = request(
                    creator.baseUri(), "GET", "/api/metrics/nodes/command", ""
            );

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body())
                    .contains("\"flows\":[{\"id\":\"command\"", "\"steps\":[{\"id\":\"command\"")
                    .doesNotContain("\"id\":\"lowercase\"");
        }
    }

    @Test
    void metricsEndpointRejectsMutationMethods() throws Exception {
        try (CreatorServer creator = start(directory.resolve("project.json"))) {
            final HttpResponse<String> response = request(creator.baseUri(), "POST", "/api/metrics", "{}");

            assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                    .containsExactly(405, "{\"status\":\"method-not-allowed\"}");
        }
    }

    @Test
    void blankNodeMetricsPathIsNotFound() throws Exception {
        try (CreatorServer creator = start(directory.resolve("project.json"))) {
            final HttpResponse<String> response = request(
                    creator.baseUri(), "GET", "/api/metrics/nodes/", ""
            );

            assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                    .containsExactly(404, "{\"status\":\"not-found\"}");
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
            final HttpResponse<String> example = awaitExampleView(creator.baseUri(), "command:0");

            assertThat(rejected.statusCode()).isEqualTo(422);
            assertThat(rejected.body()).contains("\"status\":\"rejected\"", "\"diagnostics\"");
            assertThat(string(after, "fingerprint")).isEqualTo(string(before, "fingerprint"));
            assertThat(number(after, "pid")).isEqualTo(number(before, "pid"));
            assertThat(example.statusCode()).isEqualTo(200);
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
    void existingProjectIsNotLimitedByTheHttpRequestSize() throws Exception {
        final Path project = directory.resolve("project.json");
        Files.writeString(project, " ".repeat(1_048_577) + threeStepProject(), StandardCharsets.UTF_8);

        try (CreatorServer creator = start(project)) {
            assertThat(request(creator.baseUri(), "GET", "/api/project", "").statusCode()).isEqualTo(200);
        }
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
                    "{\"format\":2,\"groups\":[],\"steps\":{\"app\":{\"name\":\"Creator\"}}}"
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
    void creatorDoesNotExposeAnExecutionEndpoint() throws Exception {
        try (CreatorServer creator = start(directory.resolve("project.json"))) {
            final HttpResponse<String> response = request(
                    creator.baseUri(),
                    "POST",
                    "/api/run/command",
                    CONTEXT
            );

            assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                    .containsExactly(404, "{\"status\":\"not-found\"}");
        }
    }

    @Test
    void creatorDoesNotExposeATraceExecutionEndpoint() throws Exception {
        try (CreatorServer creator = start(directory.resolve("project.json"))) {
            final HttpResponse<String> response = request(
                    creator.baseUri(),
                    "POST",
                    "/api/trace/command",
                    CONTEXT
            );

            assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                    .containsExactly(404, "{\"status\":\"not-found\"}");
        }
    }

    @Test
    void exampleRelayRejectsALiteralParentPathSegment() throws Exception {
        try (CreatorServer creator = startJourney()) {
            final String response = rawGet(creator.baseUri(), "/api/examples/../metrics");

            assertThat(response)
                    .contains(" 404 ", "{\"status\":\"not-found\"}")
                    .doesNotContain("metric_counter_bytes");
        }
    }

    @Test
    void exampleRelayRejectsAnEncodedParentPathSegment() throws Exception {
        try (CreatorServer creator = startJourney()) {
            final String response = rawGet(creator.baseUri(), "/api/examples/%2e%2e/metrics");

            assertThat(response)
                    .contains(" 404 ", "{\"status\":\"not-found\"}")
                    .doesNotContain("metric_counter_bytes");
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
    void rootEmbedsItsFaviconWithoutASecondHttpRequest() throws Exception {
        try (CreatorServer creator = start(directory.resolve("project.json"))) {
            final HttpResponse<String> response = request(creator.baseUri(), "GET", "/", "");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("<link rel=\"icon\" href=\"data:image/svg+xml;base64,");
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
    void browserOnlyRendersApplicationOwnedExampleViews() throws Exception {
        try (CreatorServer creator = start(directory.resolve("project.json"))) {
            final HttpResponse<String> response = request(
                    creator.baseUri(),
                    "GET",
                    "/app.js",
                    ""
            );

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains(
                    "fetch(\"/api/examples/status\"",
                    "fetch(\"/api/examples/coverage\"",
                    "function readExampleProjection(path, signal)"
            ).doesNotContain(
                    "step_start",
                    "step_result",
                    "trace_error",
                    "readTraceProjection",
                    "readSelectedStep",
                    "applyTraceChanges",
                    "exampleRoot(",
                    "exampleContext(",
                    "writePath("
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
    void stoppedDevelopmentApplicationExamplesAreUnavailable() throws Exception {
        try (CreatorServer creator = startJourney()) {
            final long pid = number(application(creator.baseUri()), "pid");
            stop(pid);

            final HttpResponse<String> response = request(
                    creator.baseUri(),
                    "GET",
                    "/api/examples",
                    ""
            );

            assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                    .containsExactly(503, "{\"reason\":\"application\",\"status\":\"unavailable\"}");
        }
    }

}

abstract class CreatorServerE2eSupport {
    static final String CONTEXT = """
            {"payload":{"arguments":["Hello RAILIX"]}}
            """;

    @TempDir
    Path directory;

    static HttpResponse<String> soundMutation(final URI baseUri, final String body) throws IOException, InterruptedException {
        final var values = new java.util.LinkedHashMap<>(object(body).values());
        values.put("revision", object(request(baseUri, "GET", "/api/sounds", "").body()).values().get("revision"));
        return request(baseUri, "POST", "/api/sounds", RailixJson.write(RailixValue.object(values)));
    }

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

    static String rawGet(final URI baseUri, final String path) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(baseUri.getHost(), baseUri.getPort()));
            socket.setSoTimeout((int) Duration.ofSeconds(15).toMillis());
            socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + baseUri.getHost() + ':' + baseUri.getPort() + "\r\n"
                    + "X-Railix-Creator-Token: " + tokenOrIncorrect(baseUri) + "\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
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

    static RailixValue.ObjectValue examples(final URI baseUri) throws Exception {
        return object(request(baseUri, "GET", "/api/examples", "").body());
    }

    static HttpResponse<String> awaitExampleView(final URI baseUri, final String id) throws Exception {
        final long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        RailixValue.ObjectValue state;
        do {
            state = examples(baseUri);
            if (number(state, "completed") == 1) {
                return request(baseUri, "GET", "/api/examples/" + id + "/view", "");
            }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Example did not complete: " + state);
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

}
