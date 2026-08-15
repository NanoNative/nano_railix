package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.step.StepContractJson;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import thirdparty.conformance.InaccessibleConstructorStepHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(90)
final class GeneratedCompilationDiagnosticE2eTest {
    private static final String STEP_ID = "diagnostic.inaccessible-constructor";
    private static final String IMPLEMENTATION = InaccessibleConstructorStepHandler.class.getCanonicalName();
    private static final String IMPLEMENTATION_ENTRY =
            InaccessibleConstructorStepHandler.class.getName().replace('.', '/') + ".class";

    @Test
    void creatorRejectsAnUnconstructableLockedImplementationWithAStableDiagnostic(
            @TempDir final Path workspace
    ) throws Exception {
        final Path project = workspace.resolve("railix.project.json");
        final Path railixHome = workspace.resolve("railix-home");
        installBundle(project, railixHome);

        try (CreatorServer creator = CreatorServer.start(0, project, railixHome)) {
            final String deployedProject = Files.readString(project, StandardCharsets.UTF_8);
            final RailixValue.ObjectValue deployedApplication = application(creator.baseUri());

            final HttpResponse<String> response = request(creator.baseUri(), invalidProject());

            assertThat(response.statusCode()).isEqualTo(422);
            final RailixValue.ObjectValue body = object(response.body());
            assertThat(text(body, "status")).isEqualTo("rejected");
            final RailixValue.ArrayValue diagnostics = (RailixValue.ArrayValue) body.values().get("diagnostics");
            assertThat(diagnostics.values()).containsExactly(RailixValue.object(Map.of(
                    "code", RailixValue.string("STEP_IMPLEMENTATION_INVALID"),
                    "message", RailixValue.string(
                            "Generated application cannot construct or invoke Step implementation: "
                                    + IMPLEMENTATION + "."
                    ),
                    "path", RailixValue.string("")
            )));
            final RailixValue.ObjectValue current = (RailixValue.ObjectValue) body.values().get("application");
            assertThat(number(current, "pid")).isEqualTo(number(deployedApplication, "pid"));
            assertThat(text(current, "fingerprint")).isEqualTo(text(deployedApplication, "fingerprint"));
            assertThat(Files.readString(project, StandardCharsets.UTF_8)).isEqualTo(deployedProject);
            assertThat(response.body()).doesNotContain("javac", "compiler.err", "private access", "line ");
        }
    }

    private static void installBundle(final Path project, final Path railixHome) throws Exception {
        final StepDefinition definition = StepDefinition.named(STEP_ID, "1")
                .run(InaccessibleConstructorStepHandler.class);
        final Path bundle = project.resolveSibling("invalid-step-bundle.jar");
        try (InputStream implementation = InaccessibleConstructorStepHandler.class
                .getResourceAsStream("/" + IMPLEMENTATION_ENTRY)) {
            if (implementation == null) {
                throw new IOException("Invalid Step implementation fixture is unavailable.");
            }
            try (OutputStream output = Files.newOutputStream(bundle);
                 JarOutputStream jar = new JarOutputStream(output)) {
                write(jar, IMPLEMENTATION_ENTRY, implementation.readAllBytes());
                write(jar, "META-INF/railix/steps.json",
                        StepContractJson.writeManifest(List.of(definition)).getBytes(StandardCharsets.UTF_8));
            }
        }
        final String artifactDigest = digest(Files.readAllBytes(bundle));
        final Path store = railixHome.resolve("artifacts");
        Files.createDirectories(store);
        Files.copy(bundle, store.resolve(artifactDigest + ".jar"));
        final String contractDigest = digest(
                StepContractJson.write(definition).getBytes(StandardCharsets.UTF_8)
        );
        final RailixValue lock = RailixValue.object(Map.of(
                "format", RailixValue.number(1),
                "artifacts", RailixValue.array(List.of(RailixValue.object(Map.of(
                        "digest", RailixValue.string("sha256:" + artifactDigest),
                        "origin", RailixValue.string("test:invalid-step-bundle"),
                        "size", RailixValue.number(Files.size(bundle))
                )))),
                "bundles", RailixValue.array(List.of(RailixValue.object(Map.of(
                        "artifact", RailixValue.string("sha256:" + artifactDigest),
                        "runtime", RailixValue.array(List.of()),
                        "steps", RailixValue.array(List.of(RailixValue.object(Map.of(
                                "contract", RailixValue.string("sha256:" + contractDigest),
                                "id", RailixValue.string(STEP_ID),
                                "implementation", RailixValue.string(IMPLEMENTATION),
                                "implementation_entry", RailixValue.string(IMPLEMENTATION_ENTRY),
                                "version", RailixValue.string("1")
                        ))))
                ))))
        ));
        Files.writeString(
                project.resolveSibling("railix.dependencies.lock.json"),
                RailixJson.write(lock),
                StandardCharsets.UTF_8
        );
    }

    private static void write(final JarOutputStream jar, final String name, final byte[] value) throws IOException {
        final JarEntry entry = new JarEntry(name);
        entry.setTime(0L);
        jar.putNextEntry(entry);
        jar.write(value);
        jar.closeEntry();
    }

    private static HttpResponse<String> request(final URI baseUri, final String body)
            throws IOException, InterruptedException {
        final HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/api/project"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("X-Railix-Creator-Token", token(baseUri))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
    }

    private static RailixValue.ObjectValue application(final URI baseUri) throws Exception {
        final HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/api/application"))
                .timeout(Duration.ofSeconds(15))
                .header("X-Railix-Creator-Token", token(baseUri))
                .GET()
                .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return object(client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body());
        }
    }

    private static String token(final URI baseUri) {
        return baseUri.getRawFragment().substring("token=".length());
    }

    private static RailixValue.ObjectValue object(final String source) {
        final RailixJson.Result result = RailixJson.parse(source);
        assertThat(result).isInstanceOf(RailixJson.Parsed.class);
        return (RailixValue.ObjectValue) ((RailixJson.Parsed) result).value();
    }

    private static String text(final RailixValue.ObjectValue value, final String field) {
        return ((RailixValue.StringValue) value.values().get(field)).value();
    }

    private static long number(final RailixValue.ObjectValue value, final String field) {
        return ((RailixValue.NumberValue) value.values().get(field)).value().longValueExact();
    }

    private static String digest(final byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (final NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private static String invalidProject() {
        return """
                {"format":1,"id":"invalid-implementation","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[{
                    "name":"invalid","payload":[]
                  }]},
                  {"id":"invalid","use":"%s","inputs":{}}
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"invalid"},
                  {"from":"invalid.next","to":"end"}
                ]}
                """.formatted(STEP_ID);
    }
}
