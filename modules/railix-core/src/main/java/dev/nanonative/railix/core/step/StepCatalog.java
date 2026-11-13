package dev.nanonative.railix.core.step;

import dev.nanonative.railix.core.util.Sha256;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
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
    private final Map<String, Implementation> validationProviders;

    private StepCatalog(
            final List<StepDefinition> definitions,
            final Map<String, Implementation> implementations,
            final Map<String, Implementation> validationProviders
    ) {
        this.definitions = Collections.unmodifiableList(new ArrayList<>(definitions));
        final Map<String, StepDefinition> byId = new LinkedHashMap<>();
        definitions.forEach(definition -> byId.put(definition.id(), definition));
        this.definitionsById = Collections.unmodifiableMap(byId);
        this.implementations = Collections.unmodifiableMap(new LinkedHashMap<>(implementations));
        this.validationProviders = Map.copyOf(validationProviders);
    }

    /**
     * Creates an explicit authoring catalog for definition checks and source generation.
     * Packaging requires verified bundles rather than classes borrowed from the host classpath.
     *
     * @param definitions explicitly constructed contracts
     * @return immutable authoring catalog, without artifact ownership
     * @throws IllegalArgumentException for Java-null or duplicate definitions
     */
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
                            "sha256:" + Sha256.hexDigest(StepContractJson.write(definition)),
                            address.jdkModules(), definition.dynamicClasses()
                    )
            ));
        }
        return new StepCatalog(copy, implementations, Map.of());
    }

    /**
     * Creates an explicit catalog with a provider for isolated configuration validation.
     *
     * @param provider class declaring public static List of StepDefinition {@code definitions()}
     * @param definitions definitions supplied by that provider
     * @return catalog; ordinary definitions without validators start no helper process
     * @throws IllegalArgumentException for an invalid provider or definitions
     */
    public static StepCatalog of(final Class<?> provider, final StepDefinition... definitions) {
        if (provider == null || provider.getCanonicalName() == null) {
            throw new IllegalArgumentException("Definitions provider must be a named Java class.");
        }
        final StepCatalog catalog = of(definitions);
        final Map<String, Implementation> providers = new LinkedHashMap<>();
        catalog.definitions.forEach(definition -> providers.put(definition.id(), new Implementation(
                provider.getCanonicalName(), provider.getName().replace('.', '/') + ".class", Ownership.PLATFORM,
                List.of(), "sha256:" + Sha256.hexDigest(StepContractJson.write(definition)), List.of(), List.of())));
        return new StepCatalog(catalog.definitions, catalog.implementations, providers);
    }

    /** Returns the explicit validation provider, or empty for a directly constructed in-process catalog. */
    public Optional<Implementation> validationProvider(final String stepId) {
        return Optional.ofNullable(validationProviders.get(stepId));
    }

    /**
     * Derives the ordinary dependency lock for a built provider without executing its code.
     * Copies immutable artifacts into the supplied store. It does not publish a project lock.
     *
     * @param bundle root JAR with a canonical Step manifest
     * @param store content-addressed artifact directory
     * @param runtime explicit runtime dependency JARs, excluding the Railix host API
     * @return canonical dependency-lock JSON ready for {@link #installSource(String, Path)}
     * @throws IllegalArgumentException for missing arguments
     * @throws IOException for invalid bundles, ownership conflicts or storage errors
     */
    public static String bundleLock(final Path bundle, final Path store, final Path... runtime) throws IOException {
        if (bundle == null || store == null || runtime == null || java.util.Arrays.stream(runtime).anyMatch(java.util.Objects::isNull))
            throw new IllegalArgumentException("Provider bundle, store and dependencies cannot be Java null.");
        try (var input = Files.newInputStream(bundle, LinkOption.NOFOLLOW_LINKS)) {
            return bundleLock(input, store, runtime);
        }
    }

    /**
     * Checks a bundle stream before publishing its immutable artifacts to the shared store.
     * Used for embedded and local providers through the same resolver. No classes are loaded.
     *
     * @param bundle caller-owned root JAR stream, left open
     * @param store content-addressed artifact directory
     * @param runtime explicit runtime dependency JARs
     * @return canonical dependency-lock JSON, without publishing a project selection
     * @throws IllegalArgumentException for missing arguments
     * @throws IOException for invalid bundles, damaged existing artifacts or storage errors
     */
    public static String bundleLock(final InputStream bundle, final Path store, final Path... runtime) throws IOException {
        if (bundle == null || store == null || runtime == null || java.util.Arrays.stream(runtime).anyMatch(java.util.Objects::isNull))
            throw new IllegalArgumentException("Provider bundle, store and dependencies cannot be Java null.");
        final Path target = store.toAbsolutePath().normalize();
        Files.createDirectories(target);
        try (var directory = new StepBundle.ImportDirectory(Files.createTempDirectory(target, ".import-"))) {
            final Path staging = directory.path();
            final Map<String, RailixValue> lockedArtifacts = new java.util.TreeMap<>();
            final List<String> identities = new ArrayList<>();
            for (int index = 0; index <= runtime.length; index++) {
                final Path staged;
                if (index == 0) staged = StepBundle.store(bundle, staging);
                else try (var input = Files.newInputStream(runtime[index - 1], LinkOption.NOFOLLOW_LINKS)) {
                    staged = StepBundle.store(input, staging);
                }
                final String identity = "sha256:" + staged.getFileName().toString().replace(".jar", "");
                if (lockedArtifacts.containsKey(identity)) throw failure("DEPENDENCY_LOCK_NON_CANONICAL", "A bundle closure must not repeat an artifact.");
                identities.add(identity);
                lockedArtifacts.put(identity, RailixValue.object(Map.of("digest", RailixValue.string(identity),
                        "origin", RailixValue.string("local"), "size", RailixValue.number(Files.size(staged)))));
            }
            final var artifactRoot = RailixValue.object(Map.of("artifacts", RailixValue.array(List.copyOf(lockedArtifacts.values()))));
            final Map<String, Artifact> verified = artifacts(artifactRoot, staging);
            final Map<String, ManifestStep> contracts = manifest(verified.get(identities.getFirst()));
            final List<RailixValue> steps = contracts.values().stream().<RailixValue>map(contract -> {
                final Map<String, RailixValue> item = new LinkedHashMap<>(Map.of(
                        "id", RailixValue.string(contract.definition().id()),
                        "version", RailixValue.string(contract.definition().version()),
                        "contract", RailixValue.string(contract.contractDigest())));
                if (contract.definition().executable()) {
                    item.put("implementation", RailixValue.string(contract.implementation()));
                    item.put("implementation_entry", RailixValue.string(contract.implementationEntry()));
                }
                return RailixValue.object(item);
            }).toList();
            final String lock = RailixJson.write(RailixValue.object(Map.of(
                    "format", RailixValue.number(1), "artifacts", artifactRoot.values().get("artifacts"),
                    "bundles", RailixValue.array(List.of(RailixValue.object(Map.of(
                            "artifact", RailixValue.string(identities.getFirst()),
                            "runtime", RailixValue.array(identities.stream().skip(1).sorted().<RailixValue>map(RailixValue::string).toList()),
                            "steps", RailixValue.array(steps))))))));
            of().installSource(lock, staging);
            for (final Artifact artifact : verified.values()) StepBundle.publish(artifact.path(), target);
            return lock;
        }
    }

    /**
     * Loads distribution-provided bundles through the ordinary manifest, lock and store resolver.
     * No provider class is loaded. A missing or invalid distribution fails without a host fallback.
     *
     * @param loader distribution classloader containing {@code META-INF/railix/providers.index}
     * @param store the normal immutable artifact directory
     * @return checked embedded catalog, ready to add a project's external dependency lock
     * @throws IllegalArgumentException for missing arguments
     * @throws IOException for absent resources, invalid bundles or storage errors
     */
    public static StepCatalog embedded(final ClassLoader loader, final Path store) throws IOException {
        if (loader == null || store == null) throw new IllegalArgumentException("Provider loader and store cannot be Java null.");
        StepCatalog catalog = of();
        final Set<String> names = new LinkedHashSet<>();
        final var indexes = loader.getResources(StepBundle.INDEX);
        while (indexes.hasMoreElements()) {
            final var index = indexes.nextElement();
            final String source;
            try (var input = index.openStream()) {
                final byte[] bytes = input.readNBytes(MANIFEST_LIMIT + 1);
                if (bytes.length > MANIFEST_LIMIT) throw failure("STEP_DEPENDENCY_OWNER_MISSING", "Embedded provider index is too large.");
                source = new String(bytes, StandardCharsets.UTF_8);
            }
            for (final String line : source.lines().toList()) {
                final String name = line.trim();
                if (name.isEmpty()) continue;
                if (!name.matches("META-INF/railix/providers/[A-Za-z0-9][A-Za-z0-9._-]*\\.jar") || !names.add(name))
                    throw failure("STEP_DEPENDENCY_OWNER_MISSING", "Embedded provider resource is invalid or duplicated: " + name + ".");
                final String address = index.toExternalForm();
                final var resource = java.net.URI.create(address.substring(0, address.length() - StepBundle.INDEX.length()) + name).toURL();
                try (var input = resource.openStream()) {
                    catalog = catalog.installSource(bundleLock(input, store), store);
                }
            }
        }
        if (names.isEmpty()) throw failure("STEP_DEPENDENCY_OWNER_MISSING", "Distribution contains no embedded provider bundles.");
        return catalog;
    }

    /**
     * Validates a provider entry using the shared import and application-packaging rules.
     *
     * @param entry forward-slash JAR entry
     * @return the validated entry name for continued processing
     * @throws IllegalArgumentException when the entry is Java {@code null}
     * @throws DependencyException for unsafe paths, host classes or unsupported JAR metadata
     */
    public static String artifactEntry(final String entry) throws DependencyException {
        if (entry == null) throw new IllegalArgumentException("Artifact entry cannot be Java null.");
        if (entry.isBlank() || entry.startsWith("/") || entry.contains("\\") || entry.indexOf('\0') >= 0
                || java.util.Arrays.stream(entry.split("/", -1)).anyMatch(part -> part.isEmpty() || ".".equals(part) || "..".equals(part)))
            throw failure("DEPENDENCY_LOCAL_ENTRY_UNSAFE", "Provider contains unsafe entry: " + entry + ".");
        if (entry.endsWith(".class") && (entry.startsWith("dev/nanonative/railix/core/")
                || entry.startsWith("dev/nanonative/railix/creator/") || entry.startsWith("dev/nanonative/railix/development/")))
            throw failure("DEPENDENCY_PLATFORM_CLASS_FORBIDDEN", "Provider cannot supply host class: " + entry + ".");
        final String upper = entry.toUpperCase(java.util.Locale.ROOT);
        if (entry.endsWith("module-info.class") || upper.startsWith("META-INF/") && (upper.endsWith(".SF")
                || upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC")))
            throw failure("DEPENDENCY_ARTIFACT_UNSUPPORTED_METADATA", "Provider contains unsupported module or signature metadata: " + entry + ".");
        return entry;
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
        return installSource(source, store);
    }

    /**
     * Adds installed bundles from one captured dependency-lock document using the normal resolver.
     *
     * @param source canonical lock JSON, captured before a build or package operation
     * @param store directory containing the immutable content-addressed bundle artifacts
     * @return a new catalog containing the installed contracts and implementation ownership
     * @throws IllegalArgumentException when either argument is Java {@code null}
     * @throws DependencyException when the lock, manifest, ownership or artifact integrity is invalid
     */
    public StepCatalog installSource(final String source, final Path store) throws DependencyException {
        if (source == null || store == null)
            throw new IllegalArgumentException("Dependency lock and store cannot be Java null.");
        if (source.getBytes(StandardCharsets.UTF_8).length > LOCK_LIMIT)
            throw failure("DEPENDENCY_LOCK_NON_CANONICAL", "Dependency lock exceeds " + LOCK_LIMIT + " bytes.");
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
        final Set<String> nextIds = new LinkedHashSet<>(definitionsById.keySet());
        final Map<String, Implementation> nextImplementations = new LinkedHashMap<>(implementations);
        final Map<String, Implementation> nextProviders = new LinkedHashMap<>(validationProviders);
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
                final boolean executable = installed.definition().executable();
                exact(step, executable ? Set.of("contract", "id", "implementation", "implementation_entry", "version")
                                : Set.of("contract", "id", "version"),
                        "DEPENDENCY_LOCK_NON_CANONICAL", "lock.bundles[].steps[]");
                final String version = text(step, "version", "DEPENDENCY_LOCK_NON_CANONICAL", "lock.bundles[].steps[]");
                final String implementation = executable ? text(
                        step, "implementation", "DEPENDENCY_LOCK_NON_CANONICAL", "lock.bundles[].steps[]"
                ) : "";
                final String implementationEntry = executable ? text(
                        step, "implementation_entry", "DEPENDENCY_LOCK_NON_CANONICAL", "lock.bundles[].steps[]"
                ) : "";
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
                if (!nextIds.add(id)) {
                    throw failure("STEP_DEPENDENCY_ID_CONFLICT", "Installed Step id is already present: " + id + ".");
                }
                nextDefinitions.add(installed.definition());
                installed.providers().forEach(provider -> nextProviders.put(id, new Implementation(
                        provider.sourceName(), provider.classEntry(), Ownership.BUNDLE, closure, contract, List.of(), List.of())));
                if (executable) nextImplementations.put(id, new Implementation(
                        implementation,
                        implementationEntry,
                        Ownership.BUNDLE,
                        closure,
                        contract,
                        installed.definition().implementationAddress().orElseThrow().jdkModules(), installed.definition().dynamicClasses()
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
        return new StepCatalog(nextDefinitions, nextImplementations, nextProviders);
    }

    public Optional<StepDefinition> find(final String id) {
        return Optional.ofNullable(definitionsById.get(id));
    }

    /**
     * Removes one definition from this immutable authoring selection without modifying its artifacts.
     * @param id definition to remove
     * @return a catalog preserving all other definitions and ownership, or this catalog when absent
     * @throws IllegalArgumentException if the identifier is Java null
     */
    public StepCatalog withoutDefinition(final String id) {
        if (id == null) throw new IllegalArgumentException("Step identifier cannot be Java null.");
        if (!definitionsById.containsKey(id)) return this;
        final var remaining = definitions.stream().filter(definition -> !definition.id().equals(id)).toList();
        final var owners = new LinkedHashMap<>(implementations);
        final var providers = new LinkedHashMap<>(validationProviders);
        owners.remove(id);
        providers.remove(id);
        return new StepCatalog(remaining, owners, providers);
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
                if (Files.size(file) != size || !digest.equals("sha256:" + Sha256.hexDigest(file))) {
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
                final Set<String> names = new LinkedHashSet<>();
                while (entries.hasMoreElements()) {
                    final JarEntry entry = entries.nextElement();
                    if (entry.isDirectory()) {
                        continue;
                    }
                    final String name = entry.getName();
                    artifactEntry(name);
                    if (!names.add(name)) throw failure("DEPENDENCY_ARTIFACT_INVALID_JAR", "Locked artifact repeats entry: " + name + ".");
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
        exact(root, root.values().containsKey("definitions") ? Set.of("format", "steps", "definitions")
                : Set.of("format", "steps"), "STEP_DEPENDENCY_MANIFEST_NON_CANONICAL", "manifest");
        final List<StepDefinition.ImplementationAddress> providers = new ArrayList<>();
        if (root.values().containsKey("definitions")) {
            final RailixValue.ObjectValue provider = object(root.values().get("definitions"),
                    "STEP_DEPENDENCY_CONTRACT_INVALID", "manifest.definitions");
            exact(provider, Set.of("class", "entry"), "STEP_DEPENDENCY_CONTRACT_INVALID", "manifest.definitions");
            try {
                providers.add(new StepDefinition.ImplementationAddress(
                        text(provider, "class", "STEP_DEPENDENCY_CONTRACT_INVALID", "manifest.definitions"),
                        text(provider, "entry", "STEP_DEPENDENCY_CONTRACT_INVALID", "manifest.definitions"), List.of()));
            } catch (final IllegalArgumentException exception) {
                throw failure("STEP_DEPENDENCY_CONTRACT_INVALID", exception.getMessage(), exception);
            }
        }
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
            final RailixValue.ObjectValue contract = object(
                    required(item, "contract", "STEP_DEPENDENCY_MANIFEST_NON_CANONICAL", "manifest.steps[]"),
                    "STEP_DEPENDENCY_MANIFEST_NON_CANONICAL",
                    "manifest.steps[].contract"
            );
            final boolean app = "app".equals(text(contract, "kind", "STEP_DEPENDENCY_CONTRACT_INVALID", "contract"));
            exact(item, app ? Set.of("contract", "contract_digest")
                            : Set.of("contract", "contract_digest", "implementation", "implementation_entry"),
                    "STEP_DEPENDENCY_MANIFEST_NON_CANONICAL", "manifest.steps[]");
            final String implementation = !app ? text(
                    item, "implementation", "STEP_DEPENDENCY_MANIFEST_NON_CANONICAL", "manifest.steps[]"
            ) : "";
            final String implementationEntry = !app ? text(
                    item, "implementation_entry", "STEP_DEPENDENCY_MANIFEST_NON_CANONICAL", "manifest.steps[]"
            ) : "";
            final String expected = digest(
                    item, "contract_digest", "STEP_DEPENDENCY_MANIFEST_NON_CANONICAL", "manifest.steps[]"
            );
            final String actual = "sha256:" + Sha256.hexDigest(RailixJson.write(contract));
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
            if (providers.isEmpty() && !StepValidation.inputs(definition).isEmpty()) {
                throw failure("STEP_DEPENDENCY_CONTRACT_INVALID", "Bundle validators require a definitions provider.");
            }
            if (definition.id().compareTo(previous) <= 0) {
                throw failure("STEP_DEPENDENCY_MANIFEST_NON_CANONICAL", "Manifest Steps must be sorted by unique id.");
            }
            previous = definition.id();
            result.put(definition.id(), new ManifestStep(
                    definition,
                    implementation,
                    implementationEntry,
                    expected,
                    List.copyOf(providers)
            ));
        }
        try (JarFile jar = new JarFile(artifact.path().toFile(), true)) {
            for (final StepDefinition.ImplementationAddress provider : providers) {
                if (jar.getJarEntry(provider.classEntry()) == null) {
                    throw failure("STEP_DEPENDENCY_OWNER_MISSING", "Bundle root does not own its definitions provider.");
                }
            }
            for (final ManifestStep step : result.values()) {
                if (!step.definition().executable()) continue;
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

    /**
     * One direct implementation address and its explicit authoring or locked bundle ownership.
     *
     * @param className Java source name for generated direct calls
     * @param classEntry owning JAR class entry
     * @param ownership physical authoring or bundle ownership
     * @param artifacts immutable bundle closure, empty only for authoring catalogs
     * @param contractDigest identity of the defining contract
     * @param jdkModules declared JDK modules needed at compilation
     * @param dynamicClasses additional dynamically loaded Java binary class names
     */
    public record Implementation(
            String className,
            String classEntry,
            Ownership ownership,
            List<Artifact> artifacts,
            String contractDigest,
            List<String> jdkModules,
            List<String> dynamicClasses
    ) {
        public Implementation {
            if (className == null || className.isBlank() || classEntry == null || classEntry.isBlank() || ownership == null
                    || artifacts == null || contractDigest == null || contractDigest.isBlank() || jdkModules == null || dynamicClasses == null) {
                throw new IllegalArgumentException("Step implementation ownership must be complete.");
            }
            artifacts = List.copyOf(artifacts);
            dynamicClasses = dynamicClasses.stream().map(StepDefinition::implementationName).sorted().distinct().toList();
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

        /**
         * Checks a definition provider's public static entrypoint from class bytes without initialization.
         * @return actionable signature problem, or empty for a directly callable provider
         * @throws DependencyException when the class bytes cannot be inspected
         */
        public Optional<String> definitionsProblem() throws DependencyException {
            final var model = classModel();
            final int modifiers = ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC;
            if ((model.flags().flagsMask() & ClassFile.ACC_PUBLIC) == 0 || model.methods().stream().noneMatch(method ->
                    method.methodName().equalsString("definitions")
                            && method.methodType().equalsString("()Ljava/util/List;")
                            && (method.flags().flagsMask() & modifiers) == modifiers)) {
                return Optional.of(className + " must declare public static List<StepDefinition> definitions().");
            }
            return Optional.empty();
        }

        /**
         * Checks the required static PROCESS entrypoint without loading or initializing its class.
         *
         * @return an actionable contract problem, or empty when PROCESS can be called directly
         * @throws DependencyException when the declared class bytes cannot be read or parsed
         */
        public Optional<String> processProblem() throws DependencyException {
            final var model = classModel();
            if ((model.flags().flagsMask() & ClassFile.ACC_PUBLIC) == 0) {
                return Optional.of(className + " must be a public implementation class for direct Step calls.");
            }
            final String descriptor = "(Ldev/nanonative/railix/core/step/StepInput;"
                    + "Ldev/nanonative/railix/core/step/StepInput;)"
                    + "Ldev/nanonative/railix/core/step/StepResult;";
            final var methods = model.methods().stream()
                    .filter(method -> method.methodName().equalsString("process")
                            && method.methodType().equalsString(descriptor)).toList();
            final int modifiers = ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC;
            if (methods.size() != 1 || (methods.getFirst().flags().flagsMask() & modifiers) != modifiers) {
                return Optional.of(className
                        + " must declare public static StepResult process(StepInput, StepInput)."
                        + " Rebuild older Step bundles against the current Step API.");
            }
            return Optional.empty();
        }

        /**
         * Resolves optional conventional lifecycle entrypoints from class bytes, without initialization.
         *
         * @return present PRE/POST method names; absent hooks require no runtime call
         * @throws DependencyException for unreadable bytes or an invalid declared hook signature
         */
        public List<String> lifecycleMethods() throws DependencyException {
            final String descriptor = "(Ldev/nanonative/railix/core/step/StepInput;"
                    + "Ldev/nanonative/railix/core/step/StepInput;)"
                    + "Ldev/nanonative/railix/core/step/StepResult;";
            final var methods = classModel().methods();
            final List<String> present = new ArrayList<>();
            for (final String name : List.of("pre", "post")) {
                final var named = methods.stream()
                        .filter(method -> method.methodName().equalsString(name)).toList();
                if (named.isEmpty()) {
                    continue;
                }
                final int modifiers = ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC;
                if (named.stream().noneMatch(method -> method.methodType().equalsString(descriptor)
                        && (method.flags().flagsMask() & modifiers) == modifiers)) {
                    throw failure("PROJECT_STEP_LIFECYCLE_INVALID", className
                            + " must declare public static StepResult " + name + "(StepInput, StepInput).");
                }
                present.add(name);
            }
            return List.copyOf(present);
        }

        private java.lang.classfile.ClassModel classModel() throws DependencyException {
            try {
                return ClassFile.of().parse(classBytes());
            } catch (final IOException | IllegalArgumentException exception) {
                throw failure("PROJECT_STEP_IMPLEMENTATION_UNREADABLE",
                        "Cannot inspect declared Step class " + className + ": " + exception.getMessage(), exception);
            }
        }

        private byte[] classBytes() throws IOException {
            if (platformOwned()) {
                try (final InputStream input = StepCatalog.class.getClassLoader().getResourceAsStream(classEntry)) {
                    if (input == null) {
                        throw new IOException("Class resource is missing: " + classEntry);
                    }
                    return input.readAllBytes();
                }
            }
            try (final JarFile jar = new JarFile(artifacts.getFirst().path().toFile())) {
                final JarEntry entry = jar.getJarEntry(classEntry);
                if (entry == null) {
                    throw new IOException("Bundle class is missing: " + classEntry);
                }
                try (final InputStream input = jar.getInputStream(entry)) {
                    return input.readAllBytes();
                }
            }
        }
    }

    /** Physical owner used by the application packager. */
    public enum Ownership {
        /** Explicit authoring classes on the host classpath, not deployable provider ownership. */
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
            String contractDigest,
            List<StepDefinition.ImplementationAddress> providers
    ) {
    }
}
