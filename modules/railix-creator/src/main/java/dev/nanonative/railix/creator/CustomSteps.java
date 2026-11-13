package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.project.Diagnostic;
import dev.nanonative.railix.core.step.JavaStepCompiler;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.util.Sha256;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
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

    record Saved(String id, String name, int position) { }

    static List<Saved> saved(final String document) throws IOException {
        final List<Saved> result = new ArrayList<>();
        for (final RailixValue value : records(document)) {
            final var record = record(value);
            final String name = text(record, "name");
            result.add(new Saved(identifier(name), name, result.size()));
        }
        return List.copyOf(result);
    }

    static String fingerprint(final String document) throws IOException {
        final var hash = Sha256.newDigest();
        for (final var value : records(document)) {
            final var entry = record(value);
            hashText(hash, text(entry, "name"));
            hashText(hash, text(entry, "source"));
        }
        return Sha256.finishHex(hash);
    }

    /** Length-prefixed UTF-16 preserves every source character without another document-sized serialization. */
    private static void hashText(final MessageDigest hash, final String value) {
        hash.update(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(value.length()).array());
        final byte[] buffer = new byte[8192];
        for (int offset = 0; offset < value.length();) {
            final int count = Math.min(buffer.length / 2, value.length() - offset);
            for (int index = 0; index < count; index++) {
                final char character = value.charAt(offset + index);
                buffer[index * 2] = (byte) (character >>> 8);
                buffer[index * 2 + 1] = (byte) character;
            }
            hash.update(buffer, 0, count * 2);
            offset += count;
        }
    }

    static String deletion(final String document, final Saved step) throws IOException {
        final List<RailixValue> next = new ArrayList<>(records(document));
        next.remove(step.position());
        return document(next);
    }

    private static String document(final List<RailixValue> steps) {
        return RailixJson.write(RailixValue.object(Map.of("format", RailixValue.number(1), "steps", RailixValue.array(steps))),
                ProjectFiles.MAX_DOCUMENT_BYTES).orElseThrow(() -> new IllegalArgumentException("Custom Step source exceeds the document limit."));
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
        final String id = identifier(name);
        if (base.find(id).isPresent()) return new JavaStepCompiler.Rejected(List.of(new Diagnostic(
                "CUSTOM_STEP_INVALID", "A Step with this name already exists. Use a new name.", "source", 0, 0)));
        final Path root = project.toAbsolutePath().normalize().getParent().resolve(".railix/custom-steps");
        Files.createDirectories(root);
        final Path temporary = Files.createTempDirectory(root, ".api-");
        try {
            return JavaStepCompiler.compile(id, name, source, ApplicationBuilder.runtimeApi(temporary), root.resolve("artifacts"),
                    JavaStepCompiler.SourcePolicy.REJECT_DYNAMIC_APIS);
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

    private static String identifier(final String name) {
        final String normalized = Normalizer.normalize(name, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        return "custom." + (normalized.isEmpty() ? Sha256.hexDigest(name).substring(0, 16) : normalized);
    }
}
