package dev.nanonative.railix.creator;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.nanonative.railix.core.project.CompileResult;
import dev.nanonative.railix.core.project.Diagnostic;
import dev.nanonative.railix.core.project.ProjectCompiler;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepContractJson;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.stdlib.StandardLibrary;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Creator HTTP server, project workspace, and rolling development-application owner. */
public final class CreatorServer implements AutoCloseable {
    static final int MAX_CONCURRENT_REQUESTS = 64;
    static final int MAX_CONCURRENT_FORWARDS = 32;
    static final int MAX_CONCURRENT_EXAMPLE_RESPONSES = 4;
    private static final String WEB_ROOT = "/dev/nanonative/railix/creator/web/";
    private static final int MAX_PROJECT_BYTES = RailixData.DEFAULT_MAX_SOURCE_BYTES;
    private static final int MAX_SCENE_BYTES = 2 * RailixData.DEFAULT_MAX_SOURCE_BYTES;
    private static final Duration BODY_READ_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration RESPONSE_DRAIN_TIMEOUT = Duration.ofSeconds(1);
    private static final String TOKEN_HEADER = "X-Railix-Creator-Token";
    private static final String[] PROJECT_PREFIXES = {
            "atomic", "brisk", "cosmic", "eager", "lunar", "neon",
            "quiet", "rapid", "solar", "steady", "tiny", "vivid"
    };
    private static final String[] PROJECT_SUBJECTS = {
            "byte", "cache", "flux", "kernel", "pixel", "quark",
            "signal", "stack", "thread", "vector", "voxel", "wave"
    };
    private static final String[] PROJECT_OBJECTS = {
            "array", "forge", "grid", "node", "relay", "rig",
            "socket", "spark", "switch", "vault", "wire", "yard"
    };
    private final HttpServer server;
    private final ExecutorService executor;
    private final ScheduledExecutorService bodyDeadlines;
    private final StepCatalog catalog;
    private final IconLibrary icons;
    private final Path projectFile;
    private final Path creatorFile;
    private final ProjectLease lease;
    private final Object applicationLock = new Object();
    private final Object sceneLock = new Object();
    private final Object buildLock = new Object();
    private final Object closeLock = new Object();
    private final CountDownLatch closed = new CountDownLatch(1);
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final Semaphore requests = new Semaphore(MAX_CONCURRENT_REQUESTS);
    private final Semaphore forwarding = new Semaphore(MAX_CONCURRENT_FORWARDS);
    private final Semaphore exampleResponses = new Semaphore(MAX_CONCURRENT_EXAMPLE_RESPONSES);
    private final Semaphore sceneObservationResponses = new Semaphore(2);
    private final String creatorToken;
    private DevelopmentApplication application;
    private DevelopmentApplication retirement;
    private boolean retirementDeletesArtifact;
    private String retirementPhase = "";
    private boolean serverClosed;
    private boolean executorShutdown;
    private boolean executorClosed;
    private boolean bodyDeadlinesShutdown;
    private boolean bodyDeadlinesClosed;
    private boolean applicationClosed;
    private boolean leaseClosed;
    private String source;
    // Persistence precedes activation, so canonical source can require a different artifact than the running child.
    private String sourceArtifactKey;
    private RailixValue.ObjectValue creatorValue;
    private List<Diagnostic> creatorDiagnostics;
    private long nextGeneration;
    private long projectRevision;
    private long sourceRevision;
    private long creatorRevision;
    private boolean deploymentPending;
    private CreatorScene scene;
    private CreatorEditor editor;

    private CreatorServer(
            final HttpServer server,
            final ExecutorService executor,
            final ScheduledExecutorService bodyDeadlines,
            final StepCatalog catalog,
            final IconLibrary icons,
            final Path projectFile,
            final Path creatorFile,
            final ProjectLease lease,
            final DevelopmentApplication application,
            final String source,
            final RailixValue.ObjectValue creatorValue,
            final List<Diagnostic> creatorDiagnostics,
            final long nextGeneration,
            final String creatorToken
    ) {
        this.server = server;
        this.executor = executor;
        this.bodyDeadlines = bodyDeadlines;
        this.catalog = catalog;
        this.icons = icons;
        this.projectFile = projectFile;
        this.creatorFile = creatorFile;
        this.lease = lease;
        this.application = application;
        this.source = source;
        this.sourceArtifactKey = application.artifact().directory().getFileName().toString();
        this.creatorValue = creatorValue;
        this.creatorDiagnostics = List.copyOf(creatorDiagnostics);
        this.nextGeneration = nextGeneration;
        this.creatorToken = creatorToken;
    }

    public static CreatorServer start(final int port, final Path projectFile) throws IOException {
        return start(port, projectFile, Path.of(System.getProperty("user.home"), ".railix"));
    }

    static CreatorServer start(final int port, final Path projectFile, final Path railixHome) throws IOException {
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("Creator port must be from 0 through 65535.");
        }
        if (projectFile == null) {
            throw new IllegalArgumentException("Creator project file cannot be Java null.");
        }
        if (railixHome == null) {
            throw new IllegalArgumentException("Railix home cannot be Java null.");
        }
        final Path absoluteProject = projectFile.toAbsolutePath().normalize();
        final Path dependencyLock = absoluteProject.resolveSibling("railix.dependencies.lock.json");
        final StepCatalog catalog = Files.exists(dependencyLock)
                ? StandardLibrary.catalog().install(dependencyLock, railixHome.resolve("artifacts"))
                : StandardLibrary.catalog();
        final IconLibrary icons = new IconLibrary(railixHome);
        final String requested = Files.exists(absoluteProject)
                ? readProject(absoluteProject)
                : defaultProject(absoluteProject);
        final CompileResult result = ProjectCompiler.compileApplication(requested, catalog);
        if (result instanceof CompileResult.Rejected rejected) {
            final Diagnostic diagnostic = rejected.diagnostics().getFirst();
            throw new IOException(
                    "Cannot open Creator project: " + diagnostic.code() + " "
                            + diagnostic.path() + " " + diagnostic.message()
            );
        }
        final CompileResult.Compiled compiled = (CompileResult.Compiled) result;
        final String canonical = compiled.source();
        final Path creatorFile = absoluteProject.resolveSibling("railix.creator.json");
        final boolean creatorExists = Files.exists(creatorFile);
        final String requestedCreator = creatorExists
                ? readProject(creatorFile)
                : CreatorDocument.EMPTY;
        final CreatorDocument.Result requestedCreatorResult = CreatorDocument.parse(
                requestedCreator,
                canonical,
                catalog
        );
        final CreatorDocument.Result creatorResult = requestedCreatorResult.diagnostics().isEmpty()
                ? requestedCreatorResult
                : CreatorDocument.parse(CreatorDocument.EMPTY, canonical, catalog);
        final ProjectLease lease = ProjectLease.acquire(absoluteProject);
        final HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        } catch (final IOException | RuntimeException exception) {
            try {
                lease.close();
            } catch (final RuntimeException cleanup) {
                exception.addSuppressed(cleanup);
            }
            throw exception;
        }
        DevelopmentApplication application = null;
        ExecutorService executor = null;
        ScheduledExecutorService bodyDeadlines = null;
        try {
            persist(absoluteProject, canonical);
            if (!creatorExists || requestedCreatorResult.diagnostics().isEmpty()) {
                persist(creatorFile, creatorResult.source());
            }
            application = DevelopmentApplication.start(1, absoluteProject, compiled);
            application.activate();
            executor = Executors.newVirtualThreadPerTaskExecutor();
            bodyDeadlines = Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon().name("railix-creator-body-deadline-", 0).factory()
            );
            final CreatorServer creator = new CreatorServer(
                    server,
                    executor,
                    bodyDeadlines,
                    catalog,
                    icons,
                    absoluteProject,
                    creatorFile,
                    lease,
                    application,
                    canonical,
                    creatorResult.value(),
                    requestedCreatorResult.diagnostics(),
                    2,
                    token()
            );
            server.createContext("/", creator::handle);
            server.setExecutor(executor);
            server.start();
            return creator;
        } catch (final IOException | RuntimeException | Error exception) {
            try {
                server.stop(0);
            } catch (final RuntimeException cleanup) {
                exception.addSuppressed(cleanup);
            }
            if (executor != null) {
                try {
                    executor.shutdownNow();
                } catch (final RuntimeException cleanup) {
                    exception.addSuppressed(cleanup);
                }
                try {
                    executor.close();
                } catch (final RuntimeException cleanup) {
                    exception.addSuppressed(cleanup);
                }
            }
            if (bodyDeadlines != null) {
                try {
                    bodyDeadlines.shutdownNow();
                } catch (final RuntimeException cleanup) {
                    exception.addSuppressed(cleanup);
                }
                try {
                    bodyDeadlines.close();
                } catch (final RuntimeException cleanup) {
                    exception.addSuppressed(cleanup);
                }
            }
            if (application != null) {
                boolean stopped = false;
                RuntimeException closeFailure = null;
                for (int attempt = 0; attempt < 2 && !stopped; attempt++) {
                    try {
                        application.close();
                        stopped = true;
                    } catch (final RuntimeException cleanup) {
                        closeFailure = closeFailure == null ? cleanup : merge(closeFailure, cleanup);
                    }
                }
                if (!stopped && closeFailure != null) {
                    exception.addSuppressed(closeFailure);
                }
                if (stopped && !application.artifact().reused()) {
                    try {
                        ApplicationBuilder.delete(application.artifact());
                    } catch (final IOException cleanup) {
                        exception.addSuppressed(cleanup);
                    }
                }
            }
            try {
                lease.close();
            } catch (final RuntimeException cleanup) {
                exception.addSuppressed(cleanup);
            }
            throw exception;
        }
    }

    public URI baseUri() {
        return URI.create(origin() + "/#token=" + creatorToken);
    }

    public CreatorServer awaitClose() throws InterruptedException {
        closed.await();
        return this;
    }

    @Override
    public void close() {
        boolean interrupted = Thread.interrupted();
        RuntimeException failure = null;
        synchronized (closeLock) {
            if (closed.getCount() == 0) {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                return;
            }
            open.set(false);
            synchronized (buildLock) {
                if (!applicationClosed) {
                    try {
                        application.close();
                        applicationClosed = true;
                    } catch (final RuntimeException exception) {
                        failure = merge(failure, exception);
                    }
                }
                final String phase = cleanupRetirement();
                if (!phase.isEmpty()) {
                    failure = merge(failure, new IllegalStateException(
                            "Retired generated application did not clean up: " + phase + "."
                    ));
                }
            }
            interrupted |= Thread.interrupted();
            if (!serverClosed) {
                boolean responsesDrained = false;
                try {
                    responsesDrained = exampleResponses.tryAcquire(
                            MAX_CONCURRENT_EXAMPLE_RESPONSES,
                            RESPONSE_DRAIN_TIMEOUT.toNanos(),
                            TimeUnit.NANOSECONDS
                    );
                } catch (final InterruptedException exception) {
                    interrupted = true;
                }
                try {
                    server.stop(0);
                    serverClosed = true;
                } catch (final RuntimeException exception) {
                    failure = merge(failure, exception);
                } finally {
                    if (responsesDrained) {
                        exampleResponses.release(MAX_CONCURRENT_EXAMPLE_RESPONSES);
                    }
                }
            }
            interrupted |= Thread.interrupted();
            if (!executorShutdown) {
                try {
                    executor.shutdownNow();
                    executorShutdown = true;
                } catch (final RuntimeException exception) {
                    failure = merge(failure, exception);
                }
            }
            if (!executorClosed) {
                try {
                    executor.close();
                    executorClosed = true;
                } catch (final RuntimeException exception) {
                    failure = merge(failure, exception);
                }
            }
            if (!bodyDeadlinesShutdown) {
                try {
                    bodyDeadlines.shutdownNow();
                    bodyDeadlinesShutdown = true;
                } catch (final RuntimeException exception) {
                    failure = merge(failure, exception);
                }
            }
            if (!bodyDeadlinesClosed) {
                try {
                    bodyDeadlines.close();
                    bodyDeadlinesClosed = true;
                } catch (final RuntimeException exception) {
                    failure = merge(failure, exception);
                }
            }
            interrupted |= Thread.interrupted();
            if (!leaseClosed) {
                try {
                    lease.close();
                    leaseClosed = true;
                } catch (final RuntimeException exception) {
                    failure = merge(failure, exception);
                }
            }
            synchronized (applicationLock) {
                if (serverClosed
                        && executorShutdown
                        && executorClosed
                        && bodyDeadlinesShutdown
                        && bodyDeadlinesClosed
                        && applicationClosed
                        && retirement == null
                        && leaseClosed) {
                    closed.countDown();
                } else if (failure == null) {
                    failure = new IllegalStateException("Creator ownership cleanup did not complete.");
                }
            }
        }
        interrupted |= Thread.interrupted();
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static RuntimeException merge(
            final RuntimeException failure,
            final RuntimeException next
    ) {
        if (failure == null) {
            return next;
        }
        if (failure != next) {
            failure.addSuppressed(next);
        }
        return failure;
    }

    private void handle(final HttpExchange exchange) {
        try (exchange) {
            if (!open.get()) {
                sendSafely(exchange, unavailable("closing"));
                return;
            }
            if (!requests.tryAcquire()) {
                sendSafely(exchange, unavailable("request-saturated"));
                return;
            }
            try {
                if (!open.get()) {
                    sendSafely(exchange, unavailable("closing"));
                    return;
                }
                final Response response = route(exchange);
                if (!response.committed()) {
                    send(exchange, response);
                }
            } catch (final IOException | RuntimeException exception) {
                sendSafely(exchange, json(500, RailixValue.object(Map.of(
                        "status", RailixValue.string("failed"),
                        "message", RailixValue.string("Creator request failed.")
                ))));
            } finally {
                requests.release();
            }
        }
    }

    private Response route(final HttpExchange exchange) throws IOException {
        final String path = exchange.getRequestURI().getPath();
        if (!trustedHost(exchange)) {
            return json(403, RailixValue.object(Map.of(
                    "status", RailixValue.string("forbidden-host")
            )));
        }
        if (path.startsWith("/api/") && !authorized(exchange)) {
            return json(401, RailixValue.object(Map.of(
                    "status", RailixValue.string("unauthorized")
            )));
        }
        if (isMutation(exchange, path)) {
            switch (mutationAccess(exchange)) {
                case FORBIDDEN_ORIGIN -> {
                    return json(403, RailixValue.object(Map.of(
                            "status", RailixValue.string("forbidden-origin")
                    )));
                }
                case UNSUPPORTED_MEDIA_TYPE -> {
                    return json(415, RailixValue.object(Map.of(
                            "status", RailixValue.string("unsupported-media-type")
                    )));
                }
                case ALLOWED -> {
                }
            }
        }
        if ("/api/project".equals(path)) {
            return project(exchange);
        }
        if ("/api/creator".equals(path)) {
            return creator(exchange);
        }
        if ("/api/scene".equals(path)) {
            return scene(exchange);
        }
        if ("/api/scene/observations".equals(path)) {
            return sceneObservations(exchange);
        }
        if ("/api/application".equals(path)) {
            return getOnly(exchange, json(200, application()));
        }
        if ("/api/metrics".equals(path)) {
            return metrics(exchange, "");
        }
        if (path.startsWith("/api/metrics/nodes/")) {
            final String node = path.substring("/api/metrics/nodes/".length());
            return node.isBlank()
                    ? json(404, RailixValue.object(Map.of("status", RailixValue.string("not-found"))))
                    : metrics(exchange, node);
        }
        if ("/api/examples".equals(path) || path.startsWith("/api/examples/")) {
            final String rawPath = exchange.getRequestURI().getRawPath();
            return examples(exchange, rawPath.length() == "/api/examples".length()
                    ? ""
                    : rawPath.substring("/api/examples/".length()));
        }
        if ("/api/catalog".equals(path)) {
            return getOnly(exchange, json(200, catalog()));
        }
        if ("/api/editor".equals(path)) {
            if (!"GET".equals(exchange.getRequestMethod())) return methodNotAllowed();
            synchronized (applicationLock) {
                try {
                    final Map<String, RailixValue> payload = new LinkedHashMap<>(projectPayloadLocked(false).values());
                    payload.putAll(editorLocked().view(exchange.getRequestURI().getRawQuery()).values());
                    return json(200, RailixValue.object(payload));
                } catch (final java.util.NoSuchElementException failure) {
                    return json(404, RailixValue.object(Map.of("message", RailixValue.string(failure.getMessage()))));
                } catch (final IllegalArgumentException failure) {
                    return json(400, RailixValue.object(Map.of("message", RailixValue.string(failure.getMessage()))));
                }
            }
        }
        if ("/api/icons".equals(path)) {
            return getOnly(exchange, json(200, icons.listing()));
        }
        if (path.startsWith("/api/")) {
            return json(404, RailixValue.object(Map.of("status", RailixValue.string("not-found"))));
        }
        return resource(exchange, path);
    }

    private Response sceneObservations(final HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            return methodNotAllowed();
        }
        if (!sceneObservationResponses.tryAcquire()) {
            return unavailable("saturated");
        }
        try {
            final Map<String, String> parameters = CreatorScene.observationParameters(exchange.getRequestURI().getRawQuery());
            final CreatorScene snapshot;
            final DevelopmentApplication deployed;
            final long functionalRevision;
            final long presentationRevision;
            final long pid;
            synchronized (sceneLock) {
                final String project;
                final RailixValue.ObjectValue metadata;
                synchronized (applicationLock) {
                    if (!open.get() || deploymentPending
                            || !sourceArtifactKey.equals(application.artifact().directory().getFileName().toString())) {
                        return unavailable(open.get() ? "application" : "closed");
                    }
                    deployed = application;
                    project = source;
                    metadata = creatorValue;
                    functionalRevision = sourceRevision;
                    presentationRevision = creatorRevision;
                    pid = ((RailixValue.NumberValue) deployed.snapshot().values().get("pid")).value().longValueExact();
                }
                if (scene == null || !scene.matches(project, metadata)) {
                    scene = new CreatorScene(project, metadata, catalog);
                }
                snapshot = scene;
            }
            if (!snapshot.observesRevision(parameters.get("revision"))) {
                return json(409, RailixValue.object(Map.of("status", RailixValue.string("scene-revision-conflict"))));
            }
            final RailixValue.ObjectValue projection = snapshot.observationView(parameters);
            final Map<String, RailixValue.ObjectValue> documents = new LinkedHashMap<>();
            try {
                if (!forwarding.tryAcquire()) {
                    return unavailable("saturated");
                }
                try {
                    final List<String> reads = parameters.containsKey("example")
                            ? List.of("metrics", "example", "coverage") : List.of("metrics", "coverage");
                    for (final String read : reads) {
                        final DevelopmentApplication.ObservationResponse response = switch (read) {
                            case "metrics" -> deployed.metricSnapshot();
                            case "coverage" -> deployed.examples("coverage");
                            default -> deployed.examples(parameters.get("example") + "/view");
                        };
                        if (!sceneObservationCurrent(deployed, functionalRevision, presentationRevision) || response == null) {
                            return unavailable("application");
                        }
                        if (response.status() == 202 || response.status() == 503) {
                            continue;
                        }
                        if (response.status() != 200) {
                            return json(response.status(), RailixValue.object(Map.of(
                                    "status", RailixValue.string("application-observation-failed"),
                                    "reason", RailixValue.string(read)
                            )));
                        }
                        final RailixJson.Result parsed = RailixJson.parse(utf8(response.body()));
                        if (!(parsed instanceof RailixJson.Parsed json)
                                || !(json.value() instanceof RailixValue.ObjectValue document)
                                || !RailixValue.number(pid).equals(document.values().get("application_pid"))) {
                            throw new IOException("Application observation does not identify the captured application.");
                        }
                        documents.put(read, document);
                    }
                } finally {
                    forwarding.release();
                }
                final RailixValue.ObjectValue observation = snapshot.observations(
                        parameters, projection, documents, functionalRevision, pid);
                final String body = RailixJson.write(observation, MAX_SCENE_BYTES).orElse(null);
                if (!sceneObservationCurrent(deployed, functionalRevision, presentationRevision)) {
                    return unavailable("application");
                }
                if (body == null) {
                    return json(413, RailixValue.object(Map.of("status", RailixValue.string("scene-observations-too-large"))));
                }
                // Retain admission until the bounded response has drained, including slow clients.
                send(exchange, new Response(200, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8)));
                return Response.committedResponse();
            } catch (final IOException failure) {
                if (!sceneObservationCurrent(deployed, functionalRevision, presentationRevision)) {
                    return unavailable("application");
                }
                return json(502, RailixValue.object(Map.of(
                        "status", RailixValue.string("invalid-application-observation"),
                        "message", RailixValue.string(failure.getMessage())
                )));
            }
        } catch (final java.util.NoSuchElementException failure) {
            return json(404, RailixValue.object(Map.of("status", RailixValue.string("scene-observation-not-found"),
                    "message", RailixValue.string(failure.getMessage()))));
        } catch (final IllegalArgumentException failure) {
            return json(400, RailixValue.object(Map.of("status", RailixValue.string("invalid-scene-query"),
                    "message", RailixValue.string(failure.getMessage()))));
        } finally {
            sceneObservationResponses.release();
        }
    }

    private boolean sceneObservationCurrent(final DevelopmentApplication deployed, final long functionalRevision,
                                             final long presentationRevision) {
        synchronized (applicationLock) {
            return open.get() && !deploymentPending && application == deployed
                    && sourceRevision == functionalRevision && creatorRevision == presentationRevision
                    && sourceArtifactKey.equals(deployed.artifact().directory().getFileName().toString());
        }
    }

    private Response scene(final HttpExchange exchange) {
        if (!"GET".equals(exchange.getRequestMethod())) {
            return methodNotAllowed();
        }
        final CreatorScene snapshot;
        synchronized (sceneLock) {
            final String project;
            final RailixValue.ObjectValue metadata;
            synchronized (applicationLock) {
                project = source;
                metadata = creatorValue;
            }
            if (scene == null || !scene.matches(project, metadata)) {
                scene = new CreatorScene(project, metadata, catalog);
            }
            snapshot = scene;
        }
        try {
            return RailixJson.write(snapshot.view(exchange.getRequestURI().getRawQuery()), MAX_SCENE_BYTES)
                    .map(body -> new Response(200, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8)))
                    .orElseGet(() -> json(413, RailixValue.object(Map.of("status", RailixValue.string("scene-too-large")))));
        } catch (final java.util.NoSuchElementException failure) {
            return json(404, RailixValue.object(Map.of(
                    "status", RailixValue.string("scene-focus-not-found"),
                    "message", RailixValue.string(failure.getMessage())
            )));
        } catch (final IllegalArgumentException failure) {
            return json(400, RailixValue.object(Map.of(
                    "status", RailixValue.string("invalid-scene-query"),
                    "message", RailixValue.string(failure.getMessage())
            )));
        }
    }

    private Response project(final HttpExchange exchange) throws IOException {
        if ("GET".equals(exchange.getRequestMethod())) {
            return json(200, projectPayload());
        }
        if ("PATCH".equals(exchange.getRequestMethod())) {
            return edit(exchange, true);
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            return methodNotAllowed();
        }
        final long revision;
        synchronized (applicationLock) {
            if (!open.get()) {
                return unavailable("closed");
            }
            revision = ++projectRevision;
        }
        final BodyRead body = body(exchange, MAX_PROJECT_BYTES);
        if (!body.diagnostics().isEmpty()) {
            return bodyResponse(body);
        }
        final String projectSource;
        try {
            projectSource = utf8(body.value());
        } catch (final CharacterCodingException exception) {
            return json(422, diagnostics(List.of(Diagnostic.atPath(
                    "PROJECT_UTF8_INVALID",
                    "Project must be valid UTF-8.",
                    ""
            )), application()));
        }
        final CompileResult result = ProjectCompiler.compileApplication(projectSource, catalog);
        if (result instanceof CompileResult.Rejected rejected) {
            return json(422, diagnostics(rejected.diagnostics(), application()));
        }
        return accept((CompileResult.Compiled) result, revision, true);
    }

    private Response creator(final HttpExchange exchange) throws IOException {
        if ("PATCH".equals(exchange.getRequestMethod())) {
            return edit(exchange, false);
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            return methodNotAllowed();
        }
        final BodyRead body = body(exchange, MAX_PROJECT_BYTES);
        if (!body.diagnostics().isEmpty()) {
            return bodyResponse(body);
        }
        final String metadata;
        try {
            metadata = utf8(body.value());
        } catch (final CharacterCodingException exception) {
            return json(422, diagnostics(List.of(Diagnostic.atPath(
                    "CREATOR_UTF8_INVALID",
                    "Creator metadata must be valid UTF-8.",
                    ""
            )), application()));
        }
        return saveCreator(metadata, true);
    }

    private Response edit(final HttpExchange exchange, final boolean project) throws IOException {
        final BodyRead body = body(exchange, MAX_PROJECT_BYTES);
        if (!body.diagnostics().isEmpty()) {
            return bodyResponse(body);
        }
        final String edited;
        final long revision;
        try {
            final RailixJson.Result parsed = RailixJson.parse(utf8(body.value()));
            if (!(parsed instanceof RailixJson.Parsed valid)
                    || !(valid.value() instanceof RailixValue.ObjectValue edit)
                    || !edit.values().keySet().equals(Set.of("revision", "changes"))
                    || !(edit.values().get("revision") instanceof RailixValue.NumberValue expected)
                    || !(edit.values().get("changes") instanceof RailixValue.ObjectValue changes)) {
                throw new IllegalArgumentException("Edit must contain a revision number and a changes object.");
            }
            synchronized (applicationLock) {
                if (!open.get()) {
                    return unavailable("closed");
                }
                if (expected.value().compareTo(java.math.BigDecimal.valueOf(
                        project ? sourceRevision : creatorRevision)) != 0) {
                    return json(409, RailixValue.object(Map.of(
                            "status", RailixValue.string("edit-conflict"),
                            "message", RailixValue.string("This document changed in another editor. Reload before saving; this edit was not applied.")
                    )));
                }
                final RailixValue.ObjectValue document = project
                        ? (RailixValue.ObjectValue) ((RailixJson.Parsed) RailixJson.parse(source)).value()
                        : creatorValue;
                final RailixValue.ObjectValue edits;
                if (!project && changes.values().containsKey("prune_removed_steps")) {
                    if (!RailixValue.bool(true).equals(changes.values().get("prune_removed_steps"))) {
                        throw new IllegalArgumentException("prune_removed_steps must be true.");
                    }
                    final Map<String, RailixValue> fields = new LinkedHashMap<>(changes.values());
                    fields.remove("prune_removed_steps");
                    final Map<String, RailixValue> steps = new LinkedHashMap<>();
                    final Set<String> current = ((RailixValue.ArrayValue) editorLocked().project().values().get("nodes")).values().stream()
                            .map(value -> ((RailixValue.StringValue) ((RailixValue.ObjectValue) value).values().get("id")).value())
                            .collect(java.util.stream.Collectors.toSet());
                    ((RailixValue.ObjectValue) creatorValue.values().get("steps")).values().keySet().stream()
                            .filter(id -> !current.contains(id)).forEach(id -> steps.put(id, RailixValue.nullValue()));
                    if (fields.get("steps") instanceof RailixValue.ObjectValue explicit) steps.putAll(explicit.values());
                    else if (fields.containsKey("steps")) throw new IllegalArgumentException("Step edits must be an object.");
                    fields.put("steps", RailixValue.object(steps));
                    edits = RailixValue.object(fields);
                } else edits = project ? editorLocked().changes(changes) : changes;
                final var updated = CreatorEditor.apply(document, edits, project);
                final var bounded = RailixJson.write(updated, MAX_PROJECT_BYTES);
                if (bounded.isEmpty()) {
                    throw new IllegalArgumentException("Edited document exceeds the project source-size limit.");
                }
                edited = bounded.orElseThrow();
                if (!project) {
                    return saveCreator(edited, false);
                }
                revision = ++projectRevision;
            }
        } catch (final CharacterCodingException | IllegalArgumentException failure) {
            return json(422, diagnostics(List.of(Diagnostic.atPath(
                    "CREATOR_EDIT_INVALID",
                    failure instanceof CharacterCodingException ? "Edit must be valid UTF-8." : failure.getMessage(),
                    ""
            )), application()));
        }
        final CompileResult result = ProjectCompiler.compileApplication(edited, catalog);
        if (result instanceof CompileResult.Rejected rejected) {
            final var document = (RailixValue.ObjectValue) ((RailixJson.Parsed) RailixJson.parse(edited)).value();
            final var paths = java.util.regex.Pattern.compile("^(nodes|links)\\[(\\d+)]");
            final var values = diagnosticValues(rejected.diagnostics()).values().stream().<RailixValue>map(value -> {
                final Map<String, RailixValue> issue = new LinkedHashMap<>(((RailixValue.ObjectValue) value).values());
                final String path = ((RailixValue.StringValue) issue.get("path")).value();
                final var match = paths.matcher(path);
                if (match.find()) {
                    final var entries = (RailixValue.ArrayValue) document.values().get(match.group(1));
                    final int index = Integer.parseInt(match.group(2));
                    if (index < entries.values().size() && entries.values().get(index) instanceof RailixValue.ObjectValue entry) {
                        final boolean node = match.group(1).equals("nodes");
                        if (entry.values().get(node ? "id" : "from") instanceof RailixValue.StringValue id) {
                            final int separator = id.value().lastIndexOf('.');
                            issue.put("node", RailixValue.string(node ? id.value()
                                    : separator > 0 ? id.value().substring(0, separator) : "app"));
                        }
                    }
                }
                return RailixValue.object(issue);
            }).toList();
            return json(422, RailixValue.object(Map.of("status", RailixValue.string("rejected"),
                    "diagnostics", RailixValue.array(values), "application", application())));
        }
        return accept((CompileResult.Compiled) result, revision, false);
    }

    private Response saveCreator(final String metadata, final boolean fullDocument) throws IOException {
        synchronized (applicationLock) {
            final CreatorDocument.Result result = CreatorDocument.parse(metadata, source, catalog);
            if (!result.diagnostics().isEmpty()) {
                return json(422, diagnostics(result.diagnostics(), applicationSnapshotLocked()));
            }
            try {
                persist(creatorFile, result.source());
            } catch (final IOException exception) {
                return json(500, RailixValue.object(Map.of(
                        "status", RailixValue.string("failed"),
                        "message", RailixValue.string("Creator metadata could not be persisted."),
                        "application", applicationSnapshotLocked()
                )));
            }
            creatorValue = result.value();
            creatorRevision++;
            creatorDiagnostics = List.of();
            return json(200, projectPayloadLocked(fullDocument));
        }
    }

    private Response accept(final CompileResult.Compiled compiled, final long revision, final boolean fullDocument)
            throws IOException {
        final String applicationKey = ApplicationBuilder.key(compiled);
        synchronized (buildLock) {
            final String cleanupPhase = cleanupRetirement();
            long generation = -1;
            boolean reused = false;
            boolean pending = false;
            try {
                synchronized (applicationLock) {
                    if (!open.get() || revision != projectRevision) {
                        return supersededLocked();
                    }
                    if (!cleanupPhase.isEmpty()) {
                        return cleanupPendingLocked();
                    }
                    deploymentPending = true;
                    pending = true;
                    if (application.artifact().directory().getFileName().toString().equals(applicationKey)
                            && application.running()) {
                        try {
                            persist(projectFile, compiled.source());
                        } catch (final IOException exception) {
                            return json(500, RailixValue.object(Map.of(
                                    "status", RailixValue.string("failed"),
                                    "message", RailixValue.string("Project could not be persisted."),
                                    "application", applicationSnapshotLocked()
                            )));
                        }
                        source = compiled.source();
                        sourceArtifactKey = applicationKey;
                        sourceRevision = revision;
                        reused = true;
                    } else {
                        generation = nextGeneration++;
                    }
                }
                if (reused) {
                    return json(200, projectPayload(fullDocument));
                }
                return buildAndAccept(compiled, revision, generation, fullDocument);
            } finally {
                if (pending) {
                    synchronized (applicationLock) {
                        deploymentPending = false;
                    }
                }
            }
        }
    }

    private Response buildAndAccept(
            final CompileResult.Compiled compiled,
            final long revision,
            final long generation,
            final boolean fullDocument
    ) throws IOException {
        final DevelopmentApplication candidate;
        try {
            candidate = DevelopmentApplication.start(generation, projectFile, compiled);
        } catch (final ApplicationBuilder.GeneratedCompilationException exception) {
            synchronized (applicationLock) {
                return json(422, diagnostics(List.of(Diagnostic.atPath(
                        exception.code(),
                        exception.getMessage(),
                        ""
                )), applicationSnapshotLocked()));
            }
        } catch (final IOException exception) {
            synchronized (applicationLock) {
                return json(503, RailixValue.object(Map.of(
                        "status", RailixValue.string("failed"),
                        "message", RailixValue.string("Generated application did not build and start."),
                        "application", applicationSnapshotLocked()
                )));
            }
        }
        boolean superseded;
        IOException persistenceFailure = null;
        IOException activationFailure = null;
        synchronized (applicationLock) {
            superseded = !open.get() || revision != projectRevision;
            if (!superseded) {
                try {
                    persist(projectFile, compiled.source());
                } catch (final IOException exception) {
                    persistenceFailure = exception;
                }
            }
            if (!superseded && persistenceFailure == null) {
                source = compiled.source();
                sourceArtifactKey = candidate.artifact().directory().getFileName().toString();
                sourceRevision = revision;
                try {
                    candidate.activate();
                } catch (final IOException exception) {
                    activationFailure = exception;
                }
                if (activationFailure == null && !candidate.running()) {
                    activationFailure = new IOException("Generated application stopped during activation.");
                }
                if (activationFailure == null) {
                    final DevelopmentApplication previous = application;
                    retirement = previous;
                    retirementDeletesArtifact = !previous.artifact().directory()
                            .equals(candidate.artifact().directory());
                    retirementPhase = "";
                    application = candidate;
                }
            }
        }
        if (superseded) {
            discard(candidate);
            return json(409, RailixValue.object(Map.of(
                    "status", RailixValue.string("superseded"),
                    "message", RailixValue.string("A newer project revision replaced this build."),
                    "application", application()
            )));
        }
        if (persistenceFailure != null) {
            discard(candidate);
            return json(500, RailixValue.object(Map.of(
                    "status", RailixValue.string("failed"),
                    "message", RailixValue.string("Project could not be persisted."),
                "application", application()
            )));
        }
        if (activationFailure != null) {
            discard(candidate);
            return json(503, RailixValue.object(Map.of(
                    "status", RailixValue.string("failed"),
                    "message", RailixValue.string("Generated application could not be activated."),
                    "application", application()
            )));
        }
        cleanupRetirement();
        return json(200, projectPayload(fullDocument));
    }

    private void discard(final DevelopmentApplication candidate) {
        synchronized (applicationLock) {
            retirement = candidate;
            retirementDeletesArtifact = !candidate.artifact().directory()
                    .equals(application.artifact().directory());
            retirementPhase = "";
        }
        cleanupRetirement();
    }

    private String cleanupRetirement() {
        final DevelopmentApplication pending;
        final boolean deleteArtifact;
        synchronized (applicationLock) {
            pending = retirement;
            deleteArtifact = retirementDeletesArtifact;
        }
        if (pending == null) {
            return "";
        }
        try {
            pending.close();
        } catch (final RuntimeException failure) {
            return markRetirement(pending, "application-cleanup");
        }
        if (deleteArtifact) {
            try {
                ApplicationBuilder.delete(pending.artifact());
            } catch (final IOException failure) {
                return markRetirement(pending, "artifact-deletion");
            }
        }
        synchronized (applicationLock) {
            if (retirement == pending) {
                retirement = null;
                retirementDeletesArtifact = false;
                retirementPhase = "";
            }
        }
        return "";
    }

    private String markRetirement(final DevelopmentApplication pending, final String phase) {
        synchronized (applicationLock) {
            if (retirement == pending) {
                retirementPhase = phase;
            }
        }
        return phase;
    }

    private Response cleanupPendingLocked() {
        return json(503, RailixValue.object(Map.of(
                "status", RailixValue.string("unavailable"),
                "reason", RailixValue.string("cleanup-pending"),
                "retirement", retirementValueLocked(),
                "application", applicationSnapshotLocked()
        )));
    }

    private Response supersededLocked() throws IOException {
        return json(409, RailixValue.object(Map.of(
                "status", RailixValue.string("superseded"),
                "message", RailixValue.string("A newer project revision replaced this build."),
                "application", applicationSnapshotLocked()
        )));
    }

    private Response metrics(final HttpExchange exchange, final String node) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            return methodNotAllowed();
        }
        if (!open.get()) {
            return unavailable("closed");
        }
        if (!forwarding.tryAcquire()) {
            return unavailable("saturated");
        }
        try {
            final DevelopmentApplication deployed;
            synchronized (applicationLock) {
                deployed = application;
            }
            final DevelopmentApplication.Response response = node.isEmpty()
                    ? deployed.metrics()
                    : deployed.metrics(node);
            synchronized (applicationLock) {
                if (application != deployed) {
                    return unavailable("application");
                }
            }
            return new Response(
                    response.status(),
                    "application/json; charset=utf-8",
                    response.body().getBytes(StandardCharsets.UTF_8)
            );
        } finally {
            forwarding.release();
        }
    }

    private Response examples(final HttpExchange exchange, final String path) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            return methodNotAllowed();
        }
        if (!open.get()) {
            return unavailable("closed");
        }
        if (!forwarding.tryAcquire()) {
            return unavailable("saturated");
        }
        if (!exampleResponses.tryAcquire()) {
            forwarding.release();
            return unavailable("saturated");
        }
        try {
            final DevelopmentApplication.ObservationResponse response;
            try {
                final DevelopmentApplication deployed;
                synchronized (applicationLock) {
                    if (deploymentPending) {
                        return unavailable("application");
                    }
                    deployed = application;
                }
                try {
                    response = deployed.examples(path);
                } catch (final IOException failure) {
                    synchronized (applicationLock) {
                        if (application != deployed || deploymentPending) {
                            return unavailable("application");
                        }
                    }
                    throw failure;
                }
                if (response == null) {
                    return unavailable("application");
                }
                synchronized (applicationLock) {
                    if (application != deployed || deploymentPending) {
                        return unavailable("application");
                    }
                }
            } finally {
                forwarding.release();
            }
            exchange.getResponseHeaders().set("Content-Type", response.contentType());
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.sendResponseHeaders(response.status(), response.body().length);
            exchange.getResponseBody().write(response.body());
            return Response.committedResponse();
        } finally {
            exampleResponses.release();
        }
    }

    private static Response unavailable(final String reason) {
        return json(503, RailixValue.object(Map.of(
                "status", RailixValue.string("unavailable"),
                "reason", RailixValue.string(reason)
        )));
    }

    private RailixValue.ObjectValue projectPayload() throws IOException {
        return projectPayload(true);
    }

    private RailixValue.ObjectValue projectPayload(final boolean fullDocument) throws IOException {
        synchronized (applicationLock) {
            return projectPayloadLocked(fullDocument);
        }
    }

    private RailixValue.ObjectValue projectPayloadLocked(final boolean fullDocument) throws IOException {
        final CreatorEditor index = editorLocked();
        final Map<String, RailixValue> payload = new LinkedHashMap<>(Map.of(
                "revision", RailixValue.number(sourceRevision),
                "creator_revision", RailixValue.number(creatorRevision),
                "diagnostics", diagnosticValues(creatorDiagnostics),
                "application", applicationSnapshotLocked(),
                "workspace", RailixValue.object(Map.of(
                        "project_path", RailixValue.string(projectFile.toString()),
                        "flow_count", RailixValue.number(index.flowCount()),
                        "step_count", RailixValue.number(index.stepCount())
                ))
        ));
        if (fullDocument) {
            payload.put("project", index.project());
            payload.put("creator", creatorValue);
        }
        return RailixValue.object(payload);
    }

    private CreatorEditor editorLocked() {
        if (editor == null || !editor.matches(source, creatorValue)) editor = new CreatorEditor(source, creatorValue, catalog);
        return editor;
    }

    private static String defaultProject(final Path project) {
        final int combinations = PROJECT_PREFIXES.length * PROJECT_SUBJECTS.length * PROJECT_OBJECTS.length;
        int index = Math.floorMod(project.toString().hashCode(), combinations);
        final String prefix = PROJECT_PREFIXES[index % PROJECT_PREFIXES.length];
        index /= PROJECT_PREFIXES.length;
        final String subject = PROJECT_SUBJECTS[index % PROJECT_SUBJECTS.length];
        index /= PROJECT_SUBJECTS.length;
        final String name = prefix + "-" + subject + "-" + PROJECT_OBJECTS[index];
        return """
                {"format":1,"id":"%s","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}}
                ],"links":[]}
                """.formatted(name);
    }

    private RailixValue.ObjectValue application() {
        synchronized (applicationLock) {
            return applicationSnapshotLocked();
        }
    }

    private RailixValue.ObjectValue applicationSnapshotLocked() {
        final RailixValue.ObjectValue snapshot = application.snapshot();
        final Map<String, RailixValue> values = new LinkedHashMap<>(snapshot.values());
        if (retirement != null && !retirementPhase.isEmpty()) {
            values.put("retirement", retirementValueLocked());
        }
        return RailixValue.object(values);
    }

    private RailixValue.ObjectValue retirementValueLocked() {
        return RailixValue.object(Map.of(
                "state", RailixValue.string("cleanup-pending"),
                "phase", RailixValue.string(retirementPhase)
        ));
    }

    private RailixValue.ObjectValue catalog() {
        return RailixValue.object(Map.of(
                "steps",
                RailixValue.array(catalog.definitions().stream()
                        .<RailixValue>map(CreatorServer::definition)
                        .toList())
        ));
    }

    private static RailixValue definition(final StepDefinition definition) {
        return StepContractJson.value(definition);
    }

    private static Response resource(final HttpExchange exchange, final String path) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            return methodNotAllowed();
        }
        final String file = switch (path) {
            case "/", "/index.html" -> "index.html";
            case "/app.css" -> "app.css";
            case "/app.js" -> "app.js";
            case "/world.js" -> "world.js";
            default -> "";
        };
        if (file.isEmpty()) {
            return json(404, RailixValue.object(Map.of("status", RailixValue.string("not-found"))));
        }
        try (var stream = CreatorServer.class.getResourceAsStream(WEB_ROOT + file)) {
            if (stream == null) {
                return json(404, RailixValue.object(Map.of("status", RailixValue.string("not-found"))));
            }
            return new Response(
                    200,
                    file.endsWith(".html")
                            ? "text/html; charset=utf-8"
                            : file.endsWith(".css")
                            ? "text/css; charset=utf-8"
                            : "text/javascript; charset=utf-8",
                    stream.readAllBytes()
            );
        }
    }

    private BodyRead body(final HttpExchange exchange, final int limit) throws IOException {
        final AtomicBoolean completed = new AtomicBoolean();
        final AtomicBoolean timedOut = new AtomicBoolean();
        final Thread handler = Thread.currentThread();
        final ScheduledFuture<?> deadline = bodyDeadlines.schedule(() -> {
            if (completed.compareAndSet(false, true)) {
                timedOut.set(true);
                commitTimeout(
                        exchange,
                        json(408, diagnostics(bodyTimeoutDiagnostics(), application())),
                        handler
                );
            }
        }, BODY_READ_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
        try {
            final byte[] value = exchange.getRequestBody().readNBytes(limit + 1);
            if (!completed.compareAndSet(false, true) || timedOut.get()) {
                return bodyTimeout(true);
            }
            if (value.length > limit) {
                return new BodyRead(
                        new byte[0],
                        413,
                        List.of(Diagnostic.atPath(
                                "REQUEST_TOO_LARGE",
                                "Request exceeds the " + limit + "-byte limit.",
                                ""
                        )),
                        false
                );
            }
            return new BodyRead(value, 200, List.of(), false);
        } catch (final IOException exception) {
            if (timedOut.get()) {
                return bodyTimeout(true);
            }
            throw exception;
        } finally {
            completed.set(true);
            deadline.cancel(false);
        }
    }

    private static BodyRead bodyTimeout(final boolean responseCommitted) {
        return new BodyRead(
                new byte[0],
                408,
                bodyTimeoutDiagnostics(),
                responseCommitted
        );
    }

    private static List<Diagnostic> bodyTimeoutDiagnostics() {
        return List.of(Diagnostic.atPath(
                "REQUEST_BODY_TIMEOUT",
                "Request body was not received within 5 seconds.",
                ""
        ));
    }

    private Response bodyResponse(final BodyRead body) {
        return body.responseCommitted()
                ? Response.committedResponse()
                : json(body.status(), diagnostics(body.diagnostics(), application()));
    }

    private static void commitTimeout(
            final HttpExchange exchange,
            final Response response,
            final Thread handler
    ) {
        try {
            exchange.getResponseHeaders().set("Content-Type", response.contentType());
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.getResponseHeaders().set("Connection", "close");
            exchange.sendResponseHeaders(response.status(), response.body().length);
            final var output = exchange.getResponseBody();
            output.write(response.body());
            output.flush();
        } catch (final IOException ignored) {
            // Interrupting the blocked handler still releases request ownership.
        } finally {
            handler.interrupt();
        }
    }

    private boolean isMutation(final HttpExchange exchange, final String path) {
        return ("POST".equals(exchange.getRequestMethod()) || "PATCH".equals(exchange.getRequestMethod()))
                && ("/api/project".equals(path)
                || "/api/creator".equals(path));
    }

    private MutationAccess mutationAccess(final HttpExchange exchange) {
        final List<String> origins = exchange.getRequestHeaders().getOrDefault("Origin", List.of());
        if (origins.size() > 1 || origins.size() == 1 && !origin().equals(origins.getFirst())) {
            return MutationAccess.FORBIDDEN_ORIGIN;
        }
        final List<String> contentTypes = exchange.getRequestHeaders().getOrDefault("Content-Type", List.of());
        if (contentTypes.size() != 1 || !jsonContentType(contentTypes.getFirst())) {
            return MutationAccess.UNSUPPORTED_MEDIA_TYPE;
        }
        return MutationAccess.ALLOWED;
    }

    private boolean trustedHost(final HttpExchange exchange) {
        final List<String> hosts = exchange.getRequestHeaders().getOrDefault("Host", List.of());
        return hosts.size() == 1
                && ("127.0.0.1:" + server.getAddress().getPort()).equals(hosts.getFirst());
    }

    private boolean authorized(final HttpExchange exchange) {
        final List<String> tokens = exchange.getRequestHeaders().getOrDefault(TOKEN_HEADER, List.of());
        return tokens.size() == 1 && MessageDigest.isEqual(
                creatorToken.getBytes(StandardCharsets.US_ASCII),
                tokens.getFirst().getBytes(StandardCharsets.US_ASCII)
        );
    }

    private static boolean jsonContentType(final String value) {
        final String[] parts = value.split(";", -1);
        if (parts.length > 2 || !"application/json".equalsIgnoreCase(parts[0].strip())) {
            return false;
        }
        return parts.length == 1 || "charset=utf-8".equalsIgnoreCase(parts[1].strip());
    }

    private String origin() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static String token() {
        final byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static RailixValue diagnostics(
            final List<Diagnostic> diagnostics,
            final RailixValue.ObjectValue application
    ) {
        return RailixValue.object(Map.of(
                "status", RailixValue.string("rejected"),
                "diagnostics", diagnosticValues(diagnostics),
                "application", application
        ));
    }

    private static RailixValue.ArrayValue diagnosticValues(final List<Diagnostic> diagnostics) {
        return RailixValue.array(diagnostics.stream()
                .<RailixValue>map(diagnostic -> RailixValue.object(Map.of(
                        "code", RailixValue.string(diagnostic.code()),
                        "message", RailixValue.string(diagnostic.message()),
                        "path", RailixValue.string(diagnostic.path())
                )))
                .toList());
    }

    private static Response getOnly(final HttpExchange exchange, final Response response) {
        return "GET".equals(exchange.getRequestMethod()) ? response : methodNotAllowed();
    }

    private static Response methodNotAllowed() {
        return json(405, RailixValue.object(Map.of("status", RailixValue.string("method-not-allowed"))));
    }

    private static Response json(final int status, final RailixValue value) {
        return new Response(
                status,
                "application/json; charset=utf-8",
                RailixJson.write(value).getBytes(StandardCharsets.UTF_8)
        );
    }

    private static void send(final HttpExchange exchange, final Response response) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", response.contentType());
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(response.status(), response.body().length);
        try (var body = exchange.getResponseBody()) {
            body.write(response.body());
        }
    }

    private static void sendSafely(final HttpExchange exchange, final Response response) {
        try {
            send(exchange, response);
        } catch (final IOException ignored) {
            exchange.close();
        }
    }

    private static String readProject(final Path project) throws IOException {
        if (Files.size(project) > MAX_PROJECT_BYTES) {
            throw new IOException("Creator project exceeds the 1048576-byte limit.");
        }
        final byte[] source = Files.readAllBytes(project);
        try {
            return utf8(source);
        } catch (final CharacterCodingException exception) {
            throw new IOException("Creator project is not valid UTF-8.", exception);
        }
    }

    private static String utf8(final byte[] source) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(source))
                .toString();
    }

    private static void persist(final Path project, final String source) throws IOException {
        final Path parent = project.getParent();
        Files.createDirectories(parent);
        final Path temporary = Files.createTempFile(parent, "." + project.getFileName(), ".tmp");
        try {
            Files.writeString(temporary, source, StandardCharsets.UTF_8);
            Files.move(
                    temporary,
                    project,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private record BodyRead(
            byte[] value,
            int status,
            List<Diagnostic> diagnostics,
            boolean responseCommitted
    ) {
        private BodyRead {
            value = value.clone();
            diagnostics = List.copyOf(diagnostics);
        }
    }

    private enum MutationAccess {
        ALLOWED,
        FORBIDDEN_ORIGIN,
        UNSUPPORTED_MEDIA_TYPE
    }

    private record Response(int status, String contentType, byte[] body, boolean committed) {
        private Response(final int status, final String contentType, final byte[] body) {
            this(status, contentType, body, false);
        }

        private Response {
            body = body.clone();
        }

        private static Response committedResponse() {
            return new Response(0, "", new byte[0], true);
        }
    }

    private record ProjectLease(FileChannel channel, FileLock lock) implements AutoCloseable {
        private static ProjectLease acquire(final Path project) throws IOException {
            final Path run = project.getParent().resolve(".railix").resolve("run");
            Files.createDirectories(run);
            final FileChannel channel = FileChannel.open(
                    run.resolve("creator.lock"),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE
            );
            try {
                final FileLock lock;
                try {
                    lock = channel.tryLock();
                } catch (final OverlappingFileLockException exception) {
                    throw new IOException("Project is already open in another Creator: " + project + ".", exception);
                }
                if (lock == null) {
                    throw new IOException("Project is already open in another Creator: " + project + ".");
                }
                return new ProjectLease(channel, lock);
            } catch (final IOException | RuntimeException failure) {
                try {
                    channel.close();
                } catch (final IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }

        @Override
        public void close() {
            try (channel; lock) {
            } catch (final IOException exception) {
                throw new IllegalStateException("Creator project lock did not close.", exception);
            }
        }
    }
}
