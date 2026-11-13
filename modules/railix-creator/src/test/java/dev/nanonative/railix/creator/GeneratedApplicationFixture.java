package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepContractJson;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;

/** Builds and owns one real generated application from a locked test Step bundle. */
final class GeneratedApplicationFixture implements AutoCloseable {
    static StepCatalog catalog() {
        try {
            return StepCatalog.embedded(GeneratedApplicationFixture.class.getClassLoader(),
                    Path.of("target", "provider-test-artifacts"));
        } catch (final IOException invalidDistribution) {
            throw new java.io.UncheckedIOException(invalidDistribution);
        }
    }

    private final DevelopmentRuntimeGeneratedProcess.Child application;
    private final URI baseUri;
    private final String token;

    private GeneratedApplicationFixture(
            final DevelopmentRuntimeGeneratedProcess.Child application,
            final URI baseUri,
            final String token
    ) {
        this.application = application;
        this.baseUri = baseUri;
        this.token = token;
    }

    static GeneratedApplicationFixture start(
            final Path workspace,
            final String source
    ) throws IOException {
        return start(workspace, source, GeneratedApplicationFixture.catalog());
    }

    static GeneratedApplicationFixture start(
            final Path workspace,
            final String source,
            final List<StepDefinition> definitions,
            final Class<?>... bundleClasses
    ) throws IOException {
        Files.createDirectories(workspace);
        final StepCatalog catalog = installedCatalog(workspace, definitions, bundleClasses);
        return start(workspace, source, catalog);
    }

    private static GeneratedApplicationFixture start(
            final Path workspace,
            final String source,
            final StepCatalog catalog
    ) throws IOException {
        final DevelopmentRuntimeGeneratedProcess.Build build =
                DevelopmentRuntimeGeneratedProcess.build(workspace, source, catalog);
        DevelopmentRuntimeGeneratedProcess.Child child = null;
        try {
            final String token = UUID.randomUUID().toString();
            child = DevelopmentRuntimeGeneratedProcess.launch(build).token(token);
            return new GeneratedApplicationFixture(
                    child,
                    child.awaitReady(),
                    token
            );
        } catch (final IOException | RuntimeException | Error failure) {
            cleanup(child, build.artifact(), failure);
            throw failure;
        }
    }

    DevelopmentApplication.Response run(final String trigger, final String context) throws IOException {
        return request("/v1/run/" + trigger, context, false);
    }

    DevelopmentApplication.Response runTest(final String trigger, final String context) throws IOException {
        return request("/v1/run/" + trigger, context, true);
    }

    List<RailixValue.ObjectValue> trace(final String trigger, final String context) throws IOException {
        final DevelopmentApplication.Response response = traceResponse(trigger, context);
        if (response.status() != 200) {
            throw new IOException("Development trace request failed with HTTP " + response.status() + ".");
        }
        return response.body().lines()
                .filter(line -> !line.isBlank())
                .map(line -> (RailixValue.ObjectValue) ((RailixJson.Parsed) RailixJson.parseDocument(line)).value())
                .toList();
    }

    DevelopmentApplication.Response traceResponse(final String trigger, final String context) throws IOException {
        return request("/v1/trace/" + trigger, context, true);
    }

    @Override
    public void close() {
        application.close();
    }

    private DevelopmentApplication.Response request(
            final String path,
            final String context,
            final boolean test
    ) throws IOException {
        try {
            final HttpResponse<String> response = application.request(
                    baseUri,
                    token,
                    "POST",
                    path,
                    context,
                    Boolean.toString(test)
            );
            return new DevelopmentApplication.Response(response.statusCode(), response.body());
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new DevelopmentApplication.Response(409, "{\"status\":\"cancelled\"}");
        }
    }

    private static void cleanup(
            final DevelopmentRuntimeGeneratedProcess.Child child,
            final ApplicationBuilder.Artifact artifact,
            final Throwable failure
    ) {
        if (child != null) {
            try {
                child.close();
            } catch (final RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
        }
        if (!artifact.reused()) {
            try {
                ApplicationBuilder.delete(artifact);
            } catch (final IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
        }
    }

    /** Compiles a separate caller against a produced JAR, without editing generated application code. */
    static Path compileProbe(final Path workspace, final Path jar, final String className, final String source)
            throws IOException {
        final Path classes = Files.createDirectories(workspace.resolve("classes"));
        final Path java = workspace.resolve(className + ".java");
        Files.writeString(java, source, StandardCharsets.UTF_8);
        final var errors = new ByteArrayOutputStream();
        if (ToolProvider.getSystemJavaCompiler().run(null, errors, errors, "-proc:none", "-classpath", jar.toString(),
                "-d", classes.toString(), java.toString()) != 0) {
            throw new IOException("Generated application caller did not compile: " + errors.toString(StandardCharsets.UTF_8));
        }
        return classes;
    }

    static StepCatalog installedCatalog(
            final Path workspace,
            final List<StepDefinition> definitions,
            final Class<?>... bundleClasses
    ) throws IOException {
        if (definitions == null || definitions.isEmpty()
                || bundleClasses == null || bundleClasses.length == 0) {
            throw new IllegalArgumentException("Generated application fixture requires definitions and bundle classes.");
        }
        final String manifest = StepContractJson.writeManifest(definitions);
        return installedCatalog(workspace, manifest, bundleClasses);
    }

    static StepCatalog installedCatalog(final Path workspace, final Class<?> provider,
                                        final List<StepDefinition> definitions, final Class<?>... bundleClasses) throws IOException {
        return installedCatalog(workspace, StepContractJson.writeManifest(provider, definitions), bundleClasses);
    }

    private static StepCatalog installedCatalog(final Path workspace, final String manifest,
                                                final Class<?>... bundleClasses) throws IOException {
        final Map<String, byte[]> entries = classes(bundleClasses);
        entries.put("META-INF/railix/steps.json", manifest.getBytes(StandardCharsets.UTF_8));
        final Path bundle = workspace.resolve("conformance-steps.jar");
        jar(bundle, entries);
        final Path store = workspace.resolve("railix-home/artifacts");
        final String lock = StepCatalog.bundleLock(bundle, store);
        final Path lockFile = workspace.resolve("railix.dependencies.lock.json");
        Files.writeString(lockFile, lock, StandardCharsets.UTF_8);
        return GeneratedApplicationFixture.catalog().install(lockFile, store);
    }

    private static Map<String, byte[]> classes(final Class<?>... types) throws IOException {
        final Set<String> names = new LinkedHashSet<>();
        for (final Class<?> type : types) {
            if (type == null) {
                throw new IllegalArgumentException("Bundle class cannot be Java null.");
            }
            String name = type.getName();
            names.add(name.replace('.', '/') + ".class");
            while (name.contains("$")) {
                name = name.substring(0, name.lastIndexOf('$'));
                names.add(name.replace('.', '/') + ".class");
            }
        }
        final Map<String, byte[]> result = new LinkedHashMap<>();
        for (final String name : names) {
            try (InputStream input = GeneratedApplicationFixture.class.getResourceAsStream("/" + name)) {
                if (input == null) {
                    throw new IOException("Conformance bundle class is unavailable: " + name + ".");
                }
                result.put(name, input.readAllBytes());
            }
        }
        return result;
    }

    private static void jar(final Path target, final Map<String, byte[]> entries) throws IOException {
        try (OutputStream output = Files.newOutputStream(target);
             JarOutputStream jar = new JarOutputStream(output)) {
            for (final Map.Entry<String, byte[]> entry : new TreeMap<>(entries).entrySet()) {
                final JarEntry item = new JarEntry(entry.getKey());
                item.setTime(0L);
                jar.putNextEntry(item);
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
    }

}
