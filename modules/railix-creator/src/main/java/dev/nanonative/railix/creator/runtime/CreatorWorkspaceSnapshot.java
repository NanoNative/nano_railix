package dev.nanonative.railix.creator.runtime;

import dev.nanonative.railix.kernel.model.KernelContractCodec;
import dev.nanonative.railix.kernel.runtime.BuiltRailixAppLoader;
import dev.nanonative.railix.railixstdhttp.HttpTrafficPanelQuery;
import dev.nanonative.railix.railixstdhttp.HttpTrafficReport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.stream.Stream;

/**
 * Captures a deterministic Creator workspace snapshot from pack metadata and local run artifacts.
 */
public final class CreatorWorkspaceSnapshot {

    private static final int MAX_ARTIFACT_ERROR_CHARS = 200;
    private static final Comparator<RunOrderKey> NEWEST_FIRST_RUN_ORDER = Comparator
            .comparing(RunOrderKey::updatedAt, Comparator.reverseOrder())
            .thenComparing(RunOrderKey::appId)
            .thenComparing(RunOrderKey::flowId)
            .thenComparing(RunOrderKey::runId);
    private static final Comparator<RunSnapshot> NEWEST_FIRST_RUN_SNAPSHOT_ORDER =
            Comparator.comparing(RunSnapshot::orderKey, NEWEST_FIRST_RUN_ORDER);
    private static final Comparator<RunSnapshot> OLDEST_FIRST_PAGE_HEAP_ORDER = NEWEST_FIRST_RUN_SNAPSHOT_ORDER.reversed();

    private CreatorWorkspaceSnapshot() {}

    /**
     * Builds a stable primitive view model for the local Creator shell.
     *
     * @param repoRoot workspace root containing packs and modules
     * @param runsRoot run-artifact root
     * @return primitive UI model suitable for stable JSON
     */
    public static Map<String, Object> capture(
            final Path repoRoot,
            final Path runsRoot,
            final Map<String, Object> controlSessions,
            final Path instanceRegistryRoot,
            final String currentInstanceId
    ) {
        final Path normalizedRepoRoot = requireDirectory(repoRoot, "repoRoot");
        final Path normalizedRunsRoot = runsRoot.toAbsolutePath().normalize();
        final List<Map<String, Object>> packs = scanPacks(normalizedRepoRoot.resolve("packs"));
        final List<RunSnapshot> runs = scanRuns(normalizedRunsRoot);
        final Map<String, Object> httpTraffic = captureHttpTraffic(normalizedRepoRoot, normalizedRunsRoot);
        final Map<String, Object> launchPrep = captureLaunchPrep(normalizedRepoRoot);
        final Map<String, Object> normalizedControlSessions = Map.copyOf(Objects.requireNonNull(controlSessions, "controlSessions"));
        final Map<String, Object> instances = CreatorInstanceRegistry.capture(
                Objects.requireNonNull(instanceRegistryRoot, "instanceRegistryRoot"),
                Objects.requireNonNull(currentInstanceId, "currentInstanceId")
        );
        final Map<String, Object> latestRunDetails = runs.isEmpty()
                ? Map.of()
                : runs.getFirst().toDetailUiModel();
        final List<Map<String, Object>> latestSignals = runs.isEmpty()
                ? List.of()
                : listOfMaps(latestRunDetails, "latestSignals");
        return orderedMap(
                "title", "Railix Creator Shell",
                "repoRoot", normalizedRepoRoot.toString(),
                "runsRoot", normalizedRunsRoot.toString(),
                "controlEndpointReady", booleanValue(normalizedControlSessions, "ready"),
                "controlEndpointStatus", stringValue(normalizedControlSessions, "status"),
                "packs", packs,
                "runs", runs.stream().map(RunSnapshot::toUiModel).toList(),
                "latestRunDetails", latestRunDetails,
                "controlSessions", normalizedControlSessions,
                "instances", instances,
                "launchPrep", launchPrep,
                "httpTraffic", httpTraffic,
                "latestSignals", latestSignals,
                "shellSections", List.of(
                        "Graph Editor Shell",
                        "Step Palette",
                        "Dependency Browser",
                        "Creator Control Sessions",
                        "Instance Discovery",
                        "Launch Prep",
                        "Step Inspector",
                        "Settings Tree",
                        "Run Timeline",
                        "HTTP Traffic",
                        "Context Diff",
                        "Run Signals"
                )
        );
    }

    /**
     * Builds a bounded historical run detail view model for one persisted run under the runs root.
     *
     * @param runsRoot run-artifact root
     * @param appId app directory identifier
     * @param flowId flow directory identifier
     * @param runId run directory identifier
     * @return primitive UI model suitable for stable JSON
     */
    public static Map<String, Object> captureHistoricalRunDetails(
            final Path runsRoot,
            final String appId,
            final String flowId,
            final String runId
    ) {
        final Path normalizedRunsRoot = requireDirectory(runsRoot, "runsRoot");
        final RunSnapshot runSnapshot = locateRunSnapshot(normalizedRunsRoot, appId, flowId, runId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Persisted run not found: " + requireRunSegment(appId, "appId")
                                + "/" + requireRunSegment(flowId, "flowId")
                                + "/" + requireRunSegment(runId, "runId")
                ));
        return runSnapshot.toHistoricalDetailUiModel();
    }

    /**
     * Builds a bounded historical run query view over persisted run summaries.
     *
     * @param runsRoot run-artifact root
     * @param appId optional exact app identifier filter
     * @param flowId optional exact flow identifier filter
     * @param limit bounded result limit
     * @return primitive UI model suitable for stable JSON
     */
    public static Map<String, Object> captureHistoricalRunsQuery(
            final Path runsRoot,
            final String appId,
            final String flowId,
            final String outcome,
            final String summaryReadStatus,
            final String cursor,
            final int limit
    ) {
        final Path normalizedRunsRoot = Objects.requireNonNull(runsRoot, "runsRoot").toAbsolutePath().normalize();
        final String normalizedAppId = optionalRunSegment(appId, "appId");
        final String normalizedFlowId = optionalRunSegment(flowId, "flowId");
        final String normalizedOutcome = optionalFilterValue(outcome, "outcome");
        final String normalizedSummaryReadStatus = optionalSummaryReadStatus(summaryReadStatus);
        final QueryCursor normalizedCursor = optionalQueryCursor(cursor);
        if (normalizedCursor.isPresent() && !normalizedCursor.matchesFilters(
                normalizedAppId,
                normalizedFlowId,
                normalizedOutcome,
                normalizedSummaryReadStatus
        )) {
            throw new IllegalArgumentException("cursor does not match current historical run filters");
        }
        final int normalizedLimit = requireLimit(limit);
        final QueryResult queryResult = queryRuns(
                normalizedRunsRoot,
                normalizedAppId,
                normalizedFlowId,
                normalizedOutcome,
                normalizedSummaryReadStatus,
                normalizedCursor,
                normalizedLimit
        );
        return orderedMap(
                "sourceKind", "historical-persisted-run-query",
                "filters", orderedMap(
                        "appId", normalizedAppId,
                        "flowId", normalizedFlowId,
                        "outcome", normalizedOutcome,
                        "summaryReadStatus", normalizedSummaryReadStatus
                ),
                "cursorApplied", normalizedCursor.isPresent(),
                "limit", normalizedLimit,
                "totalMatchedCount", queryResult.totalMatchedCount(),
                "returnedCount", queryResult.runs().size(),
                "hasMore", queryResult.hasMore(),
                "nextCursor", queryResult.nextCursor(),
                "runs", queryResult.runs().stream().map(RunSnapshot::toQueryUiModel).toList()
        );
    }

    private static Map<String, Object> captureLaunchPrep(final Path repoRoot) {
        return orderedMap(
                "title", "Launch Prep",
                "guidance", "Creator can launch explicit persisted plan.json and envelope.json pairs plus supported railix.app.yaml and envelope YAML example pairs through the same built-app launcher. Unsupported YAML features fail explicitly.",
                "runnableInputs", scanRunnableInputs(repoRoot),
                "authoringSpecs", scanAuthoringSpecs(repoRoot.resolve("examples")),
                "packageReports", scanPackageReports(repoRoot.resolve("build").resolve("reports"))
        );
    }

    private static Map<String, Object> captureHttpTraffic(final Path repoRoot, final Path runsRoot) {
        final Path panelMetadata = repoRoot
                .resolve("packs")
                .resolve("railix.std.http")
                .resolve("panels")
                .resolve("http-traffic.panel.yaml");
        final String title = readYamlValue(panelMetadata, "title").orElse("HTTP Traffic");
        final List<String> columns = readYamlList(panelMetadata, "columns");
        try {
            final HttpTrafficReport.Report report = new HttpTrafficReport().read(runsRoot, 12);
            return orderedMap(
                    "title", title,
                    "columns", columns.isEmpty() ? defaultTrafficColumns() : columns,
                    "source", "persisted-http-capture-artifacts",
                    "error", "",
                    "totalRows", report.totalRows(),
                    "handledRows", report.handledRows(),
                    "rejectedRows", report.rejectedRows(),
                    "failedRows", report.failedRows(),
                    "rows", report.rows().stream().map(CreatorWorkspaceSnapshot::toTrafficUiModel).toList()
            );
        } catch (final RuntimeException exception) {
            return orderedMap(
                    "title", title,
                    "columns", columns.isEmpty() ? defaultTrafficColumns() : columns,
                    "source", "persisted-http-capture-artifacts",
                    "error", summarize(exception),
                    "totalRows", 0,
                    "handledRows", 0,
                    "rejectedRows", 0,
                    "failedRows", 0,
                    "rows", List.of()
            );
        }
    }

    private static List<String> defaultTrafficColumns() {
        return List.of("timestamp", "method", "path", "status", "durationMs");
    }

    private static List<Map<String, Object>> scanRunnableInputs(final Path repoRoot) {
        final Map<Path, List<Path>> plansByDir = new LinkedHashMap<>();
        final Map<Path, List<Path>> envelopesByDir = new LinkedHashMap<>();
        try (Stream<Path> files = Files.find(
                repoRoot,
                8,
                (path, attributes) -> attributes.isRegularFile() && !isIgnoredPath(path)
        )) {
            files.forEach(path -> {
                final String fileName = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                if (!fileName.endsWith(".json")) {
                    return;
                }
                if (fileName.contains("plan")) {
                    plansByDir.computeIfAbsent(path.getParent(), ignored -> new java.util.ArrayList<>()).add(path);
                }
                if (fileName.contains("envelope")) {
                    envelopesByDir.computeIfAbsent(path.getParent(), ignored -> new java.util.ArrayList<>()).add(path);
                }
            });
        } catch (final IOException exception) {
            throw new UncheckedIOException(exception);
        }
        final List<Path> sharedDirs = plansByDir.keySet().stream()
                .filter(envelopesByDir::containsKey)
                .sorted()
                .toList();
        final List<Map<String, Object>> runnableInputs = new java.util.ArrayList<>();
        for (final Path dir : sharedDirs) {
            final Path planPath = firstSorted(plansByDir.get(dir));
            final Path envelopePath = firstSorted(envelopesByDir.get(dir));
            runnableInputs.add(orderedMap(
                    "label", relativePath(repoRoot, dir),
                    "directory", dir.toString(),
                    "planLocation", planPath.toString(),
                    "envelopeLocation", envelopePath.toString(),
                    "launchable", true,
                    "blocker", "",
                    "source", "persisted-json-pair"
            ));
        }
        return List.copyOf(runnableInputs);
    }

    private static List<Map<String, Object>> scanAuthoringSpecs(final Path examplesRoot) {
        if (!Files.isDirectory(examplesRoot)) {
            return List.of();
        }
        try (Stream<Path> files = Files.find(
                examplesRoot,
                4,
                (path, attributes) -> attributes.isRegularFile() && "railix.app.yaml".equals(path.getFileName().toString())
        )) {
            return files.sorted()
                    .map(specPath -> toAuthoringSpecUiModel(examplesRoot.getParent(), specPath))
                    .toList();
        } catch (final IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static Map<String, Object> toAuthoringSpecUiModel(final Path repoRoot, final Path specPath) {
        final String fallbackAppId = readYamlChildValue(specPath, "app", "id").orElse(specPath.getParent().getFileName().toString());
        final String fallbackAppName = readYamlChildValue(specPath, "app", "name").orElse(fallbackAppId);
        final List<Path> envelopeExamplePaths = scanEnvelopeExamples(specPath.getParent());
        final List<String> envelopeExamples = envelopeExamplePaths.stream().map(Path::toString).toList();
        try {
            final dev.nanonative.railix.kernel.runtime.AppPlan plan = BuiltRailixAppLoader.loadPlan(specPath.toString());
            final Optional<Path> runnableEnvelope = firstRunnableEnvelopeExample(envelopeExamplePaths);
            final boolean launchable = runnableEnvelope.isPresent();
            return orderedMap(
                    "appId", plan.appId(),
                    "appName", fallbackAppName,
                    "path", specPath.toString(),
                    "relativePath", relativePath(repoRoot, specPath),
                    "launchable", launchable,
                    "blocker", launchable ? "" : "No runnable envelope example is committed beside this authoring spec.",
                    "planLocation", specPath.toString(),
                    "envelopeLocation", runnableEnvelope.map(Path::toString).orElse(""),
                    "envelopeExamples", envelopeExamples
            );
        } catch (final RuntimeException exception) {
            return orderedMap(
                    "appId", fallbackAppId,
                    "appName", fallbackAppName,
                    "path", specPath.toString(),
                    "relativePath", relativePath(repoRoot, specPath),
                    "launchable", false,
                    "blocker", "Authoring spec is not runnable yet: " + summarize(exception),
                    "planLocation", specPath.toString(),
                    "envelopeLocation", "",
                    "envelopeExamples", envelopeExamples
            );
        }
    }

    private static Optional<Path> firstRunnableEnvelopeExample(final List<Path> envelopeExamples) {
        for (final Path envelopeExample : envelopeExamples) {
            try {
                BuiltRailixAppLoader.loadEnvelope(envelopeExample.toString());
                return Optional.of(envelopeExample);
            } catch (final RuntimeException ignored) {
                // Ignore malformed envelope examples and keep scanning for a runnable pair.
            }
        }
        return Optional.empty();
    }

    private static List<Path> scanEnvelopeExamples(final Path exampleDir) {
        if (!Files.isDirectory(exampleDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(exampleDir)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        final String fileName = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                        return (fileName.endsWith(".yaml") || fileName.endsWith(".json")) && fileName.contains("envelope");
                    })
                    .sorted()
                    .toList();
        } catch (final IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static List<Map<String, Object>> scanPackageReports(final Path reportsRoot) {
        if (!Files.isDirectory(reportsRoot)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(reportsRoot)) {
            return files
                    .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .map(CreatorWorkspaceSnapshot::readPackageReport)
                    .flatMap(Optional::stream)
                    .toList();
        } catch (final IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static Optional<Map<String, Object>> readPackageReport(final Path reportFile) {
        try {
            final Map<String, Object> report = KernelContractCodec.parseStableJsonObject(Files.readString(reportFile));
            final String type = stringValue(report, "type");
            if (type.isEmpty()) {
                return Optional.empty();
            }
            final String mode = stringValue(report, "mode").isBlank() ? "headless" : stringValue(report, "mode");
            final Map<String, Object> artifacts = objectValue(report, "artifacts");
            final String launcherPath = stringValue(artifacts, "launcherPath");
            final boolean creatorMode = "creator".equals(mode);
            return Optional.of(orderedMap(
                    "reportPath", reportFile.toString(),
                    "type", type,
                    "mode", mode,
                    "appName", stringValue(report, "appName"),
                    "launcherPath", launcherPath,
                    "launchable", false,
                    "blocker", creatorMode
                            ? "Packaged Creator launcher is proven, but it still needs explicit --repo-root at start time."
                            : "Packaged launcher still needs explicit persisted plan.json and envelope.json inputs."
            ));
        } catch (final IOException exception) {
            throw new UncheckedIOException(exception);
        } catch (final RuntimeException exception) {
            return Optional.of(orderedMap(
                    "reportPath", reportFile.toString(),
                    "type", "invalid",
                    "mode", "invalid",
                    "appName", "",
                    "launcherPath", "",
                    "launchable", false,
                    "blocker", "Package report is malformed: " + summarize(exception)
            ));
        }
    }

    private static List<Map<String, Object>> scanPacks(final Path packsRoot) {
        if (!Files.isDirectory(packsRoot)) {
            return List.of();
        }
        try (Stream<Path> packDirs = Files.list(packsRoot)) {
            return packDirs
                    .filter(Files::isDirectory)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .map(CreatorWorkspaceSnapshot::scanPack)
                    .toList();
        } catch (final IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static Map<String, Object> scanPack(final Path packDir) {
        final Path packFile = packDir.resolve("pack.yaml");
        final String packId = readYamlValue(packFile, "id").orElse(packDir.getFileName().toString());
        final String summary = readYamlValue(packFile, "summary").orElse("");
        final List<Map<String, Object>> steps = scanMetadataFiles(packDir.resolve("steps"));
        final List<Map<String, Object>> panels = scanMetadataFiles(packDir.resolve("panels"));
        return orderedMap(
                "id", packId,
                "summary", summary,
                "path", packDir.toString(),
                "stepCount", steps.size(),
                "panelCount", panels.size(),
                "steps", steps,
                "panels", panels
        );
    }

    private static List<Map<String, Object>> scanMetadataFiles(final Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".yaml"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .map(CreatorWorkspaceSnapshot::scanMetadataFile)
                    .toList();
        } catch (final IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static Map<String, Object> scanMetadataFile(final Path file) {
        final String id = readYamlValue(file, "id").orElse(file.getFileName().toString());
        final String displayName = readYamlValue(file, "displayName").orElse(id);
        final String summary = readYamlValue(file, "summary").orElse("");
        return orderedMap(
                "id", id,
                "displayName", displayName,
                "summary", summary,
                "path", file.toString()
        );
    }

    private static List<RunSnapshot> scanRuns(final Path runsRoot) {
        if (!Files.isDirectory(runsRoot)) {
            return List.of();
        }
        try (Stream<Path> runFolders = Files.find(
                runsRoot,
                3,
                (path, attributes) -> attributes.isDirectory() && isRunFolder(runsRoot, path) && hasRunArtifacts(path)
        )) {
            return runFolders
                    .map(CreatorWorkspaceSnapshot::scanRunFolder)
                    .sorted(NEWEST_FIRST_RUN_SNAPSHOT_ORDER)
                    .toList();
        } catch (final IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static RunSnapshot scanRunFolder(final Path runFolder) {
        try {
            final Path flowDir = Objects.requireNonNull(runFolder.getParent(), "flowDir");
            final Path appDir = Objects.requireNonNull(flowDir.getParent(), "appDir");
            final Path signalsFile = runFolder.resolve("signals.ndjson");
            final CreatorRunArtifactSnapshot artifactSnapshot = CreatorRunArtifactSnapshot.capture(runFolder, signalsFile);
            final Map<String, Object> summary = artifactSnapshot.summary();
            final String inferredOutcome = inferredRunOutcome(artifactSnapshot);
            final String inferredReplyMode = inferredReplyMode(artifactSnapshot);
            return new RunSnapshot(
                    appDir.getFileName().toString(),
                    flowDir.getFileName().toString(),
                    runFolder.getFileName().toString(),
                    inferredOutcome,
                    numberValue(summary, "executedSteps"),
                    inferredReplyMode,
                    artifactSnapshot.signalCount(),
                    artifactSnapshot.summaryReadStatus(),
                    artifactSnapshot.summaryReadError(),
                    latestArtifactUpdate(runFolder),
                    runFolder,
                    signalsFile
            );
        } catch (final IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static Optional<RunSnapshot> locateRunSnapshot(
            final Path runsRoot,
            final String appId,
            final String flowId,
            final String runId
    ) {
        final String normalizedAppId = requireRunSegment(appId, "appId");
        final String normalizedFlowId = requireRunSegment(flowId, "flowId");
        final String normalizedRunId = requireRunSegment(runId, "runId");
        final Path runFolder = runsRoot.resolve(normalizedAppId)
                .resolve(normalizedFlowId)
                .resolve(normalizedRunId)
                .normalize();
        if (!runFolder.startsWith(runsRoot)) {
            throw new IllegalArgumentException("Run selector escapes runsRoot: " + normalizedAppId + "/" + normalizedFlowId + "/" + normalizedRunId);
        }
        if (!hasRunArtifacts(runFolder)) {
            return Optional.empty();
        }
        return Optional.of(scanRunFolder(runFolder));
    }

    private static QueryResult queryRuns(
            final Path runsRoot,
            final String appId,
            final String flowId,
            final String outcome,
            final String summaryReadStatus,
            final QueryCursor cursor,
            final int limit
    ) {
        if (!Files.isDirectory(runsRoot)) {
            return new QueryResult(List.of(), false, "", 0);
        }
        final PriorityQueue<RunSnapshot> newestRuns = new PriorityQueue<>(OLDEST_FIRST_PAGE_HEAP_ORDER);
        int totalMatchedCount = 0;
        try (Stream<Path> runFolders = Files.find(
                runsRoot,
                3,
                (path, attributes) -> attributes.isDirectory() && isRunFolder(runsRoot, path) && hasRunArtifacts(path)
        )) {
            for (final Path runFolder : (Iterable<Path>) runFolders::iterator) {
                final RunSnapshot runSnapshot = scanRunFolder(runFolder);
                if (!appId.isBlank() && !appId.equals(runSnapshot.appId())) {
                    continue;
                }
                if (!flowId.isBlank() && !flowId.equals(runSnapshot.flowId())) {
                    continue;
                }
                if (!outcome.isBlank() && !outcome.equals(runSnapshot.outcome())) {
                    continue;
                }
                if (!summaryReadStatus.isBlank() && !summaryReadStatus.equals(runSnapshot.summaryReadStatus())) {
                    continue;
                }
                totalMatchedCount += 1;
                if (cursor.isPresent() && NEWEST_FIRST_RUN_ORDER.compare(runSnapshot.orderKey(), cursor.orderKey()) <= 0) {
                    continue;
                }
                newestRuns.add(runSnapshot);
                if (newestRuns.size() > limit + 1) {
                    newestRuns.remove();
                }
            }
        } catch (final IOException exception) {
            throw new UncheckedIOException(exception);
        }
        final List<RunSnapshot> sortedRuns = newestRuns.stream()
                .sorted(NEWEST_FIRST_RUN_SNAPSHOT_ORDER)
                .toList();
        if (sortedRuns.size() <= limit) {
            return new QueryResult(sortedRuns, false, "", totalMatchedCount);
        }
        final List<RunSnapshot> pageRuns = List.copyOf(sortedRuns.subList(0, limit));
        return new QueryResult(
                pageRuns,
                true,
                encodeQueryCursor(pageRuns.getLast(), appId, flowId, outcome, summaryReadStatus),
                totalMatchedCount
        );
    }

    private static boolean isRunFolder(final Path runsRoot, final Path runFolder) {
        if (!runFolder.normalize().startsWith(runsRoot)) {
            return false;
        }
        return runsRoot.relativize(runFolder.normalize()).getNameCount() == 3;
    }

    private static boolean hasRunArtifacts(final Path runFolder) {
        return Files.isRegularFile(runFolder.resolve("summary.json"))
                || Files.isRegularFile(runFolder.resolve("signals.ndjson"));
    }

    private static boolean isRunSummaryFile(final Path runsRoot, final Path summaryFile) {
        if (!"summary.json".equals(summaryFile.getFileName().toString()) || !summaryFile.normalize().startsWith(runsRoot)) {
            return false;
        }
        final Path relativePath = runsRoot.relativize(summaryFile.normalize());
        return relativePath.getNameCount() == 4
                && "summary.json".equals(relativePath.getName(3).toString());
    }

    private static Instant latestArtifactUpdate(final Path runFolder) throws IOException {
        Instant latest = Instant.EPOCH;
        final Path summaryFile = runFolder.resolve("summary.json");
        final Path signalsFile = runFolder.resolve("signals.ndjson");
        if (Files.isRegularFile(summaryFile)) {
            latest = Files.getLastModifiedTime(summaryFile).toInstant();
        }
        if (Files.isRegularFile(signalsFile)) {
            final Instant signalUpdatedAt = Files.getLastModifiedTime(signalsFile).toInstant();
            if (signalUpdatedAt.isAfter(latest)) {
                latest = signalUpdatedAt;
            }
        }
        return latest.equals(Instant.EPOCH) ? Files.getLastModifiedTime(runFolder).toInstant() : latest;
    }

    private static String inferredRunOutcome(final CreatorRunArtifactSnapshot artifactSnapshot) {
        final String summaryOutcome = stringValue(artifactSnapshot.summary(), "outcome");
        if (!summaryOutcome.isBlank()) {
            return summaryOutcome;
        }
        return artifactSnapshot.timeline().stream()
                .filter(event -> "run.finished".equals(event.get("type")))
                .map(event -> stringValue(event, "outcome"))
                .filter(value -> !value.isBlank())
                .reduce((ignored, latest) -> latest)
                .orElse("");
    }

    private static String inferredReplyMode(final CreatorRunArtifactSnapshot artifactSnapshot) {
        final String summaryReplyMode = stringValue(artifactSnapshot.summary(), "replyMode");
        if (!summaryReplyMode.isBlank()) {
            return summaryReplyMode;
        }
        return artifactSnapshot.timeline().stream()
                .filter(event -> "reply.produced".equals(event.get("type")))
                .map(event -> stringValue(event, "replyMode"))
                .filter(value -> !value.isBlank())
                .reduce((ignored, latest) -> latest)
                .orElse("");
    }

    private static Optional<String> readYamlValue(final Path file, final String key) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            for (final String line : Files.readAllLines(file)) {
                if (line.startsWith(key + ":")) {
                    return Optional.of(line.substring((key + ":").length()).trim());
                }
            }
            return Optional.empty();
        } catch (final IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static List<String> readYamlList(final Path file, final String key) {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            final List<String> lines = Files.readAllLines(file);
            final List<String> values = new java.util.ArrayList<>();
            boolean reading = false;
            for (final String line : lines) {
                final String trimmed = line.trim();
                if (!reading) {
                    if ((key + ":").equals(trimmed)) {
                        reading = true;
                    }
                    continue;
                }
                if (trimmed.startsWith("- ")) {
                    values.add(trimmed.substring(2).trim());
                    continue;
                }
                if (!trimmed.isEmpty()) {
                    break;
                }
            }
            return List.copyOf(values);
        } catch (final IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static Optional<String> readYamlChildValue(final Path file, final String parentKey, final String childKey) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            boolean inParent = false;
            for (final String line : Files.readAllLines(file)) {
                final String trimmed = line.trim();
                if (!inParent) {
                    if ((parentKey + ":").equals(trimmed)) {
                        inParent = true;
                    }
                    continue;
                }
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (!Character.isWhitespace(line.charAt(0))) {
                    break;
                }
                if (trimmed.startsWith(childKey + ":")) {
                    return Optional.of(trimmed.substring((childKey + ":").length()).trim());
                }
            }
            return Optional.empty();
        } catch (final IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static Path requireDirectory(final Path path, final String fieldName) {
        final Path normalized = Objects.requireNonNull(path, fieldName).toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
            throw new IllegalArgumentException(fieldName + " directory not found: " + normalized);
        }
        return normalized;
    }

    private static String stringValue(final Map<String, Object> values, final String key) {
        final Object value = values.get(key);
        return value instanceof String stringValue ? stringValue : "";
    }

    private static boolean booleanValue(final Map<String, Object> values, final String key) {
        final Object value = values.get(key);
        return value instanceof Boolean booleanValue && booleanValue;
    }

    private static int numberValue(final Map<String, Object> values, final String key) {
        final Object value = values.get(key);
        if (value instanceof Number numberValue) {
            return numberValue.intValue();
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(final Map<String, Object> source, final String key) {
        final Object value = source.get(key);
        if (value instanceof List<?> listValue) {
            return (List<Map<String, Object>>) listValue;
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectValue(final Map<String, Object> values, final String key) {
        final Object value = values.get(key);
        if (value instanceof Map<?, ?> mapValue) {
            return (Map<String, Object>) mapValue;
        }
        return Map.of();
    }

    private static boolean isIgnoredPath(final Path path) {
        for (final Path segment : path) {
            final String value = segment.toString();
            if ("target".equals(value) || ".git".equals(value) || ".idea".equals(value) || "node_modules".equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static Path firstSorted(final List<Path> paths) {
        return paths.stream().sorted().findFirst().orElseThrow();
    }

    private static String relativePath(final Path repoRoot, final Path path) {
        return repoRoot.relativize(path).toString();
    }

    private static String requireRunSegment(final String value, final String fieldName) {
        final String normalized = Objects.requireNonNull(value, fieldName).trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        if (".".equals(normalized) || "..".equals(normalized)
                || normalized.contains("/") || normalized.contains("\\")
                || normalized.startsWith("~")) {
            throw new IllegalArgumentException(fieldName + " must be a plain persisted run identifier: " + normalized);
        }
        return normalized;
    }

    private static String optionalRunSegment(final String value, final String fieldName) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return requireRunSegment(value, fieldName);
    }

    private static String optionalFilterValue(final String value, final String fieldName) {
        if (value == null || value.isBlank()) {
            return "";
        }
        final String normalized = value.trim();
        if (normalized.contains("/") || normalized.contains("\\") || normalized.startsWith("~")) {
            throw new IllegalArgumentException(fieldName + " must be a plain exact-match filter value: " + normalized);
        }
        return normalized;
    }

    private static String optionalSummaryReadStatus(final String value) {
        final String normalized = optionalFilterValue(value, "summaryReadStatus");
        if (normalized.isBlank()) {
            return "";
        }
        if ("complete".equals(normalized) || "invalid".equals(normalized)) {
            return normalized;
        }
        if ("missing".equals(normalized)) {
            return normalized;
        }
        throw new IllegalArgumentException("summaryReadStatus must be one of complete, invalid, or missing");
    }

    private static QueryCursor optionalQueryCursor(final String value) {
        if (value == null || value.isBlank()) {
            return QueryCursor.none();
        }
        try {
            final byte[] decoded = Base64.getUrlDecoder().decode(value.trim());
            final Map<String, Object> cursor = KernelContractCodec.parseStableJsonObject(new String(decoded, StandardCharsets.UTF_8));
            if (numberValue(cursor, "v") != 1) {
                throw new IllegalArgumentException("cursor.v must equal 1");
            }
            final Map<String, Object> filters = objectValue(cursor, "filters");
            return new QueryCursor(
                    Instant.parse(requiredCursorString(cursor, "updatedAt")),
                    requireRunSegment(requiredCursorString(cursor, "appId"), "cursor.appId"),
                    requireRunSegment(requiredCursorString(cursor, "flowId"), "cursor.flowId"),
                    requireRunSegment(requiredCursorString(cursor, "runId"), "cursor.runId"),
                    optionalRunSegment(stringValue(filters, "appId"), "cursor.filters.appId"),
                    optionalRunSegment(stringValue(filters, "flowId"), "cursor.filters.flowId"),
                    optionalFilterValue(stringValue(filters, "outcome"), "cursor.filters.outcome"),
                    optionalSummaryReadStatus(stringValue(filters, "summaryReadStatus"))
            );
        } catch (final RuntimeException exception) {
            throw new IllegalArgumentException("cursor must be a valid historical run cursor");
        }
    }

    private static String requiredCursorString(final Map<String, Object> cursor, final String key) {
        final String value = stringValue(cursor, key);
        if (value.isBlank()) {
            throw new IllegalArgumentException("cursor." + key + " must not be blank");
        }
        return value;
    }

    private static String encodeQueryCursor(
            final RunSnapshot runSnapshot,
            final String appId,
            final String flowId,
            final String outcome,
            final String summaryReadStatus
    ) {
        final String serialized = KernelContractCodec.toStableJson(orderedMap(
                "v", 1,
                "updatedAt", runSnapshot.updatedAt().toString(),
                "appId", runSnapshot.appId(),
                "flowId", runSnapshot.flowId(),
                "runId", runSnapshot.runId(),
                "filters", orderedMap(
                        "appId", appId,
                        "flowId", flowId,
                        "outcome", outcome,
                        "summaryReadStatus", summaryReadStatus
                )
        ));
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(serialized.getBytes(StandardCharsets.UTF_8));
    }

    private static int requireLimit(final int value) {
        if (value < 1 || value > 50) {
            throw new IllegalArgumentException("limit must be between 1 and 50");
        }
        return value;
    }

    private static Map<String, Object> toTrafficUiModel(final HttpTrafficPanelQuery.HttpTrafficRow row) {
        return orderedMap(
                "timestamp", row.timestamp().toString(),
                "appId", row.appId(),
                "flowId", row.flowId(),
                "runId", row.runId(),
                "method", row.method(),
                "path", row.path(),
                "status", row.status(),
                "durationMs", row.durationMs(),
                "outcome", row.outcome(),
                "captureKind", row.captureKind()
        );
    }

    private static String summarize(final RuntimeException exception) {
        final String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getName();
        }
        return exception.getClass().getSimpleName() + ": " + message;
    }

    private static String boundedError(final String value) {
        if (value.length() <= MAX_ARTIFACT_ERROR_CHARS) {
            return value;
        }
        return value.substring(0, MAX_ARTIFACT_ERROR_CHARS) + "...";
    }

    private static Map<String, Object> orderedMap(final Object... keyValues) {
        final LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < keyValues.length; index += 2) {
            values.put((String) keyValues[index], keyValues[index + 1]);
        }
        return values;
    }

    private record RunSnapshot(
            String appId,
            String flowId,
            String runId,
            String outcome,
            int executedSteps,
            String replyMode,
            int signalCount,
            String summaryReadStatus,
            String summaryReadError,
            Instant updatedAt,
            Path runFolder,
            Path signalsFile
    ) {
        private RunOrderKey orderKey() {
            return new RunOrderKey(updatedAt, appId, flowId, runId);
        }

        private Map<String, Object> toUiModel() {
            return orderedMap(
                    "appId", appId,
                    "flowId", flowId,
                    "runId", runId,
                    "outcome", outcome,
                    "executedSteps", executedSteps,
                    "replyMode", replyMode,
                    "signalCount", signalCount,
                    "summaryReadStatus", summaryReadStatus,
                    "summaryReadError", summaryReadError,
                    "updatedAt", updatedAt.toString(),
                    "runFolder", runFolder.toString()
            );
        }

        private Map<String, Object> toQueryUiModel() {
            return orderedMap(
                    "appId", appId,
                    "flowId", flowId,
                    "runId", runId,
                    "outcome", outcome,
                    "executedSteps", executedSteps,
                    "replyMode", replyMode,
                    "signalCount", signalCount,
                    "summaryReadStatus", summaryReadStatus,
                    "summaryReadError", summaryReadError,
                    "updatedAt", updatedAt.toString()
            );
        }

        private Map<String, Object> toDetailUiModel() {
            final CreatorRunArtifactSnapshot artifactSnapshot = CreatorRunArtifactSnapshot.capture(runFolder, signalsFile);
            final Map<String, Object> summary = artifactSnapshot.summary();
            return orderedMap(
                    "appId", appId,
                    "flowId", flowId,
                    "runId", runId,
                    "outcome", outcome,
                    "executedSteps", executedSteps,
                    "replyMode", replyMode,
                    "signalCount", artifactSnapshot.signalCount(),
                    "updatedAt", updatedAt.toString(),
                    "runFolder", runFolder.toString(),
                    "latestSignals", artifactSnapshot.latestSignals(),
                    "lastSignalType", artifactSnapshot.lastSignalType(),
                    "timeline", artifactSnapshot.timeline(),
                    "graphActivity", artifactSnapshot.graphActivity(),
                    "contextDiff", artifactSnapshot.contextDiff(),
                    "metrics", artifactSnapshot.metrics(),
                    "audits", artifactSnapshot.audits(),
                    "summary", summary,
                    "terminalFailure", summary.getOrDefault("terminalFailure", Map.of()),
                    "failureSummary", artifactSnapshot.failureSummary(),
                    "summaryReadStatus", artifactSnapshot.summaryReadStatus(),
                    "summaryReadError", artifactSnapshot.summaryReadError(),
                    "signalStreamStatus", artifactSnapshot.signalStreamStatus(),
                    "signalReadError", artifactSnapshot.signalReadError()
            );
        }

        private Map<String, Object> toHistoricalDetailUiModel() {
            final CreatorRunArtifactSnapshot artifactSnapshot = CreatorRunArtifactSnapshot.capture(runFolder, signalsFile);
            final Map<String, Object> summary = artifactSnapshot.summary();
            return orderedMap(
                    "sourceKind", "historical-persisted-run",
                    "appId", appId,
                    "flowId", flowId,
                    "runId", runId,
                    "outcome", outcome,
                    "executedSteps", executedSteps,
                    "replyMode", replyMode,
                    "signalCount", artifactSnapshot.signalCount(),
                    "updatedAt", updatedAt.toString(),
                    "latestSignals", artifactSnapshot.latestSignals(),
                    "lastSignalType", artifactSnapshot.lastSignalType(),
                    "timeline", artifactSnapshot.timeline(),
                    "graphActivity", artifactSnapshot.graphActivity(),
                    "contextDiff", artifactSnapshot.contextDiff(),
                    "metrics", artifactSnapshot.metrics(),
                    "audits", artifactSnapshot.audits(),
                    "summary", summary,
                    "terminalFailure", summary.getOrDefault("terminalFailure", Map.of()),
                    "failureSummary", artifactSnapshot.failureSummary(),
                    "summaryReadStatus", artifactSnapshot.summaryReadStatus(),
                    "summaryReadError", artifactSnapshot.summaryReadError(),
                    "signalStreamStatus", artifactSnapshot.signalStreamStatus(),
                    "signalReadError", artifactSnapshot.signalReadError()
            );
        }
    }

    private record QueryResult(
            List<RunSnapshot> runs,
            boolean hasMore,
            String nextCursor,
            int totalMatchedCount
    ) {}

    private record QueryCursor(
            Instant updatedAt,
            String appId,
            String flowId,
            String runId,
            String filterAppId,
            String filterFlowId,
            String filterOutcome,
            String filterSummaryReadStatus
    ) {
        private static QueryCursor none() {
            return new QueryCursor(Instant.EPOCH, "", "", "", "", "", "", "");
        }

        private boolean isPresent() {
            return !appId.isBlank();
        }

        private boolean matchesFilters(
                final String appId,
                final String flowId,
                final String outcome,
                final String summaryReadStatus
        ) {
            return filterAppId.equals(appId)
                    && filterFlowId.equals(flowId)
                    && filterOutcome.equals(outcome)
                    && filterSummaryReadStatus.equals(summaryReadStatus);
        }

        private RunOrderKey orderKey() {
            return new RunOrderKey(updatedAt, appId, flowId, runId);
        }
    }

    private record RunOrderKey(
            Instant updatedAt,
            String appId,
            String flowId,
            String runId
    ) {}
}
