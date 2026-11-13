package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.project.CompileResult;
import dev.nanonative.railix.core.project.WorkflowRuntime;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.util.Sha256;
import dev.nanonative.railix.development.ArtifactLease;
import dev.nanonative.railix.development.DevelopmentRuntime;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/** Builds one deterministic, project-local executable application using only JDK tools. */
final class ApplicationBuilder {
    private static final int SERVICE_DESCRIPTOR_LIMIT = 1024 * 1024;
    private static final String GENERATED_CLASS =
            "dev/nanonative/railix/core/project/RailixApplication.class";
    private static final List<String> RUNTIME_PREFIXES = List.of(
            "dev/nanonative/railix/core/project/Diagnostic",
            "dev/nanonative/railix/core/project/RuntimeApplication",
            "dev/nanonative/railix/core/project/ObservedApplication",
            "dev/nanonative/railix/core/project/WorkflowRuntime",
            "dev/nanonative/railix/core/runtime/",
            "dev/nanonative/railix/core/step/StepInput",
            "dev/nanonative/railix/core/step/StepResult",
            "dev/nanonative/railix/core/value/"
    );
    private static final String DEVELOPMENT_PREFIX = "dev/nanonative/railix/development/";

    private ApplicationBuilder() {
    }

    /** Materializes the production runtime API for project-owned Java compilation. */
    static Path runtimeApi(final Path directory) throws IOException {
        final var content = runtime(List.of(), false, Set.of()).entries();
        final Path api = directory.resolve("runtime-api.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(api, StandardOpenOption.CREATE_NEW))) {
            for (final RuntimeEntry entry : content.values()) write(output, entry);
        }
        return api;
    }

    static DevelopmentBuild build(
            final Path projectFile,
            final CompileResult.Compiled compiled
    ) throws IOException {
        return (DevelopmentBuild) build(projectFile, compiled, true, Map.of(), true);
    }

    static Artifact buildProduction(
            final Path projectFile,
            final CompileResult.Compiled compiled
    ) throws IOException {
        return (Artifact) build(projectFile, compiled, false, Map.of(), true);
    }

    static Artifact buildProduction(
            final Path projectFile,
            final ProjectFiles.Build source
    ) throws IOException {
        return buildProduction(projectFile, source, true);
    }

    static Artifact buildProduction(final Path projectFile, final ProjectFiles.Build source,
                                    final boolean observation) throws IOException {
        if (!(source.result() instanceof CompileResult.Compiled compiled))
            throw new IllegalArgumentException("Cannot package a rejected project.");
        return (Artifact) build(projectFile, compiled, false, source.documents(), observation);
    }

    private static Published build(
            final Path projectFile,
            final CompileResult.Compiled compiled,
            final boolean development,
            final Map<String, String> projectDocuments,
            final boolean observation
    ) throws IOException {
        final Set<String> reserved = new java.util.HashSet<>(compiled.developmentResources().keySet());
        reserved.addAll(projectDocuments.keySet());
        final RuntimeFiles runtime = runtime(
                compiled.applicationDependencies(),
                development,
                reserved
        );
        final String applicationSource = development
                ? compiled.developmentApplicationSource()
                : compiled.productionApplicationSource();
        final String mainClass = development
                ? compiled.developmentLauncherClass()
                : compiled.applicationClass();
        final String sourceIdentity = ProjectFiles.identity(compiled);
        final String baseKey = buildKey(compiled, runtime.entries(), development);
        final MessageDigest publication = Sha256.newDigest();
        update(publication, baseKey);
        update(publication, observation ? "observation-on" : "observation-off");
        projectDocuments.forEach((name, value) -> { update(publication, name); update(publication, value); });
        final String key = projectDocuments.isEmpty() && observation ? baseKey
                : "sha256-" + Sha256.finishHex(publication);
        final Path root = projectFile.toAbsolutePath().normalize().getParent()
                .resolve(".railix").resolve("build");
        final Path destination = root.resolve(key);
        Files.createDirectories(root);
        final Path staging = root.resolve(".building-" + ProcessHandle.current().pid()
                + "-" + UUID.randomUUID() + "-" + key);
        final ArtifactLease stagingLease;
        try {
            synchronized (ApplicationBuilder.class) {
                try (FileChannel channel = FileChannel.open(
                        root.resolveSibling("staging.lock"),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE
                ); FileLock ignored = channel.lock()) {
                    cleanStaging(root);
                    Files.createDirectory(staging);
                    stagingLease = ArtifactLease.acquire(staging);
                }
            }
        } catch (final IOException | RuntimeException | Error failure) {
            cleanup(staging, failure);
            throw failure;
        }
        try (stagingLease) {
            final Path source = staging.resolve("src/dev/nanonative/railix/core/project/RailixApplication.java");
            final Path developmentSource = staging.resolve(
                    "src/dev/nanonative/railix/core/project/RailixDevelopmentApplication.java"
            );
            final Path classes = staging.resolve("classes");
            final Path jar = staging.resolve("application.jar");
            Files.createDirectories(source.getParent());
            Files.createDirectories(classes);
            Files.writeString(
                    source,
                    applicationSource,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW
            );
            final List<Path> sources = new ArrayList<>();
            sources.add(source);
            if (development) {
                Files.writeString(
                        developmentSource,
                        compiled.developmentLauncherSource(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW
                );
                sources.add(developmentSource);
            }
            compile(
                    sources,
                    source,
                    applicationSource,
                    compiled.applicationDependencies(),
                    classes,
                    runtime.locations(),
                    runtime.jdkModules()
            );
            final Map<String, RuntimeEntry> generated = inventoryDirectory(classes, name -> name.endsWith(".class"));
            if (!generated.containsKey(GENERATED_CLASS)) {
                throw new IOException("Generated application class is missing after Java compilation.");
            }
            final Map<String, RuntimeEntry> expected = new TreeMap<>(runtime.entries());
            addEntries(expected, generated, false);
            if (development) {
                for (final Map.Entry<String, String> resource : compiled.developmentResources().entrySet()) {
                    final byte[] bytes = resource.getValue().getBytes(StandardCharsets.UTF_8);
                    putEntry(expected, new MemoryRuntimeEntry(
                            resource.getKey(),
                            bytes.length,
                            Sha256.hexDigest(bytes),
                            bytes
                    ), false);
                }
            }
            for (final var resource : projectDocuments.entrySet()) {
                final byte[] bytes = resource.getValue().getBytes(StandardCharsets.UTF_8);
                putEntry(expected, new MemoryRuntimeEntry(resource.getKey(), bytes.length, Sha256.hexDigest(bytes), bytes), false);
            }
            final byte[] manifest = manifest(mainClass, sourceIdentity, observation);
            jar(jar, manifest, expected);
            if (!matches(jar, manifest, expected)) {
                throw new IOException("Generated application JAR does not match its expected content inventory.");
            }
            synchronized (ApplicationBuilder.class) {
                try (FileChannel channel = FileChannel.open(
                        root.resolveSibling("build.lock"),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE
                ); FileLock ignored = channel.lock();
                     FileChannel stagingChannel = FileChannel.open(
                             root.resolveSibling("staging.lock"),
                             StandardOpenOption.CREATE,
                             StandardOpenOption.WRITE
                     ); FileLock ignoredStaging = stagingChannel.lock()) {
                    if (Files.exists(destination)) {
                        boolean reusable = false;
                        try {
                            reusable = cached(destination, source, developmentSource, development, generated, jar);
                        } catch (final IOException ignoredCorruptCache) {
                            reusable = false;
                        }
                        if (reusable) {
                            deleteTree(destination.resolve("content"));
                            stagingLease.close();
                            deleteTree(staging);
                            return published(artifact(destination, true), development);
                        }
                        if (development && activeApplication(destination)) {
                            throw new IOException(
                                    "Development application artifact is in use and cannot be replaced."
                            );
                        }
                        deleteTree(destination);
                    }
                    stagingLease.close();
                    try {
                        Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
                    } catch (final AtomicMoveNotSupportedException exception) {
                        throw new IOException(
                                "Project filesystem does not support atomic application publication.",
                                exception
                        );
                    }
                    return published(artifact(destination, false), development);
                }
            }
        } catch (final IOException | RuntimeException | Error failure) {
            cleanup(staging, failure);
            throw failure;
        }
    }

    static String key(final CompileResult.Compiled compiled) throws IOException {
        return buildKey(compiled, runtime(
                compiled.applicationDependencies(),
                true,
                compiled.developmentResources().keySet()
        ).entries(), true);
    }

    static DevelopmentBuild reserve(final Artifact artifact) throws IOException {
        final Path root = artifact.directory().getParent();
        synchronized (ApplicationBuilder.class) {
            try (FileChannel channel = FileChannel.open(
                    root.resolveSibling("build.lock"),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE
            ); FileLock ignored = channel.lock()) {
                // Reserve the location even after artifact loss; launch owns startup diagnostics.
                // Existing artifacts must survive a failed launch.
                return new DevelopmentBuild(new Artifact(artifact.directory(), artifact.source(), artifact.classes(),
                        artifact.jar(), artifact.fingerprint(), artifact.builtAt(), true));
            }
        }
    }

    static void delete(final Artifact artifact) throws IOException {
        if (artifact == null) {
            return;
        }
        final Path root = artifact.directory().getParent();
        synchronized (ApplicationBuilder.class) {
            try (FileChannel channel = FileChannel.open(
                    root.resolveSibling("build.lock"),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE
            ); FileLock ignored = channel.lock()) {
                if (!activeApplication(artifact.directory())) {
                    deleteTree(artifact.directory());
                }
            }
        }
    }

    static void prune(final Artifact retained) throws IOException {
        final Path root = retained.directory().getParent();
        synchronized (ApplicationBuilder.class) {
            try (FileChannel channel = FileChannel.open(
                    root.resolveSibling("build.lock"),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE
            ); FileLock ignored = channel.lock(); var entries = Files.list(root)) {
                for (final Path entry : entries
                        .filter(ApplicationBuilder::artifactDirectory)
                        .filter(ApplicationBuilder::developmentArtifact)
                        .filter(path -> !path.equals(retained.directory()))
                        .toList()) {
                    if (!activeApplication(entry)) {
                        deleteTree(entry);
                    }
                }
            }
        }
    }

    /** Returns false while publication ownership defers cleanup; live sibling runtimes are retained. */
    static boolean cleanRuntime(final Artifact artifact) throws IOException {
        final Path build = artifact.directory().getParent();
        synchronized (ApplicationBuilder.class) {
            try (FileChannel channel = FileChannel.open(
                    build.resolveSibling("build.lock"),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE
            ); FileLock ignored = channel.lock()) {
                return cleanRuntimeLocked(artifact);
            } catch (final NoSuchFileException removedWorkspace) {
                // The workspace may disappear before the child process's exit callback runs.
                return true;
            }
        }
    }

    private static boolean cleanRuntimeLocked(final Artifact artifact) throws IOException {
        final Path root = artifact.directory().resolve(".railix-runtime");
        if (!Files.isDirectory(root)) {
            return true;
        }
        if (ArtifactLease.active(root)) {
            return false;
        }
        try (var entries = Files.list(root)) {
            for (final Path entry : entries.filter(Files::isDirectory).toList()) {
                if (!ArtifactLease.active(entry)) {
                    deleteTree(entry);
                }
            }
        } catch (final NoSuchFileException removedConcurrently) {
            return true;
        }
        try {
            Files.deleteIfExists(root);
        } catch (final DirectoryNotEmptyException ignoredSiblingProcess) {
            // Another generated application owns a sibling process directory.
        }
        return true;
    }

    private static Artifact artifact(final Path directory, final boolean reused) throws IOException {
        final Path jar = directory.resolve("application.jar");
        return new Artifact(
                directory,
                source(directory),
                directory.resolve("classes"),
                jar,
                "sha256:" + Sha256.hexDigest(jar),
                Files.getLastModifiedTime(jar).toInstant().toEpochMilli(),
                reused
        );
    }

    private static Published published(final Artifact artifact, final boolean development) throws IOException {
        return development ? new DevelopmentBuild(artifact) : artifact;
    }

    private static Path source(final Path directory) {
        return directory.resolve("src/dev/nanonative/railix/core/project/RailixApplication.java");
    }

    private static void compile(
            final List<Path> sources,
            final Path applicationSourceFile,
            final String applicationSource,
            final List<StepCatalog.Implementation> implementations,
            final Path classes,
            final List<Path> dependencies,
            final String jdkModules
    ) throws IOException {
        final var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IOException("Railix Creator requires the JDK Java compiler.");
        }
        final DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(
                diagnostics,
                java.util.Locale.ROOT,
                StandardCharsets.UTF_8
        )) {
            final List<String> options = new ArrayList<>(List.of(
                    "-proc:none",
                    "-encoding", "UTF-8",
                    "-g:none",
                    "--release", Integer.toString(Runtime.version().feature())
            ));
            if (!jdkModules.isEmpty()) {
                options.add("--add-modules");
                options.add(jdkModules);
            }
            options.addAll(List.of(
                    "-classpath", dependencies.stream()
                            .map(Path::toString)
                            .collect(java.util.stream.Collectors.joining(File.pathSeparator)),
                    "-d", classes.toString()
            ));
            final boolean success = Boolean.TRUE.equals(compiler.getTask(
                    null,
                    files,
                    diagnostics,
                    options,
                    null,
                    files.getJavaFileObjectsFromPaths(sources)
            ).call());
            if (!success) {
                throw compilationFailure(
                        diagnostics.getDiagnostics(),
                        applicationSourceFile,
                        applicationSource,
                        implementations
                );
            }
        }
    }

    private static GeneratedCompilationException compilationFailure(
            final List<javax.tools.Diagnostic<? extends JavaFileObject>> diagnostics,
            final Path applicationSourceFile,
            final String applicationSource,
            final List<StepCatalog.Implementation> implementations
    ) {
        final List<String> lines = applicationSource.lines().toList();
        for (final javax.tools.Diagnostic<? extends JavaFileObject> diagnostic : diagnostics) {
            if (diagnostic.getKind() != javax.tools.Diagnostic.Kind.ERROR
                    || diagnostic.getSource() == null
                    || diagnostic.getLineNumber() < 1
                    || diagnostic.getLineNumber() > lines.size()
                    || !sameFile(diagnostic.getSource(), applicationSourceFile)) {
                continue;
            }
            final String line = lines.get((int) diagnostic.getLineNumber() - 1);
            for (final StepCatalog.Implementation implementation : implementations) {
                if (line.contains("= " + implementation.className() + "::process;")
                        || List.of("pre", "post").stream().anyMatch(method ->
                        line.contains("List.of(" + implementation.className() + "::" + method + ")"))) {
                    return GeneratedCompilationException.invalidImplementation(implementation.className());
                }
            }
        }
        return GeneratedCompilationException.generatedSource();
    }

    private static String jdkModules(final List<StepCatalog.Implementation> implementations, final Set<String> runtimeEntries)
            throws IOException {
        final Set<String> modules = new java.util.TreeSet<>();
        final var available = java.lang.module.ModuleFinder.ofSystem();
        for (final StepCatalog.Implementation implementation : implementations) {
            for (final String module : implementation.jdkModules()) {
                if (available.find(module).isEmpty()) {
                    throw new GeneratedCompilationException("STEP_IMPLEMENTATION_INVALID",
                            "Step implementation " + implementation.className() + " requires unavailable JDK module: " + module + ".");
                }
                modules.add(module);
            }
            for (final String name : implementation.dynamicClasses()) {
                final String entry = name.replace('.', '/') + ".class";
                if (runtimeEntries.contains(entry)) continue;
                if (!RuntimeClassDependencies.systemClass(entry, available.findAll())) throw new GeneratedCompilationException("STEP_DEPENDENCY_OWNER_MISSING",
                        "STEP_DEPENDENCY_OWNER_MISSING: Declared dynamic class is absent from the application: " + name + ".");
            }
        }
        return String.join(",", modules);
    }

    private static boolean sameFile(final JavaFileObject source, final Path expected) {
        try {
            return Path.of(source.toUri()).toAbsolutePath().normalize()
                    .equals(expected.toAbsolutePath().normalize());
        } catch (final RuntimeException unsupportedSource) {
            return false;
        }
    }

    private static RuntimeFiles runtime(
            final List<StepCatalog.Implementation> dependencies,
            final boolean development,
            final Set<String> reservedResources
    ) throws IOException {
        for (final StepCatalog.Implementation dependency : dependencies) {
            if (dependency.platformOwned()) throw new IOException("STEP_DEPENDENCY_OWNER_MISSING: Package the Step as a provider bundle before building: " + dependency.className() + ".");
        }
        final Map<String, RuntimeEntry> entries = new TreeMap<>();
        final LinkedHashSet<Path> locations = new LinkedHashSet<>();
        locations.add(location(WorkflowRuntime.class));
        final Map<String, StepCatalog.Artifact> artifacts = new TreeMap<>();
        final Set<Path> providerArtifacts = new java.util.HashSet<>();
        for (final StepCatalog.Implementation dependency : dependencies) {
            dependency.artifacts().forEach(artifact -> artifacts.putIfAbsent(artifact.digest(), artifact));
            providerArtifacts.add(dependency.artifacts().getFirst().path());
        }
        verifyClosure(artifacts.values());
        for (final StepCatalog.Artifact artifact : artifacts.values()) {
            if (!("sha256:" + Sha256.hexDigest(artifact.path())).equals(artifact.digest())) {
                throw new IOException("DEPENDENCY_ARTIFACT_DIGEST_MISMATCH: Locked artifact changed before build: "
                        + artifact.digest() + ".");
            }
            locations.add(artifact.path());
        }
        if (development) {
            locations.add(location(DevelopmentRuntime.class));
        }
        final Set<String> providerClasses = new java.util.HashSet<>();
        final Set<String> libraryClasses = new java.util.HashSet<>();
        for (final Path path : locations) {
            if (artifacts.values().stream().anyMatch(artifact -> artifact.path().equals(path))) {
                if (providerArtifacts.contains(path)) {
                    try (var jar = new JarFile(path.toFile())) {
                        jar.stream().map(JarEntry::getName).filter(name -> name.endsWith(".class"))
                                .forEach(providerClasses::add);
                    }
                } else {
                    try (var jar = new JarFile(path.toFile())) {
                        jar.stream().map(JarEntry::getName).filter(name -> name.endsWith(".class"))
                                .forEach(libraryClasses::add);
                    }
                }
                addDependencyJar(entries, path, reservedResources);
                continue;
            }
            if (Files.isDirectory(path)) {
                addEntries(entries, inventoryDirectory(path, name -> runtimeEntry(name, development)), false);
            } else if (Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar")) {
                addEntries(entries, inventoryJar(path, name -> runtimeEntry(name, development)), false);
            } else {
                throw new IOException("Railix runtime location is not a classes directory or JAR: " + path + ".");
            }
        }
        require(entries, "dev/nanonative/railix/core/project/RuntimeApplication.class");
        require(entries, "dev/nanonative/railix/core/project/WorkflowRuntime.class");
        if (development) {
            require(entries, DEVELOPMENT_PREFIX + "DevelopmentRuntime.class");
        }
        for (final StepCatalog.Implementation dependency : dependencies) {
            require(entries, dependency.classEntry());
        }
        final String modules = jdkModules(dependencies, entries.keySet());
        providerClasses.removeAll(libraryClasses);
        final Set<String> retained = new java.util.HashSet<>();
        final Set<String> completeClasses = new java.util.HashSet<>();
        final Map<String, Set<RuntimeClassDependencies.Method>> methods = new java.util.HashMap<>();
        final String descriptor = "(Ldev/nanonative/railix/core/step/StepInput;Ldev/nanonative/railix/core/step/StepInput;)Ldev/nanonative/railix/core/step/StepResult;";
        final var pending = new java.util.ArrayDeque<String>();
        dependencies.forEach(owner -> {
            pending.add(owner.classEntry());
            final var entrypoints = methods.computeIfAbsent(owner.classEntry(), ignored -> new java.util.HashSet<>());
            List.of("process", "pre", "post").forEach(name -> entrypoints.add(
                    new RuntimeClassDependencies.Method(owner.classEntry(), name, descriptor)));
            owner.dynamicClasses().stream().map(name -> name.replace('.', '/') + ".class")
                    .filter(entries::containsKey).forEach(name -> { completeClasses.add(name); pending.add(name); });
        });
        final var system = java.lang.module.ModuleFinder.ofSystem().findAll();
        for (final var entry : entries.values()) {
            if (entry.name().endsWith(".class") && !providerClasses.contains(entry.name())) pending.add(entry.name());
            if (entry.name().startsWith("META-INF/services/")) {
                final String serviceType = entry.name().substring("META-INF/services/".length()).replace('.', '/') + ".class";
                if (!entries.containsKey(serviceType) && !RuntimeClassDependencies.systemClass(serviceType, system)) continue;
                final var service = mergeServices(entry, entry);
                pending.add(serviceType);
                new String(service.bytes(), StandardCharsets.UTF_8).lines().map(name -> name.replace('.', '/') + ".class")
                        .forEach(name -> { completeClasses.add(name); pending.add(name); });
            }
        }
        final Set<String> jdkClasses = new java.util.HashSet<>();
        final Map<String, byte[]> originals = new java.util.HashMap<>();
        final Map<String, java.lang.classfile.ClassModel> models = new java.util.HashMap<>();
        final Map<String, Map<RuntimeClassDependencies.Method, Set<RuntimeClassDependencies.Method>>> calls = new java.util.HashMap<>();
        final Map<String, RuntimeEntry> sources = entries.entrySet().stream().filter(entry -> entry.getKey().endsWith(".class"))
                .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
        final var classFile = java.lang.classfile.ClassFile.of(java.lang.classfile.ClassFile.ConstantPoolSharingOption.NEW_POOL,
                java.lang.classfile.ClassFile.ClassHierarchyResolverOption.of(java.lang.classfile.ClassHierarchyResolver.ofResourceParsing(type -> {
                    final String symbol = type.descriptorString();
                    final String name = symbol.substring(1, symbol.length() - 1) + ".class";
                    try {
                        final RuntimeEntry source = sources.get(name);
                        if (source != null) {
                            final byte[] bytes = source.readBounded(Math.toIntExact(StepCatalog.MAX_ARTIFACT_ENTRY_BYTES));
                            if (bytes.length != source.size() || !Sha256.hexDigest(bytes).equals(source.digest()))
                                throw new IOException("DEPENDENCY_ARTIFACT_DIGEST_MISMATCH: Hierarchy class changed during build: " + name + ".");
                            return new java.io.ByteArrayInputStream(bytes);
                        }
                        final int slash = name.lastIndexOf('/');
                        final String packageName = slash < 0 ? "" : name.substring(0, slash).replace('/', '.');
                        for (final var module : system) {
                            if (!module.descriptor().packages().contains(packageName)) continue;
                            try (var reader = module.open()) {
                                final var resource = reader.open(name);
                                if (resource.isPresent()) {
                                    try (var input = resource.orElseThrow()) { return new java.io.ByteArrayInputStream(input.readAllBytes()); }
                                }
                            }
                        }
                        throw new IllegalArgumentException("Class hierarchy is unavailable in the application: " + name + ".");
                    } catch (final IOException failure) { throw new java.io.UncheckedIOException(failure); }
                }).cached()));
        final Set<String> queued = new java.util.HashSet<>(pending);
        while (!pending.isEmpty()) {
            final String name = pending.removeFirst();
            queued.remove(name);
            retained.add(name);
            final var entry = entries.get(name);
            if (entry == null) {
                if (RuntimeClassDependencies.systemClass(name, system)) continue;
                throw new GeneratedCompilationException("STEP_DEPENDENCY_OWNER_MISSING",
                        "STEP_DEPENDENCY_OWNER_MISSING: Registered service class is absent from the application: " + name + ".");
            }
            final boolean provider = providerClasses.contains(name);
            final boolean known = originals.containsKey(name);
            final byte[] bytes = known ? originals.get(name)
                    : entry.readBounded(Math.toIntExact(StepCatalog.MAX_ARTIFACT_ENTRY_BYTES));
            if (!known && (bytes.length != entry.size() || !Sha256.hexDigest(bytes).equals(entry.digest())))
                throw new IOException("DEPENDENCY_ARTIFACT_DIGEST_MISMATCH: Runtime class changed during build: " + name + ".");
            final RuntimeClassDependencies.Selection selected;
            final java.util.Optional<String> parent;
            try {
                final var model = models.containsKey(name) ? models.get(name) : java.lang.classfile.ClassFile.of().parse(bytes);
                if (!(model.thisClass().asInternalName() + ".class").equals(name))
                    throw new GeneratedCompilationException("STEP_RUNTIME_CLASS_INVALID", "Class entry does not match its declared name: " + name + ".");
                if (provider) { originals.putIfAbsent(name, bytes); models.putIfAbsent(name, model); }
                if (provider && !calls.containsKey(name)) calls.put(name, RuntimeClassDependencies.calls(model));
                selected = RuntimeClassDependencies.select(model, bytes,
                        methods.getOrDefault(name, Set.of()), !provider || completeClasses.contains(name), provider,
                        calls.getOrDefault(name, Map.of()), classFile);
                parent = model.superclass().map(type -> type.asInternalName() + ".class");
            } catch (final java.io.UncheckedIOException failure) {
                throw failure.getCause();
            } catch (final IllegalArgumentException invalid) {
                throw GeneratedCompilationException.invalidRuntimeClass(name, invalid);
            }
            if (providerClasses.contains(name)) entries.put(name, new MemoryRuntimeEntry(name, selected.bytes().length,
                    Sha256.hexDigest(selected.bytes()), selected.bytes()));
            for (final var method : selected.methods()) {
                if (!providerClasses.contains(method.owner())) continue;
                if (methods.computeIfAbsent(method.owner(), ignored -> new java.util.HashSet<>()).add(method)
                        && !method.owner().equals(name) && queued.add(method.owner())) pending.add(method.owner());
            }
            if (parent.isPresent()) {
                final String superclass = parent.orElseThrow();
                if (providerClasses.contains(superclass)) {
                    for (final var method : methods.getOrDefault(name, Set.of())) {
                        if (methods.computeIfAbsent(superclass, ignored -> new java.util.HashSet<>()).add(
                                new RuntimeClassDependencies.Method(superclass, method.name(), method.descriptor()))
                                && queued.add(superclass)) pending.add(superclass);
                    }
                }
            }
            for (final String target : selected.classes()) {
                if (entries.containsKey(target)) {
                    if (!retained.contains(target) && queued.add(target)) pending.add(target);
                } else if (providerClasses.contains(name) && !jdkClasses.contains(target)) {
                    if (!RuntimeClassDependencies.systemClass(target, system)) throw new GeneratedCompilationException("STEP_DEPENDENCY_OWNER_MISSING",
                            "STEP_DEPENDENCY_OWNER_MISSING: Runtime class " + name + " references unavailable class " + target
                                    + ". Runtime code and initialization cannot depend on authoring APIs.");
                    jdkClasses.add(target);
                }
            }
        }
        entries.keySet().removeIf(name -> providerClasses.contains(name) && !retained.contains(name));
        final Set<String> available = new java.util.HashSet<>(entries.keySet());
        available.addAll(jdkClasses);
        for (final String name : providerClasses) {
            final var model = models.get(name);
            if (model == null) continue;
            try {
                for (final var attribute : model.attributes()) {
                    if (attribute instanceof java.lang.classfile.attribute.InnerClassesAttribute nested) {
                        for (final var info : nested.classes()) {
                            final var types = new ArrayList<java.lang.classfile.constantpool.ClassEntry>();
                            types.add(info.innerClass());
                            info.outerClass().ifPresent(types::add);
                            for (final var type : types) {
                                final String target = type.asInternalName() + ".class";
                                if (!available.contains(target) && RuntimeClassDependencies.systemClass(target, system)) available.add(target);
                            }
                        }
                    }
                }
            } catch (final java.io.UncheckedIOException failure) {
                throw failure.getCause();
            } catch (final IllegalArgumentException invalid) {
                throw GeneratedCompilationException.invalidRuntimeClass(name, invalid);
            }
        }
        for (final String name : providerClasses) {
            final var entry = entries.get(name);
            if (entry == null) continue;
            final byte[] bytes = RuntimeClassDependencies.finish(name, entry.readBounded(Math.toIntExact(StepCatalog.MAX_ARTIFACT_ENTRY_BYTES)), available, classFile);
            entries.put(name, new MemoryRuntimeEntry(name, bytes.length, Sha256.hexDigest(bytes), bytes));
        }
        return new RuntimeFiles(entries, List.copyOf(locations), modules);
    }

    private static Path location(final Class<?> type) throws IOException {
        final var source = type.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            throw new IOException("Railix runtime location is unavailable for " + type.getName() + ".");
        }
        try {
            return Path.of(source.getLocation().toURI()).toAbsolutePath().normalize();
        } catch (final URISyntaxException | IllegalArgumentException exception) {
            throw new IOException("Railix runtime location is invalid for " + type.getName() + ".", exception);
        }
    }

    private static boolean runtimeEntry(
            final String name,
            final boolean development
    ) {
        if (!name.endsWith(".class") || "module-info.class".equals(name)) {
            return false;
        }
        if (RUNTIME_PREFIXES.stream().anyMatch(name::startsWith)) {
            return true;
        }
        return development && name.startsWith(DEVELOPMENT_PREFIX);
    }

    private static void require(final Map<String, RuntimeEntry> entries, final String name) throws IOException {
        if (!entries.containsKey(name)) {
            throw new IOException("Application runtime class is missing from Creator: " + name + ".");
        }
    }

    private static Map<String, RuntimeEntry> inventoryDirectory(
            final Path root,
            final java.util.function.Predicate<String> include
    ) throws IOException {
        final Map<String, RuntimeEntry> entries = new TreeMap<>();
        try (var files = Files.walk(root)) {
            for (final Path file : files.filter(Files::isRegularFile).toList()) {
                final String name = root.relativize(file).toString().replace(File.separatorChar, '/');
                if (include.test(name)) {
                    entries.put(name, new FileRuntimeEntry(name, Files.size(file), Sha256.hexDigest(file), file));
                }
            }
        }
        return entries;
    }

    private static Map<String, RuntimeEntry> inventoryJar(
            final Path source,
            final java.util.function.Predicate<String> include
    ) throws IOException {
        final Map<String, RuntimeEntry> entries = new TreeMap<>();
        try (JarFile jar = new JarFile(source.toFile())) {
            final var jarEntries = jar.entries();
            while (jarEntries.hasMoreElements()) {
                final JarEntry entry = jarEntries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                if (include.test(entry.getName())) {
                    try (var input = jar.getInputStream(entry)) {
                        final Fingerprint fingerprint = fingerprint(
                                input,
                                StepCatalog.MAX_ARTIFACT_ENTRY_BYTES,
                                "Application runtime entry exceeds the artifact entry limit: " + entry.getName() + "."
                        );
                        entries.put(entry.getName(), new JarRuntimeEntry(
                                entry.getName(), fingerprint.size(), fingerprint.digest(), source, entry.getName()
                        ));
                    }
                }
            }
        }
        return entries;
    }

    private static void addDependencyJar(
            final Map<String, RuntimeEntry> entries,
            final Path source,
            final Set<String> reservedResources
    ) throws IOException {
        try (JarFile jar = new JarFile(source.toFile(), true)) {
            final var jarEntries = jar.entries();
            while (jarEntries.hasMoreElements()) {
                final JarEntry entry = jarEntries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                final String name = entry.getName();
                StepCatalog.artifactEntry(name);
                if (reservedResources.contains(name) || name.startsWith(ProjectFiles.ARCHIVE_ROOT))
                    throw new IOException("DEPENDENCY_RESERVED_RESOURCE_FORBIDDEN: Dependency JAR cannot provide generated application resource: " + name + ".");
                if (entry.getSize() < 0 || entry.getSize() > StepCatalog.MAX_ARTIFACT_ENTRY_BYTES) {
                    throw new IOException("DEPENDENCY_ARTIFACT_ENTRY_SIZE_LIMIT: Dependency entry exceeds "
                            + StepCatalog.MAX_ARTIFACT_ENTRY_BYTES + " uncompressed bytes: " + name + ".");
                }
                try (var input = jar.getInputStream(entry)) {
                    final Fingerprint fingerprint = fingerprint(
                            input,
                            StepCatalog.MAX_ARTIFACT_ENTRY_BYTES,
                            "DEPENDENCY_ARTIFACT_ENTRY_SIZE_LIMIT: Dependency entry exceeds "
                                    + StepCatalog.MAX_ARTIFACT_ENTRY_BYTES + " uncompressed bytes: " + name + "."
                    );
                    if (fingerprint.size() != entry.getSize()) {
                        throw new IOException("DEPENDENCY_ARTIFACT_INVALID_JAR: Dependency entry size is invalid: "
                                + name + ".");
                    }
                    if (ignoredDependencyEntry(name)) {
                        continue;
                    }
                    putEntry(entries, new JarRuntimeEntry(
                            name, fingerprint.size(), fingerprint.digest(), source, name
                    ), true);
                }
            }
        }
    }

    private static void verifyClosure(final java.util.Collection<StepCatalog.Artifact> artifacts) throws IOException {
        int entries = 0;
        long bytes = 0;
        for (final StepCatalog.Artifact artifact : artifacts) {
            if (artifact.entryCount() > StepCatalog.MAX_CLOSURE_ENTRIES - entries) {
                throw new IOException("DEPENDENCY_CLOSURE_ENTRY_LIMIT: Reachable dependency closure contains more "
                        + "than " + StepCatalog.MAX_CLOSURE_ENTRIES + " files.");
            }
            if (artifact.uncompressedBytes() > StepCatalog.MAX_CLOSURE_UNCOMPRESSED_BYTES - bytes) {
                throw new IOException("DEPENDENCY_CLOSURE_UNCOMPRESSED_SIZE_LIMIT: Reachable dependency closure "
                        + "exceeds " + StepCatalog.MAX_CLOSURE_UNCOMPRESSED_BYTES + " uncompressed bytes.");
            }
            entries += artifact.entryCount();
            bytes += artifact.uncompressedBytes();
        }
    }

    private static boolean ignoredDependencyEntry(final String name) {
        return JarFile.MANIFEST_NAME.equalsIgnoreCase(name)
                || "META-INF/INDEX.LIST".equalsIgnoreCase(name)
                || "META-INF/railix/steps.json".equals(name);
    }

    private static MemoryRuntimeEntry mergeServices(
            final RuntimeEntry first,
            final RuntimeEntry second
    ) throws IOException {
        final String name = first.name();
        final Set<String> providers = new java.util.TreeSet<>();
        for (final RuntimeEntry entry : first == second ? List.of(first) : List.of(first, second)) {
            final byte[] bytes = entry.readBounded(SERVICE_DESCRIPTOR_LIMIT);
            final String source;
            try {
                source = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            } catch (final CharacterCodingException exception) {
                throw new IOException("DEPENDENCY_ENTRY_CONFLICT: Service descriptor is not UTF-8: " + name + ".",
                        exception);
            }
            source.lines().map(line -> line.replaceFirst("#.*$", "").strip())
                    .filter(line -> !line.isEmpty()).forEach(providers::add);
        }
        final byte[] merged = (String.join("\n", providers) + "\n").getBytes(StandardCharsets.UTF_8);
        if (merged.length > SERVICE_DESCRIPTOR_LIMIT) {
            throw new IOException("DEPENDENCY_ENTRY_CONFLICT: Merged service descriptor exceeds "
                    + SERVICE_DESCRIPTOR_LIMIT + " bytes: " + name + ".");
        }
        return new MemoryRuntimeEntry(name, merged.length, Sha256.hexDigest(merged), merged);
    }

    private static void addEntries(
            final Map<String, RuntimeEntry> entries,
            final Map<String, RuntimeEntry> additions,
            final boolean dependency
    ) throws IOException {
        for (final RuntimeEntry entry : additions.values()) {
            putEntry(entries, entry, dependency);
        }
    }

    private static void putEntry(
            final Map<String, RuntimeEntry> entries,
            final RuntimeEntry entry,
            final boolean dependency
    ) throws IOException {
        final RuntimeEntry previous = entries.putIfAbsent(entry.name(), entry);
        if (previous == null || previous.size() == entry.size() && previous.digest().equals(entry.digest())) {
            return;
        }
        if (dependency && entry.name().startsWith("META-INF/services/")) {
            entries.put(entry.name(), mergeServices(previous, entry));
            return;
        }
        final String prefix = dependency ? "DEPENDENCY_ENTRY_CONFLICT: " : "Application runtime conflict: ";
        throw new IOException(prefix + "Entry has different bytes: " + entry.name() + ".");
    }

    private static String buildKey(
            final CompileResult.Compiled compiled,
            final Map<String, RuntimeEntry> runtime,
            final boolean development
    ) {
        final MessageDigest digest = Sha256.newDigest();
        update(digest, "railix-application-v5");
        update(digest, ProjectFiles.identity(compiled));
        update(digest, development ? "development" : "production");
        update(digest, Runtime.version().toString());
        update(digest, System.getProperty("java.vendor"));
        update(digest, compiled.applicationClass());
        update(digest, development
                ? compiled.developmentApplicationSource()
                : compiled.productionApplicationSource());
        if (development) {
            update(digest, compiled.developmentLauncherClass());
            update(digest, compiled.developmentLauncherSource());
            compiled.developmentResources().forEach((name, value) -> {
                update(digest, name);
                update(digest, value);
            });
        }
        runtime.forEach((name, entry) -> {
            update(digest, name);
            update(digest, Long.toString(entry.size()));
            update(digest, entry.digest());
        });
        return "sha256-" + Sha256.finishHex(digest);
    }

    private static void jar(
            final Path target,
            final byte[] manifest,
            final Map<String, RuntimeEntry> content
    ) throws IOException {
        try (OutputStream file = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW);
             JarOutputStream jar = new JarOutputStream(file)) {
            write(jar, JarFile.MANIFEST_NAME, manifest);
            for (final RuntimeEntry entry : content.values()) {
                write(jar, entry);
            }
        }
    }

    private static byte[] manifest(final String mainClass, final String sourceIdentity, final boolean observation) throws IOException {
        final var manifest = new java.util.jar.Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Main-Class", mainClass);
        manifest.getMainAttributes().putValue("Railix-Project-Id", sourceIdentity);
        manifest.getMainAttributes().putValue("Railix-Observation", Boolean.toString(observation));
        final var output = new java.io.ByteArrayOutputStream();
        manifest.write(output);
        return output.toByteArray();
    }

    private static void write(
            final JarOutputStream jar,
            final String name,
            final byte[] bytes
    ) throws IOException {
        final JarEntry entry = new JarEntry(name);
        entry.setTime(0L);
        jar.putNextEntry(entry);
        jar.write(bytes);
        jar.closeEntry();
    }

    private static void write(
            final JarOutputStream jar,
            final RuntimeEntry source
    ) throws IOException {
        final JarEntry entry = new JarEntry(source.name());
        entry.setTime(0L);
        jar.putNextEntry(entry);
        source.writeTo(jar);
        jar.closeEntry();
    }

    private static boolean cached(
            final Path destination,
            final Path source,
            final Path developmentSource,
            final boolean development,
            final Map<String, RuntimeEntry> generated,
            final Path expectedJar
    ) throws IOException {
        if (!Files.isDirectory(destination)
                || Files.mismatch(source(destination), source) != -1
                || development && Files.mismatch(destination.resolve(
                "src/dev/nanonative/railix/core/project/RailixDevelopmentApplication.java"
        ), developmentSource) != -1
                || !sameInventory(
                inventoryDirectory(destination.resolve("classes"), name -> name.endsWith(".class")), generated
        )) {
            return false;
        }
        return Files.mismatch(destination.resolve("application.jar"), expectedJar) == -1;
    }

    private static boolean sameInventory(
            final Map<String, RuntimeEntry> first,
            final Map<String, RuntimeEntry> second
    ) {
        if (!first.keySet().equals(second.keySet())) {
            return false;
        }
        return first.entrySet().stream().allMatch(item -> {
            final RuntimeEntry other = second.get(item.getKey());
            return item.getValue().size() == other.size() && item.getValue().digest().equals(other.digest());
        });
    }

    private static boolean matches(
            final Path jar,
            final byte[] manifest,
            final Map<String, RuntimeEntry> content
    ) throws IOException {
        if (!Files.isRegularFile(jar)) {
            return false;
        }
        final Map<String, RuntimeEntry> expected = new TreeMap<>(content);
        expected.put(JarFile.MANIFEST_NAME, new MemoryRuntimeEntry(
                JarFile.MANIFEST_NAME, manifest.length, Sha256.hexDigest(manifest), manifest
        ));
        try (JarFile application = new JarFile(jar.toFile(), true)) {
            final var entries = application.entries();
            while (entries.hasMoreElements()) {
                final JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    return false;
                }
                final RuntimeEntry wanted = expected.remove(entry.getName());
                if (wanted == null || entry.getSize() != wanted.size()) {
                    return false;
                }
                try (InputStream input = application.getInputStream(entry)) {
                    final Fingerprint actual = fingerprint(
                            input,
                            wanted.size(),
                            "Cached application entry exceeds its expected size: " + entry.getName() + "."
                    );
                    if (actual.size() != wanted.size() || !actual.digest().equals(wanted.digest())) {
                        return false;
                    }
                }
            }
        }
        return expected.isEmpty();
    }

    private static Fingerprint fingerprint(
            final InputStream input,
            final long limit,
            final String limitMessage
    ) throws IOException {
        final MessageDigest digest = Sha256.newDigest();
        long size = 0;
        final byte[] buffer = new byte[8192];
        for (int read = input.read(buffer); read >= 0; read = input.read(buffer)) {
            if (read == 0) {
                continue;
            }
            digest.update(buffer, 0, read);
            size = Math.addExact(size, read);
            if (size > limit) {
                throw new IOException(limitMessage);
            }
        }
        return new Fingerprint(size, Sha256.finishHex(digest));
    }

    private static byte[] bounded(final InputStream input, final long size, final int limit) throws IOException {
        if (size > limit) {
            throw new IOException("DEPENDENCY_ENTRY_CONFLICT: Service descriptor exceeds " + limit + " bytes.");
        }
        final byte[] bytes = input.readNBytes(limit + 1);
        if (bytes.length > limit || input.read() != -1) {
            throw new IOException("DEPENDENCY_ENTRY_CONFLICT: Service descriptor exceeds " + limit + " bytes.");
        }
        return bytes;
    }

    private static void update(final MessageDigest digest, final String value) {
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    private static void cleanup(final Path directory, final Throwable failure) {
        try {
            deleteTree(directory);
        } catch (final IOException cleanup) {
            failure.addSuppressed(cleanup);
        }
    }

    private static void cleanStaging(final Path root) throws IOException {
        try (var entries = Files.list(root)) {
            for (final Path entry : entries
                    .filter(path -> path.getFileName().toString().startsWith(".building-"))
                    .toList()) {
                if (!ArtifactLease.active(entry)) {
                    deleteTree(entry);
                }
            }
        }
    }

    private static boolean artifactDirectory(final Path directory) {
        final String name = directory.getFileName().toString();
        return Files.isDirectory(directory)
                && name.length() == "sha256-".length() + 64
                && name.startsWith("sha256-")
                && name.substring("sha256-".length()).chars().allMatch(character ->
                (character >= '0' && character <= '9') || (character >= 'a' && character <= 'f')
        );
    }

    private static boolean developmentArtifact(final Path directory) {
        return Files.isRegularFile(directory.resolve(
                "src/dev/nanonative/railix/core/project/RailixDevelopmentApplication.java"
        ));
    }

    private static boolean activeApplication(final Path directory) throws IOException {
        final Path runtime = directory.resolve(".railix-runtime");
        if (!Files.isDirectory(runtime)) {
            return false;
        }
        if (ArtifactLease.active(runtime)) {
            return true;
        }
        try (var processes = Files.list(runtime)) {
            for (final Path process : processes.filter(Files::isDirectory).toList()) {
                if (ArtifactLease.active(process)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void deleteTree(final Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var files = Files.walk(directory)) {
            for (final Path path : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    static final class GeneratedCompilationException extends IOException {
        private final String code;

        private GeneratedCompilationException(final String code, final String message) {
            super(message);
            this.code = code;
        }

        static GeneratedCompilationException invalidImplementation(final String implementation) {
            return new GeneratedCompilationException(
                    "STEP_IMPLEMENTATION_INVALID",
                    "Generated application cannot invoke Step implementation: " + implementation + "."
            );
        }

        static GeneratedCompilationException generatedSource() {
            return new GeneratedCompilationException(
                    "GENERATED_APPLICATION_COMPILATION_FAILED",
                    "Generated application source did not compile."
            );
        }

        static GeneratedCompilationException invalidRuntimeClass(final String name, final Throwable failure) {
            final String detail = failure.getMessage();
            final var result = new GeneratedCompilationException("STEP_RUNTIME_CLASS_INVALID", "Cannot package runtime class: " + name + "."
                    + (detail == null || detail.isBlank() ? "" : " " + detail));
            result.initCause(failure);
            return result;
        }

        String code() {
            return code;
        }
    }

    private record RuntimeFiles(Map<String, RuntimeEntry> entries, List<Path> locations, String jdkModules) {
        private RuntimeFiles {
            entries = Collections.unmodifiableMap(new TreeMap<>(entries));
            locations = List.copyOf(locations);
        }
    }

    private sealed interface RuntimeEntry permits FileRuntimeEntry, JarRuntimeEntry, MemoryRuntimeEntry {
        String name();

        long size();

        String digest();

        void writeTo(OutputStream target) throws IOException;

        byte[] readBounded(int limit) throws IOException;
    }

    private record FileRuntimeEntry(
            String name,
            long size,
            String digest,
            Path source
    ) implements RuntimeEntry {
        @Override
        public void writeTo(final OutputStream target) throws IOException {
            try (InputStream input = Files.newInputStream(source)) {
                input.transferTo(target);
            }
        }

        @Override
        public byte[] readBounded(final int limit) throws IOException {
            try (InputStream input = Files.newInputStream(source)) {
                return bounded(input, size, limit);
            }
        }
    }

    private record JarRuntimeEntry(
            String name,
            long size,
            String digest,
            Path source,
            String sourceEntry
    ) implements RuntimeEntry {
        @Override
        public void writeTo(final OutputStream target) throws IOException {
            try (JarFile jar = new JarFile(source.toFile(), true)) {
                final JarEntry entry = jar.getJarEntry(sourceEntry);
                if (entry == null || entry.isDirectory()) {
                    throw new IOException("DEPENDENCY_ARTIFACT_DIGEST_MISMATCH: Entry disappeared while building: "
                            + sourceEntry + ".");
                }
                try (InputStream input = jar.getInputStream(entry)) {
                    input.transferTo(target);
                }
            }
        }

        @Override
        public byte[] readBounded(final int limit) throws IOException {
            try (JarFile jar = new JarFile(source.toFile(), true)) {
                final JarEntry entry = jar.getJarEntry(sourceEntry);
                if (entry == null || entry.isDirectory()) {
                    throw new IOException("DEPENDENCY_ARTIFACT_DIGEST_MISMATCH: Entry disappeared while building: "
                            + sourceEntry + ".");
                }
                try (InputStream input = jar.getInputStream(entry)) {
                    return bounded(input, size, limit);
                }
            }
        }
    }

    private record MemoryRuntimeEntry(
            String name,
            long size,
            String digest,
            byte[] bytes
    ) implements RuntimeEntry {
        private MemoryRuntimeEntry {
            bytes = bytes.clone();
        }

        @Override
        public void writeTo(final OutputStream target) throws IOException {
            target.write(bytes);
        }

        @Override
        public byte[] readBounded(final int limit) throws IOException {
            if (bytes.length > limit) {
                throw new IOException("DEPENDENCY_ENTRY_CONFLICT: Service descriptor exceeds " + limit + " bytes.");
            }
            return bytes.clone();
        }
    }

    private record Fingerprint(long size, String digest) {
    }

    private sealed interface Published permits Artifact, DevelopmentBuild {
    }

    record Artifact(
            Path directory,
            Path source,
            Path classes,
            Path jar,
            String fingerprint,
            long builtAt,
            boolean reused
    ) implements Published {
        Artifact {
            directory = directory.toAbsolutePath().normalize();
            source = source.toAbsolutePath().normalize();
            classes = classes.toAbsolutePath().normalize();
            jar = jar.toAbsolutePath().normalize();
        }
    }

    static final class DevelopmentBuild implements Published, AutoCloseable {
        private final Artifact artifact;
        private final ArtifactLease publication;

        private DevelopmentBuild(final Artifact artifact) throws IOException {
            this.artifact = artifact;
            cleanRuntimeLocked(artifact);
            publication = ArtifactLease.acquire(artifact.directory().resolve(".railix-runtime"));
        }

        Artifact artifact() {
            return artifact;
        }

        Path jar() {
            return artifact.jar();
        }

        @Override
        public void close() throws IOException {
            publication.close();
        }
    }
}
