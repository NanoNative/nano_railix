package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Execution(ExecutionMode.SAME_THREAD)
final class CreatorServerE2eTest {
    private static final String CONTEXT = """
            {"payload":{"arguments":["Hello RAILIX"]}}
            """;

    @TempDir
    Path directory;

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
    void creatorMetadataRejectsMalformedJsonWithoutChangingTheRunningApplication() throws Exception {
        assertCreatorMetadataRejected("{", "CREATOR_JSON_INVALID", "");
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
    void creatorMetadataMustBeAnObject() throws Exception {
        assertCreatorMetadataRejected("[]", "CREATOR_OBJECT_REQUIRED", "");
    }

    @Test
    void creatorMetadataStepsMustBeAnObject() throws Exception {
        assertCreatorMetadataRejected(
                "{\"format\":1,\"steps\":[],\"groups\":[]}",
                "CREATOR_STEPS_OBJECT_REQUIRED",
                "steps"
        );
    }

    @Test
    void creatorMetadataGroupsMustBeAnArray() throws Exception {
        assertCreatorMetadataRejected(
                "{\"format\":1,\"steps\":{},\"groups\":{}}",
                "CREATOR_GROUPS_ARRAY_REQUIRED",
                "groups"
        );
    }

    @Test
    void creatorStepPresentationMustBeAnObject() throws Exception {
        assertCreatorMetadataRejected(
                "{\"format\":1,\"steps\":{\"lowercase-text\":true},\"groups\":[]}",
                "CREATOR_PRESENTATION_OBJECT_REQUIRED",
                "steps.lowercase-text"
        );
    }

    @Test
    void creatorPresentationNameMustBeNonBlank() throws Exception {
        assertCreatorMetadataRejected(
                "{\"format\":1,\"steps\":{\"lowercase-text\":{\"name\":\" \"}},\"groups\":[]}",
                "CREATOR_PRESENTATION_NAME_INVALID",
                "steps.lowercase-text.name"
        );
    }

    @Test
    void creatorPresentationColorMustUseSixHexDigits() throws Exception {
        assertCreatorMetadataRejected(
                "{\"format\":1,\"steps\":{\"lowercase-text\":{\"color\":\"red\"}},\"groups\":[]}",
                "CREATOR_PRESENTATION_COLOR_INVALID",
                "steps.lowercase-text.color"
        );
    }

    @Test
    void creatorPresentationIconMustBeAnObject() throws Exception {
        assertCreatorMetadataRejected(
                "{\"format\":1,\"steps\":{\"lowercase-text\":{\"icon\":true}},\"groups\":[]}",
                "CREATOR_PRESENTATION_ICON_INVALID",
                "steps.lowercase-text.icon"
        );
    }

    @Test
    void creatorGroupMustBeAnObject() throws Exception {
        assertCreatorMetadataRejected(
                "{\"format\":1,\"steps\":{},\"groups\":[true]}",
                "CREATOR_GROUP_OBJECT_REQUIRED",
                "groups[0]"
        );
    }

    @Test
    void creatorGroupIdMustBeNonBlank() throws Exception {
        assertCreatorMetadataRejected(
                "{\"format\":1,\"steps\":{},\"groups\":[{\"id\":\"\",\"occurrences\":[true]}]}",
                "CREATOR_ID_INVALID",
                "groups[0].id"
        );
    }

    @Test
    void creatorGroupOccurrenceMustBeAnObject() throws Exception {
        assertCreatorMetadataRejected(
                "{\"format\":1,\"steps\":{},\"groups\":[{\"id\":\"group-one\",\"occurrences\":[true]}]}",
                "CREATOR_OCCURRENCE_OBJECT_REQUIRED",
                "groups[0].occurrences[0]"
        );
    }

    @Test
    void creatorOccurrenceParentMustBeNullOrAnId() throws Exception {
        assertCreatorMetadataRejected(
                """
                {"format":1,"steps":{},"groups":[{"id":"group-one","occurrences":[{
                  "id":"occurrence-one","flow":"command","parent":4,
                  "steps":{"slot-one":"lowercase-text"}
                }]}]}
                """,
                "CREATOR_OCCURRENCE_PARENT_INVALID",
                "groups[0].occurrences[0].parent"
        );
    }

    @Test
    void creatorOccurrenceSlotMustBeANonBlankId() throws Exception {
        assertCreatorMetadataRejected(
                """
                {"format":1,"steps":{},"groups":[{"id":"group-one","occurrences":[{
                  "id":"occurrence-one","flow":"command","parent":null,
                  "steps":{"":"lowercase-text"}
                }]}]}
                """,
                "CREATOR_OCCURRENCE_STEP_INVALID",
                "groups[0].occurrences[0].steps."
        );
    }

    @Test
    void creatorOccurrenceCannotAssignOneStepToTwoSlots() throws Exception {
        assertCreatorMetadataRejected(
                """
                {"format":1,"steps":{},"groups":[{"id":"group-one","occurrences":[{
                  "id":"occurrence-one","flow":"command","parent":null,
                  "steps":{"slot-one":"lowercase-text","slot-two":"lowercase-text"}
                }]}]}
                """,
                "CREATOR_OCCURRENCE_STEP_DUPLICATE",
                "groups[0].occurrences[0].steps.slot-two"
        );
    }

    @Test
    void creatorMetadataRejectsUnknownTopLevelFields() throws Exception {
        assertCreatorMetadataRejected(
                "{\"format\":1,\"steps\":{},\"groups\":[],\"noise\":true}",
                "CREATOR_FIELD_UNKNOWN",
                "noise"
        );
    }

    @Test
    void creatorMetadataRejectsAnUnimplementedGlobalGroupFlag() throws Exception {
        assertCreatorMetadataRejected(
                "{\"format\":1,\"steps\":{},\"groups\":[{"
                        + "\"id\":\"group-one\",\"global\":true,\"occurrences\":[]}]}",
                "CREATOR_GROUP_FIELD_UNKNOWN",
                "groups[0].global"
        );
    }

    @Test
    void creatorMetadataRejectsAGroupWithoutOccurrences() throws Exception {
        assertCreatorMetadataRejected(
                "{\"format\":1,\"steps\":{},\"groups\":[{"
                        + "\"id\":\"group-one\",\"occurrences\":[]}]}",
                "CREATOR_GROUP_OCCURRENCES_REQUIRED",
                "groups[0].occurrences"
        );
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
    void creatorMetadataRejectsUnsupportedFormats() throws Exception {
        assertCreatorMetadataRejected(
                "{\"format\":2,\"steps\":{},\"groups\":[]}",
                "CREATOR_FORMAT_UNSUPPORTED",
                "format"
        );
    }

    @Test
    void creatorMetadataRejectsPresentationForAnUnknownStep() throws Exception {
        assertCreatorMetadataRejected(
                "{\"format\":1,\"steps\":{\"missing\":{}},\"groups\":[]}",
                "CREATOR_STEP_UNKNOWN",
                "steps.missing"
        );
    }

    @Test
    void creatorMetadataRejectsDuplicateGroupIds() throws Exception {
        assertCreatorMetadataRejected(
                """
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
                """,
                "CREATOR_GROUP_ID_DUPLICATE",
                "groups[1].id"
        );
    }

    @Test
    void creatorMetadataRejectsAnUnknownOccurrenceFlow() throws Exception {
        assertCreatorMetadataRejected(
                """
                {"format":1,"steps":{},"groups":[{"id":"group-one","occurrences":[{
                  "id":"occurrence-one","flow":"missing","parent":null,
                  "steps":{"slot-one":"lowercase-text"}
                }]}]}
                """,
                "CREATOR_OCCURRENCE_FLOW_UNKNOWN",
                "groups[0].occurrences[0].flow"
        );
    }

    @Test
    void creatorMetadataRejectsAnUnknownOccurrenceStep() throws Exception {
        assertCreatorMetadataRejected(
                """
                {"format":1,"steps":{},"groups":[{"id":"group-one","occurrences":[{
                  "id":"occurrence-one","flow":"command","parent":null,
                  "steps":{"slot-one":"missing"}
                }]}]}
                """,
                "CREATOR_OCCURRENCE_STEP_UNKNOWN",
                "groups[0].occurrences[0].steps.slot-one"
        );
    }

    @Test
    void creatorMetadataRejectsDuplicateOccurrenceIds() throws Exception {
        assertCreatorMetadataRejected(
                """
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
                """,
                "CREATOR_OCCURRENCE_ID_DUPLICATE",
                "groups[1].occurrences[0].id"
        );
    }

    @Test
    void creatorMetadataRejectsDifferentSlotsAcrossSharedOccurrences() throws Exception {
        assertCreatorMetadataRejected(
                """
                {"format":1,"steps":{},"groups":[{"id":"group-one","occurrences":[
                  {"id":"occurrence-one","flow":"command","parent":null,
                   "steps":{"slot-one":"lowercase-text"}},
                  {"id":"occurrence-two","flow":"command","parent":null,
                   "steps":{"slot-two":"return-text"}}
                ]}]}
                """,
                "CREATOR_OCCURRENCE_SLOTS_MISMATCH",
                "groups[0].occurrences[1].steps"
        );
    }

    @Test
    void creatorMetadataRejectsAnUnknownParentOccurrence() throws Exception {
        assertCreatorMetadataRejected(
                """
                {"format":1,"steps":{},"groups":[{"id":"group-one","occurrences":[{
                  "id":"occurrence-one","flow":"command","parent":"missing",
                  "steps":{"slot-one":"lowercase-text"}
                }]}]}
                """,
                "CREATOR_OCCURRENCE_PARENT_UNKNOWN",
                "groups[0].occurrences[0].parent"
        );
    }

    @Test
    void creatorMetadataRejectsAParentCycle() throws Exception {
        assertCreatorMetadataRejected(
                """
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
                """,
                "CREATOR_OCCURRENCE_PARENT_CYCLE",
                "groups[0].occurrences[0].parent"
        );
    }

    @Test
    void creatorMetadataRejectsAChildOutsideItsParentRange() throws Exception {
        assertCreatorMetadataRejected(
                """
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
                """,
                "CREATOR_OCCURRENCE_OUTSIDE_PARENT",
                "groups[1].occurrences[0].steps"
        );
    }

    @Test
    void creatorMetadataRejectsOverlappingSiblingGroups() throws Exception {
        assertCreatorMetadataRejected(
                """
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
                """,
                "CREATOR_OCCURRENCE_STEP_OVERLAP",
                "groups[1].occurrences[0].steps"
        );
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

    @Test
    void previewReturnsTheActualStartsWithOutputFromTheChildApplication() throws Exception {
        final HttpResponse<String> response = primitivePreview(
                "text.starts-with",
                "{\"prefix\":\"Nano\"}",
                "\"Nano Railix\""
        );

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains(
                "\"status\":\"succeeded\",\"use\":\"text.starts-with\",\"value\":true"
        );
    }

    @Test
    void previewReturnsTheActualEndsWithOutputFromTheChildApplication() throws Exception {
        final HttpResponse<String> response = primitivePreview(
                "text.ends-with",
                "{\"suffix\":\"Railix\"}",
                "\"Nano Railix\""
        );

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains(
                "\"status\":\"succeeded\",\"use\":\"text.ends-with\",\"value\":true"
        );
    }

    @Test
    void previewReturnsTheActualValueEqualsOutputFromTheChildApplication() throws Exception {
        final HttpResponse<String> response = primitivePreview(
                "value.equals",
                "{\"expected\":{\"answer\":42}}",
                "{\"answer\":42}"
        );

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains(
                "\"status\":\"succeeded\",\"use\":\"value.equals\",\"value\":true"
        );
    }

    @Test
    void previewReturnsTheActualListReverseOutputFromTheChildApplication() throws Exception {
        final HttpResponse<String> response = primitivePreview("list.reverse", "{}", "[1,2]");

        assertThat(response.body()).contains(
                "\"status\":\"succeeded\",\"use\":\"list.reverse\",\"value\":[2,1]"
        );
    }

    @Test
    void previewReturnsTheActualNumberToTextOutputFromTheChildApplication() throws Exception {
        final HttpResponse<String> response = primitivePreview("number.to-text", "{}", "1.2300");

        assertThat(response.body()).contains(
                "\"status\":\"succeeded\",\"use\":\"number.to-text\",\"value\":\"1.23\""
        );
    }

    @Test
    void previewReturnsTheActualValueWrapListOutputFromTheChildApplication() throws Exception {
        final HttpResponse<String> response = primitivePreview("value.wrap-list", "{}", "null");

        assertThat(response.body()).contains(
                "\"status\":\"succeeded\",\"use\":\"value.wrap-list\",\"value\":[null]"
        );
    }

    @Test
    void previewReturnsTheActualValueToJsonOutputFromTheChildApplication() throws Exception {
        final HttpResponse<String> response = primitivePreview("value.to-json", "{}", "{\"z\":2,\"a\":1}");

        assertThat(response.body()).contains(
                "\"status\":\"succeeded\",\"use\":\"value.to-json\","
                        + "\"value\":\"{\\\"a\\\":1,\\\"z\\\":2}\""
        );
    }

    @Test
    void falliblePreviewReturnsTheActualInvalidOutcomeFromTheChildApplication() throws Exception {
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
                                    + "\"result\":null,\"runtime\":{\"test\":true,\"trigger\":\"command\"}},"
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
                                    + "{\"id\":\"convert\",\"outcome\":\"next\"}]}"
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
                        Content-Length: 100\r
                        Connection: close\r
                        \r
                        {
                        """.formatted(uri.getHost(), uri.getPort())).getBytes(StandardCharsets.UTF_8));
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
                        Content-Length: 1048576\r
                        Connection: close\r
                        \r
                        """.formatted(uri.getHost(), uri.getPort())).getBytes(StandardCharsets.UTF_8));
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
    void developmentStartFailureKeepsRunningApplication() throws Exception {
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

            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.body()).contains("\"message\":\"Development application did not start.\"");
            assertThat(application(creator.baseUri())).isEqualTo(before);
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

    private static HttpResponse<String> request(
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

    private static HttpResponse<String> request(
            final URI baseUri,
            final String method,
            final String path,
            final byte[] body
    ) throws IOException, InterruptedException {
        return request(baseUri, method, path, HttpRequest.BodyPublishers.ofByteArray(body));
    }

    private static HttpResponse<String> request(
            final URI baseUri,
            final String method,
            final String path,
            final HttpRequest.BodyPublisher body
    ) throws IOException, InterruptedException {
        final HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .method(method, body)
                .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
    }

    private static RailixValue.ObjectValue application(final URI baseUri) throws Exception {
        return (RailixValue.ObjectValue) object(
                request(baseUri, "GET", "/api/application", "").body()
        );
    }

    private static RailixValue.ObjectValue object(final String source) {
        final RailixJson.Result result = RailixJson.parse(source);
        assertThat(result).isInstanceOf(RailixJson.Parsed.class);
        return (RailixValue.ObjectValue) ((RailixJson.Parsed) result).value();
    }

    private static String snapshotKey(final String source) {
        final RailixValue.ObjectValue payload = object(source);
        return string((RailixValue.ObjectValue) payload.values().get("project"), "id") + ":"
                + string((RailixValue.ObjectValue) payload.values().get("application"), "fingerprint");
    }

    private void assertCreatorMetadataRejected(
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

    private void assertInvalidIcon(final String file, final byte[] source) throws Exception {
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

    private static byte[] pngHeader(final int width, final int height) {
        final byte[] value = new byte[24];
        ByteBuffer.wrap(value)
                .put(new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a})
                .position(16)
                .putInt(width)
                .putInt(height);
        return value;
    }

    private static RailixValue.ObjectValue catalogDefinition(
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

    private static RailixValue.ArrayValue refinedPort(
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

    private static long number(final RailixValue.ObjectValue object, final String field) {
        return ((RailixValue.NumberValue) object.values().get(field)).value().longValueExact();
    }

    private static String string(final RailixValue.ObjectValue object, final String field) {
        return ((RailixValue.StringValue) object.values().get(field)).value();
    }

    private static boolean awaitExit(final long pid) throws InterruptedException {
        for (int attempt = 0; attempt < 50; attempt++) {
            if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                Thread.sleep(100);
            } else {
                return true;
            }
        }
        return false;
    }

    private static void stop(final long pid) throws InterruptedException {
        final ProcessHandle process = ProcessHandle.of(pid).orElseThrow();
        process.destroy();
        if (!awaitExit(pid)) {
            process.destroyForcibly();
            assertThat(awaitExit(pid)).isTrue();
        }
    }

    private CreatorServer start(final Path project) throws IOException {
        return start(project, directory.resolve("railix-home"));
    }

    private static CreatorServer start(final Path project, final Path railixHome) throws IOException {
        return CreatorServer.start(0, project, railixHome);
    }

    private CreatorServer startJourney() throws IOException {
        final Path project = directory.resolve("project.json");
        Files.writeString(project, CreatorProjects.lowercaseCli(), StandardCharsets.UTF_8);
        return start(project);
    }

    private static Path lowercaseExampleProject() {
        return Path.of("..", "..", "examples", "lowercase-app", "railix.project.json")
                .toAbsolutePath()
                .normalize();
    }

    private static String threeStepProject() {
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

    private CreatorServer startFallibleJourney() throws IOException {
        final Path project = directory.resolve("project.json");
        Files.writeString(project, CreatorProjects.fallibleNumber(), StandardCharsets.UTF_8);
        return start(project);
    }

    private HttpResponse<String> primitivePreview(
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

    private static String primitiveProject(
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
