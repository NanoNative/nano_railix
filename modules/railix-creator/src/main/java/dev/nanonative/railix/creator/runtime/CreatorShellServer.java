package dev.nanonative.railix.creator.runtime;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.nanonative.railix.kernel.model.KernelContractCodec;
import dev.nanonative.railix.kernel.runtime.PackBackedStepResolver;
import dev.nanonative.railix.kernel.runtime.StepProvider;
import dev.nanonative.railix.kernel.runtime.StepProviderCatalog;
import dev.nanonative.railix.kernel.runtime.StepResolver;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * Local static Creator shell plus workspace JSON API.
 */
public final class CreatorShellServer implements AutoCloseable {

    private static final String RESOURCE_ROOT = "/dev/nanonative/railix/creator/runtime/web";

    private final HttpServer server;
    private final ExecutorService executor;
    private final Path repoRoot;
    private final Path runsRoot;
    private final CreatorControlSessionRegistry controlSessions;
    private final CreatorDeferredRemoteExecutionQueue deferredRemoteExecutionQueue;
    private final CreatorInstanceRegistry instanceRegistry;
    private final StepProviderCatalog.Report stepProviderCatalog;
    private final StepResolver stepResolver;

    private CreatorShellServer(
            final HttpServer server,
            final ExecutorService executor,
            final Path repoRoot,
            final Path runsRoot,
            final CreatorControlSessionRegistry controlSessions,
            final CreatorDeferredRemoteExecutionQueue deferredRemoteExecutionQueue,
            final CreatorInstanceRegistry instanceRegistry,
            final StepProviderCatalog.Report stepProviderCatalog,
            final StepResolver stepResolver
    ) {
        this.server = Objects.requireNonNull(server, "server");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.repoRoot = Objects.requireNonNull(repoRoot, "repoRoot");
        this.runsRoot = Objects.requireNonNull(runsRoot, "runsRoot");
        this.controlSessions = Objects.requireNonNull(controlSessions, "controlSessions");
        this.deferredRemoteExecutionQueue = Objects.requireNonNull(deferredRemoteExecutionQueue, "deferredRemoteExecutionQueue");
        this.instanceRegistry = Objects.requireNonNull(instanceRegistry, "instanceRegistry");
        this.stepProviderCatalog = Objects.requireNonNull(stepProviderCatalog, "stepProviderCatalog");
        this.stepResolver = Objects.requireNonNull(stepResolver, "stepResolver");
    }

    /**
     * Starts the Creator shell server on localhost.
     *
     * @param repoRoot repo root to inspect
     * @param runsRoot run root to inspect
     * @param instanceRegistryRoot shared registry root for discovery-only Creator heartbeats
     * @param port requested port, or 0 for ephemeral
     * @return started server
     */
    public static CreatorShellServer start(
            final Path repoRoot,
            final Path runsRoot,
            final Path instanceRegistryRoot,
            final int port
    ) {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port must be between 0 and 65535");
        }
        try {
            final HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
            final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
            server.setExecutor(executor);
            final Path normalizedRepoRoot = repoRoot.toAbsolutePath().normalize();
            final Path normalizedRunsRoot = runsRoot.toAbsolutePath().normalize();
            final CreatorControlSessionRegistry controlSessions = new CreatorControlSessionRegistry(
                    normalizedRunsRoot
            );
            final CreatorDeferredRemoteExecutionQueue deferredRemoteExecutionQueue = new CreatorDeferredRemoteExecutionQueue(
                    normalizedRunsRoot
            );
            final List<StepProvider> installedProviders = PackBackedStepResolver.loadProviders();
            final StepProviderCatalog.Report stepProviderCatalog = StepProviderCatalog.capture(installedProviders);
            final StepResolver stepResolver = new PackBackedStepResolver(installedProviders);
            final CreatorInstanceRegistry instanceRegistry = CreatorInstanceRegistry.start(
                    instanceRegistryRoot.toAbsolutePath().normalize(),
                    normalizedRepoRoot,
                    normalizedRunsRoot,
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"),
                    "creator-owned-live-run-sessions",
                    () -> instanceAdvertisement(normalizedRepoRoot, controlSessions, deferredRemoteExecutionQueue, stepProviderCatalog)
            );
            controlSessions.setStateChangeListener(instanceRegistry::refreshNow);
            deferredRemoteExecutionQueue.setStateChangeListener(instanceRegistry::refreshNow);
            final CreatorShellServer creatorShellServer = new CreatorShellServer(
                    server,
                    executor,
                    normalizedRepoRoot,
                    normalizedRunsRoot,
                    controlSessions,
                    deferredRemoteExecutionQueue,
                    instanceRegistry,
                    stepProviderCatalog,
                    stepResolver
            );
            creatorShellServer.registerContexts();
            server.start();
            return creatorShellServer;
        } catch (final IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * Base URI of the started server.
     *
     * @return local base URI
     */
    public URI baseUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    @Override
    public void close() {
        server.stop(0);
        instanceRegistry.close();
        controlSessions.close();
        deferredRemoteExecutionQueue.close();
        executor.close();
    }

    private void registerContexts() {
        server.createContext("/", exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendStatus(exchange, 405);
                return;
            }
            if (!"/".equals(exchange.getRequestURI().getPath())) {
                sendStatus(exchange, 404);
                return;
            }
            sendResource(exchange, "text/html; charset=utf-8", "index.html");
        });
        server.createContext("/assets/app.css", exchange -> sendAsset(exchange, "text/css; charset=utf-8", "app.css"));
        server.createContext("/assets/app.js", exchange -> sendAsset(exchange, "application/javascript; charset=utf-8", "app.js"));
        server.createContext("/api/workspace", exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendStatus(exchange, 405);
                return;
            }
            final Map<String, Object> snapshot = CreatorWorkspaceSnapshot.capture(
                    repoRoot,
                    runsRoot,
                    controlSessions.snapshot(),
                    instanceRegistry.registryRoot(),
                    instanceRegistry.instanceId()
            );
            sendJson(exchange, snapshot);
        });
        server.createContext("/api/authoring-spec", exchange -> {
            try {
                switch (exchange.getRequestMethod()) {
                    case "GET" -> {
                        final Map<String, String> query = decodeQuery(exchange.getRequestURI());
                        final Map<String, Object> document = CreatorAuthoringSpecStore.read(repoRoot, query.get("path"));
                        sendJson(exchange, document);
                    }
                    case "PUT" -> {
                        final String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                        final Map<String, Object> saved = CreatorAuthoringSpecStore.save(repoRoot, requestBody);
                        sendJson(exchange, saved);
                    }
                    default -> sendStatus(exchange, 405);
                }
            } catch (final IllegalArgumentException exception) {
                sendJson(exchange, 400, Map.of(
                        "message", summarize(exception),
                        "exitCode", 2,
                        "succeeded", false
                ));
            }
        });
        server.createContext("/api/authoring-spec/export", exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendStatus(exchange, 405);
                return;
            }
            try {
                final String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                final Map<String, Object> exported = CreatorAuthoringSpecStore.export(repoRoot, requestBody);
                sendJson(exchange, exported);
            } catch (final IllegalArgumentException exception) {
                sendJson(exchange, 400, Map.of(
                        "message", summarize(exception),
                        "exitCode", 2,
                        "succeeded", false
                ));
            }
        });
        server.createContext("/api/authoring-spec/create", exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendStatus(exchange, 405);
                return;
            }
            try {
                final String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                final Map<String, Object> created = CreatorAuthoringSpecStore.create(repoRoot, requestBody);
                sendJson(exchange, created);
            } catch (final IllegalArgumentException exception) {
                sendJson(exchange, 400, Map.of(
                        "message", summarize(exception),
                        "exitCode", 2,
                        "succeeded", false
                ));
            }
        });
        server.createContext("/api/launch-run", exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendStatus(exchange, 405);
                return;
            }
            try {
                final String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                final Map<String, Object> result = CreatorRunLauncher.launch(requestBody, runsRoot);
                sendJson(exchange, result);
            } catch (final IllegalArgumentException exception) {
                sendJson(exchange, 400, Map.of(
                        "message", summarize(exception),
                        "exitCode", 2,
                        "succeeded", false
                ));
            }
        });
        server.createContext("/api/runs", exchange -> {
            if (!"/api/runs".equals(exchange.getRequestURI().getPath())) {
                sendStatus(exchange, 404);
                return;
            }
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendStatus(exchange, 405);
                return;
            }
            try {
                final Map<String, String> query = decodeQuery(exchange.getRequestURI());
                final Map<String, Object> response = CreatorWorkspaceSnapshot.captureHistoricalRunsQuery(
                        runsRoot,
                        query.get("appId"),
                        query.get("flowId"),
                        query.get("outcome"),
                        query.get("summaryReadStatus"),
                        query.get("cursor"),
                        parseLimit(query.get("limit"))
                );
                sendJson(exchange, response);
            } catch (final IllegalArgumentException exception) {
                sendJson(exchange, 400, Map.of(
                        "message", summarize(exception),
                        "exitCode", 2,
                        "succeeded", false
                ));
            }
        });
        server.createContext("/api/runs/detail", exchange -> {
            if (!"/api/runs/detail".equals(exchange.getRequestURI().getPath())) {
                sendStatus(exchange, 404);
                return;
            }
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendStatus(exchange, 405);
                return;
            }
            try {
                final Map<String, String> query = decodeQuery(exchange.getRequestURI());
                final Map<String, Object> details = CreatorWorkspaceSnapshot.captureHistoricalRunDetails(
                        runsRoot,
                        query.get("appId"),
                        query.get("flowId"),
                        query.get("runId")
                );
                sendJson(exchange, details);
            } catch (final IllegalArgumentException exception) {
                sendJson(exchange, 400, Map.of(
                        "message", summarize(exception),
                        "exitCode", 2,
                        "succeeded", false
                ));
            } catch (final NoSuchElementException exception) {
                sendJson(exchange, 404, Map.of(
                        "message", summarize(exception),
                        "exitCode", 1,
                        "succeeded", false
                ));
            }
        });
        server.createContext("/api/control/sessions", exchange -> {
            try {
                switch (exchange.getRequestMethod()) {
                    case "GET" -> sendJson(exchange, controlSessions.snapshot());
                    case "POST" -> {
                        final String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                        final Map<String, Object> started = controlSessions.start(requestBody);
                        sendJson(exchange, 202, started);
                    }
                    default -> sendStatus(exchange, 405);
                }
            } catch (final IllegalArgumentException exception) {
                sendJson(exchange, 400, Map.of(
                        "message", summarize(exception),
                        "exitCode", 2,
                        "succeeded", false
                ));
            } catch (final IllegalStateException exception) {
                sendJson(exchange, 409, Map.of(
                        "message", summarize(exception),
                        "exitCode", 1,
                        "succeeded", false
                ));
            }
        });
        server.createContext("/api/remote-execution/requests", exchange -> {
            try {
                switch (exchange.getRequestMethod()) {
                    case "GET" -> {
                        final Map<String, String> query = decodeQuery(exchange.getRequestURI());
                        final Map<String, Object> response = CreatorRemoteExecutionRequestStore.capture(
                                runsRoot,
                                parseLimit(query.get("limit"))
                        );
                        sendJson(exchange, response);
                    }
                    case "POST" -> {
                        final Instant receivedAt = Instant.now();
                        final String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                        final CreatorRemoteExecutionBoundary.BoundaryResponse response;
                        final Map<String, Object> instancesSnapshot = CreatorInstanceRegistry.capture(
                                instanceRegistry.registryRoot(),
                                instanceRegistry.instanceId()
                        );
                        try {
                            response = CreatorRemoteExecutionBoundary.evaluate(
                                    requestBody,
                                    instanceRegistry.instanceId(),
                                    stepProviderCatalog,
                                    stepResolver,
                                    instancesSnapshot,
                                    runsRoot,
                                    deferredRemoteExecutionQueue.canAccept()
                            );
                        } catch (final IllegalArgumentException exception) {
                            final CreatorRemoteExecutionBoundary.BoundaryResponse invalidResponse =
                                    CreatorRemoteExecutionBoundary.invalidRequest(exception);
                            CreatorRemoteExecutionRequestStore.record(
                                    runsRoot,
                                    requestBody,
                                    invalidResponse.status(),
                                    invalidResponse.body(),
                                    receivedAt,
                                    Instant.now()
                            );
                            sendJson(exchange, invalidResponse.status(), invalidResponse.body());
                            return;
                        }
                        if (response.deferredExecution() != null) {
                            try {
                                final Map<String, Object> accepted = deferredRemoteExecutionQueue.submit(
                                        requestBody,
                                        response,
                                        receivedAt
                                );
                                sendJson(exchange, response.status(), accepted);
                            } catch (final IllegalStateException exception) {
                                final CreatorRemoteExecutionBoundary.BoundaryResponse queueUnavailable =
                                        CreatorRemoteExecutionBoundary.deferredQueueUnavailable(
                                                requestBody,
                                                instanceRegistry.instanceId(),
                                                stepProviderCatalog,
                                                summarize(exception)
                                        );
                                CreatorRemoteExecutionRequestStore.record(
                                        runsRoot,
                                        requestBody,
                                        queueUnavailable.status(),
                                        queueUnavailable.body(),
                                        receivedAt,
                                        Instant.now()
                                );
                                sendJson(exchange, queueUnavailable.status(), queueUnavailable.body());
                            }
                            return;
                        }
                        CreatorRemoteExecutionRequestStore.record(
                                runsRoot,
                                requestBody,
                                response.status(),
                                response.body(),
                                receivedAt,
                                Instant.now()
                        );
                        sendJson(exchange, response.status(), response.body());
                    }
                    default -> sendStatus(exchange, 405);
                }
            } catch (final IllegalArgumentException exception) {
                sendJson(exchange, 400, Map.of(
                        "message", summarize(exception),
                        "exitCode", 2,
                        "succeeded", false
                ));
            } catch (final RuntimeException exception) {
                sendJson(exchange, 500, Map.of(
                        "message", summarize(exception),
                        "exitCode", 1,
                        "succeeded", false
                ));
            }
        });
        server.createContext("/api/remote-execution/preflight", exchange -> {
            try {
                switch (exchange.getRequestMethod()) {
                    case "POST" -> {
                        final String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                        final Map<String, Object> instancesSnapshot = CreatorInstanceRegistry.capture(
                                instanceRegistry.registryRoot(),
                                instanceRegistry.instanceId()
                        );
                        final CreatorRemoteExecutionBoundary.BoundaryResponse response;
                        try {
                            response = CreatorRemoteExecutionBoundary.preflight(
                                    requestBody,
                                    instanceRegistry.instanceId(),
                                    stepProviderCatalog,
                                    stepResolver,
                                    instancesSnapshot,
                                    deferredRemoteExecutionQueue.canAccept()
                            );
                        } catch (final IllegalArgumentException exception) {
                            final CreatorRemoteExecutionBoundary.BoundaryResponse invalidResponse =
                                    CreatorRemoteExecutionBoundary.invalidPreflightRequest(exception);
                            sendJson(exchange, invalidResponse.status(), invalidResponse.body());
                            return;
                        }
                        sendJson(exchange, response.status(), response.body());
                    }
                    default -> sendStatus(exchange, 405);
                }
            } catch (final IllegalArgumentException exception) {
                sendJson(exchange, 400, Map.of(
                        "message", summarize(exception),
                        "exitCode", 2,
                        "succeeded", false
                ));
            } catch (final RuntimeException exception) {
                sendJson(exchange, 500, Map.of(
                        "message", summarize(exception),
                        "exitCode", 1,
                        "succeeded", false
                ));
            }
        });
    }

    private static Map<String, Object> instanceAdvertisement(
            final Path repoRoot,
            final CreatorControlSessionRegistry controlSessions,
            final CreatorDeferredRemoteExecutionQueue deferredRemoteExecutionQueue,
            final StepProviderCatalog.Report stepProviderCatalog
    ) {
        final Map<String, Object> controlSnapshot = controlSessions.snapshot();
        final Map<String, Object> deferredSnapshot = deferredRemoteExecutionQueue.snapshot();
        return orderedMap(
                "stepProviders", stepProviderCatalog.toUiModel(),
                "runtimeIdentity", stepProviderCatalog.toRuntimeIdentityModel(),
                "remoteExecutionBoundary", CreatorRemoteExecutionBoundary.advertisement(),
                "workspaceRuntimeAlignment", workspaceRuntimeAlignment(repoRoot, stepProviderCatalog),
                "trust", orderedMap(
                        "mode", "loopback-only",
                        "sharedRegistryDiscovery", true,
                        "remoteControlSupported", false,
                        "remoteExecutionSupported", false
                ),
                "executionModel", orderedMap(
                        "localStepExecutionSupported", true,
                        "remoteStepExecutionSupported", false,
                        "queuedStepExecutionSupported", true,
                        "crossInstancePermissionSharingSupported", false,
                        "cacheShareSupported", false
                ),
                "distributedSupport", orderedMap(
                        "status", "foundation-only",
                        "runtimePackIdentitySupported", false,
                        "remoteExecutionBoundarySupported", true,
                        "distributedQueueSupported", false,
                        "crossInstancePermissionPropagationSupported", false,
                        "sharedCacheDigestSupported", false
                ),
                "load", orderedMap(
                        "activeControlSessions", intValue(controlSnapshot, "activeCount"),
                        "maxActiveSessions", intValue(controlSnapshot, "maxActiveSessions"),
                        "activeDeferredRemoteRequests", intValue(deferredSnapshot, "activeCount"),
                        "maxActiveDeferredRemoteRequests", intValue(deferredSnapshot, "maxActiveRequests")
                ),
                "runtime", orderedMap(
                        "osName", System.getProperty("os.name", ""),
                        "osArch", System.getProperty("os.arch", ""),
                        "javaVersion", System.getProperty("java.version", "")
                )
        );
    }

    private static Map<String, Object> workspaceRuntimeAlignment(
            final Path repoRoot,
            final StepProviderCatalog.Report stepProviderCatalog
    ) {
        final List<String> workspacePackIds = scanWorkspacePackIds(repoRoot.resolve("packs"));
        final LinkedHashSet<String> runtimeModuleIds = new LinkedHashSet<>(stepProviderCatalog.providerModuleIds());
        final List<String> matchedPackIds = workspacePackIds.stream()
                .filter(runtimeModuleIds::contains)
                .toList();
        final List<String> workspaceOnlyPackIds = workspacePackIds.stream()
                .filter(packId -> !runtimeModuleIds.contains(packId))
                .toList();
        final LinkedHashSet<String> workspacePackSet = new LinkedHashSet<>(workspacePackIds);
        final List<String> runtimeOnlyModuleIds = stepProviderCatalog.providerModuleIds().stream()
                .filter(moduleId -> !workspacePackSet.contains(moduleId))
                .toList();
        return orderedMap(
                "workspacePackCount", workspacePackIds.size(),
                "runtimeProviderModuleCount", stepProviderCatalog.providerModuleCount(),
                "matchedPackCount", matchedPackIds.size(),
                "workspaceOnlyPackCount", workspaceOnlyPackIds.size(),
                "runtimeOnlyModuleCount", runtimeOnlyModuleIds.size(),
                "fullyAligned", workspaceOnlyPackIds.isEmpty() && runtimeOnlyModuleIds.isEmpty(),
                "workspacePackIds", workspacePackIds,
                "runtimeProviderModuleIds", stepProviderCatalog.providerModuleIds(),
                "matchedPackIds", matchedPackIds,
                "workspaceOnlyPackIds", workspaceOnlyPackIds,
                "runtimeOnlyModuleIds", runtimeOnlyModuleIds
        );
    }

    private static List<String> scanWorkspacePackIds(final Path packsRoot) {
        if (!Files.isDirectory(packsRoot)) {
            return List.of();
        }
        try (Stream<Path> packDirs = Files.list(packsRoot)) {
            return packDirs
                    .filter(Files::isDirectory)
                    .map(packDir -> readYamlValue(packDir.resolve("pack.yaml"), "id")
                            .orElse(packDir.getFileName().toString()))
                    .sorted()
                    .toList();
        } catch (final IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static java.util.Optional<String> readYamlValue(final Path file, final String key) {
        if (!Files.isRegularFile(file)) {
            return java.util.Optional.empty();
        }
        try {
            for (final String line : Files.readAllLines(file)) {
                if (line.startsWith(key + ":")) {
                    return java.util.Optional.of(line.substring((key + ":").length()).trim());
                }
            }
            return java.util.Optional.empty();
        } catch (final IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static int intValue(final Map<String, Object> values, final String key) {
        final Object value = values.get(key);
        return value instanceof Number numberValue ? numberValue.intValue() : 0;
    }

    private static Map<String, Object> orderedMap(final Object... keyValues) {
        final LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < keyValues.length; index += 2) {
            values.put((String) keyValues[index], keyValues[index + 1]);
        }
        return values;
    }

    private void sendAsset(final HttpExchange exchange, final String contentType, final String resourceName) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendStatus(exchange, 405);
            return;
        }
        sendResource(exchange, contentType, resourceName);
    }

    private void sendResource(final HttpExchange exchange, final String contentType, final String resourceName) throws IOException {
        final String resourcePath = RESOURCE_ROOT + "/" + resourceName;
        try (InputStream inputStream = CreatorShellServer.class.getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                sendStatus(exchange, 404);
                return;
            }
            final byte[] body = inputStream.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        } finally {
            exchange.close();
        }
    }

    private void sendJson(final HttpExchange exchange, final Map<String, Object> body) throws IOException {
        sendJson(exchange, 200, body);
    }

    private void sendJson(final HttpExchange exchange, final int status, final Map<String, Object> body) throws IOException {
        final byte[] payload = KernelContractCodec.toStableJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }

    private void sendStatus(final HttpExchange exchange, final int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    private static String summarize(final RuntimeException exception) {
        final String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getName();
        }
        return exception.getClass().getSimpleName() + ": " + message;
    }

    private static Map<String, String> decodeQuery(final URI uri) {
        final String rawQuery = uri.getRawQuery();
        if (rawQuery == null || rawQuery.isBlank()) {
            return Map.of();
        }
        final LinkedHashMap<String, String> values = new LinkedHashMap<>();
        for (final String pair : rawQuery.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            final int separatorIndex = pair.indexOf('=');
            final String rawKey = separatorIndex >= 0 ? pair.substring(0, separatorIndex) : pair;
            final String rawValue = separatorIndex >= 0 ? pair.substring(separatorIndex + 1) : "";
            values.put(
                    URLDecoder.decode(rawKey, StandardCharsets.UTF_8),
                    URLDecoder.decode(rawValue, StandardCharsets.UTF_8)
            );
        }
        return Map.copyOf(values);
    }

    private static int parseLimit(final String value) {
        if (value == null || value.isBlank()) {
            return 20;
        }
        try {
            return Integer.parseInt(value);
        } catch (final NumberFormatException exception) {
            throw new IllegalArgumentException("limit must be an integer between 1 and 50");
        }
    }
}
