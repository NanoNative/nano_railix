package dev.nanonative.railix.creator.runtime;

import dev.nanonative.railix.kernel.model.KernelContractCodec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Maintains a shared-filesystem heartbeat registry for live Creator shells.
 */
public final class CreatorInstanceRegistry implements AutoCloseable {

    static final int DEFAULT_TTL_SECONDS = 15;
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(5);
    private static final Comparator<Map<String, Object>> INSTANCE_ORDER = Comparator
            .comparing((Map<String, Object> entry) -> !"active".equals(stringValue(entry, "state")))
            .thenComparing((Map<String, Object> entry) -> stringValue(entry, "heartbeatAt"), Comparator.reverseOrder())
            .thenComparing(entry -> stringValue(entry, "instanceId"));

    private final ScheduledExecutorService executor;
    private final Path registryRoot;
    private final Path repoRoot;
    private final Path runsRoot;
    private final String instanceId;
    private final String creatorState;
    private final String loopbackControlUrl;
    private final Supplier<Map<String, Object>> advertisementSupplier;
    private final AtomicBoolean closed;

    private CreatorInstanceRegistry(
            final ScheduledExecutorService executor,
            final Path registryRoot,
            final Path repoRoot,
            final Path runsRoot,
            final String instanceId,
            final String creatorState,
            final String loopbackControlUrl,
            final Supplier<Map<String, Object>> advertisementSupplier
    ) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.registryRoot = Objects.requireNonNull(registryRoot, "registryRoot").toAbsolutePath().normalize();
        this.repoRoot = Objects.requireNonNull(repoRoot, "repoRoot").toAbsolutePath().normalize();
        this.runsRoot = Objects.requireNonNull(runsRoot, "runsRoot").toAbsolutePath().normalize();
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
        this.creatorState = Objects.requireNonNull(creatorState, "creatorState");
        this.loopbackControlUrl = Objects.requireNonNull(loopbackControlUrl, "loopbackControlUrl");
        this.advertisementSupplier = Objects.requireNonNull(advertisementSupplier, "advertisementSupplier");
        this.closed = new AtomicBoolean(false);
    }

    public static CreatorInstanceRegistry start(
            final Path registryRoot,
            final Path repoRoot,
            final Path runsRoot,
            final URI loopbackControlUrl,
            final String creatorState,
            final Supplier<Map<String, Object>> advertisementSupplier
    ) {
        final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
        final CreatorInstanceRegistry registry = new CreatorInstanceRegistry(
                executor,
                registryRoot,
                repoRoot,
                runsRoot,
                "creator-instance-" + java.util.UUID.randomUUID(),
                creatorState,
                Objects.requireNonNull(loopbackControlUrl, "loopbackControlUrl").toString(),
                advertisementSupplier
        );
        registry.writeHeartbeat();
        executor.scheduleAtFixedRate(
                registry::writeHeartbeatSafely,
                HEARTBEAT_INTERVAL.toSeconds(),
                HEARTBEAT_INTERVAL.toSeconds(),
                TimeUnit.SECONDS
        );
        return registry;
    }

    public Path registryRoot() {
        return registryRoot;
    }

    public String instanceId() {
        return instanceId;
    }

    public void refreshNow() {
        writeHeartbeatSafely();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        executor.shutdownNow();
        try {
            Files.deleteIfExists(entryPath());
        } catch (final IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public static Map<String, Object> capture(final Path registryRoot, final String currentInstanceId) {
        final Path normalizedRegistryRoot = Objects.requireNonNull(registryRoot, "registryRoot").toAbsolutePath().normalize();
        final String normalizedCurrentInstanceId = Objects.requireNonNull(currentInstanceId, "currentInstanceId");
        final List<Map<String, Object>> entries = readEntries(normalizedRegistryRoot, normalizedCurrentInstanceId);
        final long activeCount = entries.stream().filter(entry -> "active".equals(stringValue(entry, "state"))).count();
        final long staleCount = entries.stream().filter(entry -> "stale".equals(stringValue(entry, "state"))).count();
        return orderedMap(
                "title", "Instance Discovery",
                "status", "shared-filesystem-instance-registry",
                "registryRoot", normalizedRegistryRoot.toString(),
                "currentInstanceId", normalizedCurrentInstanceId,
                "totalCount", entries.size(),
                "activeCount", Math.toIntExact(activeCount),
                "staleCount", Math.toIntExact(staleCount),
                "entries", entries
        );
    }

    private static List<Map<String, Object>> readEntries(final Path registryRoot, final String currentInstanceId) {
        if (!Files.isDirectory(registryRoot)) {
            return List.of();
        }
        final Instant now = Instant.now();
        try (Stream<Path> files = Files.list(registryRoot)) {
            return files
                    .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".json"))
                    .map(path -> readEntry(path, currentInstanceId, now))
                    .sorted(INSTANCE_ORDER)
                    .toList();
        } catch (final IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static Map<String, Object> readEntry(final Path path, final String currentInstanceId, final Instant now) {
        final Map<String, Object> values = KernelContractCodec.parseStableJsonObject(readString(path));
        final String instanceId = stringValue(values, "instanceId");
        final String heartbeatAt = stringValue(values, "heartbeatAt");
        final String expiresAt = stringValue(values, "expiresAt");
        final boolean active = isActive(expiresAt, now);
        return orderedMap(
                "instanceId", instanceId,
                "heartbeatAt", heartbeatAt,
                "expiresAt", expiresAt,
                "ttlSeconds", integerValue(values, "ttlSeconds"),
                "creatorState", stringValue(values, "creatorState"),
                "repoRoot", stringValue(values, "repoRoot"),
                "runsRoot", stringValue(values, "runsRoot"),
                "loopbackControlUrl", stringValue(values, "loopbackControlUrl"),
                "stepProviders", mapValue(values, "stepProviders"),
                "runtimeIdentity", mapValue(values, "runtimeIdentity"),
                "remoteExecutionBoundary", mapValue(values, "remoteExecutionBoundary"),
                "workspaceRuntimeAlignment", mapValue(values, "workspaceRuntimeAlignment"),
                "trust", mapValue(values, "trust"),
                "executionModel", mapValue(values, "executionModel"),
                "distributedSupport", mapValue(values, "distributedSupport"),
                "load", mapValue(values, "load"),
                "runtime", mapValue(values, "runtime"),
                "state", active ? "active" : "stale",
                "currentInstance", instanceId.equals(currentInstanceId)
        );
    }

    private static boolean isActive(final String expiresAt, final Instant now) {
        if (expiresAt.isBlank()) {
            return false;
        }
        return !Instant.parse(expiresAt).isBefore(now);
    }

    private void writeHeartbeatSafely() {
        if (closed.get()) {
            return;
        }
        writeHeartbeat();
    }

    private void writeHeartbeat() {
        try {
            Files.createDirectories(registryRoot);
            final Instant heartbeatAt = Instant.now();
            final Path tempFile = registryRoot.resolve(instanceId + "-" + java.util.UUID.randomUUID() + ".json.tmp");
            final Map<String, Object> contents = orderedMap(
                    "schemaVersion", 1,
                    "instanceId", instanceId,
                    "heartbeatAt", heartbeatAt.toString(),
                    "expiresAt", heartbeatAt.plusSeconds(DEFAULT_TTL_SECONDS).toString(),
                    "ttlSeconds", DEFAULT_TTL_SECONDS,
                    "creatorState", creatorState,
                    "repoRoot", repoRoot.toString(),
                    "runsRoot", runsRoot.toString(),
                    "loopbackControlUrl", loopbackControlUrl
            );
            contents.putAll(Map.copyOf(advertisementSupplier.get()));
            Files.writeString(tempFile, KernelContractCodec.toStableJson(contents));
            moveAtomically(tempFile, entryPath());
        } catch (final IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private Path entryPath() {
        return registryRoot.resolve(instanceId + ".json");
    }

    private static void moveAtomically(final Path source, final Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (final AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String readString(final Path path) {
        try {
            return Files.readString(path);
        } catch (final IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static String stringValue(final Map<String, Object> values, final String key) {
        final Object value = values.get(key);
        return value instanceof String stringValue ? stringValue : "";
    }

    private static int integerValue(final Map<String, Object> values, final String key) {
        final Object value = values.get(key);
        return value instanceof Number numberValue ? numberValue.intValue() : 0;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(final Map<String, Object> values, final String key) {
        final Object value = values.get(key);
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    private static Map<String, Object> orderedMap(final Object... keyValues) {
        final LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < keyValues.length; index += 2) {
            values.put((String) keyValues[index], keyValues[index + 1]);
        }
        return values;
    }
}
