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
    private static final String WEB_ROOT = "/dev/nanonative/railix/creator/web/";
    private static final int MAX_PROJECT_BYTES = RailixData.DEFAULT_MAX_SOURCE_BYTES;
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
    private final Object buildLock = new Object();
    private final Object closeLock = new Object();
    private final CountDownLatch closed = new CountDownLatch(1);
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final Semaphore requests = new Semaphore(MAX_CONCURRENT_REQUESTS);
    private final Semaphore forwarding = new Semaphore(MAX_CONCURRENT_FORWARDS);
    private final Semaphore responses = new Semaphore(MAX_CONCURRENT_FORWARDS);
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
    private RailixValue.ObjectValue creatorValue;
    private List<Diagnostic> creatorDiagnostics;
    private long nextGeneration;
    private long projectRevision;

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
                try {
                    application.close();
                } catch (final RuntimeException cleanup) {
                    exception.addSuppressed(cleanup);
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
                    responsesDrained = responses.tryAcquire(
                            MAX_CONCURRENT_FORWARDS,
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
                        responses.release(MAX_CONCURRENT_FORWARDS);
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
        failure.addSuppressed(next);
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
        if ("/api/application".equals(path)) {
            return getOnly(exchange, json(200, application()));
        }
        if ("/api/catalog".equals(path)) {
            return getOnly(exchange, json(200, catalog()));
        }
        if ("/api/icons".equals(path)) {
            return getOnly(exchange, json(200, icons.listing()));
        }
        if (path.startsWith("/api/run/")) {
            return run(exchange, path.substring("/api/run/".length()));
        }
        if (path.startsWith("/api/preview/")) {
            return preview(exchange, path.substring("/api/preview/".length()));
        }
        return resource(exchange, path);
    }

    private Response project(final HttpExchange exchange) throws IOException {
        if ("GET".equals(exchange.getRequestMethod())) {
            return json(200, projectPayload());
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
            projectSource = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body.value()))
                    .toString();
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
        return accept((CompileResult.Compiled) result, revision);
    }

    private Response creator(final HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            return methodNotAllowed();
        }
        final BodyRead body = body(exchange, MAX_PROJECT_BYTES);
        if (!body.diagnostics().isEmpty()) {
            return bodyResponse(body);
        }
        final String metadata;
        try {
            metadata = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body.value()))
                    .toString();
        } catch (final CharacterCodingException exception) {
            return json(422, diagnostics(List.of(Diagnostic.atPath(
                    "CREATOR_UTF8_INVALID",
                    "Creator metadata must be valid UTF-8.",
                    ""
            )), application()));
        }
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
            creatorDiagnostics = List.of();
            return json(200, projectPayloadLocked());
        }
    }

    private Response accept(final CompileResult.Compiled compiled, final long revision) throws IOException {
        final String applicationKey = ApplicationBuilder.key(compiled);
        synchronized (buildLock) {
            final String cleanupPhase = cleanupRetirement();
            final long generation;
            synchronized (applicationLock) {
                if (!open.get() || revision != projectRevision) {
                    return supersededLocked();
                }
                if (!cleanupPhase.isEmpty()) {
                    return cleanupPendingLocked();
                }
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
                    return json(200, projectPayloadLocked());
                }
                generation = nextGeneration++;
            }
            return buildAndAccept(compiled, revision, generation);
        }
    }

    private Response buildAndAccept(
            final CompileResult.Compiled compiled,
            final long revision,
            final long generation
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
                final DevelopmentApplication previous = application;
                retirement = previous;
                retirementDeletesArtifact = !previous.artifact().directory()
                        .equals(candidate.artifact().directory());
                retirementPhase = "";
                application = candidate;
                source = compiled.source();
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
        cleanupRetirement();
        return json(200, projectPayload());
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
            return markRetirement(pending, "termination");
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

    private Response run(
            final HttpExchange exchange,
            final String trigger
    ) throws IOException {
        return execute(exchange, new String[]{trigger}, false);
    }

    private Response preview(
            final HttpExchange exchange,
            final String target
    ) throws IOException {
        return execute(exchange, target.split("/", -1), true);
    }

    private Response execute(
            final HttpExchange exchange,
            final String[] target,
            final boolean preview
    ) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            return methodNotAllowed();
        }
        final int targetSize = preview ? 2 : 1;
        if (target.length != targetSize || Arrays.stream(target).anyMatch(String::isBlank)) {
            return json(404, RailixValue.object(Map.of("status", RailixValue.string("not-found"))));
        }
        if (!open.get()) {
            return unavailable("closed");
        }
        if (!forwarding.tryAcquire()) {
            return unavailable("saturated");
        }
        try {
            if (!open.get()) {
                return unavailable("closed");
            }
            final BodyRead body = body(exchange, RailixData.DEFAULT_MAX_SOURCE_BYTES);
            if (!body.diagnostics().isEmpty()) {
                return bodyResponse(body);
            }
            if (!open.get() || !responses.tryAcquire()) {
                return unavailable("closed");
            }
            try {
                if (!open.get()) {
                    return unavailable("closed");
                }
                final DevelopmentApplication deployed;
                synchronized (applicationLock) {
                    deployed = application;
                }
                final DevelopmentApplication.Response response = preview
                        ? deployed.preview(target[0], target[1], body.value(), true)
                        : deployed.run(target[0], body.value(), true);
                send(exchange, new Response(
                        response.status(),
                        "application/json; charset=utf-8",
                        response.body().getBytes(StandardCharsets.UTF_8)
                ));
                return Response.committedResponse();
            } finally {
                responses.release();
            }
        } finally {
            forwarding.release();
        }
    }

    private static Response unavailable(final String reason) {
        return json(503, RailixValue.object(Map.of(
                "status", RailixValue.string("unavailable"),
                "reason", RailixValue.string(reason)
        )));
    }

    private RailixValue.ObjectValue projectPayload() throws IOException {
        synchronized (applicationLock) {
            return projectPayloadLocked();
        }
    }

    private RailixValue.ObjectValue projectPayloadLocked() throws IOException {
        final RailixValue.ObjectValue project =
                (RailixValue.ObjectValue) ((RailixJson.Parsed) RailixJson.parse(source)).value();
        final RailixValue.ArrayValue nodes = (RailixValue.ArrayValue) project.values().get("nodes");
        final long triggers = nodes.values().stream()
                .filter(RailixValue.ObjectValue.class::isInstance)
                .map(RailixValue.ObjectValue.class::cast)
                .map(node -> node.values().get("use"))
                .filter(RailixValue.StringValue.class::isInstance)
                .map(RailixValue.StringValue.class::cast)
                .map(RailixValue.StringValue::value)
                .map(catalog::find)
                .filter(java.util.Optional::isPresent)
                .map(java.util.Optional::orElseThrow)
                .filter(definition -> definition.kind() == StepDefinition.Kind.TRIGGER)
                .count();
        return RailixValue.object(Map.of(
                "project", project,
                "creator", creatorValue,
                "diagnostics", diagnosticValues(creatorDiagnostics),
                "application", applicationSnapshotLocked(),
                "workspace", RailixValue.object(Map.of(
                        "project_path", RailixValue.string(projectFile.toString()),
                        "flow_count", RailixValue.number(triggers),
                        "step_count", RailixValue.number(nodes.values().size())
                ))
        ));
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
        if (retirement == null || retirementPhase.isEmpty()) {
            return snapshot;
        }
        final Map<String, RailixValue> values = new LinkedHashMap<>(snapshot.values());
        values.put("retirement", retirementValueLocked());
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
        return "POST".equals(exchange.getRequestMethod())
                && ("/api/project".equals(path)
                || "/api/creator".equals(path)
                || path.startsWith("/api/run/")
                || path.startsWith("/api/preview/"));
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
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(source))
                    .toString();
        } catch (final CharacterCodingException exception) {
            throw new IOException("Creator project is not valid UTF-8.", exception);
        }
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
