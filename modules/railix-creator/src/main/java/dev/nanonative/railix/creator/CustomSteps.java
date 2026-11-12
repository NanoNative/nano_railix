package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.project.Diagnostic;
import dev.nanonative.railix.core.step.JavaStepCompiler;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Project-owned source and bundle preparation for ordinary Java Steps. */
final class CustomSteps {
    static final String FILE = "railix.custom-steps.json";

    private CustomSteps() { }

    sealed interface Preparation permits Prepared, Rejected { }
    record Prepared(StepCatalog catalog, String document, StepDefinition definition) implements Preparation { }
    record Rejected(List<Diagnostic> diagnostics) implements Preparation {
        Rejected { diagnostics = List.copyOf(diagnostics); }
    }

    static String read(final Path project) throws IOException {
        final Path file = project.resolveSibling(FILE);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS))
            return "{\"format\":1,\"steps\":[]}";
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Custom Step source must be a regular project file.");
        return ProjectFiles.readText(file);
    }

    static StepCatalog compileAndInstall(final StepCatalog base, final Path project, final String document) throws IOException {
        StepCatalog result = base;
        for (final RailixValue value : records(document)) {
            final var record = record(value);
            final var compiled = artifact(result, project, text(record, "name"), text(record, "source"));
            if (compiled instanceof JavaStepCompiler.Rejected rejected)
                throw new IOException("Cannot compile saved custom Step: " + rejected.diagnostics().getFirst().message());
            final var bundle = (JavaStepCompiler.Compiled) compiled;
            result = result.installSource(bundle.lock(), bundle.artifact().getParent());
        }
        return result;
    }

    static Preparation prepare(final StepCatalog base, final Path project, final String previous,
                          final String name, final String methodSource) throws IOException {
        final var result = artifact(base, project, name, methodSource);
        if (result instanceof JavaStepCompiler.Rejected(List<Diagnostic> diagnostics))
            return new Rejected(diagnostics);
        final var bundle = (JavaStepCompiler.Compiled) result;
        final StepCatalog candidate = base.installSource(bundle.lock(), bundle.artifact().getParent());
        final List<RailixValue> next = new ArrayList<>(records(previous));
        next.add(RailixValue.object(Map.of("name", RailixValue.string(name), "source", RailixValue.string(methodSource))));
        final var document = RailixJson.write(RailixValue.object(Map.of("format", RailixValue.number(1),
                "steps", RailixValue.array(next))), ProjectFiles.MAX_DOCUMENT_BYTES);
        if (document.isEmpty())
            return new Rejected(List.of(new Diagnostic("CUSTOM_STEP_INVALID",
                    "The project's custom Step source exceeds the supported document bounds.", "source", 0, 0)));
        return new Prepared(candidate, document.orElseThrow(), bundle.definition());
    }

    private static JavaStepCompiler.Result artifact(final StepCatalog base, final Path project,
                                                   final String name, final String source) throws IOException {
        if (name.isBlank()) return new JavaStepCompiler.Rejected(List.of(new Diagnostic(
                "CUSTOM_STEP_INVALID", "Give the custom Step a name.", "source", 0, 0)));
        final String normalized = Normalizer.normalize(name, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        final String id = "custom." + (normalized.isEmpty() ? digest(name).substring(0, 16) : normalized);
        if (base.find(id).isPresent()) return new JavaStepCompiler.Rejected(List.of(new Diagnostic(
                "CUSTOM_STEP_INVALID", "A Step with this name already exists. Use a new name.", "source", 0, 0)));
        final Path root = project.toAbsolutePath().normalize().getParent().resolve(".railix/custom-steps");
        Files.createDirectories(root);
        final Path temporary = Files.createTempDirectory(root, ".api-");
        try {
            return JavaStepCompiler.compile(id, name, source, ApplicationBuilder.runtimeApi(temporary), root.resolve("artifacts"));
        } finally {
            Files.deleteIfExists(temporary.resolve("runtime-api.jar"));
            Files.deleteIfExists(temporary);
        }
    }

    private static List<RailixValue> records(final String document) throws IOException {
        final var parsed = RailixJson.parse(document);
        if (!(parsed instanceof RailixJson.Parsed valid) || !(valid.value() instanceof RailixValue.ObjectValue object)
                || !object.values().keySet().equals(Set.of("format", "steps"))
                || !RailixValue.number(1).equals(object.values().get("format"))
                || !(object.values().get("steps") instanceof RailixValue.ArrayValue steps))
            throw new IOException("Invalid custom-Step source document.");
        return steps.values();
    }

    private static RailixValue.ObjectValue record(final RailixValue value) throws IOException {
        if (!(value instanceof RailixValue.ObjectValue object) || !object.values().keySet().equals(Set.of("name", "source"))
                || !(object.values().get("name") instanceof RailixValue.StringValue)
                || !(object.values().get("source") instanceof RailixValue.StringValue))
            throw new IOException("A saved custom Step needs its name and Java source.");
        return object;
    }

    private static String text(final RailixValue.ObjectValue value, final String name) {
        return ((RailixValue.StringValue) value.values().get(name)).value();
    }

    private static String digest(final String source) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8))); }
        catch (final NoSuchAlgorithmException unavailable) { throw new IllegalStateException("SHA-256 is required.", unavailable); }
    }
}
