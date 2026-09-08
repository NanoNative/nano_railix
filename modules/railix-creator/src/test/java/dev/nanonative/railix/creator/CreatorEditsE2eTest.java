package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import thirdparty.conformance.GenericContractSteps;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(90)
final class CreatorEditsE2eTest extends CreatorServerE2eSupport {
    @Test
    void projectEditReturnsAReceiptWithoutRetransmittingTheGraph() throws Exception {
        final Path project = directory.resolve("project.json");
        try (CreatorServer creator = start(project)) {
            final RailixValue.ObjectValue initial = workspace(creator.baseUri());
            assertThat(number(initial, "revision")).isZero();
            assertThat(number(initial, "creator_revision")).isZero();
            final HttpResponse<String> response = request(creator.baseUri(), "PATCH", "/api/project", """
                    {"revision":0,"changes":{"id":"edited-app"}}
                    """);

            final RailixValue.ObjectValue receipt = receipt(response);
            assertThat(number(receipt, "revision")).isPositive();
            assertThat(number(receipt, "creator_revision")).isZero();
            assertThat(Files.readString(project)).contains("\"id\":\"edited-app\"");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/project", "/api/creator"})
    void staleRevisionCannotOverwriteAnAcceptedEdit(final String path) throws Exception {
        try (CreatorServer creator = start(directory.resolve("project.json"))) {
            receipt(edit(creator.baseUri(), path, 0, changes(path, "accepted")));
            final Map<String, RailixValue> accepted = persisted(creator.baseUri());
            final String source = Files.readString(directory.resolve("project.json"));
            final String metadata = Files.readString(directory.resolve("railix.creator.json"));

            final HttpResponse<String> stale = edit(creator.baseUri(), path, 0, changes(path, "stale"));

            assertThat(stale.statusCode()).isEqualTo(409);
            assertThat(string(object(stale.body()), "status")).isEqualTo("edit-conflict");
            assertThat(persisted(creator.baseUri())).isEqualTo(accepted);
            assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(source);
            assertThat(Files.readString(directory.resolve("railix.creator.json"))).isEqualTo(metadata);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/project", "/api/creator"})
    void concurrentSameBaseEditsAcceptOnlyOneWriter(final String path) throws Exception {
        try (CreatorServer creator = start(directory.resolve("project.json"));
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final CountDownLatch ready = new CountDownLatch(2);
            final CountDownLatch release = new CountDownLatch(1);
            final var writes = Stream.of("first", "second").map(name -> executor.submit(() -> {
                ready.countDown();
                assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                return edit(creator.baseUri(), path, 0, changes(path, name));
            })).toList();
            final boolean bothReady = ready.await(10, TimeUnit.SECONDS);
            release.countDown();
            assertThat(bothReady).isTrue();
            final HttpResponse<String> first = writes.get(0).get(30, TimeUnit.SECONDS);
            final HttpResponse<String> second = writes.get(1).get(30, TimeUnit.SECONDS);

            assertThat(List.of(first.statusCode(), second.statusCode())).containsExactlyInAnyOrder(200, 409);
            final String winner = first.statusCode() == 200 ? "first" : "second";
            final RailixValue.ObjectValue after = workspace(creator.baseUri());
            final RailixValue.ObjectValue saved = path.equals("/api/project")
                    ? child(after, "project") : child(child(child(after, "creator"), "steps"), "app");
            assertThat(string(saved, path.equals("/api/project") ? "id" : "name")).isEqualTo(winner);
            assertThat(number(after, revisionField(path))).isPositive();
        }
    }

    @Test
    void rejectedCompilationLeavesTheRevisionReusableAndTheApplicationRunning() throws Exception {
        try (CreatorServer creator = startJourney()) {
            final Map<String, RailixValue> before = persisted(creator.baseUri());
            final long pid = number(application(creator.baseUri()), "pid");

            final HttpResponse<String> rejected = edit(creator.baseUri(), "/api/project", 0, "{\"format\":2}");

            assertThat(rejected.statusCode()).isEqualTo(422);
            assertThat(rejected.body()).contains("PROJECT_FORMAT_UNSUPPORTED");
            assertThat(persisted(creator.baseUri())).isEqualTo(before);
            assertThat(number(application(creator.baseUri()), "pid")).isEqualTo(pid);
            assertThat(awaitExampleView(creator.baseUri(), "command:0").statusCode()).isEqualTo(200);

            final RailixValue.ObjectValue accepted = receipt(edit(
                    creator.baseUri(), "/api/project", 0, "{\"id\":\"recovered\",\"format\":1}"
            ));
            assertThat(number(accepted, "revision")).isPositive();
            assertThat(string(child(workspace(creator.baseUri()), "project"), "id")).isEqualTo("recovered");
        }
    }

    @Test
    void replacingAStepChangesTheRealExampleResult() throws Exception {
        try (CreatorServer creator = startJourney()) {
            final RailixValue.ObjectValue before = child(workspace(creator.baseUri()), "project");
            final Map<String, RailixValue> replacement = new LinkedHashMap<>(node(before, "lowercase-text").values());
            replacement.put("use", RailixValue.string("text.uppercase"));

            receipt(edit(creator.baseUri(), "/api/project", 0, RailixJson.write(RailixValue.object(Map.of(
                    "nodes", RailixValue.object(Map.of("lowercase-text", RailixValue.object(replacement)))
            )))));
            final HttpResponse<String> view = awaitExampleView(creator.baseUri(), "command:0");

            assertThat(view.statusCode()).isEqualTo(200);
            assertThat(string(child(object(view.body()), "result"), "status")).isEqualTo("succeeded");
            assertThat(child(child(object(view.body()), "result"), "context").values().get("result"))
                    .isEqualTo(RailixValue.string("HELLO RAILIX"));
            final RailixValue.ObjectValue after = child(workspace(creator.baseUri()), "project");
            assertThat(node(after, "command")).isEqualTo(node(before, "command"));
            assertThat(after.values().get("links")).isEqualTo(before.values().get("links"));
        }
    }

    @Test
    void utf8ExampleEditRoundTripsThroughTheGeneratedApplication() throws Exception {
        try (CreatorServer creator = startJourney()) {
            final String changes = """
                    {"nodes":{"command":{"id":"command","use":"railix.trigger.cli",
                      "inputs":{"target":["context","payload","arguments"]},
                      "examples":[{"name":"caf\u00e9","payload":["H\u00c9LLO"]}]}}}
                    """;
            assertThat(changes.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(changes.length());

            receipt(edit(creator.baseUri(), "/api/project", 0, changes));
            final HttpResponse<String> view = awaitExampleView(creator.baseUri(), "command:0");

            assertThat(view.statusCode()).isEqualTo(200);
            assertThat(child(child(object(view.body()), "result"), "context").values().get("result"))
                    .isEqualTo(RailixValue.string("h\u00e9llo"));
            assertThat(Files.readString(directory.resolve("project.json"))).contains("caf\u00e9", "H\u00c9LLO");
        }
    }

    @Test
    void insertingAStepRetainsExistingNodesAndTheOtherBranch() throws Exception {
        try (CreatorServer creator = startProject(branchGroupProject())) {
            final RailixValue.ObjectValue before = child(workspace(creator.baseUri()), "project");

            receipt(edit(creator.baseUri(), "/api/project", 0, """
                    {"nodes":{"inserted":{"id":"inserted","use":"railix.field-manipulation","inputs":{}}},
                     "links":{"matched.next":[{"from":"matched.next","to":"inserted"}],
                              "inserted.next":[{"from":"inserted.next","to":"end"}]}}
                    """));

            final RailixValue.ObjectValue after = child(workspace(creator.baseUri()), "project");
            assertThat(array(after, "nodes")).startsWith(array(before, "nodes").toArray(RailixValue[]::new));
            assertThat(node(after, "inserted").values().get("use"))
                    .isEqualTo(RailixValue.string("railix.field-manipulation"));
            assertThat(links(after, "matched.next")).containsExactly(link("matched.next", "inserted"));
            assertThat(links(after, "inserted.next")).containsExactly(link("inserted.next", "end"));
            assertThat(links(after, "choice.otherwise")).isEqualTo(links(before, "choice.otherwise"));
            assertThat(links(after, "otherwise.next")).isEqualTo(links(before, "otherwise.next"));
            assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(RailixJson.write(after));
        }
    }

    @Test
    void deletingABranchLeafRemovesOnlyItsNodeAndSourcePort() throws Exception {
        try (CreatorServer creator = startProject(branchGroupProject())) {
            final RailixValue.ObjectValue before = child(workspace(creator.baseUri()), "project");

            receipt(edit(creator.baseUri(), "/api/project", 0, """
                    {"nodes":{"matched":null},"links":{
                      "choice.match":[{"from":"choice.match","to":"end"}],"matched.next":null}}
                    """));

            final RailixValue.ObjectValue after = child(workspace(creator.baseUri()), "project");
            assertThat(array(after, "nodes")).containsExactlyElementsOf(array(before, "nodes").stream()
                    .filter(value -> !string((RailixValue.ObjectValue) value, "id").equals("matched")).toList());
            assertThat(links(after, "matched.next")).isEmpty();
            assertThat(links(after, "choice.match")).containsExactly(link("choice.match", "end"));
            assertThat(links(after, "choice.otherwise")).isEqualTo(links(before, "choice.otherwise"));
            assertThat(links(after, "otherwise.next")).isEqualTo(links(before, "otherwise.next"));
        }
    }

    @Test
    void appSourcePortSupportsFanoutAndReplacementOfItsWholeTargetSet() throws Exception {
        GeneratedApplicationFixture.installedCatalog(directory, Stream.of("first", "second")
                .map(id -> StepDefinition.named("example.trigger." + id, "1")
                        .kind(StepDefinition.Kind.TRIGGER).source("example.input." + id)
                        .run(GenericContractSteps.Trigger.class)).toList(), GenericContractSteps.Trigger.class);
        try (CreatorServer creator = start(directory.resolve("project.json"))) {
            final RailixValue.ObjectValue added = receipt(edit(creator.baseUri(), "/api/project", 0, """
                    {"nodes":{
                      "first":{"id":"first","use":"example.trigger.first","inputs":{},
                        "examples":[{"name":"example","payload":null}]},
                      "second":{"id":"second","use":"example.trigger.second","inputs":{},
                        "examples":[{"name":"example","payload":null}]}},
                     "links":{"app.start":[{"from":"app.start","to":"first"},{"from":"app.start","to":"second"}],
                       "first.next":[{"from":"first.next","to":"end"}],
                       "second.next":[{"from":"second.next","to":"end"}]}}
                    """));
            final RailixValue.ObjectValue before = child(workspace(creator.baseUri()), "project");
            assertThat(links(before, "app.start")).containsExactly(link("app.start", "first"), link("app.start", "second"));
            assertThat(number(child(added, "workspace"), "flow_count")).isEqualTo(2);

            receipt(edit(creator.baseUri(), "/api/project", number(added, "revision"), """
                    {"nodes":{"first":null},"links":{
                      "app.start":[{"from":"app.start","to":"second"}],"first.next":null}}
                    """));

            final RailixValue.ObjectValue after = child(workspace(creator.baseUri()), "project");
            assertThat(links(after, "app.start")).containsExactly(link("app.start", "second"));
            assertThat(node(after, "second")).isEqualTo(node(before, "second"));
            assertThat(links(after, "second.next")).isEqualTo(links(before, "second.next"));
        }
    }

    @Test
    void metadataUpsertsReturnACompactReceiptWithoutRestarting() throws Exception {
        try (CreatorServer creator = startProject(branchGroupProject())) {
            final long pid = number(application(creator.baseUri()), "pid");
            final String source = Files.readString(directory.resolve("project.json"));
            final RailixValue.ObjectValue first = receipt(edit(creator.baseUri(), "/api/creator", 0, """
                    {"groups":{"first":{"id":"first","name":"First"},"second":{"id":"second","name":"Second"}},
                     "steps":{"matched":{"name":"Caf\u00e9","group":"first"},"otherwise":{"group":"second"}}}
                    """));
            assertThat(Files.readString(directory.resolve("railix.creator.json"))).contains("Caf\u00e9");

            final RailixValue.ObjectValue updated = receipt(edit(creator.baseUri(), "/api/creator",
                    number(first, "creator_revision"), """
                    {"groups":{"first":{"id":"first","name":"Renamed"}},"steps":{"matched":{"name":"Updated"}}}
                    """));

            final RailixValue.ObjectValue metadata = child(workspace(creator.baseUri()), "creator");
            assertThat(array(metadata, "groups").stream().map(value -> string((RailixValue.ObjectValue) value, "id")))
                    .containsExactly("first", "second");
            assertThat(string((RailixValue.ObjectValue) array(metadata, "groups").getFirst(), "name"))
                    .isEqualTo("Renamed");
            assertThat(child(child(metadata, "steps"), "matched").values())
                    .containsOnlyKeys("name").containsEntry("name", RailixValue.string("Updated"));
            assertThat(child(child(metadata, "steps"), "otherwise").values().get("group"))
                    .isEqualTo(RailixValue.string("second"));
            assertThat(number(updated, "revision")).isZero();
            assertThat(number(updated, "creator_revision")).isGreaterThan(number(first, "creator_revision"));
            assertThat(number(application(creator.baseUri()), "pid")).isEqualTo(pid);
            assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(source);
            assertThat(Files.readString(directory.resolve("railix.creator.json"))).isEqualTo(RailixJson.write(metadata));
        }
    }

    @Test
    void deletingAGroupAndItsPresentationPreservesUnrelatedMetadata() throws Exception {
        try (CreatorServer creator = startProject(branchGroupProject())) {
            final RailixValue.ObjectValue added = receipt(edit(creator.baseUri(), "/api/creator", 0, """
                    {"groups":{"first":{"id":"first"},"second":{"id":"second"}},
                     "steps":{"matched":{"group":"first"},"otherwise":{"group":"second","name":"Kept"}}}
                    """));

            receipt(edit(creator.baseUri(), "/api/creator", number(added, "creator_revision"), """
                    {"groups":{"first":null},"steps":{"matched":null}}
                    """));

            final RailixValue.ObjectValue metadata = child(workspace(creator.baseUri()), "creator");
            assertThat(array(metadata, "groups")).containsExactly(object("{\"id\":\"second\"}"));
            assertThat(child(metadata, "steps").values()).containsOnlyKeys("otherwise");
            assertThat(child(child(metadata, "steps"), "otherwise"))
                    .isEqualTo(object("{\"group\":\"second\",\"name\":\"Kept\"}"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/project", "/api/creator"})
    void wholeDocumentPostAdvancesItsRevisionAndInvalidatesEarlierEdits(final String path) throws Exception {
        try (CreatorServer creator = start(directory.resolve("project.json"))) {
            final long previous = number(receipt(edit(creator.baseUri(), path, 0, changes(path, "before-import"))),
                    revisionField(path));

            final HttpResponse<String> imported = request(creator.baseUri(), "POST", path,
                    path.equals("/api/project") ? CreatorProjects.empty("imported") : CreatorDocument.EMPTY);

            assertThat(imported.statusCode()).isEqualTo(200);
            final RailixValue.ObjectValue body = object(imported.body());
            assertThat(body.values()).containsKeys("project", "creator");
            assertThat(number(body, revisionField(path))).isGreaterThan(previous);
            final Map<String, RailixValue> accepted = persisted(creator.baseUri());
            assertThat(edit(creator.baseUri(), path, previous, changes(path, "stale")).statusCode()).isEqualTo(409);
            assertThat(persisted(creator.baseUri())).isEqualTo(accepted);
        }
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("invalidEdits")
    void invalidEditDoesNotChangeEitherDocumentOrRevision(final String path, final String body, final String code)
            throws Exception {
        try (CreatorServer creator = start(directory.resolve("project.json"))) {
            final Map<String, RailixValue> before = persisted(creator.baseUri());

            final HttpResponse<String> response = request(creator.baseUri(), "PATCH", path, body);

            assertThat(response.statusCode()).as(response.body()).isEqualTo(422);
            assertThat(response.body()).contains(code);
            assertThat(persisted(creator.baseUri())).isEqualTo(before);
        }
    }

    private static Stream<Arguments> invalidEdits() {
        final Stream<Arguments> envelopes = Stream.of("/api/project", "/api/creator").flatMap(path -> Stream.of(
                "{", "[]", "null", "{}", "{\"revision\":0}", "{\"changes\":{}}",
                "{\"revision\":\"0\",\"changes\":{}}", "{\"revision\":0,\"changes\":[]}",
                "{\"revision\":0,\"changes\":{},\"extra\":true}"
        ).map(body -> Arguments.of(path, body, "CREATOR_EDIT_INVALID")));
        final Stream<Arguments> changes = Stream.of(
                Arguments.of("/api/project", "{\"unknown\":true}", "CREATOR_EDIT_INVALID"),
                Arguments.of("/api/project", "{\"nodes\":[]}", "CREATOR_EDIT_INVALID"),
                Arguments.of("/api/project", "{\"nodes\":{\"app\":7}}", "CREATOR_EDIT_INVALID"),
                Arguments.of("/api/project", "{\"nodes\":{\"app\":{\"id\":\"other\"}}}", "CREATOR_EDIT_INVALID"),
                Arguments.of("/api/project", "{\"links\":{\"app.start\":{\"from\":\"app.start\",\"to\":\"end\"}}}", "CREATOR_EDIT_INVALID"),
                Arguments.of("/api/project", "{\"links\":{\"app.start\":[7]}}", "CREATOR_EDIT_INVALID"),
                Arguments.of("/api/project", "{\"links\":{\"app.start\":[{\"from\":\"other.next\",\"to\":\"end\"}]}}", "CREATOR_EDIT_INVALID"),
                Arguments.of("/api/creator", "{\"format\":2}", "CREATOR_EDIT_INVALID"),
                Arguments.of("/api/creator", "{\"groups\":[]}", "CREATOR_EDIT_INVALID"),
                Arguments.of("/api/creator", "{\"groups\":{\"one\":{\"id\":\"other\"}}}", "CREATOR_EDIT_INVALID"),
                Arguments.of("/api/creator", "{\"steps\":[]}", "CREATOR_EDIT_INVALID"),
                Arguments.of("/api/creator", "{\"steps\":{\"missing\":{}}}", "CREATOR_STEP_UNKNOWN"),
                Arguments.of("/api/creator", "{\"steps\":{\"app\":7}}", "CREATOR_PRESENTATION_OBJECT_REQUIRED"),
                Arguments.of("/api/creator", "{\"steps\":{\"app\":{\"unknown\":true}}}", "CREATOR_PRESENTATION_FIELD_UNKNOWN")
        ).map(arguments -> Arguments.of(arguments.get()[0],
                "{\"revision\":0,\"changes\":" + arguments.get()[1] + "}", arguments.get()[2]));
        return Stream.concat(envelopes, changes);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/project", "/api/creator"})
    void malformedUtf8IsRejectedWithoutConsumingTheRevision(final String path) throws Exception {
        try (CreatorServer creator = start(directory.resolve("project.json"))) {
            final Map<String, RailixValue> before = persisted(creator.baseUri());

            final HttpResponse<String> response = request(creator.baseUri(), "PATCH", path,
                    new byte[]{(byte) 0xc3, 0x28});

            assertThat(response.statusCode()).isEqualTo(422);
            assertThat(response.body()).contains("CREATOR_EDIT_INVALID", "Edit must be valid UTF-8.");
            assertThat(persisted(creator.baseUri())).isEqualTo(before);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/project", "/api/creator"})
    void oversizedRequestIsRejectedBeforeAnEditIsApplied(final String path) throws Exception {
        try (CreatorServer creator = start(directory.resolve("project.json"))) {
            final Map<String, RailixValue> before = persisted(creator.baseUri());

            final HttpResponse<String> response = request(creator.baseUri(), "PATCH", path,
                    "x".repeat(RailixData.DEFAULT_MAX_SOURCE_BYTES + 1));

            assertThat(response.statusCode()).isEqualTo(413);
            assertThat(response.body()).contains("REQUEST_TOO_LARGE");
            assertThat(persisted(creator.baseUri())).isEqualTo(before);
        }
    }

    @Test
    void boundedPatchCannotAssembleAnOversizedProject() throws Exception {
        try (CreatorServer creator = startProject(branchGroupProject())) {
            final Map<String, RailixValue> before = persisted(creator.baseUri());
            final String template = """
                    {"revision":0,"changes":{"nodes":{"matched":{
                      "id":"matched","use":"railix.field-manipulation","inputs":{
                        "field":["context","result"],"value":[{"option":"literal","inputs":{"literal":"%s"}}]}}}}}
                    """;
            final String body = template.formatted("x".repeat(
                    RailixData.DEFAULT_MAX_SOURCE_BYTES - template.formatted("").length() - 128));
            assertThat(body.getBytes(StandardCharsets.UTF_8).length).isLessThan(RailixData.DEFAULT_MAX_SOURCE_BYTES);

            final HttpResponse<String> response = request(creator.baseUri(), "PATCH", "/api/project", body);

            assertThat(response.statusCode()).isEqualTo(422);
            assertThat(response.body()).contains("CREATOR_EDIT_INVALID", "Edited document exceeds the project source-size limit.");
            assertThat(persisted(creator.baseUri())).isEqualTo(before);
        }
    }

    @Test
    void boundedPatchCannotAssembleOversizedMetadata() throws Exception {
        try (CreatorServer creator = start(directory.resolve("project.json"))) {
            final String initial = RailixJson.write(RailixValue.object(Map.of(
                    "format", RailixValue.number(2), "steps", RailixValue.object(Map.of()),
                    "groups", RailixValue.array(List.copyOf(groups(0, 6_000).values()))
            )));
            assertThat(initial.getBytes(StandardCharsets.UTF_8).length).isLessThan(RailixData.DEFAULT_MAX_SOURCE_BYTES);
            final HttpResponse<String> imported = request(creator.baseUri(), "POST", "/api/creator", initial);
            assertThat(imported.statusCode()).as(imported.body()).isEqualTo(200);
            final Map<String, RailixValue> before = persisted(creator.baseUri());
            final long revision = number(object(imported.body()), "creator_revision");
            final String changes = RailixJson.write(RailixValue.object(Map.of(
                    "groups", RailixValue.object(groups(6_000, 1_000))
            )));
            assertThat(changes.getBytes(StandardCharsets.UTF_8).length + 100).isLessThan(RailixData.DEFAULT_MAX_SOURCE_BYTES);

            final HttpResponse<String> response = edit(creator.baseUri(), "/api/creator", revision, changes);

            assertThat(response.statusCode()).isEqualTo(422);
            assertThat(response.body()).contains("CREATOR_EDIT_INVALID", "Edited document exceeds the project source-size limit.");
            assertThat(persisted(creator.baseUri())).isEqualTo(before);
        }
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("protectedRequests")
    void editEndpointsEnforceRequestProtections(final String path, final String scenario, final int status,
                                              final String expectedStatus) throws Exception {
        try (CreatorServer creator = start(directory.resolve("project.json"));
             HttpClient client = HttpClient.newHttpClient()) {
            final Map<String, RailixValue> before = persisted(creator.baseUri());
            final HttpRequest.Builder builder = HttpRequest.newBuilder(creator.baseUri().resolve(path))
                    .timeout(Duration.ofSeconds(15));
            if (!scenario.equals("missing-token")) {
                builder.header("X-Railix-Creator-Token", scenario.equals("wrong-token")
                        ? "incorrect" : tokenOrIncorrect(creator.baseUri()));
            }
            if (!scenario.equals("missing-media")) {
                builder.header("Content-Type", switch (scenario) {
                    case "wrong-media" -> "text/plain";
                    case "wrong-charset" -> "application/json; charset=us-ascii";
                    default -> "application/json";
                });
            }
            if (scenario.equals("foreign-origin")) {
                builder.header("Origin", "https://foreign.invalid");
            }
            final String method = scenario.equals("wrong-method") ? "PUT" : "PATCH";
            final HttpResponse<String> response = client.send(builder.method(method,
                    HttpRequest.BodyPublishers.ofString("{\"revision\":0,\"changes\":"
                            + changes(path, "forbidden") + "}", StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            assertThat(response.statusCode()).isEqualTo(status);
            assertThat(string(object(response.body()), "status")).isEqualTo(expectedStatus);
            assertThat(persisted(creator.baseUri())).isEqualTo(before);
        }
    }

    private static Stream<Arguments> protectedRequests() {
        return Stream.of("/api/project", "/api/creator").flatMap(path -> Stream.of(
                Arguments.of(path, "missing-token", 401, "unauthorized"),
                Arguments.of(path, "wrong-token", 401, "unauthorized"),
                Arguments.of(path, "foreign-origin", 403, "forbidden-origin"),
                Arguments.of(path, "missing-media", 415, "unsupported-media-type"),
                Arguments.of(path, "wrong-media", 415, "unsupported-media-type"),
                Arguments.of(path, "wrong-charset", 415, "unsupported-media-type"),
                Arguments.of(path, "wrong-method", 405, "method-not-allowed")
        ));
    }

    private CreatorServer startProject(final String source) throws Exception {
        final Path project = directory.resolve("project.json");
        Files.writeString(project, source, StandardCharsets.UTF_8);
        return start(project);
    }

    private static HttpResponse<String> edit(final URI uri, final String path, final long revision, final String changes)
            throws Exception {
        return request(uri, "PATCH", path, "{\"revision\":" + revision + ",\"changes\":" + changes + "}");
    }

    private static RailixValue.ObjectValue receipt(final HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        final RailixValue.ObjectValue body = object(response.body());
        assertThat(body.values()).containsKeys("revision", "creator_revision", "application", "workspace")
                .doesNotContainKeys("project", "creator");
        return body;
    }

    private static RailixValue.ObjectValue workspace(final URI uri) throws Exception {
        final HttpResponse<String> response = request(uri, "GET", "/api/project", "");
        assertThat(response.statusCode()).isEqualTo(200);
        return object(response.body());
    }

    private static Map<String, RailixValue> persisted(final URI uri) throws Exception {
        final Map<String, RailixValue> fields = workspace(uri).values();
        return Map.of("project", fields.get("project"), "creator", fields.get("creator"),
                "revision", fields.get("revision"), "creator_revision", fields.get("creator_revision"));
    }

    private static RailixValue.ObjectValue child(final RailixValue.ObjectValue object, final String field) {
        return (RailixValue.ObjectValue) object.values().get(field);
    }

    private static List<RailixValue> array(final RailixValue.ObjectValue object, final String field) {
        return ((RailixValue.ArrayValue) object.values().get(field)).values();
    }

    private static RailixValue.ObjectValue node(final RailixValue.ObjectValue project, final String id) {
        return array(project, "nodes").stream().map(RailixValue.ObjectValue.class::cast)
                .filter(value -> string(value, "id").equals(id)).findFirst().orElseThrow();
    }

    private static List<RailixValue> links(final RailixValue.ObjectValue project, final String from) {
        return array(project, "links").stream()
                .filter(value -> string((RailixValue.ObjectValue) value, "from").equals(from)).toList();
    }

    private static RailixValue.ObjectValue link(final String from, final String to) {
        return RailixValue.object(Map.of("from", RailixValue.string(from), "to", RailixValue.string(to)));
    }

    private static String changes(final String path, final String name) {
        return path.equals("/api/project") ? "{\"id\":\"" + name + "\"}"
                : "{\"steps\":{\"app\":{\"name\":\"" + name + "\"}}}";
    }

    private static String revisionField(final String path) {
        return path.equals("/api/project") ? "revision" : "creator_revision";
    }

    private static Map<String, RailixValue> groups(final int start, final int count) {
        final Map<String, RailixValue> groups = new LinkedHashMap<>();
        IntStream.range(start, start + count).forEach(index -> {
            final String id = "group-" + index;
            groups.put(id, RailixValue.object(Map.of("id", RailixValue.string(id),
                    "name", RailixValue.string("n".repeat(128)))));
        });
        return groups;
    }
}
