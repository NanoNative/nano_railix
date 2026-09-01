package dev.nanonative.railix.core.step;

import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class StepDependencyLockE2eTest {
    private static final String DIGEST = "sha256:" + "0".repeat(64);

    @TempDir
    Path directory;

    @Test
    void dependencyLockMustExist() throws IOException {
        final Path store = directory.resolve("store");
        Files.createDirectories(store);

        assertThatThrownBy(() -> StepCatalog.of().install(directory.resolve("missing.json"), store))
                .isInstanceOf(StepCatalog.DependencyException.class)
                .hasMessageStartingWith("DEPENDENCY_LOCK_REQUIRED:");
    }

    @Test
    void dependencyLockCannotBeJavaNull() {
        assertThatThrownBy(() -> StepCatalog.of().install(null, directory))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Dependency lock and store cannot be Java null.");
    }

    @Test
    void dependencyStoreCannotBeJavaNull() {
        assertThatThrownBy(() -> StepCatalog.of().install(directory.resolve("lock.json"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Dependency lock and store cannot be Java null.");
    }

    @Test
    void dependencyLockCannotExceedFourMebibytes() throws IOException {
        final Path store = directory.resolve("store");
        final Path lock = directory.resolve("oversized-lock.json");
        Files.createDirectories(store);
        Files.write(lock, new byte[(4 * 1024 * 1024) + 1]);

        assertThatThrownBy(() -> StepCatalog.of().install(lock, store))
                .isInstanceOf(StepCatalog.DependencyException.class)
                .hasMessageStartingWith("DEPENDENCY_LOCK_NON_CANONICAL: Dependency lock exceeds ");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidArtifacts")
    void dependencyArtifactRejectsEachInvalidIdentityOrInventory(
            final String scenario,
            final Runnable construction
    ) {
        assertThatThrownBy(construction::run)
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidImplementations")
    void stepImplementationRejectsEachIncompleteOrContradictoryOwnership(
            final String scenario,
            final Runnable construction
    ) {
        assertThatThrownBy(construction::run)
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dependencyLockFormatMustBeOne() throws Exception {
        assertRejected(root(RailixValue.number(2), array(), array()), "Dependency lock format must be 1.");
    }

    @Test
    void dependencyLockFormatMustFitASignedThirtyTwoBitInteger() throws Exception {
        assertRejected(
                root(RailixValue.number((long) Integer.MAX_VALUE + 1), array(), array()),
                "lock.format must be a 32-bit integer."
        );
    }

    @Test
    void dependencyLockRequiresExactRootFields() throws Exception {
        assertRejected(
                RailixValue.object(Map.of(
                        "artifacts", array(),
                        "bundles", array(),
                        "format", RailixValue.number(1),
                        "unknown", RailixValue.bool(true)
                )),
                "lock fields must be exactly: [artifacts, bundles, format]."
        );
    }

    @Test
    void dependencyLockArtifactsMustBeAnArray() throws Exception {
        assertRejected(
                root(RailixValue.number(1), RailixValue.string("artifacts"), array()),
                "lock.artifacts must be an array."
        );
    }

    @Test
    void dependencyLockArtifactMustBeAnObject() throws Exception {
        assertRejected(
                root(RailixValue.number(1), array(RailixValue.string("artifact")), array()),
                "lock.artifacts[] must be an object."
        );
    }

    @Test
    void dependencyLockArtifactDigestMustUseSha256() throws Exception {
        assertRejected(
                root(RailixValue.number(1), array(artifact("md5:" + "0".repeat(64), "origin", 1)), array()),
                "lock.artifacts[].digest must be a sha256 digest."
        );
    }

    @Test
    void dependencyLockArtifactDigestMustBeLowercase() throws Exception {
        assertRejected(
                root(RailixValue.number(1), array(artifact("sha256:" + "A".repeat(64), "origin", 1)), array()),
                "lock.artifacts[].digest must be a lowercase sha256 digest."
        );
    }

    @Test
    void dependencyLockArtifactOriginMustBeNonBlank() throws Exception {
        assertRejected(
                root(RailixValue.number(1), array(artifact(DIGEST, " ", 1)), array()),
                "lock.artifacts[].origin must be a non-blank string."
        );
    }

    @Test
    void dependencyLockArtifactSizeMustBeANumber() throws Exception {
        assertRejected(
                root(RailixValue.number(1), array(artifact(DIGEST, "origin", RailixValue.string("one"))), array()),
                "lock.artifacts[].size must be a number."
        );
    }

    @Test
    void dependencyLockArtifactSizeMustBeAnInteger() throws Exception {
        assertRejected(
                root(RailixValue.number(1), array(artifact(
                        DIGEST,
                        "origin",
                        RailixValue.number(new BigDecimal("1.5"))
                )), array()),
                "lock.artifacts[].size must be an integer."
        );
    }

    @Test
    void dependencyLockArtifactSizeMustBePositive() throws Exception {
        assertRejected(
                root(RailixValue.number(1), array(artifact(DIGEST, "origin", 0)), array()),
                "Artifact size must be positive."
        );
    }

    @Test
    void dependencyLockBundlesMustBeAnArray() throws Exception {
        assertRejected(
                root(RailixValue.number(1), array(), RailixValue.string("bundles")),
                "lock.bundles must be an array."
        );
    }

    @Test
    void dependencyLockBundleMustBeAnObject() throws Exception {
        assertRejected(
                root(RailixValue.number(1), array(), array(RailixValue.string("bundle"))),
                "lock.bundles[] must be an object."
        );
    }

    @Test
    void dependencyLockBundleRequiresExactFields() throws Exception {
        assertRejected(
                root(RailixValue.number(1), array(), array(RailixValue.object(Map.of(
                        "artifact", RailixValue.string(DIGEST),
                        "runtime", array()
                )))),
                "lock.bundles[] fields must be exactly: [artifact, runtime, steps]."
        );
    }

    @Test
    void dependencyLockBundleMustReferenceALockedRootArtifact() throws Exception {
        assertRejected(
                root(RailixValue.number(1), array(), array(bundle(DIGEST, array()))),
                "DEPENDENCY_ARTIFACT_MISSING",
                "Bundle references an unlocked artifact: " + DIGEST + "."
        );
    }

    @Test
    void everyLockedArtifactMustBelongToABundleClosure() throws Exception {
        final LockedArtifact artifact = artifact("unowned.txt", "unowned");

        assertRejected(
                root(RailixValue.number(1), array(artifact.value()), array()),
                "Every locked artifact must belong to a bundle closure."
        );
    }

    @Test
    void dependencyLockRuntimeMustBeAnArray() throws Exception {
        final LockedArtifact artifact = artifact("root.txt", "root");

        assertRejected(
                root(RailixValue.number(1), array(artifact.value()), array(bundle(
                        artifact.digest(),
                        RailixValue.string("runtime")
                ))),
                "lock.bundles[].runtime must be an array."
        );
    }

    @Test
    void dependencyLockRuntimeCannotRepeatItsRootArtifact() throws Exception {
        final LockedArtifact artifact = artifact("root.txt", "root");

        assertRejected(
                root(RailixValue.number(1), array(artifact.value()), array(bundle(
                        artifact.digest(),
                        array(RailixValue.string(artifact.digest()))
                ))),
                "Bundle runtime cannot repeat its root artifact."
        );
    }

    @Test
    void dependencyLockRuntimeDigestsMustBeUnique() throws Exception {
        final LockedArtifact root = artifact("root.txt", "root");
        final LockedArtifact helper = artifact("helper.txt", "helper");
        final List<LockedArtifact> artifacts = new ArrayList<>(List.of(root, helper));
        artifacts.sort(Comparator.comparing(LockedArtifact::digest));

        assertRejected(
                root(
                        RailixValue.number(1),
                        RailixValue.array(artifacts.stream().<RailixValue>map(LockedArtifact::value).toList()),
                        array(bundle(root.digest(), array(
                                RailixValue.string(helper.digest()),
                                RailixValue.string(helper.digest())
                        )))
                ),
                "lock.bundles[].runtime must contain sorted unique digests."
        );
    }

    @Test
    void dependencyLockArtifactsMustBeUnique() throws Exception {
        final LockedArtifact artifact = artifact("duplicate.txt", "duplicate");

        assertRejected(
                root(RailixValue.number(1), array(artifact.value(), artifact.value()), array()),
                "Artifacts must be sorted by unique digest."
        );
    }

    @Test
    void dependencyLockBundlesMustBeUnique() throws Exception {
        final LockedArtifact artifact = artifact(
                "META-INF/railix/steps.json",
                "{\"format\":1,\"steps\":[]}"
        );

        assertRejected(
                root(
                        RailixValue.number(1),
                        array(artifact.value()),
                        array(bundle(artifact.digest(), array()), bundle(artifact.digest(), array()))
                ),
                "Bundles must be sorted by unique artifact digest."
        );
    }

    private void assertRejected(final RailixValue.ObjectValue lock, final String message) throws Exception {
        assertRejected(lock, "DEPENDENCY_LOCK_NON_CANONICAL", message);
    }

    private void assertRejected(
            final RailixValue.ObjectValue lock,
            final String code,
            final String message
    ) throws Exception {
        final Path store = directory.resolve("store");
        Files.createDirectories(store);
        final Path file = directory.resolve("railix.dependencies.lock.json");
        Files.writeString(file, RailixJson.write(lock), StandardCharsets.UTF_8);

        assertThatThrownBy(() -> StepCatalog.of().install(file, store))
                .isInstanceOf(StepCatalog.DependencyException.class)
                .hasMessage(code + ": " + message);
    }

    private LockedArtifact artifact(final String entry, final String content) throws Exception {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(output)) {
            final JarEntry item = new JarEntry(entry);
            item.setTime(0L);
            jar.putNextEntry(item);
            jar.write(content.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        final byte[] bytes = output.toByteArray();
        final String digest = "sha256:" + sha256(bytes);
        final Path store = directory.resolve("store");
        Files.createDirectories(store);
        Files.write(store.resolve(digest.substring("sha256:".length()) + ".jar"), bytes);
        return new LockedArtifact(digest, artifact(digest, "test:" + entry, bytes.length));
    }

    private static RailixValue.ObjectValue root(
            final RailixValue format,
            final RailixValue artifacts,
            final RailixValue bundles
    ) {
        return RailixValue.object(Map.of(
                "format", format,
                "artifacts", artifacts,
                "bundles", bundles
        ));
    }

    private static RailixValue.ObjectValue artifact(
            final String digest,
            final String origin,
            final long size
    ) {
        return artifact(digest, origin, RailixValue.number(size));
    }

    private static RailixValue.ObjectValue artifact(
            final String digest,
            final String origin,
            final RailixValue size
    ) {
        final Map<String, RailixValue> value = new LinkedHashMap<>();
        value.put("digest", RailixValue.string(digest));
        value.put("origin", RailixValue.string(origin));
        value.put("size", size);
        return RailixValue.object(value);
    }

    private static RailixValue.ObjectValue bundle(final String artifact, final RailixValue runtime) {
        return RailixValue.object(Map.of(
                "artifact", RailixValue.string(artifact),
                "runtime", runtime,
                "steps", array()
        ));
    }

    private static RailixValue.ArrayValue array(final RailixValue... values) {
        return RailixValue.array(List.of(values));
    }

    private static Stream<Arguments> invalidArtifacts() {
        final Path path = Path.of("artifact.jar");
        return Stream.of(
                Arguments.of("null digest", (Runnable) () -> new StepCatalog.Artifact(null, path, 0, 0)),
                Arguments.of("null path", (Runnable) () -> new StepCatalog.Artifact(DIGEST, null, 0, 0)),
                Arguments.of("negative entry count",
                        (Runnable) () -> new StepCatalog.Artifact(DIGEST, path, -1, 0)),
                Arguments.of("entry count above limit", (Runnable) () -> new StepCatalog.Artifact(
                        DIGEST, path, StepCatalog.MAX_ARTIFACT_ENTRIES + 1, 0
                )),
                Arguments.of("negative uncompressed bytes",
                        (Runnable) () -> new StepCatalog.Artifact(DIGEST, path, 0, -1)),
                Arguments.of("uncompressed bytes above limit", (Runnable) () -> new StepCatalog.Artifact(
                        DIGEST, path, 0, StepCatalog.MAX_ARTIFACT_UNCOMPRESSED_BYTES + 1
                ))
        );
    }

    private static Stream<Arguments> invalidImplementations() {
        final StepCatalog.Artifact artifact = new StepCatalog.Artifact(DIGEST, Path.of("artifact.jar"), 0, 0);
        final List<StepCatalog.Artifact> closure = List.of(artifact);
        return Stream.of(
                Arguments.of("null class name", (Runnable) () -> implementation(null, "a/Step.class",
                        StepCatalog.Ownership.BUNDLE, closure, DIGEST)),
                Arguments.of("blank class name", (Runnable) () -> implementation(" ", "a/Step.class",
                        StepCatalog.Ownership.BUNDLE, closure, DIGEST)),
                Arguments.of("null class entry", (Runnable) () -> implementation("a.Step", null,
                        StepCatalog.Ownership.BUNDLE, closure, DIGEST)),
                Arguments.of("blank class entry", (Runnable) () -> implementation("a.Step", " ",
                        StepCatalog.Ownership.BUNDLE, closure, DIGEST)),
                Arguments.of("null ownership", (Runnable) () -> implementation(
                        "a.Step", "a/Step.class", null, closure, DIGEST
                )),
                Arguments.of("null artifact closure", (Runnable) () -> implementation(
                        "a.Step", "a/Step.class", StepCatalog.Ownership.BUNDLE, null, DIGEST
                )),
                Arguments.of("null contract digest", (Runnable) () -> implementation(
                        "a.Step", "a/Step.class", StepCatalog.Ownership.BUNDLE, closure, null
                )),
                Arguments.of("blank contract digest", (Runnable) () -> implementation(
                        "a.Step", "a/Step.class", StepCatalog.Ownership.BUNDLE, closure, " "
                )),
                Arguments.of("platform implementation with artifacts", (Runnable) () -> implementation(
                        "a.Step", "a/Step.class", StepCatalog.Ownership.PLATFORM, closure, DIGEST
                )),
                Arguments.of("bundle implementation without artifacts", (Runnable) () -> implementation(
                        "a.Step", "a/Step.class", StepCatalog.Ownership.BUNDLE, List.of(), DIGEST
                ))
        );
    }

    private static StepCatalog.Implementation implementation(
            final String className,
            final String classEntry,
            final StepCatalog.Ownership ownership,
            final List<StepCatalog.Artifact> artifacts,
            final String contractDigest
    ) {
        return new StepCatalog.Implementation(className, classEntry, ownership, artifacts, contractDigest);
    }

    private static String sha256(final byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (final NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private record LockedArtifact(String digest, RailixValue.ObjectValue value) {
    }
}
