package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.project.CompileResult;
import dev.nanonative.railix.core.project.ProjectCompiler;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;
import dev.nanonative.railix.stdlib.StandardLibrary;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import thirdparty.conformance.GenericContractSteps;

import static org.assertj.core.api.Assertions.assertThat;

final class CreatorConfigurationE2eTest extends CreatorServerE2eSupport {
    @Test
    void transformingOneUseDoesNotMutateAnotherUseOfTheSameKey() throws Exception {
        final String source = """
                {"format":1,"id":"shared-config","configuration":{"shared":"MiXeD"},"nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[{"name":"empty","payload":[]}]},
                  {"id":"first","use":"railix.field-manipulation","inputs":{"field":["context","first"],
                    "value":[{"option":"literal","inputs":{},"input_sources":{"literal":{"configuration":"shared"}}}],
                    "steps":[{"use":"text.lowercase","inputs":{}}]}},
                  {"id":"result","use":"railix.field-manipulation","inputs":{"field":["context","result"],
                    "value":[{"option":"literal","inputs":{},"input_sources":{"literal":{"configuration":"shared"}}}],"steps":[]}}
                ],"links":[{"from":"app.start","to":"command"},{"from":"command.next","to":"first"},
                  {"from":"first.next","to":"result"},{"from":"result.next","to":"end"}]}
                """;
        try (var application = GeneratedApplicationFixture.start(directory.resolve("shared"), source)) {
            final var response = application.run("command", "{}");
            assertThat(response.status()).isEqualTo(200);
            final var context = (RailixValue.ObjectValue) object(response.body()).values().get("context");
            assertThat(context.values()).containsEntry("first", RailixValue.string("mixed"))
                    .containsEntry("result", RailixValue.string("MiXeD"));
        }
    }

    @Test
    void aLockedThirdPartyOptionReceivesTheExactConfiguredValue() throws Exception {
        final String source = """
                {"format":1,"id":"third-party-config","configuration":{"exact":9007199254740993},"nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[{"name":"empty","payload":[]}]},
                  {"id":"result","use":"thirdparty.config","inputs":{"choice":{"option":"literal","inputs":{},
                    "input_sources":{"value":{"configuration":"exact"}}}}}
                ],"links":[{"from":"app.start","to":"command"},{"from":"command.next","to":"result"},
                  {"from":"result.next","to":"end"}]}
                """;
        final var definition = StepDefinition.named("thirdparty.config", "1")
                .input("target", StepDefinition.Input.path(StepDefinition.PathAccess.WRITE).defaultPath("context", "result"))
                .input("choice", StepDefinition.Input.options(StepDefinition.Input.option("literal")
                        .input("value", StepDefinition.Input.json(ValueShape.ANY)).fromOwned("value")))
                .run(GenericContractSteps.Option.class);
        try (var application = GeneratedApplicationFixture.start(directory.resolve("external"), source, List.of(definition),
                GenericContractSteps.class, GenericContractSteps.Option.class)) {
            final var response = application.run("command", "{}");
            assertThat(response.status()).isEqualTo(200);
            assertThat(((RailixValue.ObjectValue) object(response.body()).values().get("context")).values().get("result"))
                    .isEqualTo(RailixValue.number(new BigDecimal("9007199254740993")));
        }
    }

    @Test
    void editsAreRevisionCheckedAndPreserveAuthoredReferencesAcrossReopening() throws Exception {
        final var project = directory.resolve("project.json");
        Files.writeString(project, configuredProject("\"RAILIX\""));
        try (var creator = start(project)) {
            final var before = application(creator.baseUri());
            final String a = request(creator.baseUri(), "GET", "/api/editor?node=result", "").body();
            assertThat(a).contains("\"configuration\":{\"value\":\"RAILIX\"}", "\"input_sources\"");
            final String body = "{\"revision\":0,\"changes\":{\"configuration\":{\"value\":\"NEXT\",\"precise\":9007199254740993,\"off\":false}}}";
            final var saved = request(creator.baseUri(), "PATCH", "/api/project", body);
            assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);
            assertThat(request(creator.baseUri(), "PATCH", "/api/project", body).statusCode()).isEqualTo(409);
            assertThat(request(creator.baseUri(), "GET", "/api/editor?node=command", "").body())
                    .contains("9007199254740993", "\"off\":false");
            assertThat(number(application(creator.baseUri()), "pid")).isNotEqualTo(number(before, "pid"));
            assertThat(Files.readString(project)).contains("\"configuration\":\"value\"");
            assertThat(Files.readString(directory.resolve("railix.creator.json"))).doesNotContain("configuration", "precise");
        }
        try (var creator = start(project)) {
            assertThat(request(creator.baseUri(), "GET", "/api/editor?node=result", "").body()).contains("\"value\":\"NEXT\"", "9007199254740993");
        }
    }

    @Test
    void anInUseDeletionIsRejectedWithoutChangingAcceptedSourceOrApplication() throws Exception {
        final var project = directory.resolve("project.json");
        Files.writeString(project, configuredProject("\"RAILIX\""));
        try (var creator = start(project)) {
            final String source = Files.readString(project);
            final long pid = number(application(creator.baseUri()), "pid");
            final var rejected = request(creator.baseUri(), "PATCH", "/api/project",
                    "{\"revision\":0,\"changes\":{\"configuration\":{\"value\":null}}}");
            assertThat(rejected.statusCode()).as(rejected.body()).isEqualTo(422);
            assertThat(rejected.body()).contains("PROJECT_CONFIGURATION_KEY_UNKNOWN", "input_sources", "value");
            assertThat(Files.readString(project)).isEqualTo(source);
            assertThat(number(application(creator.baseUri()), "pid")).isEqualTo(pid);
        }
    }

    @Test
    void invalidConfigurationCharactersAreRejectedWithoutChangingAcceptedSourceOrApplication() throws Exception {
        final var project = directory.resolve("project.json");
        final String source = configuredProject("\"RAILIX\"");
        Files.writeString(project, source);
        try (var creator = start(project)) {
            final String accepted = Files.readString(project);
            final long pid = number(application(creator.baseUri()), "pid");
            for (final String key : List.of("bad name", "bad.name", "bad@name", "bad/name", "bad\\name", "line\nkey", "tab\tkey")) {
                final String encoded = RailixJson.write(RailixValue.string(key));
                final var response = request(creator.baseUri(), "PATCH", "/api/project",
                        "{\"revision\":0,\"changes\":{\"configuration\":{" + encoded + ":true}}}");
                assertThat(response.statusCode()).as(response.body()).isEqualTo(422);
                assertThat(response.body()).contains("PROJECT_CONFIGURATION_KEY_INVALID");
                assertThat(Files.readString(project)).isEqualTo(accepted);
            }
            for (final String value : List.of("has space", "line\nvalue", "return\rvalue", "tab\tvalue", "nul\0value")) {
                final String encoded = RailixJson.write(RailixValue.string(value));
                final var response = request(creator.baseUri(), "PATCH", "/api/project",
                        "{\"revision\":0,\"changes\":{\"configuration\":{\"value\":" + encoded + "}}}");
                assertThat(response.statusCode()).as(response.body()).isEqualTo(422);
                assertThat(response.body()).contains("PROJECT_CONFIGURATION_VALUE_INVALID");
                assertThat(Files.readString(project)).isEqualTo(accepted);
            }
            assertThat(number(application(creator.baseUri()), "pid")).isEqualTo(pid);
        }
    }

    @Test
    void concurrentEditsDoNotLoseConfigurationKeys() throws Exception {
        final var project = directory.resolve("project.json");
        Files.writeString(project, configuredProject("\"RAILIX\""));
        try (var creator = start(project)) {
            final var tasks = List.of("a", "b").stream().map(key -> CompletableFuture.supplyAsync(() -> {
                try {
                    return request(creator.baseUri(), "PATCH", "/api/project",
                            "{\"revision\":0,\"changes\":{\"configuration\":{\"" + key + "\":true}}}").statusCode();
                } catch (final Exception failure) { throw new AssertionError(failure); }
            })).toList();
            final var results = tasks.stream().map(CompletableFuture::join).toList();
            assertThat(results).contains(200);
            // A second request may read the same accepted revision while compilation is pending;
            // publication must either reject it or supersede the earlier build without losing a saved edit.
            assertThat(results).allMatch(status -> status == 200 || status == 409);
            final var configuration = (RailixValue.ObjectValue) object(Files.readString(project)).values().get("configuration");
            assertThat(configuration.values()).containsKey("value");
            assertThat(configuration.values().keySet()).anyMatch(key -> key.equals("a") || key.equals("b"));
            for (int index = 0; index < results.size(); index++) {
                if (results.get(index) == 200) assertThat(configuration.values()).containsKey(List.of("a", "b").get(index));
            }
        }
    }

    @Test
    void oldAndNewGeneratedApplicationsUseTheirOwnImmutableValues() throws Exception {
        try (var a = GeneratedApplicationFixture.start(directory.resolve("a"), configuredProject("\"FIRST\""));
             var b = GeneratedApplicationFixture.start(directory.resolve("b"), configuredProject("\"SECOND\""))) {
            assertThat(a.run("command", "{}").body()).contains("FIRST").doesNotContain("SECOND");
            assertThat(b.run("command", "{}").body()).contains("SECOND").doesNotContain("FIRST");
            assertThat(a.run("command", "{}").body()).contains("FIRST");
        }
        final String source = configuredProject("9007199254740993");
        final var result = ProjectCompiler.compileApplication(source, StandardLibrary.catalog());
        assertThat(result).isInstanceOf(CompileResult.Compiled.class);
        final var jar = CreatorFirstExecutionArtifacts.production(directory.resolve("production"), source);
        final var probe = CreatorFirstExecutionArtifacts.compileProductionProbe(directory.resolve("probe"), jar);
        final var executed = probe.run("parity-success");
        assertThat(executed.exitCode()).isZero();
        assertThat(executed.output()).contains("9007199254740993");
    }

    static String configuredProject(final String value) {
        return literalProject().replace("\"format\":1", "\"format\":1,\"configuration\":{\"value\":" + value + "}")
                .replace("\"inputs\":{\"literal\":\"RAILIX\"}", "\"inputs\":{},\"input_sources\":{\"literal\":{\"configuration\":\"value\"}}");
    }

    static String literalProject() {
        return """
                {"format":1,"id":"configured-project","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[{"name":"empty","payload":[]}]},
                  {"id":"result","use":"railix.field-manipulation","inputs":{"field":["context","result"],
                    "value":[{"option":"literal","inputs":{"literal":"RAILIX"}}],"steps":[]}}
                ],"links":[{"from":"app.start","to":"command"},{"from":"command.next","to":"result"},
                  {"from":"result.next","to":"end"}]}
                """;
    }
}
