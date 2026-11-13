package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.project.CompileResult;
import dev.nanonative.railix.core.project.ProjectCompiler;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.RailixValue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.jar.JarFile;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(180)
final class TransformGeneratedE2eTest {
    static final Map<String, String> FALLBACKS = Map.of(
            "text", "\"fallback\"", "number", "0", "integer", "0", "boolean", "false",
            "list", "[]", "object", "{}", "timestamp_utc_millis", "0",
            "json_text", "\"fallback\"", "json_value", "\"fallback\"");
    private GeneratedApplicationFixture application;
    private GeneratedApplicationFixture fallbackApplication;

    @BeforeAll
    void start(@TempDir final Path directory) throws Exception {
        application = GeneratedApplicationFixture.start(directory.resolve("plain"), project(false));
        fallbackApplication = GeneratedApplicationFixture.start(directory.resolve("fallback"), project(true));
    }

    @AfterAll
    void stop() {
        if (application != null) application.close();
        if (fallbackApplication != null) fallbackApplication.close();
    }

    @ParameterizedTest(name = "{0}: {1} -> {2}")
    @MethodSource("conversions")
    void convertsRealRequestsWithoutChangingTheirSource(final String type, final String value, final String expected) throws Exception {
        final var context = run(type, value);
        assertThat(context.values().get("result")).isEqualTo(json(expected));
        assertThat(context.values().get("value")).isEqualTo(json(value));
        assertThat(context.values().get("route")).isEqualTo(RailixValue.string("ok"));
    }

    static Stream<Arguments> conversions() {
        return Stream.of(
                Arguments.of("text", "42", "\"42\""),
                Arguments.of("text", "100", "\"100\""),
                Arguments.of("text", "0.0000001", "\"0.0000001\""),
                Arguments.of("text", "true", "\"true\""),
                Arguments.of("text", "\"\"", "\"\""),
                Arguments.of("text", "\"A\\nB\\\"C\"", "\"A\\nB\\\"C\""),
                Arguments.of("number", "\"42\"", "42"),
                Arguments.of("number", "\"9007199254740993.123456789\"", "9007199254740993.123456789"),
                Arguments.of("number", "true", "1"),
                Arguments.of("number", "false", "0"),
                Arguments.of("number", "\"1.25e2\"", "125"),
                Arguments.of("number", "\"+42\"", "42"),
                Arguments.of("number", "\"001.20\"", "1.2"),
                Arguments.of("integer", "\"42\"", "42"),
                Arguments.of("integer", "42.0", "42"),
                Arguments.of("integer", "\"9007199254740993123456789\"", "9007199254740993123456789"),
                Arguments.of("boolean", "true", "true"),
                Arguments.of("boolean", "\"true\"", "true"),
                Arguments.of("boolean", "\"false\"", "false"),
                Arguments.of("boolean", "\"1\"", "true"),
                Arguments.of("boolean", "0", "false"),
                Arguments.of("boolean", "1.0", "true"),
                Arguments.of("list", "42", "[42]"),
                Arguments.of("list", "[1,\"two\",null]", "[1,\"two\",null]"),
                Arguments.of("list", "{\"id\":1}", "[{\"id\":1}]"),
                Arguments.of("list", "\"[1,2]\"", "[\"[1,2]\"]"),
                Arguments.of("list_fallback", "[42]", "[42]"),
                Arguments.of("object", "{\"a\":[1,true,null]}", "{\"a\":[1,true,null]}"),
                Arguments.of("timestamp_utc_millis", "\"2026-01-01T00:00:00Z\"", "1767225600000"),
                Arguments.of("timestamp_utc_millis", "\"2026-01-01T01:00:00+01:00\"", "1767225600000"),
                Arguments.of("timestamp_utc_millis", "1800000000", "1800000000"),
                Arguments.of("timestamp_utc_millis", "\"0\"", "0"),
                Arguments.of("json_text", "{\"z\":1,\"a\":false}", "\"{\\\"a\\\":false,\\\"z\\\":1}\""),
                Arguments.of("json_text", "\"hello\"", "\"\\\"hello\\\"\""),
                Arguments.of("json_value", "\"[1,true,null]\"", "[1,true,null]"),
                Arguments.of("json_value", "\"{\\\"id\\\":9007199254740993}\"", "{\"id\":9007199254740993}"),
                Arguments.of("json_value", "\"42\"", "42"),
                Arguments.of("json_value", "\"\\\"hello\\\"\"", "\"hello\"")
        );
    }

    @ParameterizedTest(name = "{0} rejects {1}")
    @MethodSource("invalidConversions")
    void invalidValuesUseOnlyTheExplicitFallback(final String type, final String value) throws Exception {
        final var without = run(type, value);
        assertThat(without.values().get("route")).isEqualTo(RailixValue.string("invalid"));
        assertThat(without.values().get("result")).isEqualTo(RailixValue.string("unchanged"));
        final var with = run(type + "_fallback", value);
        assertThat(with.values().get("route")).isEqualTo(RailixValue.string("ok"));
        assertThat(with.values().get("result")).isEqualTo(json(FALLBACKS.get(type)));
    }

    static Stream<Arguments> invalidConversions() {
        return Stream.of(
                Arguments.of("number", "\"oops\""), Arguments.of("number", "\"NaN\""),
                Arguments.of("number", "\"\""), Arguments.of("number", "\" \""),
                Arguments.of("number", "\"Infinity\""), Arguments.of("number", "[]"),
                Arguments.of("number", "\"1e999999999\""), Arguments.of("integer", "42.5"),
                Arguments.of("integer", "\"42.5\""), Arguments.of("boolean", "\"unknown\""),
                Arguments.of("boolean", "2"), Arguments.of("boolean", "1.5"),
                Arguments.of("text", "[]"), Arguments.of("text", "{}"),
                Arguments.of("object", "[]"), Arguments.of("object", "\"{\\\"a\\\":1}\""),
                Arguments.of("timestamp_utc_millis", "\"2026-01-01T00:00:00\""),
                Arguments.of("timestamp_utc_millis", "\"2026-02-30T00:00:00Z\""),
                Arguments.of("timestamp_utc_millis", "9223372036854775808"),
                Arguments.of("timestamp_utc_millis", "1.5"),
                Arguments.of("timestamp_utc_millis", "true"),
                Arguments.of("timestamp_utc_millis", "false"),
                Arguments.of("json_value", "\"{broken}\""),
                Arguments.of("json_value", "\"{\\\"a\\\":1,\\\"a\\\":2}\""),
                Arguments.of("json_value", "\"null\""), Arguments.of("json_value", "42")
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"text", "number", "integer", "boolean", "list", "object", "timestamp_utc_millis", "json_text", "json_value"})
    void absentAndNullSourcesDoNotBecomeInventedValues(final String type) throws Exception {
        for (final String value : List.of("null", "missing")) {
            assertThat(run(type, value).values().get("route")).isEqualTo(RailixValue.string("invalid"));
            final var with = run(type + "_fallback", value);
            assertThat(with.values().get("result")).isEqualTo(json(FALLBACKS.get(type)));
            assertThat(with.values().get("route")).isEqualTo(RailixValue.string("ok"));
        }
    }

    @Test
    void configuredFallbackDoesNotReplaceAValidConversion() throws Exception {
        assertThat(run("integer_fallback", "\"123\"").values().get("result")).isEqualTo(RailixValue.number(123));
        assertThat(run("text_fallback", "\"\"").values().get("result")).isEqualTo(RailixValue.string(""));
    }

    @Test
    void concurrentRequestsKeepTheirConversionAndFallbackValuesIndependent() throws Exception {
        try (var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            final List<java.util.concurrent.Future<?>> requests = new ArrayList<>();
            for (int index = 0; index < 32; index++) {
                final int value = index;
                requests.add(workers.submit(() -> {
                    final String source = value % 2 == 0 ? "\"" + value + "\"" : "\"invalid\"";
                    final var result = run("integer_fallback", source);
                    assertThat(result.values().get("result")).isEqualTo(json(Integer.toString(value % 2 == 0 ? value : 0)));
                    return result;
                }));
            }
            for (final var request : requests) request.get(30, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @Test
    void conversionRetainsTheCanonicalDepthBoundaryAndRejectsOneExtraWrapper() throws Exception {
        final String source = "[".repeat(62) + "0" + "]".repeat(62);
        assertThat(run("json_value", RailixJson.write(RailixValue.string(source))).values().get("result")).isEqualTo(json(source));
        final String boundary = "[".repeat(63) + "0" + "]".repeat(63);
        assertThat(run("json_value", RailixJson.write(RailixValue.string(boundary))).values().get("result")).isEqualTo(json(boundary));
        final String tooDeep = RailixJson.write(RailixValue.string("[".repeat(64) + "0" + "]".repeat(64)));
        assertThat(run("json_value", tooDeep).values().get("route")).isEqualTo(RailixValue.string("invalid"));
        assertThat(run("json_value_fallback", tooDeep).values().get("result")).isEqualTo(RailixValue.string("fallback"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"wrong\"", "0.5", "null"})
    void invalidIntegerFallbackIsRejectedAtBuildTime(final String fallback) {
        final String project = cliProject("integer", fallback);
        assertThat(ProjectCompiler.compileApplication(project, GeneratedApplicationFixture.catalog()))
                .isInstanceOf(CompileResult.Rejected.class);
    }

    @Test
    void invalidContextInACopiedBranchCannotHideBehindAHealthyParent(@TempDir final Path directory) throws Exception {
        try (var fixture = GeneratedApplicationFixture.start(directory, invalidContextProject(), List.of(invalidContextDefinition()),
                thirdparty.conformance.StepRuntimeContractProbe.class)) {
            final var response = fixture.run("command", "{\"payload\":{\"arguments\":[]}}");
            assertThat(response.status()).as(response.body()).isEqualTo(500);
            assertThat(response.body()).contains("RUN_RESPONSE_INVALID").doesNotContain("\"context\"");
            final var events = fixture.trace("command", "{\"payload\":{\"arguments\":[]}}");
            assertThat(events).anySatisfy(event -> {
                assertThat(event.values().get("type")).isEqualTo(RailixValue.string("trace_error"));
                assertThat(event.values().get("code")).isEqualTo(RailixValue.string("RUN_RESPONSE_INVALID"));
            });
            assertThat(events).noneMatch(event -> RailixValue.string("result").equals(event.values().get("type")));
        }
    }

    static dev.nanonative.railix.core.step.StepDefinition invalidContextDefinition() {
        return dev.nanonative.railix.core.step.StepDefinition.named("external.invalid-context", "1")
                .input("mode", dev.nanonative.railix.core.step.StepDefinition.Input.json(
                        dev.nanonative.railix.core.value.ValueShape.STRING))
                .input("target", dev.nanonative.railix.core.step.StepDefinition.Input.path(
                        dev.nanonative.railix.core.step.StepDefinition.PathAccess.WRITE).defaultPath("context", "bad"))
                .implementation(thirdparty.conformance.StepRuntimeContractProbe.class);
    }

    static String invalidContextProject() {
        return """
                {"format":1,"id":"copied-context-validation","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[{"name":"empty","payload":[]}]},
                  {"id":"split","use":"railix.branch","inputs":{"selection":{"option":"all","inputs":{}},
                    "routes":[{"outcome":"bad","groups":[],"inputs":{"own_context":true}},
                              {"outcome":"healthy","groups":[],"inputs":{}}]}},
                  {"id":"bad","use":"external.invalid-context","inputs":{"mode":"payload-depth"}},
                  {"id":"healthy","use":"railix.field-manipulation","inputs":{"field":["context","result"],
                    "value":[{"option":"literal","inputs":{"literal":"healthy"}}]}}
                ],"links":[{"from":"app.start","to":"command"},{"from":"command.next","to":"split"},
                  {"from":"split.bad","to":"bad"},{"from":"split.healthy","to":"healthy"},
                  {"from":"split.otherwise","to":"end"},{"from":"bad.next","to":"end"},{"from":"healthy.next","to":"end"}]}
                """;
    }

    @Test
    void productionPackagesTypeMapWithoutAuthoringClassesAndWorksWithoutCreator(@TempDir final Path directory) throws Exception {
        assertThat(GeneratedApplicationFixture.catalog().implementation("railix.transform").orElseThrow().ownership())
                .isEqualTo(dev.nanonative.railix.core.step.StepCatalog.Ownership.BUNDLE);
        final Path jar = CreatorFirstExecutionArtifacts.production(directory, cliProject("integer", "0"));
        try (var archive = new JarFile(jar.toFile())) {
            assertThat(archive.getJarEntry("berlin/yuna/typemap/logic/TypeConverter.class")).isNotNull();
            assertThat(archive.getJarEntry("dev/nanonative/railix/stdlib/StandardLibrary.class")).isNull();
            assertThat(archive.getJarEntry("dev/nanonative/railix/core/step/StepDefinition.class")).isNull();
            assertThat(archive.getJarEntry("META-INF/licenses/type-map/LICENSE")).isNotNull();
        }
        final var success = CreatorFirstExecutionArtifacts.runJar(jar, "42");
        assertThat(success.exitCode()).as(success.output()).isZero();
        assertThat(success.output()).isEqualTo("42");
        final var fallback = CreatorFirstExecutionArtifacts.runJar(jar, "oops");
        assertThat(fallback.exitCode()).as(fallback.output()).isZero();
        assertThat(fallback.output()).isEqualTo("0");
        final Path plain = CreatorFirstExecutionArtifacts.production(directory.resolve("plain"), CreatorProjects.lowercaseCli());
        try (var archive = new JarFile(plain.toFile())) {
            assertThat(archive.stream().map(entry -> entry.getName())).noneMatch(name -> name.startsWith("berlin/yuna/typemap/"));
        }
    }

    @Test
    void measureGeneratedSourceBoundaryAgainstTheExistingNumberConversion(@TempDir final Path directory) throws Exception {
        final String baseline = cliProject("number", "0").replace("\"use\":\"railix.transform\",\"inputs\":{\n"
                        + "    \"source\":[\"context\",\"payload\",\"arguments\",0],\"target\":[\"context\",\"result\"],\n"
                        + "    \"convert_to\":{\"option\":\"number\",\"inputs\":{\"fallback\":0}}}",
                "\"use\":\"text.to-number\",\"inputs\":{},\"receives\":{\"value\":[\"context\",\"payload\",\"arguments\",0]},"
                        + "\"returns\":{\"value\":[\"context\",\"result\"]}");
        assertThat(baseline).doesNotContain("railix.transform");
        final String probe = """
                package dev.nanonative.railix.core.project;
                import dev.nanonative.railix.core.value.RailixValue;
                import java.util.*;
                public final class TransformTimingProbe {
                    public static void main(String[] args) {
                        final var app = RailixApplication.runtime();
                        final var input = Map.<String,RailixValue>of("arguments", RailixValue.array(List.of(RailixValue.string("42"))));
                        final var expected = RailixValue.number(42);
                        for (int round = -5; round < 7; round++) {
                            final long start = System.nanoTime();
                            for (int i = 0; i < 10000; i++) {
                                final var result = app.runSource("application.arguments", input);
                                if (!expected.equals(result.responses().get("output"))) throw new AssertionError(result);
                            }
                            if (round >= 0) System.out.println((System.nanoTime() - start) / 10000);
                        }
                    }
                }
                """;
        final List<String> evidence = new ArrayList<>();
        for (final var entry : Map.of("existing", baseline, "transform", cliProject("number", "0")).entrySet()) {
            final Path jar = CreatorFirstExecutionArtifacts.production(directory.resolve(entry.getKey()), entry.getValue());
            final Path classes = CreatorFirstExecutionArtifacts.compileProbe(directory.resolve(entry.getKey() + "-probe"),
                    jar, "dev.nanonative.railix.core.project.TransformTimingProbe", probe);
            final var result = CreatorFirstExecutionArtifacts.runProbe(classes, jar,
                    "dev.nanonative.railix.core.project.TransformTimingProbe");
            assertThat(result.exitCode()).as(result.output()).isZero();
            final List<Long> samples = result.output().lines().map(Long::parseLong).sorted().toList();
            assertThat(samples).hasSize(7);
            evidence.add(entry.getKey() + " median_ns=" + samples.get(3) + " samples_ns=" + samples
                    + " jar_bytes=" + java.nio.file.Files.size(jar));
        }
        java.nio.file.Files.write(Path.of("target", "transform-performance.txt"), evidence);
    }

    RailixValue.ObjectValue run(final String type, final String value) throws Exception {
        final String context = "{\"payload\":{\"arguments\":[]},\"mode\":\"" + type.replace('_', '-')
                + "\",\"result\":\"unchanged\"" + (value.equals("missing") ? "" : ",\"value\":" + value) + "}";
        final var response = (type.endsWith("_fallback") ? fallbackApplication : application).run("command", context);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        final var body = (RailixValue.ObjectValue) ((RailixJson.Parsed) RailixJson.parseDocument(response.body())).value();
        assertThat(body.values().get("status")).isEqualTo(RailixValue.string("succeeded"));
        return (RailixValue.ObjectValue) json(RailixJson.write(body.values().get("context")));
    }

    static RailixValue json(final String source) {
        return ((RailixData.Normalized) RailixData.normalize(RailixData.Format.JSON,
                source.getBytes(java.nio.charset.StandardCharsets.UTF_8))).value();
    }

    static String cliProject(final String type, final String fallback) {
        return """
                {"format":1,"id":"transform-cli","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[{"name":"value","payload":["42"]}]},
                  {"id":"convert","use":"railix.transform","inputs":{
                    "source":["context","payload","arguments",0],"target":["context","result"],
                    "convert_to":{"option":"%s","inputs":{"fallback":%s}}}}
                ],"links":[{"from":"app.start","to":"command"},{"from":"command.next","to":"convert"},
                  {"from":"convert.ok","to":"end"},{"from":"convert.invalid","to":"end"}]}
                """.formatted(type, fallback);
    }

    static String project(final boolean fallback) {
        final List<String> nodes = new ArrayList<>();
        final List<String> links = new ArrayList<>(List.of("{\"from\":\"app.start\",\"to\":\"command\"}",
                "{\"from\":\"command.next\",\"to\":\"seed\"}",
                "{\"from\":\"seed.next\",\"to\":\"dispatch-0\"}"));
        nodes.add("{\"id\":\"seed\",\"use\":\"railix.field-manipulation\",\"inputs\":{"
                + "\"field\":[\"context\",\"result\"],\"value\":[{\"option\":\"literal\",\"inputs\":{\"literal\":\"unchanged\"}}]}}");
        int index = 0;
        for (final String type : FALLBACKS.keySet().stream().sorted().toList()) {
                final String id = type.replace('_', '-') + (fallback ? "-fallback" : "");
                nodes.add("{\"id\":\"" + id + "\",\"use\":\"railix.transform\",\"inputs\":{"
                        + "\"source\":[\"context\",\"value\"],\"target\":[\"context\",\"result\"],"
                        + "\"convert_to\":{\"option\":\"" + type + "\",\"inputs\":{" + (fallback ? "\"fallback\":" + FALLBACKS.get(type) : "") + "}}}}");
                final String route = "{\"outcome\":\"matched\",\"inputs\":{},\"groups\":[[{\"option\":\"field\",\"inputs\":{"
                        + "\"field\":[\"context\",\"mode\"]},\"when\":{\"transforms\":[],\"all\":[[{\"use\":\"value.equals\",\"inputs\":{\"expected\":\"" + id + "\"}}]]}}]]}";
                nodes.add("{\"id\":\"dispatch-" + index + "\",\"use\":\"railix.branch\",\"inputs\":{\"routes\":[" + route + "]}}");
                links.add("{\"from\":\"dispatch-" + index + ".matched\",\"to\":\"" + id + "\"}");
                links.add("{\"from\":\"dispatch-" + index + ".otherwise\",\"to\":\""
                        + (++index < FALLBACKS.size() ? "dispatch-" + index : "end") + "\"}");
                links.add("{\"from\":\"" + id + ".ok\",\"to\":\"ok\"}");
                links.add("{\"from\":\"" + id + ".invalid\",\"to\":\"invalid\"}");
        }
        for (final String route : List.of("ok", "invalid")) {
            nodes.add("{\"id\":\"" + route + "\",\"use\":\"railix.field-manipulation\",\"inputs\":{"
                    + "\"field\":[\"context\",\"route\"],\"value\":[{\"option\":\"literal\",\"inputs\":{\"literal\":\"" + route + "\"}}]}}");
            links.add("{\"from\":\"" + route + ".next\",\"to\":\"end\"}");
        }
        return "{\"format\":1,\"id\":\"transform-conformance\",\"nodes\":["
                + "{\"id\":\"app\",\"use\":\"railix.app\",\"inputs\":{}},{\"id\":\"command\",\"use\":\"railix.trigger.cli\",\"inputs\":{},\"examples\":[{\"name\":\"empty\",\"payload\":[]}]},"
                + String.join(",", nodes) + "],\"links\":[" + String.join(",", links)
                + "]}";
    }
}
