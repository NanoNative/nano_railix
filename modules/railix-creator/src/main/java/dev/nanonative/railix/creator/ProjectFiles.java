package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.project.Diagnostic;
import dev.nanonative.railix.core.project.CompileResult;
import dev.nanonative.railix.core.project.ProjectCompiler;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** One disk owner for functional, Example and presentation documents and their recovery. */
final class ProjectFiles {
    static final String EMPTY_EXAMPLES = "{\"format\":1,\"examples\":[]}";
    static final Set<String> FILES = Set.of("project", "creator", "examples");
    static final int MAX_DOCUMENT_BYTES = 64 * 1024 * 1024;
    static final String ARCHIVE_ROOT = "META-INF/railix/project/";
    static final List<String> PROJECT_DOCUMENTS = List.of("railix.project.json", "railix.examples.json",
            "railix.creator.json", "railix.dependencies.lock.json", CustomSteps.FILE);

    private ProjectFiles() { }

    /** Uses the same installed bundle resolver for local and restored project documents. */
    static StepCatalog catalog(final Path project, final Path home) throws IOException {
        final Path lock = project.resolveSibling("railix.dependencies.lock.json");
        final StepCatalog defaults = StepCatalog.embedded(ProjectFiles.class.getClassLoader(), home.resolve("artifacts"));
        final StepCatalog base = Files.exists(lock) ? defaults.install(lock, home.resolve("artifacts")) : defaults;
        return CustomSteps.compileAndInstall(base, project, CustomSteps.read(project));
    }

    /** Identifies the compiled source and locked implementations, not Examples or presentation. */
    static String identity(final CompileResult.Compiled compiled) {
        try {
            final var hash = MessageDigest.getInstance("SHA-256");
            hash.update(split(compiled.source(), EMPTY_EXAMPLES).project().getBytes(StandardCharsets.UTF_8));
            hash.update((byte) 0);
            compiled.applicationDependencies().stream().sorted(java.util.Comparator.comparing(StepCatalog.Implementation::className))
                    .forEach(implementation -> {
                        hash.update((byte) 0);
                        hash.update(implementation.className().getBytes(StandardCharsets.UTF_8));
                        hash.update((byte) 0);
                        hash.update(implementation.contractDigest().getBytes(StandardCharsets.UTF_8));
                        implementation.artifacts().stream().map(StepCatalog.Artifact::digest).sorted().forEach(digest -> {
                            hash.update((byte) 0); hash.update(digest.getBytes(StandardCharsets.UTF_8));
                        });
                    });
            return "sha256-" + HexFormat.of().formatHex(hash.digest());
        } catch (final NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is required by the Java platform.", unavailable);
        } catch (final IOException invalidCompiledSource) {
            throw new IllegalArgumentException("Compiled project source is invalid.", invalidCompiledSource);
        }
    }

    /** Compiles and optionally packages one captured set of ordinary source documents. */
    static Build build(final Path project, final Path home, final boolean includeProject) throws IOException {
        if (Files.exists(journal(project.toAbsolutePath().normalize())))
            return new Build.Rejected(new CompileResult.Rejected(List.of(Diagnostic.atPath("PROJECT_EDIT_PENDING",
                    "Finish or recover the editor action before building.", ""))));
        final Map<String, String> documents = new TreeMap<>();
        for (final String name : PROJECT_DOCUMENTS) {
            if (!includeProject && name.equals("railix.creator.json")) continue;
            final Path file = name.equals("railix.project.json") ? project : project.resolveSibling(name);
            if (Files.exists(file)) documents.put(ARCHIVE_ROOT + name, readText(file));
        }
        final String graph = documents.get(ARCHIVE_ROOT + "railix.project.json");
        if (graph == null) return new Build.Rejected(new CompileResult.Rejected(List.of(Diagnostic.atPath("PROJECT_SOURCE_REQUIRED",
                "Select an existing project file.", ""))));
        final String examples = documents.get(ARCHIVE_ROOT + "railix.examples.json");
        final Read snapshot = examples == null ? new Read(identify(graph, true), List.of()) : combine(graph, examples);
        if (!snapshot.diagnostics().isEmpty()) return new Build.Rejected(new CompileResult.Rejected(snapshot.diagnostics()));
        final String lock = documents.get(ARCHIVE_ROOT + "railix.dependencies.lock.json");
        final StepCatalog defaults = StepCatalog.embedded(ProjectFiles.class.getClassLoader(), home.resolve("artifacts"));
        final StepCatalog base = lock == null ? defaults : defaults.installSource(lock, home.resolve("artifacts"));
        final String custom = documents.get(ARCHIVE_ROOT + CustomSteps.FILE);
        final StepCatalog catalog = custom == null ? base : CustomSteps.compileAndInstall(base, project, custom);
        final var result = ProjectCompiler.compileApplication(snapshot.source(), catalog);
        if (result instanceof CompileResult.Rejected rejected) {
            return new Build.Rejected(new CompileResult.Rejected(snapshot.locations(rejected.diagnostics())));
        }
        if (Files.exists(journal(project.toAbsolutePath().normalize())))
            return new Build.Rejected(new CompileResult.Rejected(List.of(Diagnostic.atPath("PROJECT_EDIT_PENDING",
                    "Project files are being edited. Retry after the action completes.", ""))));
        for (final String name : PROJECT_DOCUMENTS) {
            if (!includeProject && name.equals("railix.creator.json")) continue;
            final Path file = name.equals("railix.project.json") ? project : project.resolveSibling(name);
            final String captured = documents.get(ARCHIVE_ROOT + name);
            if (Files.exists(file) != (captured != null) || captured != null && !captured.equals(readText(file)))
                return new Build.Rejected(new CompileResult.Rejected(List.of(Diagnostic.atPath("PROJECT_SOURCE_CHANGED",
                        "Project files changed during compilation. Build again.", ""))));
        }
        return new Build.Compiled((CompileResult.Compiled) result,
                includeProject ? java.util.Collections.unmodifiableMap(documents) : Map.of(), catalog);
    }

    sealed interface Build {
        CompileResult result();
        Map<String, String> documents();

        record Compiled(CompileResult.Compiled result, Map<String, String> documents, StepCatalog catalog) implements Build { }

        record Rejected(CompileResult.Rejected result) implements Build {
            @Override public Map<String, String> documents() { return Map.of(); }
        }
    }

    /** Opens ordinary project files after matching an artifact, without loading artifact classes. */
    static ApplicationSource fromApplication(final Path artifact, final Path destination, final Path home) throws IOException {
        final Path project = destination.toAbsolutePath().normalize();
        if (project.getFileName() == null || Files.isDirectory(project))
            throw new IOException("PROJECT_DESTINATION_INVALID: Select a project file inside a workspace directory.");
        if (PROJECT_DOCUMENTS.contains(project.getFileName().toString())
                && !project.getFileName().toString().equals("railix.project.json"))
            throw new IOException("PROJECT_DESTINATION_INVALID: Select a project filename, not another project document.");
        Path staging = null;
        IOException failure = null;
        try {
            final String expected;
            try (JarFile jar = new JarFile(artifact.toFile())) {
                expected = applicationRevision(jar);
                if (Files.exists(project)) {
                    if (Files.exists(journal(project)))
                        throw new IOException("PROJECT_EDIT_PENDING: Open the project normally to recover its interrupted editor action first.");
                    return new ApplicationSource(project, expected);
                }
                final var embedded = projectEntries(jar);
                if (Files.exists(project.getParent(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("PROJECT_DESTINATION_EXISTS: Select a new workspace directory. Existing files are never replaced.");
                if (!Files.isDirectory(project.getParent().getParent()))
                    throw new IOException("PROJECT_DESTINATION_PARENT_REQUIRED: Create the workspace parent directory first.");
                try { staging = Files.createTempDirectory(project.getParent().getParent(), ".railix-project-"); }
                catch (final IOException destinationFailure) {
                    throw new IOException("PROJECT_DESTINATION_WRITE_FAILED: Cannot create the temporary workspace.", destinationFailure);
                }
                extractProject(jar, embedded, staging, project.getFileName().toString());
            } catch (final SecurityException invalidSignature) {
                throw new IOException("PROJECT_SOURCE_INVALID: Application signature verification failed.", invalidSignature);
            } catch (final IOException unreadable) {
                throw archiveFailure(unreadable);
            }
            final Path candidate = staging.resolve(project.getFileName());
            if (Files.exists(journal(candidate))) throw new IOException("PROJECT_EDIT_PENDING: Recover the project in Creator first.");
            final Read documents = read(candidate);
            if (!documents.diagnostics().isEmpty()) {
                final Diagnostic diagnostic = documents.diagnostics().getFirst();
                throw new IOException(diagnostic.code() + " " + diagnostic.path() + " " + diagnostic.message());
            }
            final var result = ProjectCompiler.compileApplication(documents.source(), catalog(candidate, home));
            if (!(result instanceof CompileResult.Compiled compiled)) {
                final Diagnostic diagnostic = documents.locations(((CompileResult.Rejected) result).diagnostics()).getFirst();
                throw new IOException(diagnostic.code() + " " + diagnostic.path() + " " + diagnostic.message());
            }
            if (!expected.equals(identity(compiled)))
                throw new IOException("PROJECT_SOURCE_MISMATCH: Project files do not match this application build.");
            if (staging != null) {
                try { Files.move(staging, project.getParent()); }
                catch (final java.nio.file.FileAlreadyExistsException | java.nio.file.DirectoryNotEmptyException occupied) {
                    throw new IOException("PROJECT_DESTINATION_EXISTS: Another workspace now occupies this destination.", occupied);
                }
                staging = null;
            }
            return new ApplicationSource(project, expected);
        } catch (final IOException rejected) {
            failure = rejected;
            throw rejected;
        } finally {
            if (staging != null) {
                try {
                    deleteTemporaryWorkspace(staging);
                } catch (final IOException cleanup) {
                    if (failure != null) failure.addSuppressed(cleanup);
                    else throw cleanup;
                }
            }
        }
    }

    record ApplicationSource(Path project, String revision) { }

    private static String applicationRevision(final JarFile jar) throws IOException {
        final var manifestEntry = jar.getJarEntry(JarFile.MANIFEST_NAME);
        if (manifestEntry == null || manifestEntry.getSize() < 0 || manifestEntry.getSize() > MAX_DOCUMENT_BYTES)
            throw new IOException("PROJECT_SOURCE_ID_REQUIRED: Application has no supported project identity.");
        final var manifest = jar.getManifest();
        final String revision = manifest == null ? null : manifest.getMainAttributes().getValue("Railix-Project-Id");
        if (revision == null || !revision.matches("sha256-[a-f0-9]{64}"))
            throw new IOException("PROJECT_SOURCE_ID_REQUIRED: Application has no supported project identity.");
        return revision;
    }

    private static Map<String, JarEntry> projectEntries(final JarFile jar) throws IOException {
        final Map<String, JarEntry> embedded = new TreeMap<>();
        final var entries = jar.entries();
        while (entries.hasMoreElements()) {
            final var entry = entries.nextElement();
            if (!entry.getName().startsWith(ARCHIVE_ROOT)) continue;
            final String name = entry.getName().substring(ARCHIVE_ROOT.length());
            if (!PROJECT_DOCUMENTS.contains(name) || embedded.containsKey(name) || entry.isDirectory()
                    || entry.getSize() < 0 || entry.getSize() > MAX_DOCUMENT_BYTES)
                throw new IOException("PROJECT_SOURCE_INVALID: Unexpected or oversized project entry: " + entry.getName());
            embedded.put(name, entry);
        }
        if (embedded.isEmpty())
            throw new IOException("PROJECT_SOURCE_REQUIRED: Select matching local project files for this application.");
        if (!embedded.containsKey("railix.project.json"))
            throw new IOException("PROJECT_SOURCE_INVALID: Embedded project data have no project document.");
        return embedded;
    }

    /** Extracts checked entries into a caller-owned temporary workspace. */
    private static void extractProject(final JarFile jar, final Map<String, JarEntry> embedded,
                                       final Path directory, final String projectName) throws IOException {
        final byte[] buffer = new byte[8192];
        for (final var entry : embedded.entrySet()) {
            final Path file = directory.resolve(entry.getKey().equals("railix.project.json") ? projectName : entry.getKey());
            final java.io.OutputStream output;
            try { output = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW); }
            catch (final IOException destinationFailure) {
                throw new IOException("PROJECT_DESTINATION_WRITE_FAILED: Cannot create a project document.", destinationFailure);
            }
            try (output; var input = jar.getInputStream(entry.getValue())) {
                long size = 0;
                for (int count = input.read(buffer); count >= 0; count = input.read(buffer)) {
                    size += count;
                    if (size > MAX_DOCUMENT_BYTES || size > entry.getValue().getSize())
                        throw new IOException("PROJECT_SOURCE_INVALID: Project entry exceeds its declared size.");
                    try { output.write(buffer, 0, count); }
                    catch (final IOException destinationFailure) {
                        throw new IOException("PROJECT_DESTINATION_WRITE_FAILED: Cannot write a project document.", destinationFailure);
                    }
                }
                if (size != entry.getValue().getSize()) throw new IOException("PROJECT_SOURCE_INVALID: Project entry size changed.");
            }
        }
    }

    private static IOException archiveFailure(final IOException failure) {
        if (failure.getMessage() != null && failure.getMessage().startsWith("PROJECT_")) return failure;
        return new IOException("PROJECT_SOURCE_INVALID: Cannot read or extract the application archive.", failure);
    }

    /** Captures original project documents for an observed build, never persisting a derived topology. */
    static Snapshot snapshot(final Path artifact, final Path local, final Path home, final String revision) throws IOException {
        if (Files.isRegularFile(local)) {
            final var result = build(local, home, true);
            if (result instanceof Build.Compiled compiled && identity(compiled.result()).equals(revision))
                return snapshot(compiled);
        }
        return embeddedSnapshot(artifact, home, revision);
    }

    private static Snapshot embeddedSnapshot(final Path artifact, final Path home, final String revision) throws IOException {
        final Path temporary = Files.createTempDirectory("railix-observed-source-");
        try {
            final String expected;
            try (JarFile jar = new JarFile(artifact.toFile())) {
                expected = applicationRevision(jar);
                extractProject(jar, projectEntries(jar), temporary, "railix.project.json");
            } catch (final SecurityException invalidSignature) {
                throw new IOException("PROJECT_SOURCE_INVALID: Application signature verification failed.", invalidSignature);
            } catch (final IOException unreadable) {
                throw archiveFailure(unreadable);
            }
            final var result = build(temporary.resolve("railix.project.json"), home, true);
            if (!(result instanceof Build.Compiled compiled)) {
                final Diagnostic diagnostic = ((Build.Rejected) result).result().diagnostics().getFirst();
                throw new IOException(diagnostic.code() + " " + diagnostic.path() + " " + diagnostic.message());
            }
            final String identity = identity(compiled.result());
            if (!expected.equals(identity))
                throw new IOException("PROJECT_SOURCE_MISMATCH: Project files do not match this application build.");
            if (!revision.equals(identity))
                throw new IOException("PROJECT_SOURCE_MISMATCH: Select the project files for this running build.");
            return snapshot(compiled);
        } finally {
            deleteTemporaryWorkspace(temporary);
        }
    }

    private static Snapshot snapshot(final Build.Compiled build) throws IOException {
        final var metadata = CreatorDocument.parse(build.documents().getOrDefault(ARCHIVE_ROOT + "railix.creator.json", CreatorDocument.EMPTY),
                build.result().source(), build.catalog());
        if (!metadata.diagnostics().isEmpty()) throw new IOException("Observed Creator metadata is invalid.");
        return new Snapshot(build.result().source(), metadata.value(), build.catalog());
    }

    record Snapshot(String source, RailixValue.ObjectValue creator, StepCatalog catalog) { }

    /** Deletes temporary workspaces created including project-local compiler artifacts. */
    private static void deleteTemporaryWorkspace(final Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            for (final Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
                Files.deleteIfExists(path);
        }
    }

    /** Loads a transient compiler/editor projection, never a second writable Example source. */
    static Read read(final Path project) throws IOException {
        final String source = readText(project);
        final Path file = project.resolveSibling("railix.examples.json");
        return Files.exists(file) ? combine(source, readText(file)) : new Read(identify(source, true), List.of());
    }

    /** Uses the same document composition for disk reads and the journal's planned complete state. */
    static Read combine(final String source, final String exampleSource) {
        final var parsed = RailixJson.parse(source);
        if (!(parsed instanceof RailixJson.Parsed valid) || !(valid.value() instanceof RailixValue.ObjectValue graph)
                || !(graph.values().get("nodes") instanceof RailixValue.ArrayValue nodes))
            return new Read(source, List.of());
        final var data = RailixJson.parse(exampleSource);
        if (!(data instanceof RailixJson.Parsed document) || !(document.value() instanceof RailixValue.ObjectValue root)
                || !root.values().keySet().equals(Set.of("format", "examples"))
                || !RailixValue.number(1).equals(root.values().get("format"))
                || !(root.values().get("examples") instanceof RailixValue.ArrayValue cases))
            return Read.rejected("EXAMPLES_DOCUMENT_INVALID", "railix.examples.json must contain format 1 and an examples array.");
        final Map<String, List<RailixValue>> byTrigger = new LinkedHashMap<>();
        final Set<String> identifiers = new java.util.HashSet<>();
        final Set<String> owners = new java.util.HashSet<>();
        for (final RailixValue node : nodes.values()) {
            if (!(node instanceof RailixValue.ObjectValue object) || !(object.values().get("id") instanceof RailixValue.StringValue id))
                return new Read(source, List.of());
            if (object.values().containsKey("examples"))
                return Read.rejected("EXAMPLES_TWO_SOURCES", "Examples exist in both project and Example files. Preserve both and resolve the conflict.");
            owners.add(id.value());
        }
        for (final RailixValue item : cases.values()) {
            if (!(item instanceof RailixValue.ObjectValue example)
                    || !Set.of("id", "trigger", "name", "payload", "context", "expectations").containsAll(example.values().keySet())
                    || !(example.values().get("id") instanceof RailixValue.StringValue id)
                    || !id.value().matches("[a-z][a-z0-9:-]{0,127}") || !identifiers.add(id.value())
                    || !(example.values().get("trigger") instanceof RailixValue.StringValue trigger)
                    || !owners.contains(trigger.value()))
                return Read.rejected("EXAMPLES_CASE_INVALID", "Each Example needs a unique stable id and an existing Trigger.");
            if (example.values().containsKey("expectations")) {
                if (!(example.values().get("expectations") instanceof RailixValue.ArrayValue expectations))
                    return Read.rejected("EXAMPLES_EXPECTATIONS_INVALID", "Example expectations must be an array.");
                final Set<String> steps = new java.util.HashSet<>();
                for (final RailixValue entry : expectations.values()) {
                    if (!(entry instanceof RailixValue.ObjectValue expectation)
                            || !expectation.values().keySet().equals(Set.of("step", "checks"))
                            || !(expectation.values().get("step") instanceof RailixValue.StringValue step)
                            || !step.value().matches("[a-z][a-z0-9-]{0,63}")
                            || !steps.add(step.value())
                            || !ExampleAssertions.parse(expectation.values().get("checks")).diagnostics().isEmpty())
                        return Read.rejected("EXAMPLES_EXPECTATIONS_INVALID", "Each expectation needs a Step id and supported field checks.");
                }
            }
            final Map<String, RailixValue> fields = new LinkedHashMap<>(example.values());
            fields.remove("trigger"); fields.remove("expectations");
            byTrigger.computeIfAbsent(trigger.value(), ignored -> new ArrayList<>()).add(RailixValue.object(fields));
        }
        final List<RailixValue> merged = new ArrayList<>();
        for (final RailixValue item : nodes.values()) {
            final var node = (RailixValue.ObjectValue) item;
            final var id = (RailixValue.StringValue) node.values().get("id");
            final var examples = byTrigger.get(id.value());
            merged.add(examples == null ? node : CreatorChanges.with(node, "examples", RailixValue.array(examples)));
        }
        final var combined = CreatorChanges.with(graph, "nodes", RailixValue.array(merged));
        return new Read(RailixJson.write(combined), List.of(), exampleSource);
    }

    /** Assigns identity once. Position is used only while importing an old embedded document. */
    static String identify(final String source, final boolean importing) {
        final var parsed = RailixJson.parse(source);
        if (!(parsed instanceof RailixJson.Parsed valid) || !(valid.value() instanceof RailixValue.ObjectValue graph)
                || !(graph.values().get("nodes") instanceof RailixValue.ArrayValue nodes)) return source;
        final List<RailixValue> identified = new ArrayList<>();
        for (final RailixValue item : nodes.values()) {
            if (!(item instanceof RailixValue.ObjectValue node)
                    || !(node.values().get("id") instanceof RailixValue.StringValue trigger)
                    || !(node.values().get("examples") instanceof RailixValue.ArrayValue examples)) {
                identified.add(item); continue;
            }
            final List<RailixValue> cases = new ArrayList<>();
            for (int index = 0; index < examples.values().size(); index++) {
                final var example = examples.values().get(index);
                cases.add(example instanceof RailixValue.ObjectValue object && !object.values().containsKey("id")
                        ? CreatorChanges.with(object, "id", RailixValue.string(importing ? trigger.value() + ":" + index
                        : "example-" + UUID.randomUUID())) : example);
            }
            final Map<String, RailixValue> fields = new LinkedHashMap<>(node.values());
            fields.remove("examples");
            fields.put("examples", RailixValue.array(cases));
            identified.add(RailixValue.object(fields));
        }
        return RailixJson.write(CreatorChanges.with(graph, "nodes", RailixValue.array(identified)));
    }

    /** A flow-only edit retains the current cases. Explicit case edits replace their own array. */
    static String inputs(final String source, final String current) {
        final var parsed = RailixJson.parse(source);
        final var retained = RailixJson.parse(current);
        if (!(parsed instanceof RailixJson.Parsed valid) || !(valid.value() instanceof RailixValue.ObjectValue graph)
                || !(graph.values().get("nodes") instanceof RailixValue.ArrayValue nodes)
                || !(retained instanceof RailixJson.Parsed previous) || !(previous.value() instanceof RailixValue.ObjectValue old)
                || !(old.values().get("nodes") instanceof RailixValue.ArrayValue existing)) return source;
        final Map<RailixValue, RailixValue.ObjectValue> cases = new LinkedHashMap<>();
        for (final var item : existing.values()) if (item instanceof RailixValue.ObjectValue node
                && node.values().containsKey("examples")) cases.put(node.values().get("id"), node);
        final List<RailixValue> merged = new ArrayList<>();
        for (final var item : nodes.values()) {
            if (!(item instanceof RailixValue.ObjectValue node) || !cases.containsKey(node.values().get("id"))
                    || !java.util.Objects.equals(node.values().get("use"), cases.get(node.values().get("id")).values().get("use"))) {
                merged.add(item); continue;
            }
            final var previousCases = cases.get(node.values().get("id")).values().get("examples");
            if (!node.values().containsKey("examples")) {
                merged.add(CreatorChanges.with(node, "examples", previousCases)); continue;
            }
            merged.add(node);
        }
        return identify(RailixJson.write(CreatorChanges.with(graph, "nodes", RailixValue.array(merged))), false);
    }

    static Documents split(final String source, final String retained) throws IOException {
        final var parsed = RailixJson.parse(source);
        final var previous = RailixJson.parse(retained);
        if (!(parsed instanceof RailixJson.Parsed valid) || !(valid.value() instanceof RailixValue.ObjectValue graph)
                || !(graph.values().get("nodes") instanceof RailixValue.ArrayValue nodes)
                || !(previous instanceof RailixJson.Parsed stored) || !(stored.value() instanceof RailixValue.ObjectValue root)
                || !(root.values().get("examples") instanceof RailixValue.ArrayValue saved))
            throw new IOException("Cannot save invalid project or Example documents.");
        final Map<RailixValue, RailixValue> expectations = new LinkedHashMap<>();
        for (final RailixValue item : saved.values()) if (item instanceof RailixValue.ObjectValue example
                && example.values().containsKey("expectations"))
            expectations.put(example.values().get("id"), example.values().get("expectations"));
        final List<RailixValue> stripped = new ArrayList<>(), cases = new ArrayList<>();
        for (final RailixValue item : nodes.values()) {
            final var node = (RailixValue.ObjectValue) item;
            final Map<String, RailixValue> fields = new LinkedHashMap<>(node.values());
            final var embedded = fields.remove("examples");
            if (embedded instanceof RailixValue.ArrayValue examples) for (final RailixValue entry : examples.values()) {
                final Map<String, RailixValue> example = new LinkedHashMap<>(((RailixValue.ObjectValue) entry).values());
                example.put("trigger", node.values().get("id"));
                final var checks = expectations.get(example.get("id"));
                if (checks instanceof RailixValue.ArrayValue values && !values.values().isEmpty()) example.put("expectations", checks);
                cases.add(RailixValue.object(example));
            }
            stripped.add(RailixValue.object(fields));
        }
        return new Documents(RailixJson.write(CreatorChanges.with(graph, "nodes", RailixValue.array(stripped))),
                RailixJson.write(RailixValue.object(Map.of("format", RailixValue.number(1), "examples", RailixValue.array(cases)))));
    }

    static RailixValue.ObjectValue expectations(final String source) {
        final var root = (RailixValue.ObjectValue) ((RailixJson.Parsed) RailixJson.parse(source)).value();
        final var result = new LinkedHashMap<String, RailixValue>();
        for (final var item : ((RailixValue.ArrayValue) root.values().get("examples")).values()) {
            final var example = (RailixValue.ObjectValue) item;
            if (example.values().containsKey("expectations"))
                result.put(((RailixValue.StringValue) example.values().get("id")).value(), example.values().get("expectations"));
        }
        return RailixValue.object(result);
    }

    /** Applies history only to expectation fields. Example inputs remain owned by the accepted edit. */
    static String restoreExpectations(final String source, final RailixValue.ObjectValue changes) {
        final var root = (RailixValue.ObjectValue) ((RailixJson.Parsed) RailixJson.parse(source)).value();
        final List<RailixValue> cases = new ArrayList<>();
        for (final var item : ((RailixValue.ArrayValue) root.values().get("examples")).values()) {
            final var example = (RailixValue.ObjectValue) item;
            final var change = changes.values().get(((RailixValue.StringValue) example.values().get("id")).value());
            if (change == null) cases.add(example);
            else {
                final var fields = new LinkedHashMap<>(example.values());
                if (change instanceof RailixValue.NullValue) fields.remove("expectations");
                else fields.put("expectations", change);
                cases.add(RailixValue.object(fields));
            }
        }
        return RailixJson.write(CreatorChanges.with(root, "examples", RailixValue.array(cases)));
    }

    static Path journal(final Path project) { return project.getParent().resolve(".railix/run/editor-transaction.json"); }

    static String backup(final Path project) throws IOException {
        final Map<String, RailixValue> files = new LinkedHashMap<>();
        for (final String key : List.of("project", "creator", "examples")) {
            final Path file = target(project, key);
            files.put(key, Files.exists(file) ? RailixValue.string(readText(file)) : RailixValue.nullValue());
        }
        return RailixJson.write(RailixValue.object(files));
    }

    static void begin(final Path project, final Map<String, String> candidates) throws IOException {
        final var before = (RailixValue.ObjectValue) ((RailixJson.Parsed) RailixJson.parse(backup(project))).value();
        final var after = new LinkedHashMap<>(before.values());
        candidates.forEach((key, value) -> after.put(key, RailixValue.string(value)));
        if (candidates.containsKey("project")) {
            final var examples = before.values().get("examples");
            final var documents = split(candidates.get("project"), examples instanceof RailixValue.StringValue text
                    ? text.value() : EMPTY_EXAMPLES);
            after.put("project", RailixValue.string(documents.project()));
            after.put("examples", RailixValue.string(candidates.getOrDefault("examples", documents.examples())));
        }
        persist(journal(project), RailixJson.write(RailixValue.object(Map.of("before", before, "after", RailixValue.object(after)))));
    }

    /** Updates the two data owners under the same recovery journal used by complete editor actions. */
    static void writeProject(final Path project, final String source) throws IOException {
        final Path examples = target(project, "examples");
        final Path journal = journal(project);
        final boolean owner = !Files.exists(journal);
        String retained = Files.exists(examples) ? readText(examples) : EMPTY_EXAMPLES;
        Documents documents = split(source, retained);
        if (!owner) {
            final var plan = readPlan(project);
            for (final String key : List.of("project", "examples")) if (!matchesPlan(project, key, plan))
                throw new IOException("Project files changed outside Creator during the edit.");
            final var before = (RailixValue.ObjectValue) plan.values().get("before");
            final var original = before.values().get("examples");
            retained = original instanceof RailixValue.StringValue text ? text.value() : EMPTY_EXAMPLES;
            documents = split(source, retained);
            final var after = (RailixValue.ObjectValue) plan.values().get("after");
            if (!(after.values().get("project") instanceof RailixValue.StringValue nextProject)
                    || !(after.values().get("examples") instanceof RailixValue.StringValue nextExamples))
                throw new IOException("The planned project documents are invalid.");
            final var next = combine(nextProject.value(), nextExamples.value());
            if (!next.diagnostics().isEmpty()) throw new IOException("The planned project documents are invalid.");
            // History restoration must write its expectations with the case, not in a later partial state.
            if (next.source().equals(source)) documents = new Documents(nextProject.value(), nextExamples.value());
        }
        if (owner) begin(project, Map.of("project", source));
        try {
            persist(examples, documents.examples());
            persist(project, documents.project());
            if (owner) Files.delete(journal);
        } catch (final IOException failure) {
            if (owner) try { recover(project, Map.of("project", source)); }
            catch (final IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    static RailixValue.ObjectValue readPlan(final Path project) throws IOException {
        final var parsed = RailixJson.parse(Files.readString(journal(project), StandardCharsets.UTF_8));
        if (!(parsed instanceof RailixJson.Parsed valid) || !(valid.value() instanceof RailixValue.ObjectValue root)
                || !(root.values().get("before") instanceof RailixValue.ObjectValue)
                || !(root.values().get("after") instanceof RailixValue.ObjectValue))
            throw new IOException("An interrupted edit must be recovered before saving.");
        return root;
    }

    static boolean matchesPlan(final Path project, final String key, final RailixValue.ObjectValue plan) throws IOException {
        final var before = (RailixValue.ObjectValue) plan.values().get("before");
        final var after = (RailixValue.ObjectValue) plan.values().get("after");
        final RailixValue current;
        try { current = RailixValue.string(readText(target(project, key))); }
        catch (final NoSuchFileException absent) {
            return RailixValue.nullValue().equals(before.values().get(key)) || RailixValue.nullValue().equals(after.values().get(key));
        }
        return current.equals(before.values().get(key)) || current.equals(after.values().get(key));
    }

    static List<String> recover(final Path project, final Map<String, String> candidates) throws IOException {
        final Path journal = journal(project);
        if (!Files.exists(journal)) return List.of();
        final var parsed = RailixJson.parse(Files.readString(journal, StandardCharsets.UTF_8));
        if (!(parsed instanceof RailixJson.Parsed valid) || !(valid.value() instanceof RailixValue.ObjectValue root))
            throw new IOException("The interrupted editor transaction is invalid. Preserve " + journal + " for recovery.");
        final boolean planned = root.values().keySet().equals(Set.of("before", "after"));
        final RailixValue.ObjectValue backup;
        final Map<String, String> desired = new LinkedHashMap<>(candidates);
        if (planned) {
            if (!(root.values().get("before") instanceof RailixValue.ObjectValue before)
                    || !(root.values().get("after") instanceof RailixValue.ObjectValue after)
                    || !before.values().keySet().equals(FILES) || !after.values().keySet().equals(FILES)
                    || after.values().values().stream().anyMatch(value -> !(value instanceof RailixValue.StringValue)
                    && !(value instanceof RailixValue.NullValue)))
                throw new IOException("The interrupted editor transaction is invalid. Preserve " + journal + " for recovery.");
            backup = before;
            desired.clear();
            after.values().forEach((key, value) -> { if (value instanceof RailixValue.StringValue text) desired.put(key, text.value()); });
        } else backup = root;
        if (!(backup.values().keySet().equals(FILES) || backup.values().keySet().equals(Set.of("project", "creator")))
                || backup.values().values().stream().anyMatch(value -> !(value instanceof RailixValue.StringValue)
                && !(value instanceof RailixValue.NullValue)))
            throw new IOException("The interrupted editor transaction is invalid. Preserve " + journal + " for recovery.");
        if (!planned && backup.values().containsKey("examples") && candidates.containsKey("project")) {
            final var previous = backup.values().get("examples");
            final var documents = split(candidates.get("project"), previous instanceof RailixValue.StringValue text
                    ? text.value() : EMPTY_EXAMPLES);
            desired.put("project", documents.project()); desired.put("examples", documents.examples());
        }
        final List<String> preserved = new ArrayList<>();
        for (final var entry : backup.values().entrySet()) {
            final Path file = target(project, entry.getKey());
            final String current;
            try { current = readText(file); }
            catch (final NoSuchFileException absent) {
                if (!planned && candidates.isEmpty() && entry.getValue() instanceof RailixValue.StringValue old) persist(file, old.value());
                else if (entry.getValue() instanceof RailixValue.StringValue) preserved.add(file.getFileName().toString());
                continue;
            }
            if ((!planned && candidates.isEmpty()) || current.equals(desired.get(entry.getKey()))) {
                if (entry.getValue() instanceof RailixValue.StringValue old) persist(file, old.value());
                else Files.delete(file);
            } else if (!(entry.getValue() instanceof RailixValue.StringValue old) || !current.equals(old.value()))
                preserved.add(file.getFileName().toString());
        }
        Files.delete(journal);
        return List.copyOf(preserved);
    }

    static Path target(final Path project, final String key) {
        return switch (key) {
            case "project" -> project;
            case "creator" -> project.resolveSibling("railix.creator.json");
            case "examples" -> project.resolveSibling("railix.examples.json");
            default -> throw new IllegalArgumentException("Unknown project document: " + key);
        };
    }

    static String readText(final Path file) throws IOException {
        if (Files.size(file) > MAX_DOCUMENT_BYTES)
            throw new IOException("PROJECT_DOCUMENT_TOO_LARGE: " + file.getFileName() + " exceeds 64 MiB.");
        try (var input = Files.newInputStream(file)) {
            final byte[] bytes = input.readNBytes(MAX_DOCUMENT_BYTES + 1);
            if (bytes.length > MAX_DOCUMENT_BYTES)
                throw new IOException("PROJECT_DOCUMENT_TOO_LARGE: " + file.getFileName() + " exceeds 64 MiB.");
            return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (final CharacterCodingException invalid) {
            throw new IOException("Creator project is not valid UTF-8.", invalid);
        }
    }

    static void persist(final Path file, final String source) throws IOException {
        Files.createDirectories(file.getParent());
        final Path temporary = Files.createTempFile(file.getParent(), "." + file.getFileName(), ".tmp");
        try {
            Files.writeString(temporary, source, StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    record Documents(String project, String examples) { }
    record Read(String source, List<Diagnostic> diagnostics, String examples) {
        Read { diagnostics = List.copyOf(diagnostics); }
        Read(final String source, final List<Diagnostic> diagnostics) { this(source, diagnostics, ""); }

        List<Diagnostic> locations(final List<Diagnostic> problems) {
            if (examples.isEmpty()) return problems;
            final var graph = (RailixValue.ObjectValue) ((RailixJson.Parsed) RailixJson.parse(source)).value();
            final var nodes = (RailixValue.ArrayValue) graph.values().get("nodes");
            final var document = (RailixValue.ObjectValue) ((RailixJson.Parsed) RailixJson.parse(examples)).value();
            final var cases = (RailixValue.ArrayValue) document.values().get("examples");
            final Map<RailixValue, List<Integer>> byTrigger = new LinkedHashMap<>();
            for (int index = 0; index < cases.values().size(); index++) {
                final var example = (RailixValue.ObjectValue) cases.values().get(index);
                byTrigger.computeIfAbsent(example.values().get("trigger"), ignored -> new ArrayList<>()).add(index);
            }
            final var pattern = java.util.regex.Pattern.compile("^nodes\\[(\\d+)]\\.examples(?:\\[(\\d+)])?(.*)$");
            return problems.stream().map(problem -> {
                final var match = pattern.matcher(problem.path());
                if (!match.matches()) return problem;
                final int node = Integer.parseInt(match.group(1));
                if (node >= nodes.values().size() || !(nodes.values().get(node) instanceof RailixValue.ObjectValue owner)) return problem;
                final var positions = byTrigger.getOrDefault(owner.values().get("id"), List.of());
                final int position = match.group(2) == null ? -1 : Integer.parseInt(match.group(2));
                final String path = "railix.examples.json.examples" + (position >= 0 && position < positions.size()
                        ? "[" + positions.get(position) + "]" : "") + match.group(3);
                return Diagnostic.atPath(problem.code(), problem.message(), path);
            }).toList();
        }

        static Read rejected(final String code, final String message) {
            return new Read("", List.of(Diagnostic.atPath(code, message, "railix.examples.json")));
        }
    }
}
