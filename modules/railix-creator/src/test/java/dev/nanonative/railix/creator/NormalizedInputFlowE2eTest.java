package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
final class NormalizedInputFlowE2eTest {
    @TempDir
    static Path directory;

    private final Map<Sample, CreatorServer> applications = new EnumMap<>(Sample.class);

    @BeforeAll
    void startGeneratedApplications() throws Exception {
        applications.put(Sample.DIRECT, start(Sample.DIRECT));
        applications.put(Sample.NESTED_OBJECT, start(Sample.NESTED_OBJECT));
        applications.put(Sample.NESTED_ARRAY, start(Sample.NESTED_ARRAY));
    }

    @AfterAll
    void stopGeneratedApplications() {
        applications.values().forEach(CreatorServer::close);
    }

    @ParameterizedTest(name = "{0} direct field mapping")
    @EnumSource(RailixData.Format.class)
    void normalizedInputExecutesTheSameFieldMapping(final RailixData.Format format) throws Exception {
        final RailixValue.ObjectValue result = run(format, Sample.DIRECT);

        assertThat(context(result).values().get("result")).isEqualTo(RailixValue.string("railix"));
    }

    @ParameterizedTest(name = "{0} malformed input rejection")
    @EnumSource(RailixData.Format.class)
    void malformedInputKeepsItsFormatDiagnostic(final RailixData.Format format) {
        final RailixData.Invalid result = (RailixData.Invalid) RailixData.normalize(
                format,
                document(format, Sample.MALFORMED).getBytes(StandardCharsets.UTF_8)
        );

        assertThat(result.code()).isEqualTo("DATA_" + format + "_INVALID");
    }

    @ParameterizedTest(name = "{0} missing mapped field")
    @EnumSource(RailixData.Format.class)
    void normalizedMissingFieldPreservesTheTargetAndContinues(final RailixData.Format format) throws Exception {
        final RailixValue.ObjectValue result = run(format, Sample.MISSING);

        assertThat(stepExecutions(result)).containsExactly(
                new Execution("normalise-name", "next"),
                new Execution("return-name", "next")
        );
        assertThat(context(result).values().get("result")).isEqualTo(RailixValue.nullValue());
    }

    @ParameterizedTest(name = "{0} nested object mapping")
    @EnumSource(RailixData.Format.class)
    void normalizedNestedObjectExecutesTheSameFieldMapping(final RailixData.Format format) throws Exception {
        final RailixValue.ObjectValue result = run(format, Sample.NESTED_OBJECT);

        assertThat(context(result).values().get("result")).isEqualTo(RailixValue.string("railix"));
    }

    @ParameterizedTest(name = "{0} nested array mapping")
    @EnumSource(RailixData.Format.class)
    void normalizedNestedArrayExecutesTheSameFieldMapping(final RailixData.Format format) throws Exception {
        final RailixValue.ObjectValue result = run(format, Sample.NESTED_ARRAY);

        assertThat(context(result).values().get("result")).isEqualTo(RailixValue.string("railix"));
    }

    private RailixValue.ObjectValue run(final RailixData.Format format, final Sample sample) throws Exception {
        final RailixData.Normalized normalized = (RailixData.Normalized) RailixData.normalize(
                format,
                document(format, sample).getBytes(StandardCharsets.UTF_8)
        );
        final HttpResponse<String> response = request(
                applications.get(application(sample)).baseUri(),
                RailixJson.write(normalized.value())
        );
        final RailixJson.Result parsed = RailixJson.parse(response.body());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(parsed).isInstanceOf(RailixJson.Parsed.class);
        return (RailixValue.ObjectValue) ((RailixJson.Parsed) parsed).value();
    }

    private CreatorServer start(final Sample sample) throws Exception {
        final Path workspace = directory.resolve(sample.name().toLowerCase());
        Files.createDirectories(workspace);
        final Path project = workspace.resolve("railix.project.json");
        Files.writeString(project, project(sample), StandardCharsets.UTF_8);
        return CreatorServer.start(0, project, directory.resolve("railix-home"));
    }

    private static HttpResponse<String> request(final URI baseUri, final String body) throws Exception {
        final HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/api/run/command"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("X-Railix-Creator-Token", baseUri.getRawFragment().substring("token=".length()))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
    }

    private static RailixValue.ObjectValue context(final RailixValue.ObjectValue result) {
        return (RailixValue.ObjectValue) result.values().get("context");
    }

    private static List<Execution> stepExecutions(final RailixValue.ObjectValue result) {
        return ((RailixValue.ArrayValue) result.values().get("steps")).values().stream()
                .map(RailixValue.ObjectValue.class::cast)
                .map(step -> new Execution(
                        ((RailixValue.StringValue) step.values().get("id")).value(),
                        ((RailixValue.StringValue) step.values().get("outcome")).value()
                ))
                .toList();
    }

    private static Sample application(final Sample sample) {
        return sample == Sample.MISSING ? Sample.DIRECT : sample;
    }

    private static String project(final Sample sample) {
        final String direct = """
                {"format":1,"id":"normalized-input","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[
                    {"name":"hello","payload":[],"context":{"payload":{"name":"Hello RAILIX"}}}
                  ]},
                  {"id":"normalise-name","use":"railix.field-manipulation","inputs":{
                    "field":["context","payload","name"],
                    "value":[{"option":"current","inputs":{}}],
                    "steps":[{"use":"text.lowercase","inputs":{}}]
                  }},
                  {"id":"return-name","use":"railix.field-manipulation","inputs":{
                    "field":["context","result"],
                    "value":[{"option":"field","inputs":{"source":["context","payload","name"]}}],
                    "steps":[]
                  }}
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"normalise-name"},
                  {"from":"normalise-name.next","to":"return-name"},
                  {"from":"return-name.next","to":"end"}
                ]}
                """;
        return switch (sample) {
            case NESTED_OBJECT -> direct
                    .replace("\"payload\":{\"name\":\"Hello RAILIX\"}",
                            "\"payload\":{\"person\":{\"name\":\"Hello RAILIX\"}}")
                    .replace("[\"context\",\"payload\",\"name\"]",
                            "[\"context\",\"payload\",\"person\",\"name\"]");
            case NESTED_ARRAY -> direct
                    .replace("\"payload\":{\"name\":\"Hello RAILIX\"}",
                            "\"payload\":{\"people\":[{\"name\":\"Hello RAILIX\"}]}")
                    .replace("[\"context\",\"payload\",\"name\"]",
                            "[\"context\",\"payload\",\"people\",0,\"name\"]");
            default -> direct;
        };
    }

    private static String document(final RailixData.Format format, final Sample sample) {
        return switch (format) {
            case JSON -> switch (sample) {
                case DIRECT -> "{\"payload\":{\"name\":\"RAILIX\"}}";
                case MISSING -> "{\"payload\":{}}";
                case NESTED_OBJECT -> "{\"payload\":{\"person\":{\"name\":\"RAILIX\"}}}";
                case NESTED_ARRAY -> "{\"payload\":{\"people\":[{\"name\":\"RAILIX\"}]}}";
                case MALFORMED -> "{";
            };
            case YAML -> switch (sample) {
                case DIRECT -> "payload:\n  name: \"RAILIX\"";
                case MISSING -> "payload: {}";
                case NESTED_OBJECT -> "payload:\n  person:\n    name: \"RAILIX\"";
                case NESTED_ARRAY -> "payload:\n  people:\n    -\n      name: \"RAILIX\"";
                case MALFORMED -> "payload: \"RAILIX";
            };
            case XML -> switch (sample) {
                case DIRECT -> """
                        <object><field name="payload"><object>
                          <field name="name"><string>RAILIX</string></field>
                        </object></field></object>
                        """;
                case MISSING -> """
                        <object><field name="payload"><object/></field></object>
                        """;
                case NESTED_OBJECT -> """
                        <object><field name="payload"><object>
                          <field name="person"><object>
                            <field name="name"><string>RAILIX</string></field>
                          </object></field>
                        </object></field></object>
                        """;
                case NESTED_ARRAY -> """
                        <object><field name="payload"><object>
                          <field name="people"><array><item><object>
                            <field name="name"><string>RAILIX</string></field>
                          </object></item></array></field>
                        </object></field></object>
                        """;
                case MALFORMED -> "<object>";
            };
        };
    }

    private enum Sample {
        DIRECT,
        MALFORMED,
        MISSING,
        NESTED_OBJECT,
        NESTED_ARRAY
    }

    private record Execution(String stepId, String outcome) {
    }
}
