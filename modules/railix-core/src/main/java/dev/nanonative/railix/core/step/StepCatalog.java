package dev.nanonative.railix.core.step;

import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.DigestInputStream;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Explicit Step catalog with immutable implementation ownership; no reflection or scanning. */
public final class StepCatalog {
    /** Maximum number of files accepted in one locked artifact. */
    public static final int MAX_ARTIFACT_ENTRIES = 65_536;
    /** Maximum uncompressed bytes accepted for one artifact entry. */
    public static final long MAX_ARTIFACT_ENTRY_BYTES = 1024L * 1024 * 1024;
    /** Maximum uncompressed bytes accepted across one locked artifact. */
    public static final long MAX_ARTIFACT_UNCOMPRESSED_BYTES = 8L * 1024 * 1024 * 1024;
    /** Maximum files accepted across one reachable application dependency closure. */
    public static final int MAX_CLOSURE_ENTRIES = 262_144;
    /** Maximum uncompressed bytes accepted across one reachable application dependency closure. */
    public static final long MAX_CLOSURE_UNCOMPRESSED_BYTES = 32L * 1024 * 1024 * 1024;
    private static final String STEP_MANIFEST = "META-INF/railix/steps.json";
    private static final int MANIFEST_LIMIT = 4 * 1024 * 1024;
    private static final int LOCK_LIMIT = 4 * 1024 * 1024;
    private final List<StepDefinition> definitions;
    private final Map<String, StepDefinition> definitionsById;
    private final Map<String, Implementation> implementations;

    private StepCatalog(
            final List<StepDefinition> definitions,
            final Map<String, Implementation> implementations
    ) {
        this.definitions = Collections.unmodifiableList(new ArrayList<>(definitions));
        final Map<String, StepDefinition> byId = new LinkedHashMap<>();
        definitions.forEach(definition -> byId.put(definition.id(), definition));
        this.definitionsById = Collections.unmodifiableMap(byId);
        this.implementations = Collections.unmodifiableMap(new LinkedHashMap<>(implementations));
    }

    /** Creates a platform-owned catalog from explicitly constructed definitions. */
    public static StepCatalog of(final StepDefinition... definitions) {
        if (definitions == null) {
            throw new IllegalArgumentException("Step definitions cannot be Java null.");
        }
        final List<StepDefinition> copy = new ArrayList<>(definitions.length);
        final Set<String> ids = new LinkedHashSet<>();
        final Map<String, Implementation> implementations = new LinkedHashMap<>();
        for (int index = 0; index < definitions.length; index++) {
            final StepDefinition definition = definitions[index];
            if (definition == null) {
                throw new IllegalArgumentException("Step definition at index " + index + " cannot be Java null.");
            }
            if (!ids.add(definition.id())) {
                throw new IllegalArgumentException("Step definition id is duplicated: " + definition.id());
            }
            copy.add(definition);
            definition.implementationAddress().ifPresent(address -> implementations.put(
                    definition.id(),
                    new Implementation(
                            address.sourceName(),
                            address.classEntry(),
                            Ownership.PLATFORM,
                            List.of(),
                            "sha256:" + digest(StepContractJson.write(definition).getBytes(StandardCharsets.UTF_8)),
                            address.jdkModules()
                    )
            ));
        }
        return new StepCatalog(copy, implementations);
    }

    /**
     * Adds every Step from one canonical installed dependency lock and content-addressed store.
     *
     * @param lock canonical {@code railix.dependencies.lock.json}
     * @param store directory containing {@code <sha256>.jar} immutable artifacts
     * @return a new catalog containing this catalog plus all installed bundle contracts
     * @throws DependencyException when lock, manifest, ownership, or artifact integrity is invalid
     */
    public StepCatalog install(final Path lock, final Path store) throws DependencyException {
        if (lock == null || store == null) {
            throw new IllegalArgumentException("Dependency lock and store cannot be Java null.");
        }
        final String source;
        try (InputStream input = Files.newInputStream(lock)) {
            if (Files.size(lock) > LOCK_LIMIT) {
                throw failure("DEPENDENCY_LOCK_NON_CANONICAL", "Dependency lock exceeds " + LOCK_LIMIT + " bytes.");
            }
            final byte[] bytes = input.readNBytes(LOCK_LIMIT + 1);
            if (bytes.length > LOCK_LIMIT) {
                throw failure("DEPENDENCY_LOCK_NON_CANONICAL", "Dependency lock exceeds " + LOCK_LIMIT + " bytes.");
            }
            source = new String(bytes, StandardCharsets.UTF_8);
        } catch (final DependencyException exception) {
            throw exception;
        } catch (final IOException exception) {
            throw failure("DEPENDENCY_LOCK_REQUIRED", "Dependency lock cannot be read: " + lock + ".", exception);
        }
        final RailixValue.ObjectValue root = parseCanonical(
                source,
                "DEPENDENCY_LOCK_NON_CANONICAL",
                "Dependency lock"
        );
        exact(root, Set.of("artifacts", "bundles", "format"), "DEPENDENCY_LOCK_NON_CANONICAL", "lock");
        if (integer(root, "format", "DEPENDENCY_LOCK_NON_CANONICAL", "lock") != 1) {
            throw failure("DEPENDENCY_LOCK_NON_CANONICAL", "Dependency lock format must be 1.");
        }
        final Map<String, Artifact> artifacts = artifacts(root, store.toAbsolutePath().normalize());
        final List<StepDefinition> nextDefinitions = new ArrayList<>(definitions);
        final Map<String, Implementation> nextImplementations = new LinkedHashMap<>(implementations);
        final Set<String> referencedArtifacts = new LinkedHashSet<>();
        String previousBundle = "";
        for (final RailixValue value : array(root, "bundles", "DEPENDENCY_LOCK_NON_CANONICAL", "lock").values()) {
            final RailixValue.ObjectValue bundle = object(
                    value, "DEPENDENCY_LOCK_NON_CANONICAL", "lock.bundles[]"
            );
            exact(bundle, Set.of("artifact", "runtime", "steps"),
                    "DEPENDENCY_LOCK_NON_CANONICAL", "lock.bundles[]");
            final String rootDigest = digest(bundle, "artifact", "DEPENDENCY_LOCK_NON_CANONICAL", "lock.bundles[]");
            if (rootDigest.compareTo(previousBundle) <= 0) {
                throw failure("DEPENDENCY_LOCK_NON_CANONICAL", "Bundles must be sorted by unique artifact digest.");
            }
            previousBundle = rootDigest;
            final Artifact rootArtifact = artifact(artifacts, rootDigest);
            final List<String> runtime = digests(
                    array(bundle, "runtime", "DEPENDENCY_LOCK_NON_CANONICAL", "lock.bundles[]"),
                    "lock.bundles[].runtime"
            );
            if (runtime.contains(rootDigest)) {
                throw failure("DEPENDENCY_LOCK_NON_CANONICAL", "Bundle runtime cannot repeat its root artifact.");
            }
            final List<Artifact> closure = new ArrayList<>();
            closure.add(rootArtifact);
            for (final String item : runtime) {
                closure.add(artifact(artifacts, item));
            }
            closure.forEach(item -> referencedArtifacts.add(item.digest()));
            final Map<String, ManifestStep> manifest = manifest(rootArtifact);
            String previousStep = "";
            final Set<String> lockedSteps = new LinkedHashSet<>();
            for (final RailixValue stepValue : array(
                    bundle, "steps", "DEPENDENCY_LOCK_NON_CANONICAL", "lock.bundles[]"
            ).values()) {
                final RailixValue.ObjectValue step = object(
                        stepValue, "DEPENDENCY_LOCK_NON_CANONICAL", "lock.bundles[].steps[]"
                );
                exact(step, Set.of("contract", "id", "implementation", "implementation_entry", "version"),
                        "DEPENDENCY_LOCK_NON_CANONICAL", "lock.bundles[].steps[]");
                final String id = text(step, "id", "DEPENDENCY_LOCK_NON_CANONICAL", "lock.bundles[].steps[]");
                if (id.compareTo(previousStep) <= 0) {
                    throw failure("DEPENDENCY_LOCK_NON_CANONICAL", "Bundle Steps must be sorted by unique id.");
                }
                previousStep = id;
                lockedSteps.add(id);
                final ManifestStep installed = manifest.get(id);
                if (installed == null) {
                    throw failure("STEP_DEPENDENCY_OWNER_MISSING", "Bundle manifest does not own Step: " + id + ".");
                }
                final String version = text(step, "version", "DEPENDENCY_LOCK_NON_CANONICAL", "lock.bundles[].steps[]");
                final String implementation = text(
                        step, "implementation", "DEPENDENCY_LOCK_NON_CANONICAL", "lock.bundles[].steps[]"
                );
                final String implementationEntry = text(
                        step, "implementation_entry", "DEPENDENCY_LOCK_NON_CANONICAL", "lock.bundles[].steps[]"
                );
                final String contract = digest(
                        step, "contract", "DEPENDENCY_LOCK_NON_CANONICAL", "lock.bundles[].steps[]"
                );
                if (!installed.definition().version().equals(version)
                        || !installed.implementation().equals(implementation)
                        || !installed.implementationEntry().equals(implementationEntry)
                        || !installed.contractDigest().equals(contract)) {
                    throw failure(
                            "STEP_DEPENDENCY_CONTRACT_MISMATCH",
                            "Dependency lock does not match the bundle contract for Step: " + id + "."
                    );
                }
                if (find(id).isPresent() || nextImplementations.containsKey(id)) {
                    throw failure("STEP_DEPENDENCY_ID_CONFLICT", "Installed Step id is already present: " + id + ".");
                }
                nextDefinitions.add(installed.definition());
                nextImplementations.put(id, new Implementation(
                        implementation,
                        implementationEntry,
                        Ownership.BUNDLE,
                        closure,
                        contract,
                        List.of()
                ));
            }
            if (!manifest.keySet().equals(lockedSteps)) {
                throw failure(
                        "STEP_DEPENDENCY_OWNER_MISSING",
                        "Dependency lock must list every Step owned by bundle: " + rootDigest + "."
                );
            }
        }
        if (!referencedArtifacts.equals(artifacts.keySet())) {
            throw failure("DEPENDENCY_LOCK_NON_CANONICAL", "Every locked artifact must belong to a bundle closure.");
        }
        return new StepCatalog(nextDefinitions, nextImplementations);
    }

    public Optional<StepDefinition> find(final String id) {
        return Optional.ofNullable(definitionsById.get(id));
    }

    /** Returns verified implementation ownership for an executable definition. */
    public Optional<Implementation> implementation(final String id) {
        return Optional.ofNullable(implementations.get(id));
    }

    public List<StepDefinition> definitions() {
        return definitions;
    }

    private static Map<String, Artifact> artifacts(
            final RailixValue.ObjectValue root,
            final Path store
    ) throws DependencyException {
        final Map<String, Artifact> result = new LinkedHashMap<>();
        String previous = "";
        for (final RailixValue value : array(
                root, "artifacts", "DEPENDENCY_LOCK_NON_CANONICAL", "lock"
        ).values()) {
            final RailixValue.ObjectValue item = object(value, "DEPENDENCY_LOCK_NON_CANONICAL", "lock.artifacts[]");
            exact(item, Set.of("digest", "origin", "size"),
                    "DEPENDENCY_LOCK_NON_CANONICAL", "lock.artifacts[]");
            final String digest = digest(item, "digest", "DEPENDENCY_LOCK_NON_CANONICAL", "lock.artifacts[]");
            if (digest.compareTo(previous) <= 0) {
                throw failure("DEPENDENCY_LOCK_NON_CANONICAL", "Artifacts must be sorted by unique digest.");
            }
            previous = digest;
            text(item, "origin", "DEPENDENCY_LOCK_NON_CANONICAL", "lock.artifacts[]");
            final long size = longValue(item, "size", "DEPENDENCY_LOCK_NON_CANONICAL", "lock.artifacts[]");
            if (size < 1) {
                throw failure("DEPENDENCY_LOCK_NON_CANONICAL", "Artifact size must be positive.");
            }
            final Path file = store.resolve(digest.substring("sha256:".length()) + ".jar").normalize();
            if (!file.getParent().equals(store)
                    || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw failure("DEPENDENCY_ARTIFACT_MISSING", "Locked artifact is missing: " + digest + ".");
            }
            try {
                if (Files.size(file) != size || !digest.equals("sha256:" + digest(file))) {
                    throw failure("DEPENDENCY_ARTIFACT_DIGEST_MISMATCH", "Locked artifact bytes do not match: "
                            + digest + ".");
                }
            } catch (final DependencyException exception) {
                throw exception;
            } catch (final IOException exception) {
                throw failure("DEPENDENCY_ARTIFACT_MISSING", "Locked artifact cannot be read: " + digest + ".", exception);
            }
            int entryCount = 0;
            long declaredBytes = 0;
            long uncompressedBytes = 0;
            try (JarFile jar = new JarFile(file.toFile(), true)) {
                final var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    final JarEntry entry = entries.nextElement();
                    if (entry.isDirectory()) {
                        continue;
                    }
                    entryCount++;
                    if (entryCount > MAX_ARTIFACT_ENTRIES) {
                        throw failure("DEPENDENCY_ARTIFACT_ENTRY_LIMIT", "Locked artifact contains more than "
                                + MAX_ARTIFACT_ENTRIES + " files: " + digest + ".");
                    }
                    if (entry.getSize() < 0 || entry.getSize() > MAX_ARTIFACT_ENTRY_BYTES) {
                        throw failure("DEPENDENCY_ARTIFACT_ENTRY_SIZE_LIMIT", "Locked artifact entry exceeds "
                                + MAX_ARTIFACT_ENTRY_BYTES + " uncompressed bytes: " + entry.getName() + ".");
                    }
                    declaredBytes = Math.addExact(declaredBytes, entry.getSize());
                    if (declaredBytes > MAX_ARTIFACT_UNCOMPRESSED_BYTES) {
                        throw failure("DEPENDENCY_ARTIFACT_UNCOMPRESSED_SIZE_LIMIT", "Locked artifact exceeds "
                                + MAX_ARTIFACT_UNCOMPRESSED_BYTES + " declared uncompressed bytes: " + digest + ".");
                    }
                }
                final var verifiedEntries = jar.entries();
                while (verifiedEntries.hasMoreElements()) {
                    final JarEntry entry = verifiedEntries.nextElement();
                    if (entry.isDirectory()) {
                        continue;
                    }
                    try (InputStream input = jar.getInputStream(entry)) {
                        final long actual = limitedSize(input, MAX_ARTIFACT_ENTRY_BYTES);
                        if (actual != entry.getSize()) {
                            throw failure("DEPENDENCY_ARTIFACT_INVALID_JAR", "Locked artifact entry size is invalid: "
                                    + entry.getName() + ".");
                        }
                        uncompressedBytes = Math.addExact(uncompressedBytes, actual);
                        if (uncompressedBytes > MAX_ARTIFACT_UNCOMPRESSED_BYTES) {
                            throw failure("DEPENDENCY_ARTIFACT_UNCOMPRESSED_SIZE_LIMIT", "Locked artifact exceeds "
                                    + MAX_ARTIFACT_UNCOMPRESSED_BYTES + " uncompressed bytes: " + digest + ".");
                        }
                    }
                }
            } catch (final DependencyException exception) {
                throw exception;
            } catch (final ArithmeticException exception) {
                throw failure("DEPENDENCY_ARTIFACT_UNCOMPRESSED_SIZE_LIMIT", "Locked artifact size overflows: "
                        + digest + ".", exception);
            } catch (final IOException exception) {
                throw failure("DEPENDENCY_ARTIFACT_INVALID_JAR", "Locked artifact is not a valid JAR: " + digest + ".", exception);
            }
            result.put(digest, new Artifact(digest, file, entryCount, uncompressedBytes));
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, ManifestStep> manifest(final Artifact artifact) throws DependencyException {
        final String source;
        try (JarFile jar = new JarFile(artifact.path().toFile(), true)) {
            final JarEntry entry = jar.getJarEntry(STEP_MANIFEST);
            if (entry == null || entry.isDirectory() || entry.getSize() > MANIFEST_LIMIT) {
                throw failure("STEP_DEPENDENCY_OWNER_MISSING", "Bundle Step manifest is missing or too large: "
                        + artifact.digest() + ".");
            }
            try (InputStream input = jar.getInputStream(entry)) {
                final byte[] bytes = input.readNBytes(MANIFEST_LIMIT + 1);
                if (bytes.length > MANIFEST_LIMIT) {
                    throw failure("STEP_DEPENDENCY_OWNER_MISSING", "Bundle Step manifest is too large: "
                            + artifact.digest() + ".");
                }
                source = new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (final DependencyException exception) {
            throw exception;
        } catch (final IOException exception) {
            throw failure("DEPENDENCY_ARTIFACT_INVALID_JAR", "Bundle Step manifest cannot be read: "
                    + artifact.digest() + ".", exception);
        }
        final RailixValue.ObjectValue root = parseCanonical(
                source,
                "STEP_DEPENDENCY_MANIFEST_NON_CANONICAL",
                "Bundle Step manifest"
        );
        exact(root, Set.of("format", "steps"), "STEP_DEPENDENCY_MANIFEST_NON_CANONICAL", "manifest");
        if (integer(root, "format", "STEP_DEPENDENCY_MANIFEST_NON_CANONICAL", "manifest") != 1) {
            throw failure("STEP_DEPENDENCY_MANIFEST_NON_CANONICAL", "Bundle Step manifest format must be 1.");
        }
        final Map<String, ManifestStep> result = new LinkedHashMap<>();
        String previous = "";
        for (final RailixValue value : array(
                root, "steps", "STEP_DEPENDENCY_MANIFEST_NON_CANONICAL", "manifest"
        ).values()) {
            final RailixValue.ObjectValue item = object(
                    value, "STEP_DEPENDENCY_MANIFEST_NON_CANONICAL", "manifest.steps[]"
            );
            exact(item, Set.of("contract", "contract_digest", "implementation", "implementation_entry"),
                    "STEP_DEPENDENCY_MANIFEST_NON_CANONICAL", "manifest.steps[]");
            final RailixValue.ObjectValue contract = object(
                    required(item, "contract", "STEP_DEPENDENCY_MANIFEST_NON_CANONICAL", "manifest.steps[]"),
                    "STEP_DEPENDENCY_MANIFEST_NON_CANONICAL",
                    "manifest.steps[].contract"
            );
            final String implementation = text(
                    item, "implementation", "STEP_DEPENDENCY_MANIFEST_NON_CANONICAL", "manifest.steps[]"
            );
            final String implementationEntry = text(
                    item, "implementation_entry", "STEP_DEPENDENCY_MANIFEST_NON_CANONICAL", "manifest.steps[]"
            );
            final String expected = digest(
                    item, "contract_digest", "STEP_DEPENDENCY_MANIFEST_NON_CANONICAL", "manifest.steps[]"
            );
            final String actual = "sha256:" + digest(RailixJson.write(contract).getBytes(StandardCharsets.UTF_8));
            if (!expected.equals(actual)) {
                throw failure("STEP_DEPENDENCY_CONTRACT_MISMATCH", "Bundle Step contract digest does not match.");
            }
            final StepDefinition definition;
            try {
                definition = StepContractJson.read(contract, implementation, implementationEntry);
            } catch (final IllegalArgumentException exception) {
                throw failure("STEP_DEPENDENCY_CONTRACT_INVALID", "Bundle Step contract is invalid: "
                        + exception.getMessage(), exception);
            }
            if (definition.kind() == StepDefinition.Kind.APP) {
                throw failure("STEP_DEPENDENCY_CONTRACT_INVALID", "Installed bundles cannot replace railix.app.");
            }
            if (definition.id().compareTo(previous) <= 0) {
                throw failure("STEP_DEPENDENCY_MANIFEST_NON_CANONICAL", "Manifest Steps must be sorted by unique id.");
            }
            previous = definition.id();
            result.put(definition.id(), new ManifestStep(
                    definition,
                    implementation,
                    implementationEntry,
                    expected
            ));
        }
        try (JarFile jar = new JarFile(artifact.path().toFile(), true)) {
            for (final ManifestStep step : result.values()) {
                final JarEntry implementation = jar.getJarEntry(step.implementationEntry());
                if (implementation == null || implementation.isDirectory()) {
                    throw failure(
                            "STEP_DEPENDENCY_OWNER_MISSING",
                            "Bundle root artifact does not own Step implementation: "
                                    + step.implementationEntry() + "."
                    );
                }
            }
        } catch (final DependencyException exception) {
            throw exception;
        } catch (final IOException exception) {
            throw failure("DEPENDENCY_ARTIFACT_INVALID_JAR", "Bundle root artifact cannot be verified: "
                    + artifact.digest() + ".", exception);
        }
        return Collections.unmodifiableMap(result);
    }

    private static List<String> digests(final RailixValue.ArrayValue values, final String path)
            throws DependencyException {
        final List<String> result = new ArrayList<>();
        String previous = "";
        for (final RailixValue value : values.values()) {
            final String digest = digest(string(value, "DEPENDENCY_LOCK_NON_CANONICAL", path + "[]"),
                    "DEPENDENCY_LOCK_NON_CANONICAL", path + "[]");
            if (digest.compareTo(previous) <= 0) {
                throw failure("DEPENDENCY_LOCK_NON_CANONICAL", path + " must contain sorted unique digests.");
            }
            previous = digest;
            result.add(digest);
        }
        return List.copyOf(result);
    }

    private static Artifact artifact(final Map<String, Artifact> artifacts, final String digest)
            throws DependencyException {
        final Artifact artifact = artifacts.get(digest);
        if (artifact == null) {
            throw failure("DEPENDENCY_ARTIFACT_MISSING", "Bundle references an unlocked artifact: " + digest + ".");
        }
        return artifact;
    }

    private static RailixValue.ObjectValue parseCanonical(
            final String source,
            final String code,
            final String label
    ) throws DependencyException {
        final RailixJson.Result parsed = RailixJson.parse(source);
        if (!(parsed instanceof RailixJson.Parsed valid)
                || !(valid.value() instanceof RailixValue.ObjectValue object)
                || !RailixJson.write(valid.value()).equals(source)) {
            throw failure(code, label + " must be one canonical JSON object.");
        }
        return object;
    }

    private static void exact(
            final RailixValue.ObjectValue object,
            final Set<String> fields,
            final String code,
            final String path
    ) throws DependencyException {
        if (!object.values().keySet().equals(fields)) {
            throw failure(code, path + " fields must be exactly: " + fields.stream().sorted().toList() + ".");
        }
    }

    private static RailixValue required(
            final RailixValue.ObjectValue owner,
            final String name,
            final String code,
            final String path
    ) throws DependencyException {
        final RailixValue value = owner.values().get(name);
        if (value == null) {
            throw failure(code, path + "." + name + " is required.");
        }
        return value;
    }

    private static RailixValue.ObjectValue object(
            final RailixValue value,
            final String code,
            final String path
    ) throws DependencyException {
        if (value instanceof RailixValue.ObjectValue object) {
            return object;
        }
        throw failure(code, path + " must be an object.");
    }

    private static RailixValue.ArrayValue array(
            final RailixValue.ObjectValue owner,
            final String name,
            final String code,
            final String path
    ) throws DependencyException {
        final RailixValue value = required(owner, name, code, path);
        if (value instanceof RailixValue.ArrayValue array) {
            return array;
        }
        throw failure(code, path + "." + name + " must be an array.");
    }

    private static String text(
            final RailixValue.ObjectValue owner,
            final String name,
            final String code,
            final String path
    ) throws DependencyException {
        return string(required(owner, name, code, path), code, path + "." + name);
    }

    private static String string(final RailixValue value, final String code, final String path)
            throws DependencyException {
        if (value instanceof RailixValue.StringValue string && !string.value().isBlank()) {
            return string.value();
        }
        throw failure(code, path + " must be a non-blank string.");
    }

    private static int integer(
            final RailixValue.ObjectValue owner,
            final String name,
            final String code,
            final String path
    ) throws DependencyException {
        final long value = longValue(owner, name, code, path);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw failure(code, path + "." + name + " must be a 32-bit integer.");
        }
        return (int) value;
    }

    private static long longValue(
            final RailixValue.ObjectValue owner,
            final String name,
            final String code,
            final String path
    ) throws DependencyException {
        if (required(owner, name, code, path) instanceof RailixValue.NumberValue number) {
            try {
                return number.value().longValueExact();
            } catch (final ArithmeticException exception) {
                throw failure(code, path + "." + name + " must be an integer.", exception);
            }
        }
        throw failure(code, path + "." + name + " must be a number.");
    }

    private static String digest(
            final RailixValue.ObjectValue owner,
            final String name,
            final String code,
            final String path
    ) throws DependencyException {
        return digest(text(owner, name, code, path), code, path + "." + name);
    }

    private static String digest(final String value, final String code, final String path)
            throws DependencyException {
        if (value.length() != 71 || !value.startsWith("sha256:")) {
            throw failure(code, path + " must be a sha256 digest.");
        }
        try {
            HexFormat.of().parseHex(value.substring(7));
            if (!value.equals(value.toLowerCase(java.util.Locale.ROOT))) {
                throw new IllegalArgumentException("uppercase digest");
            }
            return value;
        } catch (final IllegalArgumentException exception) {
            throw failure(code, path + " must be a lowercase sha256 digest.", exception);
        }
    }

    private static String digest(final byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (final NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available.", exception);
        }
    }

    private static String digest(final Path file) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available.", exception);
        }
        try (InputStream input = Files.newInputStream(file);
             DigestInputStream stream = new DigestInputStream(input, digest)) {
            stream.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static long limitedSize(final InputStream input, final long limit) throws IOException, DependencyException {
        long size = 0;
        final byte[] buffer = new byte[8192];
        for (int read = input.read(buffer); read >= 0; read = input.read(buffer)) {
            if (read == 0) {
                continue;
            }
            size = Math.addExact(size, read);
            if (size > limit) {
                throw failure("DEPENDENCY_ARTIFACT_ENTRY_SIZE_LIMIT", "Locked artifact entry exceeds "
                        + limit + " uncompressed bytes while reading.");
            }
        }
        return size;
    }

    private static DependencyException failure(final String code, final String message) {
        return new DependencyException(code, message, null);
    }

    private static DependencyException failure(
            final String code,
            final String message,
            final Throwable cause
    ) {
        return new DependencyException(code, message, cause);
    }

    /** One immutable content-addressed artifact in a verified bundle closure. */
    public record Artifact(String digest, Path path, int entryCount, long uncompressedBytes) {
        public Artifact {
            if (digest == null || path == null) {
                throw new IllegalArgumentException("Dependency artifact identity cannot be Java null.");
            }
            if (entryCount < 0 || entryCount > MAX_ARTIFACT_ENTRIES
                    || uncompressedBytes < 0 || uncompressedBytes > MAX_ARTIFACT_UNCOMPRESSED_BYTES) {
                throw new IllegalArgumentException("Dependency artifact inventory exceeds Railix limits.");
            }
            path = path.toAbsolutePath().normalize();
        }
    }

    /** One direct implementation address and its explicit platform or locked bundle ownership. */
    public record Implementation(
            String className,
            String classEntry,
            Ownership ownership,
            List<Artifact> artifacts,
            String contractDigest,
            List<String> jdkModules
    ) {
        public Implementation {
            if (className == null || className.isBlank() || classEntry == null || classEntry.isBlank() || ownership == null
                    || artifacts == null || contractDigest == null || contractDigest.isBlank() || jdkModules == null) {
                throw new IllegalArgumentException("Step implementation ownership must be complete.");
            }
            artifacts = List.copyOf(artifacts);
            final Set<String> uniqueModules = new LinkedHashSet<>();
            for (final String module : jdkModules) {
                if (module == null || module.isBlank()) {
                    throw new IllegalArgumentException("Step implementation ownership must be complete.");
                }
               uniqueModules.add(module);
            }
            jdkModules = List.copyOf(uniqueModules);
            if (ownership == Ownership.PLATFORM && !artifacts.isEmpty()
                    || ownership == Ownership.BUNDLE && artifacts.isEmpty()) {
                throw new IllegalArgumentException("Step implementation artifacts must match its ownership.");
            }
        }

        public boolean platformOwned() {
            return ownership == Ownership.PLATFORM;
        }
    }

    /** Physical owner used by the application packager. */
    public enum Ownership {
        /** Railix runtime or standard-library code shipped with Creator. */
        PLATFORM,
        /** An immutable root bundle and locked runtime closure. */
        BUNDLE
    }

    /** Deterministic installed-dependency boundary failure. */
    public static final class DependencyException extends IOException {
        private final String code;

        private DependencyException(final String code, final String message, final Throwable cause) {
            super(code + ": " + message, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private record ManifestStep(
            StepDefinition definition,
            String implementation,
            String implementationEntry,
            String contractDigest
    ) {
    }
}
