package dev.nanonative.railix.core.step;

import dev.nanonative.railix.core.util.Sha256;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** Build-time export and immutable storage of ordinary provider bundles. */
public final class StepBundle {
    static final String INDEX = "META-INF/railix/providers.index";
    static final String MANIFEST = "META-INF/railix/steps.json";

    private StepBundle() { }

    /**
     * Writes a reproducible bundle from one provider's compiled classes and resources.
     * Call this during the provider's own build, not while discovering installed Steps.
     * The Railix API dependency must not be copied into the provider's classes directory.
     *
     * @param classes compiled provider directory, without dependencies
     * @param artifact destination JAR, which may be an embedded-provider resource
     * @param provider public class declaring {@code static List<StepDefinition> definitions()}
     * @param definitions authoritative definitions supplied by the provider
     * @return the absolute path of the completed bundle
     * @throws IllegalArgumentException for missing arguments or invalid declarations
     * @throws IOException for unreadable classes, an unsafe entry or failed publication
     */
    public static Path write(final Path classes, final Path artifact, final Class<?> provider,
                             final List<StepDefinition> definitions) throws IOException {
        return write(classes, artifact, StepContractJson.writeManifest(provider, definitions));
    }

    /**
     * Writes compiled Steps without loading a definitions provider. Executable definitions
     * must already carry their implementation addresses and have no validation callbacks.
     *
     * @param classes compiled provider directory, without dependencies
     * @param artifact destination JAR
     * @param definitions authoritative definitions with implementation addresses
     * @return the absolute path of the completed bundle
     * @throws IllegalArgumentException for missing arguments or invalid declarations
     * @throws IOException for unreadable classes, an unsafe entry or failed publication
     */
    public static Path write(final Path classes, final Path artifact,
                             final List<StepDefinition> definitions) throws IOException {
        return write(classes, artifact, StepContractJson.writeManifest(definitions));
    }

    private static Path write(final Path classes, final Path artifact, final String manifest) throws IOException {
        if (classes == null || artifact == null) throw new IllegalArgumentException("Bundle paths cannot be Java null.");
        final Path root = classes.toAbsolutePath().normalize();
        final Path output = artifact.toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Provider classes directory is missing: " + root + ".");
        final TreeMap<String, Path> entries = new TreeMap<>();
        try (var files = Files.walk(root)) {
            for (final Path file : files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).toList()) {
                final String name = root.relativize(file).toString().replace(java.io.File.separatorChar, '/');
                if (file.equals(output) || name.equals(INDEX) || name.equals(MANIFEST)
                        || name.startsWith("META-INF/railix/providers/")) continue;
                StepCatalog.artifactEntry(name);
                entries.put(name, file);
            }
        }
        Files.createDirectories(output.getParent());
        final Path temporary = Files.createTempFile(output.getParent(), ".provider-", ".jar");
        try {
            try (var jar = new JarOutputStream(Files.newOutputStream(temporary))) {
                final var metadata = new JarEntry(MANIFEST);
                metadata.setTimeLocal(java.time.LocalDateTime.of(2000, 1, 1, 0, 0));
                jar.putNextEntry(metadata);
                jar.write(manifest.getBytes(StandardCharsets.UTF_8));
                jar.closeEntry();
                for (final var entry : entries.entrySet()) {
                    final var item = new JarEntry(entry.getKey());
                    item.setTimeLocal(java.time.LocalDateTime.of(2000, 1, 1, 0, 0));
                    jar.putNextEntry(item);
                    Files.copy(entry.getValue(), jar);
                    jar.closeEntry();
                }
            }
            Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return output;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** Stages one caller-owned stream under its content identity, without accepting it as a valid bundle. */
    static Path store(final InputStream input, final Path store) throws IOException {
        final Path root = store.toAbsolutePath().normalize();
        Files.createDirectories(root);
        final Path temporary = Files.createTempFile(root, ".provider-", ".jar");
        try {
            final var hash = Sha256.newDigest();
            long bytes = 0;
            final byte[] buffer = new byte[65_536];
            try (var output = Files.newOutputStream(temporary)) {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    bytes += count;
                    if (bytes > StepCatalog.MAX_ARTIFACT_UNCOMPRESSED_BYTES) throw new IOException("DEPENDENCY_ARTIFACT_UNCOMPRESSED_SIZE_LIMIT: Provider archive exceeds the artifact size limit.");
                    hash.update(buffer, 0, count);
                    output.write(buffer, 0, count);
                }
            }
            final Path staged = root.resolve(Sha256.finishHex(hash) + ".jar");
            if (Files.exists(staged, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(staged, LinkOption.NOFOLLOW_LINKS) || Files.mismatch(temporary, staged) != -1)
                    throw new IOException("DEPENDENCY_ARTIFACT_DIGEST_MISMATCH: Staged provider bytes do not match their identity.");
                return staged;
            }
            Files.move(temporary, staged, StandardCopyOption.ATOMIC_MOVE);
            return staged;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** Publishes a validated staged artifact, preserving an existing identical immutable cache entry. */
    static Path publish(final Path staged, final Path store) throws IOException {
        final Path target = store.resolve(staged.getFileName());
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            try {
                return Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (final IOException raceOrStorageFailure) {
                if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw raceOrStorageFailure;
            }
        }
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.mismatch(staged, target) != -1)
            throw new IOException("DEPENDENCY_ARTIFACT_DIGEST_MISMATCH: Existing provider cache does not match its content identity.");
        return target;
    }

    /** Owns one private import directory and preserves validation failures if cleanup also fails. */
    record ImportDirectory(Path path) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            try (var files = Files.walk(path)) {
                for (final Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
    }
}
