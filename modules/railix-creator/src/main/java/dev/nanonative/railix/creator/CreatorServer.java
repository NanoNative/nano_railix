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
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.Channels;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.ArrayDeque;
import java.util.ArrayList;
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
    private record MmlSequence(int tempo, List<RailixValue> notes) { }
    static final int MAX_CONCURRENT_REQUESTS = 64;
    static final int MAX_CONCURRENT_FORWARDS = 32;
    static final int MAX_CONCURRENT_EXAMPLE_RESPONSES = 4;
    private static final String WEB_ROOT = "/dev/nanonative/railix/creator/web/";
    private static final String ASSET_ROOT = "/dev/nanonative/railix/creator/assets/";
    private static final int MAX_REQUEST_BYTES = RailixData.DEFAULT_MAX_SOURCE_BYTES;
    private static final int MAX_SCENE_BYTES = 2 * RailixData.DEFAULT_MAX_SOURCE_BYTES;
    private static final Duration BODY_READ_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration RESPONSE_DRAIN_TIMEOUT = Duration.ofSeconds(1);
    private static final String TOKEN_HEADER = "X-Railix-Creator-Token";
    private static final int MAX_SOUND_NOTES = 256;
    private static final Set<String> SOUND_INSTRUMENTS = Set.of("sine", "square", "sawtooth", "triangle", "kick", "snare", "hat");
    private static final int MAX_SETTINGS_BYTES = 65_536;
    private static final String SETTINGS_FILE = "creator.settings.json";
    private static final String SETTINGS_LOCK_FILE = ".creator.settings.lock";
    private static final String SOUND_LOCK_FILE = ".creator.sounds.lock";
    private static final Map<String, RailixValue.ObjectValue> DEFAULT_SCORES = soundDefaults();
    private static final List<ThemeSpec> EMBEDDED_THEMES = embeddedThemes();
    private static final Set<String> EMBEDDED_THEME_ASSETS = embeddedThemeAssets();
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
    private final Path themeDirectory;
    private final Path soundDirectory;
    private final Path musicDirectory;
    private final Path legacyMusicDirectory;
    private final Path settingsDirectory;
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
    private final String themeAssetCookieName;
    private final String themeAssetSecret;
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
            final Path themeDirectory,
            final Path soundDirectory,
            final Path musicDirectory,
            final Path legacyMusicDirectory,
            final Path settingsDirectory,
            final Path projectFile,
            final Path creatorFile,
            final ProjectLease lease,
            final DevelopmentApplication application,
            final String source,
            final RailixValue.ObjectValue creatorValue,
            final List<Diagnostic> creatorDiagnostics,
            final long nextGeneration,
            final String creatorToken,
            final String themeAssetCookieName,
            final String themeAssetSecret
    ) {
        this.server = server;
        this.executor = executor;
        this.bodyDeadlines = bodyDeadlines;
        this.catalog = catalog;
        this.icons = icons;
        this.themeDirectory = themeDirectory;
        this.soundDirectory = soundDirectory;
        this.musicDirectory = musicDirectory;
        this.legacyMusicDirectory = legacyMusicDirectory;
        this.settingsDirectory = settingsDirectory;
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
        this.themeAssetCookieName = themeAssetCookieName;
        this.themeAssetSecret = themeAssetSecret;
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
        final Path themeDirectory = Files.createDirectories(railixHome).toRealPath().resolve("themes");
        final Path soundDirectory = themeDirectory.resolveSibling("sounds");
        final Path musicDirectory = themeDirectory.resolveSibling("music");
        Files.createDirectories(themeDirectory);
        Files.createDirectories(soundDirectory);
        Files.createDirectories(musicDirectory);
        final boolean projectExists = Files.exists(absoluteProject);
        final String requested = projectExists
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
        final String defaultCreator = projectExists ? CreatorDocument.EMPTY
                : "{\"created_at\":" + System.currentTimeMillis() + "," + CreatorDocument.EMPTY.substring(1);
        final String requestedCreator = creatorExists
                ? readProject(creatorFile)
                : defaultCreator;
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
                    themeDirectory,
                    soundDirectory,
                    musicDirectory,
                    soundDirectory.resolve("music"),
                    themeDirectory.getParent(),
                    absoluteProject,
                    creatorFile,
                    lease,
                    application,
                    canonical,
                    creatorResult.value(),
                    requestedCreatorResult.diagnostics(),
                    2,
                    token(),
                    "railix_theme_" + server.getAddress().getPort(),
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
        final boolean themeAsset = path.startsWith("/api/themes/files/");
        if (themeAsset && !themeAssetFetchAllowed(exchange)) {
            return json(403, RailixValue.object(Map.of("status", RailixValue.string("forbidden-origin"))));
        }
        if (path.startsWith("/api/") && !(themeAsset ? themeAssetAuthorized(exchange) : authorized(exchange))) {
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
        if ("/api/metrics/catalog".equals(path)) {
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
                    payload.putAll(editorLocked().view(exchange.getRequestURI().getRawQuery(), creatorValue).values());
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
        if ("/api/themes".equals(path)) {
            final Response response = themes(exchange);
            if ("GET".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Set-Cookie", themeAssetCookie());
            }
            return response;
        }
        if (path.startsWith("/api/themes/files/")) {
            return themeFile(exchange, exchange.getRequestURI().getRawPath().substring("/api/themes/files/".length()));
        }
        if ("/api/sounds".equals(path)) return sounds(exchange);
        if ("/api/settings".equals(path)) return settings(exchange);
        if (path.startsWith("/api/")) {
            return json(404, RailixValue.object(Map.of("status", RailixValue.string("not-found"))));
        }
        return resource(exchange, path);
    }

    private Response themes(final HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod()) && !"POST".equals(exchange.getRequestMethod())) return methodNotAllowed();
        try {
            if ("POST".equals(exchange.getRequestMethod())) return copyTheme(exchange);
            final String query = exchange.getRequestURI().getRawQuery();
            if (query == null || query.isEmpty()) {
                final List<RailixValue> entries = new ArrayList<>();
                final List<RailixValue> diagnostics = new ArrayList<>();
                final List<String> local = localFiles(themeDirectory, id -> id.endsWith(".css"));
                final Map<String, ThemeSpec> localThemes = new LinkedHashMap<>();
                for (final String id : local) {
                    try {
                        localThemes.put(id, localTheme(id));
                    } catch (final IOException | IllegalArgumentException failure) {
                        diagnostics.add(themeDiagnostic(id.substring(0, id.length() - 4) + ".json", failure.getMessage()));
                        localThemes.put(id, standaloneTheme(id));
                    }
                }
                final Set<String> embeddedStylesheets = EMBEDDED_THEMES.stream().map(ThemeSpec::stylesheet).collect(java.util.stream.Collectors.toSet());
                final Set<String> descriptorAssets = new java.util.HashSet<>();
                for (final ThemeSpec theme : localThemes.values()) {
                    if (theme.descriptor()) {
                        theme.variants().forEach(variant -> descriptorAssets.add(themeString(variant, "stylesheet", true)));
                        descriptorAssets.addAll(theme.files());
                    }
                }
                for (final ThemeSpec embedded : EMBEDDED_THEMES) {
                    final ThemeSpec override = localThemes.get(embedded.stylesheet());
                    entries.add(themeEntry(override != null && override.descriptor() ? mergeTheme(embedded, override) : embedded,
                            override == null, true));
                }
                localThemes.values().stream()
                        .filter(theme -> !embeddedStylesheets.contains(theme.stylesheet())
                                && !EMBEDDED_THEME_ASSETS.contains(theme.stylesheet())
                                && !descriptorAssets.contains(theme.stylesheet()))
                        .sorted(java.util.Comparator.comparing(ThemeSpec::id))
                        .forEach(theme -> entries.add(themeEntry(theme, false, false)));
                return json(200, RailixValue.object(Map.of(
                        "directory", RailixValue.string(themeDirectory.toString()),
                        "themes", RailixValue.array(entries), "diagnostics", RailixValue.array(diagnostics)
                )));
            }
            final String[] parameter = query.split("=", 2);
            if (parameter.length != 2 || query.contains("&")
                    || !"file".equals(URLDecoder.decode(parameter[0], StandardCharsets.UTF_8))) {
                throw new IllegalArgumentException("Theme requests accept one file parameter.");
            }
            final String file = URLDecoder.decode(parameter[1], StandardCharsets.UTF_8);
            final Path relative = Path.of(file);
            if (!canonicalLocalPath(file) || !file.endsWith(".css")
                    || relative.isAbsolute() || !relative.normalize().toString().equals(file)
                    || !themeDirectory.resolve(relative).normalize().startsWith(themeDirectory)) {
                throw new IllegalArgumentException("Theme file must be a canonical relative .css path inside the themes directory.");
            }
            final byte[] source = readTheme(themeDirectory, file, "Stylesheet");
            return source == null
                    ? themeFailure(404, "theme-not-found", "The requested theme file or directory does not exist.")
                    : new Response(200, "text/css; charset=utf-8", source);
        } catch (final ThemeTooLarge failure) {
            return themeFailure(400, "theme-too-large", failure.getMessage());
        } catch (final NoSuchFileException | NotDirectoryException failure) {
            return themeFailure(404, "theme-not-found", "The requested theme file or directory does not exist.");
        } catch (final IllegalArgumentException failure) {
            return themeFailure(400, "invalid-theme", failure.getMessage());
        } catch (final IOException | DirectoryIteratorException failure) {
            return themeFailure(400, "invalid-theme", "Could not read the theme path without following symbolic links.");
        }
    }

    private Response copyTheme(final HttpExchange exchange) throws IOException {
        final BodyRead body = body(exchange, MAX_REQUEST_BYTES);
        if (body.status() != 200) return bodyResponse(body);
        final RailixValue.ObjectValue request = soundObject(utf8(body.value()), "Theme copy must be a JSON object.");
        final String action = soundString(request, "action");
        if ("install".equals(action)) return installTheme(request);
        if (!request.values().keySet().equals(Set.of("action", "id", "content")) || !"copy".equals(action)) {
            throw new IllegalArgumentException("Theme copy requires action, id, and content.");
        }
        if (!(request.values().get("id") instanceof RailixValue.StringValue requested)) {
            throw new IllegalArgumentException("Theme copy id must be a string.");
        }
        final String id = requested.value();
        final String content = soundString(request, "content");
        if (!canonicalLocalPath(id) || !id.endsWith(".css") || content.getBytes(StandardCharsets.UTF_8).length > MAX_REQUEST_BYTES) {
            throw new IllegalArgumentException("Theme copy requires a canonical CSS name within the byte limit.");
        }
        if (!createSound(themeDirectory, id, content)) {
            throw new IllegalArgumentException("Theme copy already exists; choose a new name to preserve the custom file.");
        }
        return json(200, RailixValue.object(Map.of("status", RailixValue.string("theme-copied"), "id", RailixValue.string(id))));
    }

    private Response installTheme(final RailixValue.ObjectValue request) throws IOException {
        if (!request.values().keySet().equals(Set.of("action", "id"))) {
            throw new IllegalArgumentException("Theme install requires action and id.");
        }
        final String id = themeId(request, "id");
        final ThemeSpec theme = EMBEDDED_THEMES.stream().filter(candidate -> candidate.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Embedded theme id does not exist."));
        int installed = 0;
        int skipped = 0;
        final List<String> files = new ArrayList<>(themeFiles(theme));
        for (final String file : files) {
            final byte[] source = embeddedThemeFile(file);
            if (source == null) throw new IllegalArgumentException("Embedded theme asset is unavailable: " + file);
            if (createTheme(themeDirectory, file, source)) installed++;
            else skipped++;
        }
        final String descriptor = themeDescriptorPath(theme);
        files.add(descriptor);
        final byte[] embeddedDescriptor = embeddedThemeFile(descriptor);
        if (embeddedDescriptor == null) throw new IllegalArgumentException("Embedded theme descriptor is unavailable: " + descriptor);
        if (createTheme(themeDirectory, descriptor, embeddedDescriptor)) {
            installed++;
        } else skipped++;
        return json(200, RailixValue.object(Map.of("status", RailixValue.string("theme-installed"),
                "id", RailixValue.string(id), "installed", RailixValue.number(installed), "skipped", RailixValue.number(skipped),
                "descriptor", RailixValue.string(descriptor), "files", RailixValue.array(files.stream().<RailixValue>map(RailixValue::string).toList()),
                "guidance", RailixValue.string("Descriptor paths are relative to its parent; edit the listed files under the themes directory."))));
    }

    private Response themeFile(final HttpExchange exchange, final String encoded) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) return methodNotAllowed();
        try {
            final String id = URLDecoder.decode(encoded.replace("+", "%2B"), StandardCharsets.UTF_8);
            if (!themeAssetPath(id)) throw new IllegalArgumentException("Theme asset must be a canonical relative CSS, PNG, SVG, or JSON descriptor path.");
            final byte[] local = readTheme(themeDirectory, id);
            final byte[] source = local == null && EMBEDDED_THEME_ASSETS.contains(id) ? embeddedThemeFile(id) : local;
            if (source == null) return themeFailure(404, "theme-not-found", "The requested theme asset is not available.");
            return new Response(200, themeContentType(id), source);
        } catch (final NoSuchFileException | NotDirectoryException failure) {
            return themeFailure(404, "theme-not-found", "The requested theme asset is not available.");
        } catch (final IllegalArgumentException | DirectoryIteratorException failure) {
            return themeFailure(400, "invalid-theme", failure.getMessage());
        }
    }

    private static List<ThemeSpec> embeddedThemes() {
        try {
            final byte[] source = embeddedThemeFile("catalog.json");
            if (source == null) throw new IllegalStateException("Embedded theme catalog is missing.");
            final RailixValue.ObjectValue catalog = soundObject(utf8(source), "Embedded theme catalog must be a JSON object.");
            if (!catalog.values().keySet().equals(Set.of("themes")) || !(catalog.values().get("themes") instanceof RailixValue.ArrayValue themes)) {
                throw new IllegalStateException("Embedded theme catalog must contain only a themes array.");
            }
            final List<ThemeSpec> result = new ArrayList<>();
            for (final RailixValue value : themes.values()) {
                if (!(value instanceof RailixValue.ObjectValue entry) || !entry.values().keySet().equals(Set.of("id", "stylesheet"))) {
                    throw new IllegalStateException("Embedded theme catalog entries require only id and stylesheet.");
                }
                final String id = themeId(entry, "id");
                final String stylesheet = themeString(entry, "stylesheet", true);
                if (!id.isEmpty() && (!id.endsWith(".css") || !canonicalThemePath(id))
                        || !themeAssetPath(stylesheet) || !stylesheet.endsWith(".css")) {
                    throw new IllegalStateException("Embedded theme catalog paths are invalid.");
                }
                final String descriptor = stylesheet.substring(0, stylesheet.length() - ".css".length()) + ".json";
                final byte[] descriptorSource = embeddedThemeFile(descriptor);
                if (descriptorSource == null) throw new IllegalStateException("Embedded theme descriptor is missing: " + descriptor);
                final ThemeSpec parsed = themeSpec(soundObject(utf8(descriptorSource), "Embedded theme descriptor must be a JSON object."), false, stylesheet);
                result.add(new ThemeSpec(id, parsed.name(), parsed.defaultVariant(), parsed.stylesheet(), parsed.variants(), parsed.files(), false));
            }
            if (result.stream().map(ThemeSpec::id).distinct().count() != result.size()) {
                throw new IllegalStateException("Embedded theme ids must be unique.");
            }
            return List.copyOf(result);
        } catch (final IOException | IllegalArgumentException failure) {
            throw new IllegalStateException("Embedded theme catalog could not be loaded.", failure);
        }
    }

    private static Set<String> embeddedThemeAssets() {
        final Set<String> assets = new java.util.HashSet<>();
        EMBEDDED_THEMES.forEach(theme -> {
            assets.addAll(themeFiles(theme));
            assets.add(themeDescriptorPath(theme));
        });
        return Set.copyOf(assets);
    }

    private ThemeSpec localTheme(final String id) throws IOException {
        final String descriptor = id.substring(0, id.length() - ".css".length()) + ".json";
        final byte[] source = readTheme(themeDirectory, descriptor);
        if (source == null) return standaloneTheme(id);
        return themeSpec(soundObject(utf8(source), "Theme descriptor must be a JSON object."), false, id);
    }

    private static ThemeSpec standaloneTheme(final String id) {
        return new ThemeSpec(id, id.substring(id.lastIndexOf('/') + 1, id.length() - ".css".length()), "", id, List.of(), List.of(), false);
    }

    private static ThemeSpec mergeTheme(final ThemeSpec embedded, final ThemeSpec override) {
        return new ThemeSpec(embedded.id(), override.name(), override.defaultVariant(), embedded.stylesheet(),
                override.variants(), override.files(), true);
    }

    private static ThemeSpec themeSpec(final RailixValue.ObjectValue value, final boolean embedded, final String localStylesheet) {
        final Set<String> allowed = embedded
                ? Set.of("id", "name", "defaultVariant", "stylesheet", "variants", "files")
                : Set.of("name", "defaultVariant", "variants", "files");
        if (!allowed.containsAll(value.values().keySet())) throw new IllegalArgumentException("Theme descriptor has unsupported fields.");
        final String id = embedded ? themeId(value, "id") : localStylesheet;
        if (!id.isEmpty() && (!id.endsWith(".css") || !canonicalThemePath(id))) {
            throw new IllegalArgumentException("Theme id must be empty or a canonical CSS path.");
        }
        final String requestedName = themeString(value, "name", false);
        final String name = requestedName.isEmpty() && !embedded ? id.substring(id.lastIndexOf('/') + 1, id.length() - ".css".length()) : requestedName;
        if (name.isEmpty()) throw new IllegalArgumentException("Theme name must be a non-empty string.");
        final String defaultVariant = themeString(value, "defaultVariant", false);
        if (!defaultVariant.isEmpty() && !canonicalSegment(defaultVariant)) {
            throw new IllegalArgumentException("Theme defaultVariant must be empty or a safe variant id.");
        }
        final String stylesheet = embedded ? themeString(value, "stylesheet", true) : id;
        if (!themeAssetPath(stylesheet) || !stylesheet.endsWith(".css")) {
            throw new IllegalArgumentException("Theme stylesheet must be a canonical CSS asset path.");
        }
        final List<RailixValue.ObjectValue> variants = themeVariants(value, embedded ? "" : parentPath(id));
        if (!defaultVariant.isEmpty() && variants.stream().noneMatch(variant -> defaultVariant.equals(themeString(variant, "id", true)))) {
            throw new IllegalArgumentException("Theme defaultVariant must name a listed variant.");
        }
        final List<String> files = themeFiles(value, embedded ? "" : parentPath(id));
        return new ThemeSpec(id, name, defaultVariant, stylesheet, variants, files, !embedded);
    }

    private static List<RailixValue.ObjectValue> themeVariants(final RailixValue.ObjectValue value, final String parent) {
        final RailixValue variantsValue = value.values().get("variants");
        if (variantsValue == null) return List.of();
        if (!(variantsValue instanceof RailixValue.ArrayValue variants)) throw new IllegalArgumentException("Theme variants must be an array.");
        final List<RailixValue.ObjectValue> result = new ArrayList<>();
        final Set<String> ids = new java.util.HashSet<>();
        for (final RailixValue item : variants.values()) {
            if (!(item instanceof RailixValue.ObjectValue variant)
                    || !Set.of("id", "name", "description", "stylesheet", "renderer", "atlas").containsAll(variant.values().keySet())) {
                throw new IllegalArgumentException("Theme variants require id, name and stylesheet; description, renderer and atlas are optional.");
            }
            final String id = themeString(variant, "id", true);
            final String name = themeString(variant, "name", true);
            final String description = themeString(variant, "description", false);
            final String stylesheet = themeAsset(parent, themeString(variant, "stylesheet", true));
            final String requestedRenderer = themeString(variant, "renderer", false);
            final String renderer = requestedRenderer.isEmpty() ? "css" : requestedRenderer;
            final String atlas = themeString(variant, "atlas", false);
            if (!Set.of("css", "canvas").contains(renderer) || "canvas".equals(renderer) != !atlas.isEmpty()
                    || !atlas.isEmpty() && !themeAsset(parent, atlas).endsWith(".json")
                    || !canonicalSegment(id) || !stylesheet.endsWith(".css") || !ids.add(id)) {
                throw new IllegalArgumentException("Theme variants require unique safe ids, CSS stylesheets, and a JSON atlas only for canvas rendering.");
            }
            final Map<String, RailixValue> normalized = new LinkedHashMap<>();
            normalized.put("id", RailixValue.string(id));
            normalized.put("name", RailixValue.string(name));
            normalized.put("description", RailixValue.string(description));
            normalized.put("stylesheet", RailixValue.string(stylesheet));
            normalized.put("renderer", RailixValue.string(renderer));
            if (!atlas.isEmpty()) normalized.put("atlas", RailixValue.string(themeAsset(parent, atlas)));
            result.add(RailixValue.object(normalized));
        }
        return List.copyOf(result);
    }

    private static List<String> themeFiles(final RailixValue.ObjectValue value, final String parent) {
        final RailixValue filesValue = value.values().get("files");
        if (filesValue == null) return List.of();
        if (!(filesValue instanceof RailixValue.ArrayValue files)) throw new IllegalArgumentException("Theme files must be an array.");
        final Set<String> result = new java.util.LinkedHashSet<>();
        for (final RailixValue item : files.values()) {
            if (!(item instanceof RailixValue.StringValue file)) throw new IllegalArgumentException("Theme files must contain strings.");
            final String path = themeAsset(parent, file.value());
            result.add(path);
        }
        return List.copyOf(result);
    }

    private static String themeAsset(final String parent, final String reference) {
        if (!themeAssetPath(reference)) throw new IllegalArgumentException("Theme asset paths must be canonical relative CSS, PNG, SVG, or JSON descriptor paths.");
        final String path = parent + reference;
        if (!themeAssetPath(path)) throw new IllegalArgumentException("Theme asset paths must remain within the descriptor directory.");
        return path;
    }

    private static String parentPath(final String id) {
        final int separator = id.lastIndexOf('/');
        return separator < 0 ? "" : id.substring(0, separator + 1);
    }

    private static String themeString(final RailixValue.ObjectValue value, final String field, final boolean required) {
        final RailixValue candidate = value.values().get(field);
        if (candidate == null && !required) return "";
        if (!(candidate instanceof RailixValue.StringValue string) || required && string.value().isBlank()) {
            throw new IllegalArgumentException("Theme " + field + " must be " + (required ? "a non-empty" : "a") + " string.");
        }
        return string.value();
    }

    private static String themeId(final RailixValue.ObjectValue value, final String field) {
        if (!(value.values().get(field) instanceof RailixValue.StringValue string)) {
            throw new IllegalArgumentException("Theme " + field + " must be a string.");
        }
        return string.value();
    }

    private static RailixValue themeEntry(final ThemeSpec theme, final boolean builtin, final boolean installable) {
        final List<RailixValue> variants = theme.variants().stream().<RailixValue>map(variant -> {
            final Map<String, RailixValue> entry = new LinkedHashMap<>();
            entry.put("id", variant.values().get("id"));
            entry.put("name", variant.values().get("name"));
            entry.put("description", variant.values().get("description"));
            entry.put("renderer", variant.values().get("renderer"));
            entry.put("url", RailixValue.string(themeUrl(themeString(variant, "stylesheet", true))));
            if (variant.values().containsKey("atlas")) entry.put("atlas", RailixValue.string(themeUrl(themeString(variant, "atlas", true))));
            return RailixValue.object(entry);
        }).toList();
        return RailixValue.object(Map.of("id", RailixValue.string(theme.id()), "name", RailixValue.string(theme.name()),
                "builtin", RailixValue.bool(builtin), "stylesheet", RailixValue.string(theme.stylesheet()), "url", RailixValue.string(themeUrl(theme.stylesheet())),
                "defaultVariant", RailixValue.string(theme.defaultVariant()), "variants", RailixValue.array(variants),
                "installable", RailixValue.bool(installable)));
    }

    private static RailixValue themeDiagnostic(final String id, final String message) {
        return RailixValue.object(Map.of("id", RailixValue.string(id), "code", RailixValue.string("invalid-theme-descriptor"),
                "message", RailixValue.string(message == null ? "Theme descriptor is invalid." : message)));
    }

    private static List<String> themeFiles(final ThemeSpec theme) {
        final Set<String> files = new java.util.LinkedHashSet<>();
        files.add(theme.stylesheet());
        theme.variants().forEach(variant -> {
            files.add(themeString(variant, "stylesheet", true));
            if (variant.values().containsKey("atlas")) files.add(themeString(variant, "atlas", true));
        });
        files.addAll(theme.files());
        return List.copyOf(files);
    }

    private static String themeDescriptorPath(final ThemeSpec theme) {
        return theme.stylesheet().substring(0, theme.stylesheet().length() - ".css".length()) + ".json";
    }

    private static String themeDescriptor(final ThemeSpec theme) {
        final String parent = parentPath(themeDescriptorPath(theme));
        final List<RailixValue> variants = theme.variants().stream().<RailixValue>map(variant -> {
            final Map<String, RailixValue> entry = new LinkedHashMap<>();
            entry.put("id", variant.values().get("id"));
            entry.put("name", variant.values().get("name"));
            entry.put("description", variant.values().get("description"));
            entry.put("stylesheet", RailixValue.string(themeString(variant, "stylesheet", true).substring(parent.length())));
            entry.put("renderer", variant.values().get("renderer"));
            if (variant.values().containsKey("atlas")) entry.put("atlas", RailixValue.string(themeString(variant, "atlas", true).substring(parent.length())));
            return RailixValue.object(entry);
        }).toList();
        final List<RailixValue> files = theme.files().stream()
                .filter(file -> file.startsWith(parent))
                .<RailixValue>map(file -> RailixValue.string(file.substring(parent.length())))
                .toList();
        return RailixJson.write(RailixValue.object(Map.of("name", RailixValue.string(theme.name()),
                "defaultVariant", RailixValue.string(theme.defaultVariant()), "variants", RailixValue.array(variants),
                "files", RailixValue.array(files))));
    }

    private static boolean themeAssetPath(final String path) {
        return canonicalThemePath(path) && (path.endsWith(".css") || path.endsWith(".png") || path.endsWith(".svg") || path.endsWith(".json"));
    }

    private static boolean canonicalThemePath(final String path) {
        final Path relative = Path.of(path);
        return canonicalLocalPath(path) && !relative.isAbsolute() && relative.normalize().toString().equals(path);
    }

    private static String themeUrl(final String id) {
        return "/api/themes/files/" + Arrays.stream(id.split("/", -1))
                .map(segment -> java.net.URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"))
                .collect(java.util.stream.Collectors.joining("/"));
    }

    private static String themeContentType(final String id) {
        return id.endsWith(".css") ? "text/css; charset=utf-8"
                : id.endsWith(".png") ? "image/png"
                : id.endsWith(".svg") ? "image/svg+xml"
                : "application/json; charset=utf-8";
    }

    private static byte[] embeddedThemeFile(final String id) throws IOException {
        if (!themeAssetPath(id) && !"catalog.json".equals(id)) return null;
        try (var stream = CreatorServer.class.getResourceAsStream(ASSET_ROOT + "themes/" + id)) {
            return stream == null ? null : stream.readNBytes(MAX_REQUEST_BYTES + 1);
        }
    }

    private static byte[] readTheme(final Path root, final String id) throws IOException {
        return readTheme(root, id, "Theme asset");
    }

    private static byte[] readTheme(final Path root, final String id, final String label) throws IOException {
        if (!themeAssetPath(id)) throw new IllegalArgumentException("Theme asset path is invalid.");
        final Path relative = Path.of(id);
        final Path parent = relative.getParent();
        try (var directory = openLocalDirectory(parent == null ? root : root.resolve(parent))) {
            final Path file = relative.getFileName();
            final var attributes = directory.getFileAttributeView(file, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).readAttributes();
            if (attributes.isSymbolicLink()) throw new IllegalArgumentException("Theme paths must not contain symbolic links.");
            if (!attributes.isRegularFile()) throw new NoSuchFileException(id);
            if (attributes.size() > MAX_REQUEST_BYTES) throw new ThemeTooLarge(label + " exceeds the " + MAX_REQUEST_BYTES + "-byte limit.");
            try (var channel = directory.newByteChannel(file, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                final byte[] source = Channels.newInputStream(channel).readNBytes(MAX_REQUEST_BYTES + 1);
                if (source.length > MAX_REQUEST_BYTES) throw new ThemeTooLarge(label + " exceeds the " + MAX_REQUEST_BYTES + "-byte limit.");
                return source;
            }
        } catch (final NoSuchFileException | NotDirectoryException missing) {
            return null;
        }
    }

    private static boolean createTheme(final Path root, final String id, final byte[] source) throws IOException {
        try (var directory = themeDirectory(root, id)) {
            final Path file = Path.of(id.substring(id.lastIndexOf('/') + 1));
            try (var channel = directory.newByteChannel(file, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS))) {
                write(channel, source);
                return true;
            } catch (final java.nio.file.FileAlreadyExistsException ignored) {
                return false;
            }
        }
    }

    private static SecureDirectoryStream<Path> themeDirectory(final Path root, final String id) throws IOException {
        SecureDirectoryStream<Path> current = openLocalDirectory(root);
        try {
            final String[] parts = id.split("/");
            for (int index = 0; index < parts.length - 1; index++) {
                final Path component = Path.of(parts[index]);
                final SecureDirectoryStream<Path> parent = current;
                try {
                    try {
                        if (!parent.getFileAttributeView(component, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).readAttributes().isDirectory()) {
                            throw new IllegalArgumentException("Theme directories must be regular directories.");
                        }
                    } catch (final NoSuchFileException missing) {
                        createSoundGroup(parent, component);
                    }
                    current = parent.newDirectoryStream(component, LinkOption.NOFOLLOW_LINKS);
                } finally {
                    parent.close();
                }
            }
            return current;
        } catch (final IOException | RuntimeException | Error failure) {
            try { current.close(); } catch (final IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    private Response settings(final HttpExchange exchange) throws IOException {
        try {
            if ("GET".equals(exchange.getRequestMethod())) return settingsResponse(readSettings());
            if (!"POST".equals(exchange.getRequestMethod())) return methodNotAllowed();
            final BodyRead body = body(exchange, MAX_SETTINGS_BYTES);
            if (body.status() != 200) return bodyResponse(body);
            final RailixValue.ObjectValue request = soundObject(utf8(body.value()), "Settings request must be a JSON object.");
            if (!request.values().keySet().equals(Set.of("revision", "values"))
                    || !(request.values().get("revision") instanceof RailixValue.StringValue revision)
                    || !(request.values().get("values") instanceof RailixValue.ObjectValue values)) {
                throw new IllegalArgumentException("Settings request requires revision and values.");
            }
            final RailixValue.ObjectValue valid = settingsValues(values);
            try (FileChannel channel = FileChannel.open(settingsDirectory.resolve(SETTINGS_LOCK_FILE),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 FileLock ignored = fileLock(channel, SettingsRevisionConflict::new)) {
                final SettingsSnapshot current = readSettings();
                if (!revision.value().equals(current.revision())) throw new SettingsRevisionConflict();
                persistSettings(RailixJson.write(valid));
                return settingsResponse(readSettings());
            }
        } catch (final SettingsRevisionConflict failure) {
            return themeFailure(409, "settings-revision-conflict", "Settings changed. Refresh Creator-wide preferences.");
        } catch (final IllegalArgumentException | DirectoryIteratorException failure) {
            return themeFailure(400, "invalid-settings", failure.getMessage());
        }
    }

    private Response settingsResponse(final SettingsSnapshot settings) {
        return json(200, RailixValue.object(Map.of(
                "revision", RailixValue.string(settings.revision()), "values", settings.values(),
                "diagnostics", RailixValue.array(settings.diagnostics())
        )));
    }

    private SettingsSnapshot readSettings() throws IOException {
        String source;
        try (var directory = openLocalDirectory(settingsDirectory)) {
            final Path file = Path.of(SETTINGS_FILE);
            try {
                final var attributes = directory.getFileAttributeView(file, BasicFileAttributeView.class,
                        LinkOption.NOFOLLOW_LINKS).readAttributes();
                if (attributes.isSymbolicLink() || !attributes.isRegularFile()) {
                    throw new IllegalArgumentException("Creator settings must be a regular file, not a symbolic link.");
                }
                if (attributes.size() > MAX_SETTINGS_BYTES) throw new IllegalArgumentException("Creator settings exceed the byte limit.");
                try (var channel = directory.newByteChannel(file, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                    final byte[] bytes = Channels.newInputStream(channel).readNBytes(MAX_SETTINGS_BYTES + 1);
                    if (bytes.length > MAX_SETTINGS_BYTES) throw new IllegalArgumentException("Creator settings exceed the byte limit.");
                    source = utf8(bytes);
                }
            } catch (final NoSuchFileException missing) {
                source = RailixJson.write(defaultSettings());
            }
        } catch (final IllegalArgumentException | CharacterCodingException failure) {
            return invalidSettings("Saved Creator settings are invalid: " + failure.getMessage(), "");
        }
        try {
            return new SettingsSnapshot(contentRevision(source), settingsValues(soundObject(source,
                    "Saved Creator settings must be a JSON object.")), List.of());
        } catch (final IllegalArgumentException failure) {
            return invalidSettings("Saved Creator settings are invalid: " + failure.getMessage(), source);
        }
    }

    private SettingsSnapshot invalidSettings(final String message, final String source) {
        return new SettingsSnapshot(contentRevision(source), defaultSettings(), List.of(RailixValue.object(Map.of(
                "code", RailixValue.string("invalid-settings"), "message", RailixValue.string(message)
        ))));
    }

    private void persistSettings(final String source) throws IOException {
        try (var directory = openLocalDirectory(settingsDirectory)) {
            final Path file = Path.of(SETTINGS_FILE);
            try {
                if (directory.getFileAttributeView(file, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                        .readAttributes().isSymbolicLink()) throw new IllegalArgumentException("Creator settings must not be a symbolic link.");
            } catch (final NoSuchFileException ignored) { }
            final Path temporary = Path.of("." + SETTINGS_FILE + "." + token() + ".tmp");
            try {
                try (var channel = directory.newByteChannel(temporary, Set.of(
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS))) {
                    write(channel, source.getBytes(StandardCharsets.UTF_8));
                }
                Files.move(settingsDirectory.resolve(temporary), settingsDirectory.resolve(file),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                try { directory.deleteFile(temporary); } catch (final NoSuchFileException ignored) { }
            }
        }
    }

    private static FileLock fileLock(final FileChannel channel,
                                    final java.util.function.Supplier<? extends RuntimeException> conflict) throws IOException {
        try {
            final FileLock lock = channel.tryLock();
            if (lock != null) return lock;
        } catch (final OverlappingFileLockException ignored) { }
        throw conflict.get();
    }

    private static String contentRevision(final String source) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (final java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable.", unavailable);
        }
    }

    private static RailixValue.ObjectValue defaultSettings() {
        return RailixValue.object(Map.of(
                "theme", RailixValue.string(""), "theme_variant", RailixValue.string(""), "reduced_motion", RailixValue.bool(false), "effects", RailixValue.bool(true),
                "effects_volume", RailixValue.number(java.math.BigDecimal.valueOf(.35)),
                "music_volume", RailixValue.number(java.math.BigDecimal.valueOf(.25)),
                "music_enabled", RailixValue.bool(true), "music", RailixValue.string(""), "sounds", RailixValue.object(Map.of())
        ));
    }

    private static RailixValue.ObjectValue settingsValues(final RailixValue.ObjectValue values) {
        final var fields = new LinkedHashMap<>(values.values());
        fields.putIfAbsent("music_enabled", RailixValue.bool(true));
        fields.putIfAbsent("theme_variant", RailixValue.string(""));
        if (!fields.keySet().equals(Set.of("theme", "theme_variant", "reduced_motion", "effects", "effects_volume", "music_volume", "music_enabled", "music", "sounds"))) {
            throw new IllegalArgumentException("Creator settings have unsupported fields.");
        }
        fields.computeIfPresent("theme", (ignored, value) -> value instanceof RailixValue.StringValue theme
                ? RailixValue.string(normalizeThemeId(theme.value())) : value);
        fields.computeIfPresent("music", (ignored, value) -> value instanceof RailixValue.StringValue music
                ? RailixValue.string(normalizeMusicChoice(music.value())) : value);
        if (fields.get("sounds") instanceof RailixValue.ObjectValue sounds) {
            final Map<String, RailixValue> choices = new LinkedHashMap<>(sounds.values());
            choices.replaceAll((ignored, value) -> value instanceof RailixValue.StringValue score
                    ? RailixValue.string(normalizeScoreKey(score.value())) : value);
            fields.put("sounds", RailixValue.object(choices));
        }
        final RailixValue.ObjectValue normalized = RailixValue.object(fields);
        final String theme = settingsString(normalized, "theme");
        if (!theme.isEmpty() && (!theme.endsWith(".css") || !canonicalLocalPath(theme) || !Path.of(theme).normalize().toString().equals(theme))) {
            throw new IllegalArgumentException("Theme must be empty or a canonical local CSS path.");
        }
        final String themeVariant = settingsString(normalized, "theme_variant");
        if (!themeVariant.isEmpty() && !canonicalSegment(themeVariant)) {
            throw new IllegalArgumentException("Theme variant must be empty or a safe single-segment id.");
        }
        if (!(normalized.values().get("reduced_motion") instanceof RailixValue.BooleanValue)
                || !(normalized.values().get("effects") instanceof RailixValue.BooleanValue)
                || !(normalized.values().get("music_enabled") instanceof RailixValue.BooleanValue)) {
            throw new IllegalArgumentException("Motion, effects, and music settings must be boolean.");
        }
        settingsVolume(normalized, "effects_volume");
        settingsVolume(normalized, "music_volume");
        final String music = settingsString(normalized, "music");
        if (!music.isEmpty() && !(music.startsWith("group:") && (music.equals("group:") || canonicalSegment(music.substring("group:".length())))
                || music.startsWith("track:") && settingsScoreKey(music.substring("track:".length())))) {
            throw new IllegalArgumentException("Music must be empty, group:name, or track:score-key.");
        }
        if (!(normalized.values().get("sounds") instanceof RailixValue.ObjectValue sounds) || sounds.values().size() > 32) {
            throw new IllegalArgumentException("Sounds must contain at most 32 event mappings.");
        }
        for (final Map.Entry<String, RailixValue> entry : sounds.values().entrySet()) {
            if (!eventId(entry.getKey()) || !(entry.getValue() instanceof RailixValue.StringValue score)
                    || !settingsScoreKey(score.value())) throw new IllegalArgumentException("Sound mappings must use embedded event ids and score keys.");
        }
        return normalized;
    }

    private static String settingsString(final RailixValue.ObjectValue values, final String field) {
        if (!(values.values().get(field) instanceof RailixValue.StringValue value)) {
            throw new IllegalArgumentException("Creator setting " + field + " must be a string.");
        }
        return value.value();
    }

    private static void settingsVolume(final RailixValue.ObjectValue values, final String field) {
        if (!(values.values().get(field) instanceof RailixValue.NumberValue value) || !Double.isFinite(value.value().doubleValue())
                || value.value().doubleValue() < 0 || value.value().doubleValue() > 1) {
            throw new IllegalArgumentException("Creator setting " + field + " must be from 0 through 1.");
        }
    }

    private static boolean settingsScoreKey(final String key) {
        final int delimiter = key.indexOf(':');
        if (delimiter <= 0 || !(key.startsWith("builtin:") || key.startsWith("local:"))) return false;
        try {
            soundId(key.substring(delimiter + 1));
            return true;
        } catch (final IllegalArgumentException invalid) {
            return false;
        }
    }

    private static String normalizeThemeId(final String theme) {
        return switch (theme) {
            case "railix.css" -> "";
            case "classic.css" -> "classic/theme.css";
            default -> theme;
        };
    }

    private static String normalizeMusicChoice(final String value) {
        return value.startsWith("track:") ? "track:" + normalizeScoreKey(value.substring("track:".length())) : value;
    }

    private static String normalizeScoreKey(final String key) {
        final int delimiter = key.indexOf(':');
        if (delimiter <= 0) return key;
        final String scope = key.substring(0, delimiter + 1);
        String id = key.substring(delimiter + 1);
        if (!(scope.equals("builtin:") || scope.equals("local:"))) return key;
        if (id.startsWith("music/")) id = "music/" + mmlDefaultId(id.substring("music/".length()));
        else if (!id.startsWith("sounds/")) id = "sounds/" + mmlDefaultId(id);
        return scope + id;
    }

    private static boolean canonicalSegment(final String value) {
        return canonicalLocalPath(value) && !value.contains("/");
    }

    private static boolean eventId(final String event) {
        return DEFAULT_SCORES.keySet().stream().filter(id -> id.startsWith("sounds/"))
                .map(id -> id.substring("sounds/".length(), id.length() - ".mml".length())).anyMatch(event::equals);
    }

    private Response sounds(final HttpExchange exchange) throws IOException {
        try {
            if ("GET".equals(exchange.getRequestMethod())) {
                return json(200, soundListing());
            }
            if (!"POST".equals(exchange.getRequestMethod())) return methodNotAllowed();
            final BodyRead body = body(exchange, 65_536);
            if (body.status() != 200) return bodyResponse(body);
            final RailixValue.ObjectValue request = soundObject(utf8(body.value()), "Sound request must be a JSON object.");
            final String action = soundString(request, "action");
            if ("preview".equals(action)) return previewSound(request);
            try (FileChannel channel = FileChannel.open(settingsDirectory.resolve(SOUND_LOCK_FILE),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 FileLock ignored = fileLock(channel, () -> new SoundRevisionConflict("Sound library is being edited. Refresh and retry."))) {
                return switch (action) {
                    case "install-defaults" -> installSoundDefaults(request);
                    case "save" -> saveSound(request);
                    case "copy" -> copySound(request);
                    case "delete" -> deleteSound(request);
                    default -> throw new IllegalArgumentException("Sound action must be save, delete, or install-defaults.");
                };
            }
        } catch (final NoSuchFileException | NotDirectoryException failure) {
            return themeFailure(404, "sound-not-found", "Sound file is not available.");
        } catch (final SoundRevisionConflict failure) {
            return themeFailure(409, "sound-revision-conflict", failure.getMessage());
        } catch (final IllegalArgumentException | DirectoryIteratorException failure) {
            return themeFailure(400, "invalid-sound", failure.getMessage());
        }
    }

    private RailixValue.ObjectValue soundListing() throws IOException {
        final Map<String, RailixValue.ObjectValue> local = new LinkedHashMap<>();
        final Map<String, RailixValue> sources = new java.util.TreeMap<>();
        final List<RailixValue> invalid = new ArrayList<>();
        localScores(soundDirectory, "sounds/", local, invalid, sources);
        localScores(musicDirectory, "music/", local, invalid, sources);
        // Legacy mixed music stays readable but loses to its canonical counterpart.
        localScores(legacyMusicDirectory, "music/", local, invalid, sources);
        final List<RailixValue> scores = new ArrayList<>();
        DEFAULT_SCORES.forEach((id, score) -> scores.add(soundEntry(id, score, true)));
        local.forEach((id, score) -> scores.add(soundEntry(id, score, false)));
        final List<RailixValue> events = DEFAULT_SCORES.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("sounds/"))
                .<RailixValue>map(entry -> RailixValue.object(Map.of(
                        "id", RailixValue.string(entry.getKey().substring("sounds/".length(), entry.getKey().length() - ".mml".length())),
                        "name", entry.getValue().values().get("name"),
                        "default", RailixValue.string("builtin:" + entry.getKey())
                ))).toList();
        return RailixValue.object(Map.of(
                "directory", RailixValue.string(soundDirectory + " and " + musicDirectory),
                "scores", RailixValue.array(scores),
                "events", RailixValue.array(events),
                "invalid", RailixValue.array(invalid),
                "revision", RailixValue.string(contentRevision(RailixJson.write(RailixValue.object(sources))))
        ));
    }

    private void localScores(final Path root, final String prefix, final Map<String, RailixValue.ObjectValue> local,
                             final List<RailixValue> invalid, final Map<String, RailixValue> sources) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        final var files = localFiles(root, candidate -> legacySoundFile(prefix, candidate)).stream()
                .sorted(java.util.Comparator.comparing((String file) -> !file.endsWith(".mml")).thenComparing(file -> file)).toList();
        for (final String relative : files) {
            final String id = prefix + mmlDefaultId(relative);
            if (local.containsKey(id)) continue;
            String source = "";
            try {
                source = readSound(root, relative);
                sources.put(root.resolve(relative).toString(), RailixValue.string(source));
                local.put(id, soundScore(source));
            } catch (final IOException | IllegalArgumentException failure) {
                sources.put(root.resolve(relative).toString(), RailixValue.string(source + "\n" + failure.getMessage()));
                invalid.add(RailixValue.object(Map.of("id", RailixValue.string(id),
                        "message", RailixValue.string(failure.getMessage()), "source", RailixValue.string(source))));
            }
        }
    }

    private Response installSoundDefaults(final RailixValue.ObjectValue request) throws IOException {
        soundRevision(request, Set.of("action", "revision"));
        int installed = 0;
        for (final var entry : DEFAULT_SCORES.entrySet()) {
            final RailixValue content = entry.getValue().values().get("content");
            final String source = content instanceof RailixValue.StringValue text ? text.value() : mmlSource(entry.getValue());
            final SoundPath target = soundPath(entry.getKey());
            if (createSound(target.root(), target.relative(), source)) installed++;
        }
        return soundResponse("defaults-installed", Map.of("installed", RailixValue.number(installed)));
    }

    private Response saveSound(final RailixValue.ObjectValue request) throws IOException {
        if (!request.values().keySet().equals(Set.of("action", "id", "revision", "content"))
                && !request.values().keySet().equals(Set.of("action", "id", "revision", "score"))) {
            throw new IllegalArgumentException("Sound save requires id, revision, and MML content.");
        }
        soundRevision(request, request.values().containsKey("content")
                ? Set.of("action", "id", "revision", "content") : Set.of("action", "id", "revision", "score"));
        final String id = soundId(soundString(request, "id"));
        final String source;
        if (request.values().get("content") instanceof RailixValue.StringValue content) source = content.value();
        else if (request.values().get("score") instanceof RailixValue.ObjectValue score) source = mmlSource(soundScore(RailixJson.write(score)));
        else throw new IllegalArgumentException("Sound save requires MML content.");
        soundScore(source);
        final SoundPath target = soundPath(id);
        writeSound(target.root(), target.relative(), source);
        return soundResponse("sound-saved", Map.of("id", RailixValue.string(id)));
    }

    private Response copySound(final RailixValue.ObjectValue request) throws IOException {
        soundRevision(request, Set.of("action", "id", "revision", "content"));
        final String id = soundId(soundString(request, "id"));
        final String source = soundString(request, "content");
        soundScore(source);
        final SoundPath target = soundPath(id);
        if (!createSound(target.root(), target.relative(), source)) {
            throw new IllegalArgumentException("Sound copy already exists; choose a new name to preserve the custom file.");
        }
        return soundResponse("sound-copied", Map.of("id", RailixValue.string(id)));
    }

    private Response previewSound(final RailixValue.ObjectValue request) {
        if (!request.values().keySet().equals(Set.of("action", "content"))) {
            throw new IllegalArgumentException("Sound preview requires MML content.");
        }
        final RailixValue.ObjectValue score = soundScore(soundString(request, "content"));
        return json(200, RailixValue.object(Map.of("status", RailixValue.string("sound-preview"), "score", score)));
    }

    private Response deleteSound(final RailixValue.ObjectValue request) throws IOException {
        soundRevision(request, Set.of("action", "id", "revision"));
        final String id = soundId(soundString(request, "id"));
        final SoundPath target = soundPath(id);
        try (var directory = soundDirectory(target.root(), target.relative(), false)) {
            final Path file = Path.of(target.relative().substring(target.relative().lastIndexOf('/') + 1));
            if (!directory.getFileAttributeView(file, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).readAttributes().isRegularFile()) {
                throw new IllegalArgumentException("Sound must be a regular MML or legacy JSON file.");
            }
            directory.deleteFile(file);
        }
        return soundResponse("sound-deleted", Map.of());
    }

    private static Map<String, RailixValue.ObjectValue> soundDefaults() {
        try (var stream = CreatorServer.class.getResourceAsStream(ASSET_ROOT + "sounds/catalog.json")) {
            if (stream == null) throw new IllegalStateException("Embedded sound catalog is missing.");
            final var catalog = soundObject(utf8(stream.readAllBytes()), "Embedded sounds must be a JSON object.");
            if (!(catalog.values().get("scores") instanceof RailixValue.ArrayValue entries)
                    || !catalog.values().keySet().equals(Set.of("scores"))) throw new IllegalStateException("Embedded sound catalog must contain scores.");
            final Map<String, RailixValue.ObjectValue> scores = new java.util.TreeMap<>();
            for (final RailixValue value : entries.values()) {
                if (!(value instanceof RailixValue.ObjectValue entry) || !entry.values().keySet().equals(Set.of("path"))) {
                    throw new IllegalStateException("Embedded sound catalog entries require a path.");
                }
                final String path = soundId(soundString(entry, "path"));
                try (var score = CreatorServer.class.getResourceAsStream(ASSET_ROOT + path)) {
                    if (score == null) throw new IllegalStateException("Embedded sound is missing: " + path);
                    scores.put(path, soundScore(utf8(score.readNBytes(65_537))));
                }
            }
            return java.util.Collections.unmodifiableMap(scores);
        } catch (final IOException failure) {
            throw new IllegalStateException("Embedded sound catalog could not be loaded.", failure);
        }
    }

    private static RailixValue.ObjectValue soundEntry(final String id, final RailixValue.ObjectValue score, final boolean builtin) {
        final Map<String, RailixValue> editable = new LinkedHashMap<>(score.values());
        if (!editable.containsKey("content")) editable.put("content", RailixValue.string(mmlSource(score)));
        final String displayId = id;
        final String[] parts = displayId.split("/");
        final String group = !displayId.startsWith("music/") ? "effects" : parts.length == 2 ? "" : parts[1];
        return RailixValue.object(Map.of("id", RailixValue.string(displayId), "key", RailixValue.string((builtin ? "builtin:" : "local:") + id), "group", RailixValue.string(group),
                "builtin", RailixValue.bool(builtin), "score", RailixValue.object(editable)));
    }

    private static String mmlDefaultId(final String id) {
        return id.endsWith(".json") ? id.substring(0, id.length() - 5) + ".mml" : id;
    }

    private static String mmlSource(final RailixValue.ObjectValue score) {
        final StringBuilder source = new StringBuilder("name: ").append(settingsString(score, "name"))
                .append("\ntempo: ").append(number(score, "tempo"));
        final RailixValue.ArrayValue tracks = (RailixValue.ArrayValue) score.values().get("tracks");
        for (final RailixValue value : tracks.values()) {
            final RailixValue.ObjectValue track = (RailixValue.ObjectValue) value;
            source.append('\n').append(settingsString(track, "waveform")).append(' ').append(decimal(track, "volume"))
                    .append(' ').append(decimal(track, "attack")).append(' ').append(decimal(track, "release")).append(" |");
            for (final RailixValue noteValue : ((RailixValue.ArrayValue) track.values().get("notes")).values()) {
                final RailixValue.ObjectValue note = (RailixValue.ObjectValue) noteValue;
                final double duration = decimal(note, "duration");
                if (note.values().get("note") instanceof RailixValue.NullValue) source.append(" r@").append(duration);
                else source.append(' ').append(mmlPitch(number(note, "note"))).append('@').append(duration);
            }
        }
        return source.toString();
    }

    private static String mmlPitch(final long midi) {
        final String[] names = {"c", "c+", "d", "d+", "e", "f", "f+", "g", "g+", "a", "a+", "b"};
        return "o" + ((midi - 12) / 12) + " " + names[(int) midi % 12];
    }

    private Response soundResponse(final String status, final Map<String, RailixValue> values) throws IOException {
        final Map<String, RailixValue> response = new LinkedHashMap<>(values);
        response.put("status", RailixValue.string(status));
        response.put("revision", soundListing().values().get("revision"));
        return json(200, RailixValue.object(response));
    }

    private void soundRevision(final RailixValue.ObjectValue request, final Set<String> fields) throws IOException {
        if (!request.values().keySet().equals(fields)) throw new IllegalArgumentException("Sound request has unsupported fields.");
        if (!(request.values().get("revision") instanceof RailixValue.StringValue revision)
                || !revision.value().matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Sound revision must be a content hash from the library.");
        if (!revision.equals(soundListing().values().get("revision"))) throw new SoundRevisionConflict("Sound revision conflict. Refresh the library and reselect the file before editing again; your draft is retained.");
    }

    private static String readSound(final Path root, final String id) throws IOException {
        try (var directory = soundDirectory(root, id, false)) {
            final Path file = Path.of(id.substring(id.lastIndexOf('/') + 1));
            if (!directory.getFileAttributeView(file, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).readAttributes().isRegularFile()) {
                throw new IllegalArgumentException("Sound must be a regular MML or legacy JSON file.");
            }
            try (var channel = directory.newByteChannel(file, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                if (channel.size() > 65_536) throw new IllegalArgumentException("Sound score exceeds the 65536-byte limit.");
                final byte[] source = new byte[(int) channel.size()];
                final ByteBuffer buffer = ByteBuffer.wrap(source);
                while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                    // A secure channel can legally return partial reads.
                }
                if (buffer.hasRemaining()) throw new IOException("Sound changed while reading.");
                return utf8(source);
            }
        }
    }

    private static void writeSound(final Path root, final String id, final String source) throws IOException {
        try (var directory = soundDirectory(root, id, true)) {
            final Path file = Path.of(id.substring(id.lastIndexOf('/') + 1));
            final Path temporary = Path.of("." + file + "." + token() + ".tmp");
            try {
                try (var channel = directory.newByteChannel(temporary, Set.of(
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS))) {
                    write(channel, source.getBytes(StandardCharsets.UTF_8));
                }
                directory.move(temporary, directory, file);
            } finally {
                try { directory.deleteFile(temporary); } catch (final NoSuchFileException ignored) { }
            }
        }
    }

    private static boolean createSound(final Path root, final String id, final String source) throws IOException {
        try (var directory = soundDirectory(root, id, true)) {
            final Path file = Path.of(id.substring(id.lastIndexOf('/') + 1));
            try (var channel = directory.newByteChannel(file, Set.of(
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS))) {
                write(channel, source.getBytes(StandardCharsets.UTF_8));
                return true;
            } catch (final java.nio.file.FileAlreadyExistsException ignored) {
                return false;
            }
        }
    }

    private static void write(final java.nio.channels.SeekableByteChannel channel, final byte[] source) throws IOException {
        final ByteBuffer buffer = ByteBuffer.wrap(source);
        while (buffer.hasRemaining()) channel.write(buffer);
    }

    private static SecureDirectoryStream<Path> soundDirectory(final Path root, final String id, final boolean createGroup) throws IOException {
        final String[] parts = id.split("/");
        SecureDirectoryStream<Path> current = openLocalDirectory(root);
        try {
            for (int index = 0; index < parts.length - 1; index++) {
                final Path component = Path.of(parts[index]);
                final SecureDirectoryStream<Path> parent = current;
                try {
                    if (!parent.getFileAttributeView(component, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                            .readAttributes().isDirectory()) throw new IllegalArgumentException("Sound directories must not be symbolic links.");
                    current = parent.newDirectoryStream(component, LinkOption.NOFOLLOW_LINKS);
                } catch (final NoSuchFileException missing) {
                    if (createGroup && index == parts.length - 2) {
                        createSoundGroup(parent, component);
                        current = parent.newDirectoryStream(component, LinkOption.NOFOLLOW_LINKS);
                    } else {
                        throw missing;
                    }
                } finally {
                    parent.close();
                }
            }
            return current;
        } catch (final IOException | RuntimeException | Error failure) {
            try { current.close(); } catch (final IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    /** Creates only a missing music group by moving an owned empty directory into the pinned parent. */
    private static void createSoundGroup(final SecureDirectoryStream<Path> parent, final Path component) throws IOException {
        final Path temporary = Files.createTempDirectory("railix-sound-group-").toRealPath();
        try (var temporaryParent = openLocalDirectory(temporary.getParent())) {
            try {
                temporaryParent.move(temporary.getFileName(), parent, component);
            } catch (final java.nio.file.FileAlreadyExistsException ignored) {
                // A concurrent creator won; reopening below still verifies it without following links.
            } catch (final java.nio.file.AtomicMoveNotSupportedException unsupported) {
                throw new IllegalArgumentException("Secure music-group creation is unavailable on this filesystem.", unsupported);
            }
        } finally {
            try { Files.deleteIfExists(temporary); } catch (final NoSuchFileException ignored) { }
        }
    }

    private static String soundId(final String id) {
        final Path relative = Path.of(id);
        final String[] parts = id.split("/");
        if (!canonicalLocalPath(id) || relative.isAbsolute() || !relative.normalize().toString().equals(id) || !id.endsWith(".mml")
                || !(parts.length == 2 && "sounds".equals(parts[0]) || parts.length == 2 && "music".equals(parts[0])
                || parts.length == 3 && "music".equals(parts[0]))) {
            throw new IllegalArgumentException("Sound id must be sounds/name.mml, music/name.mml, or music/group/name.mml.");
        }
        return id;
    }

    private static boolean legacySoundFile(final String prefix, final String id) {
        try {
            soundId(prefix + mmlDefaultId(id));
            return true;
        } catch (final IllegalArgumentException ignored) {
            return false;
        }
    }

    private SoundPath soundPath(final String id) {
        final String canonical = soundId(id);
        if (canonical.startsWith("sounds/")) return new SoundPath(soundDirectory, canonical.substring("sounds/".length()));
        return new SoundPath(musicDirectory, canonical.substring("music/".length()));
    }

    private static RailixValue.ObjectValue soundObject(final String source, final String message) {
        final RailixJson.Result parsed = RailixJson.parse(source);
        if (!(parsed instanceof RailixJson.Parsed valid) || !(valid.value() instanceof RailixValue.ObjectValue object)) {
            throw new IllegalArgumentException(message);
        }
        return object;
    }

    private static String soundString(final RailixValue.ObjectValue object, final String field) {
        if (!(object.values().get(field) instanceof RailixValue.StringValue value) || value.value().isBlank()) {
            throw new IllegalArgumentException("Sound " + field + " must be a non-empty string.");
        }
        return value.value();
    }

    private static RailixValue.ObjectValue soundScore(final String source) {
        if (source.getBytes(StandardCharsets.UTF_8).length > 65_536) throw new IllegalArgumentException("Sound score exceeds the 65536-byte limit.");
        if (!source.stripLeading().startsWith("{")) return mmlScore(source);
        final RailixValue.ObjectValue score = soundObject(source, "Sound score must be a JSON object.");
        if (score.values().get("content") instanceof RailixValue.StringValue content) return mmlScore(content.value());
        if (!score.values().keySet().equals(Set.of("version", "name", "tempo", "tracks"))
                || number(score, "version") != 1 || !(score.values().get("name") instanceof RailixValue.StringValue name)
                || name.value().isBlank() || name.value().length() > 120 || number(score, "tempo") < 40 || number(score, "tempo") > 240
                || !(score.values().get("tracks") instanceof RailixValue.ArrayValue tracks) || tracks.values().isEmpty() || tracks.values().size() > 8) {
            throw new IllegalArgumentException("Sound score requires version 1, name, tempo 40-240, and 1-8 tracks.");
        }
        int notes = 0;
        for (final RailixValue value : tracks.values()) {
            if (!(value instanceof RailixValue.ObjectValue track) || !track.values().keySet().equals(Set.of("waveform", "volume", "attack", "release", "notes"))
                    || !(track.values().get("waveform") instanceof RailixValue.StringValue waveform)
                    || !SOUND_INSTRUMENTS.contains(waveform.value())
                    || decimal(track, "volume") < 0 || decimal(track, "volume") > 1 || decimal(track, "attack") < 0 || decimal(track, "attack") > 1
                    || decimal(track, "release") < 0 || decimal(track, "release") > 2 || !(track.values().get("notes") instanceof RailixValue.ArrayValue sequence) || sequence.values().isEmpty()) {
                throw new IllegalArgumentException("Sound tracks require a waveform, bounded envelope, volume, and notes.");
            }
            notes += sequence.values().size();
            for (final RailixValue noteValue : sequence.values()) {
                if (!(noteValue instanceof RailixValue.ObjectValue note) || !note.values().keySet().equals(Set.of("note", "duration"))
                        || decimal(note, "duration") < 1d / 64d || decimal(note, "duration") > 16
                        || !(note.values().get("note") instanceof RailixValue.NullValue) && (number(note, "note") < 24 || number(note, "note") > 108)) {
                    throw new IllegalArgumentException("Sound notes require a MIDI note 24-108 or null rest and duration 1/64-16 beats.");
                }
            }
        }
        if (notes > MAX_SOUND_NOTES) throw new IllegalArgumentException("Sound score exceeds the " + MAX_SOUND_NOTES + "-note limit.");
        return score;
    }

    /** Validates compact declarative MML without executing user supplied source. */
    private static RailixValue.ObjectValue mmlScore(final String source) {
        final String[] lines = source.replace("\r", "").split("\n");
        String name = "";
        int tempo = 120;
        final List<RailixValue> tracks = new ArrayList<>();
        int noteCount = 0;
        for (final String raw : lines) {
            final String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("name:")) {
                if (!tracks.isEmpty()) throw new IllegalArgumentException("MML headers must appear before instrument lines.");
                name = line.substring(5).strip();
                continue;
            }
            if (line.startsWith("tempo:")) {
                if (!tracks.isEmpty()) throw new IllegalArgumentException("MML headers must appear before instrument lines.");
                try { tempo = Integer.parseInt(line.substring(6).strip()); }
                catch (final NumberFormatException invalid) { throw new IllegalArgumentException("MML tempo must be an integer from 40 to 240."); }
                continue;
            }
            final int divider = line.indexOf('|');
            if (divider < 1) {
                throw new IllegalArgumentException("MML tracks use 'instrument volume attack release | notes'.");
            }
            final String[] instrument = line.substring(0, divider).strip().split("\\s+");
            if (instrument.length < 4 || !SOUND_INSTRUMENTS.contains(instrument[0])) {
                throw new IllegalArgumentException("MML tracks use a supported instrument and three envelope values.");
            }
            final double volume = mmlDecimal(instrument[1], "volume", 0, 1);
            final double attack = mmlDecimal(instrument[2], "attack", 0, 1);
            final double release = mmlDecimal(instrument[3], "release", 0, 2);
            final MmlSequence sequence = mmlNotes(line.substring(divider + 1).strip());
            if (sequence.tempo() != 0 && sequence.tempo() != tempo) {
                throw new IllegalArgumentException("MML track tempo conflicts with the score tempo header.");
            }
            final List<RailixValue> notes = sequence.notes();
            noteCount += mmlNoteCount(notes);
            if (noteCount > MAX_SOUND_NOTES) throw new IllegalArgumentException("MML exceeds the " + MAX_SOUND_NOTES + "-note limit.");
            final Map<String, RailixValue> track = new LinkedHashMap<>(Map.of("waveform", RailixValue.string(instrument[0]),
                    "volume", RailixValue.number(java.math.BigDecimal.valueOf(volume)),
                    "attack", RailixValue.number(java.math.BigDecimal.valueOf(attack)),
                    "release", RailixValue.number(java.math.BigDecimal.valueOf(release)),
                    "notes", RailixValue.array(notes)));
            for (int index = 4; index < instrument.length; index++) {
                final String[] option = instrument[index].split("=", -1);
                if (option.length != 2 || track.containsKey(option[0])) {
                    throw new IllegalArgumentException("MML tone controls use distinct name=value pairs.");
                }
                final double value = switch (option[0]) {
                    case "decay" -> mmlDecimal(option[1], option[0], 0, 2);
                    case "sustain" -> mmlDecimal(option[1], option[0], 0, 1);
                    case "cutoff" -> mmlDecimal(option[1], option[0], 40, 16000);
                    default -> throw new IllegalArgumentException("MML tone controls are decay, sustain and cutoff.");
                };
                track.put(option[0], RailixValue.number(java.math.BigDecimal.valueOf(value)));
            }
            tracks.add(RailixValue.object(track));
        }
        if (name.isBlank() || name.length() > 120 || tempo < 40 || tempo > 240 || tracks.isEmpty() || tracks.size() > 8) {
            throw new IllegalArgumentException("MML requires name, tempo 40-240, and 1-8 instrument lines.");
        }
        return RailixValue.object(Map.of("version", RailixValue.number(java.math.BigDecimal.valueOf(2)),
                "name", RailixValue.string(name), "tempo", RailixValue.number(java.math.BigDecimal.valueOf(tempo)),
                "content", RailixValue.string(source), "tracks", RailixValue.array(tracks)));
    }

    private static double mmlDecimal(final String value, final String field, final double minimum, final double maximum) {
        try {
            final double decimal = Double.parseDouble(value);
            if (Double.isFinite(decimal) && decimal >= minimum && decimal <= maximum) return decimal;
        } catch (final NumberFormatException ignored) {
            // The diagnostic below is stable for malformed numeric fields.
        }
        throw new IllegalArgumentException("MML " + field + " must be from " + minimum + " to " + maximum + ".");
    }

    private static MmlSequence mmlNotes(final String source) {
        if (source.isBlank()) throw new IllegalArgumentException("MML instrument lines require notes.");
        List<RailixValue> notes = new ArrayList<>();
        final ArrayDeque<List<RailixValue>> repeats = new ArrayDeque<>();
        int octave = 4;
        int length = 8;
        int tempo = 0;
        for (final String token : source.split("\\s+")) {
            if (token.matches("t(?:[4-9]\\d|1\\d\\d|2[0-3]\\d|240)")) {
                final int value = Integer.parseInt(token.substring(1));
                if (tempo != 0 && tempo != value) throw new IllegalArgumentException("MML track has conflicting tempo directives.");
                tempo = value;
                continue;
            }
            if (token.matches("o[1-8]")) { octave = Integer.parseInt(token.substring(1)); continue; }
            if (token.matches("l(?:1|2|4|8|16|32|64)")) { length = Integer.parseInt(token.substring(1)); continue; }
            if ("/:".equals(token)) {
                if (repeats.size() == 3) throw new IllegalArgumentException("MML repeats nest at most three levels.");
                repeats.push(notes);
                notes = new ArrayList<>();
                continue;
            }
            if (token.matches(":/(?:[1-9]|1[0-6])")) {
                if (repeats.isEmpty()) throw new IllegalArgumentException("MML repeat closes without an opening /:.");
                if (notes.isEmpty()) throw new IllegalArgumentException("MML repeat bodies require at least one note or rest.");
                final int count = Integer.parseInt(token.substring(2));
                final List<RailixValue> parent = repeats.pop();
                parent.add(RailixValue.object(Map.of("repeat", RailixValue.number(java.math.BigDecimal.valueOf(count)),
                        "notes", RailixValue.array(notes))));
                notes = parent;
                continue;
            }
            notes.add(mmlNote(token, octave, length));
            if (notes.size() > MAX_SOUND_NOTES) throw new IllegalArgumentException("MML exceeds the " + MAX_SOUND_NOTES + "-note limit.");
        }
        if (!repeats.isEmpty()) throw new IllegalArgumentException("MML repeat is missing its :/count closer.");
        if (notes.isEmpty()) throw new IllegalArgumentException("MML instrument lines require at least one note or rest.");
        return new MmlSequence(tempo, List.copyOf(notes));
    }

    private static RailixValue.ObjectValue mmlNote(final String token, final int octave, final int defaultLength) {
        final int explicit = token.indexOf('@');
        final String timed = explicit < 0 ? token : token.substring(0, explicit);
        final int durationIndex = timed.length() - timed.replaceAll("\\d+$", "").length();
        final String symbol = durationIndex == 0 ? timed : timed.substring(0, timed.length() - durationIndex);
        final int denominator;
        try { denominator = durationIndex == 0 ? defaultLength : Integer.parseInt(timed.substring(timed.length() - durationIndex)); }
        catch (final NumberFormatException invalid) { throw new IllegalArgumentException("MML note duration is invalid: " + token + "."); }
        final double duration = explicit < 0 ? 4d / denominator : mmlDecimal(token.substring(explicit + 1), "@beats duration", 1d / 64d, 16);
        if (explicit < 0 && !Set.of(1, 2, 4, 8, 16, 32, 64).contains(denominator)) throw new IllegalArgumentException("MML note duration must be 1, 2, 4, 8, 16, 32, or 64.");
        final var values = new LinkedHashMap<String, RailixValue>();
        values.put("duration", RailixValue.number(java.math.BigDecimal.valueOf(duration)));
        if ("r".equals(symbol)) { values.put("note", RailixValue.nullValue()); return RailixValue.object(values); }
        final String chord = symbol.startsWith("[") && symbol.endsWith("]") ? symbol.substring(1, symbol.length() - 1) : symbol;
        final List<Integer> pitches = new ArrayList<>();
        for (int index = 0; index < chord.length();) {
            final char note = chord.charAt(index++);
            if (note < 'a' || note > 'g') throw new IllegalArgumentException("MML note is invalid: " + token + ".");
            int shift = 0;
            if (index < chord.length() && "+#-".indexOf(chord.charAt(index)) >= 0) shift = chord.charAt(index++) == '-' ? -1 : 1;
            pitches.add(mmlPitch(note, shift, octave, token));
        }
        if (pitches.isEmpty() || pitches.size() > 4 || (!symbol.startsWith("[") && pitches.size() != 1)) throw new IllegalArgumentException("MML chords use [ceg] with at most four notes.");
        values.put("note", RailixValue.number(java.math.BigDecimal.valueOf(pitches.getFirst())));
        if (pitches.size() > 1) values.put("chord", RailixValue.array(pitches.subList(1, pitches.size()).stream()
                .<RailixValue>map(value -> RailixValue.number(java.math.BigDecimal.valueOf(value))).toList()));
        return RailixValue.object(values);
    }

    private static int mmlNoteCount(final List<RailixValue> notes) {
        int count = 0;
        for (final RailixValue value : notes) {
            final RailixValue.ObjectValue node = (RailixValue.ObjectValue) value;
            if (node.values().containsKey("repeat")) {
                count += Math.multiplyExact((int) number(node, "repeat"),
                        mmlNoteCount(((RailixValue.ArrayValue) node.values().get("notes")).values()));
            } else count++;
            if (count > MAX_SOUND_NOTES) return count;
        }
        return count;
    }

    private static int mmlPitch(final char note, final int shift, final int octave, final String token) {
        final int semitone = switch (note) { case 'c' -> 0; case 'd' -> 2; case 'e' -> 4; case 'f' -> 5; case 'g' -> 7; case 'a' -> 9; case 'b' -> 11; default -> throw new IllegalArgumentException("MML note is invalid: " + token + "."); };
        final int midi = octave * 12 + 12 + semitone + shift;
        if (midi < 24 || midi > 108) throw new IllegalArgumentException("MML note must resolve to MIDI 24-108: " + token + ".");
        return midi;
    }

    private static long number(final RailixValue.ObjectValue object, final String field) {
        if (!(object.values().get(field) instanceof RailixValue.NumberValue value)) throw new IllegalArgumentException("Sound " + field + " must be numeric.");
        try { return value.value().longValueExact(); } catch (final ArithmeticException invalid) { throw new IllegalArgumentException("Sound " + field + " must be an integer."); }
    }

    private static double decimal(final RailixValue.ObjectValue object, final String field) {
        if (!(object.values().get(field) instanceof RailixValue.NumberValue value)) throw new IllegalArgumentException("Sound " + field + " must be numeric.");
        final double result = value.value().doubleValue();
        if (!Double.isFinite(result)) throw new IllegalArgumentException("Sound " + field + " must be finite.");
        return result;
    }

    private static SecureDirectoryStream<Path> openLocalDirectory(final Path path) throws IOException {
        final var root = Files.newDirectoryStream(path.getRoot());
        if (!(root instanceof SecureDirectoryStream<Path> secure)) {
            root.close();
            throw new IllegalArgumentException("The filesystem does not support secure local directory access.");
        }
        SecureDirectoryStream<Path> current = secure;
        try {
            // Open through the pinned parent, then release it before advancing to the next component.
            for (final Path component : path) {
                try (var parent = current) {
                    if (parent.getFileAttributeView(component, BasicFileAttributeView.class,
                            LinkOption.NOFOLLOW_LINKS).readAttributes().isSymbolicLink()) {
                        throw new IllegalArgumentException("Local paths must not contain symbolic links.");
                    }
                    current = parent.newDirectoryStream(component, LinkOption.NOFOLLOW_LINKS);
                }
            }
            return current;
        } catch (final IOException | RuntimeException | Error failure) {
            try {
                current.close();
            } catch (final IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    private static List<String> localFiles(final Path root, final java.util.function.Predicate<String> accepts) throws IOException {
        final List<String> files = new ArrayList<>();
        final ArrayDeque<Path> pending = new ArrayDeque<>();
        pending.add(root.getFileSystem().getPath(""));
        while (!pending.isEmpty()) {
            final Path relative = pending.removeLast();
            try (var directory = openLocalDirectory(root.resolve(relative))) {
                for (final Path entry : directory) {
                    final Path name = entry.getFileName();
                    final Path candidate = relative.resolve(name);
                    final String id = candidate.toString();
                    if (!canonicalLocalPath(id)) continue;
                    final var attributes = directory.getFileAttributeView(name,
                            BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).readAttributes();
                    if (attributes.isDirectory()) {
                        pending.add(candidate);
                    } else if (attributes.isRegularFile() && accepts.test(id)) {
                        files.add(id);
                    }
                }
            }
        }
        return files;
    }

    private static boolean canonicalLocalPath(final String path) {
        return path.length() <= 4096 && path.indexOf('\\') < 0
                && path.chars().noneMatch(Character::isISOControl)
                && Arrays.stream(path.split("/", -1))
                .noneMatch(part -> part.isEmpty() || part.equals(".") || part.equals(".."));
    }

    private static Response themeFailure(final int status, final String code, final String message) {
        return json(status, RailixValue.object(Map.of(
                "status", RailixValue.string(code), "message", RailixValue.string(message)
        )));
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
            if (parameters.containsKey("members")) {
                final RailixValue.ObjectValue page = snapshot.memberPage(parameters);
                if (!forwarding.tryAcquire()) return unavailable("saturated");
                try {
                    final var read = deployed.observationQuery("metrics", (RailixValue.ObjectValue) page.values().get("query"),
                            System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
                    if (!sceneObservationCurrent(deployed, functionalRevision, presentationRevision) || read == null) return unavailable("application");
                    if (read.status() != 200) return new Response(read.status(), "application/json; charset=utf-8", read.body());
                    final var parsed = RailixJson.parse(utf8(read.body()));
                    if (!(parsed instanceof RailixJson.Parsed json) || !(json.value() instanceof RailixValue.ObjectValue document)
                            || !RailixValue.number(pid).equals(document.values().get("application_pid"))) return unavailable("application");
                    final Map<String, RailixValue> result = new LinkedHashMap<>(page.values());
                    result.remove("query");
                    result.putAll(document.values());
                    result.put("revision", RailixValue.string(parameters.get("revision")));
                    send(exchange, json(200, RailixValue.object(result)));
                    return Response.committedResponse();
                } finally {
                    forwarding.release();
                }
            }
            final CreatorScene.Observation projection = snapshot.observationView(parameters);
            try {
                if (!forwarding.tryAcquire()) {
                    return unavailable("saturated");
                }
                try {
                    final long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
                    for (final String read : List.of("metrics", "examples")) {
                        if (read.equals("metrics")) {
                            final DevelopmentApplication.Response catalog = deployed.metricCatalog(deadline);
                            if (!sceneObservationCurrent(deployed, functionalRevision, presentationRevision)) {
                                return unavailable("application");
                            }
                            if (catalog.status() != 200) {
                                projection.unavailable(read);
                                continue;
                            }
                            projection.metricDefinitions((RailixValue.ObjectValue)
                                    ((RailixJson.Parsed) RailixJson.parse(catalog.body())).value());
                        }
                        for (final RailixValue.ObjectValue query : projection.queries(read)) {
                            if (System.nanoTime() >= deadline) return unavailable("observation-timeout");
                            final DevelopmentApplication.ObservationResponse response = deployed.observationQuery(read, query, deadline);
                            if (!sceneObservationCurrent(deployed, functionalRevision, presentationRevision) || response == null) {
                                return unavailable("application");
                            }
                            if (response.status() == 202 || response.status() == 503) {
                                projection.unavailable(read);
                                break;
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
                            if (!projection.accept(read, query, document)) break;
                        }
                    }
                } finally {
                    forwarding.release();
                }
                final RailixValue.ObjectValue observation = projection.response(functionalRevision, pid);
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
        final BodyRead body = body(exchange, MAX_REQUEST_BYTES);
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
        final BodyRead body = body(exchange, MAX_REQUEST_BYTES);
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
        final BodyRead body = body(exchange, MAX_REQUEST_BYTES);
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
                edited = RailixJson.write(updated);
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
            final DevelopmentApplication.Response response = "/api/metrics/catalog".equals(exchange.getRequestURI().getPath())
                    ? deployed.metricCatalog() : node.isEmpty()
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
        } catch (final IOException failure) {
            return json(502, RailixValue.object(Map.of(
                    "status", RailixValue.string("invalid-application-metrics"),
                    "message", RailixValue.string(failure.getMessage())
            )));
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
        final boolean boundedSnapshot = boundedExampleSnapshot(path);
        if (!boundedSnapshot && !exampleResponses.tryAcquire()) {
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
            if (!boundedSnapshot) {
                exampleResponses.release();
            }
        }
    }

    private static boolean boundedExampleSnapshot(final String path) {
        return "status".equals(path)
                || (!path.isBlank() && !"coverage".equals(path)
                && !path.startsWith("steps/") && !path.endsWith("/view") && !path.contains("/steps/"));
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
        if (path.startsWith("/themes/")) {
            final String requested = path.substring("/themes/".length());
            final String asset = switch (requested) {
                case "railix.css" -> "foundry/theme.css";
                case "classic.css" -> "classic/theme.css";
                default -> requested;
            };
            if (!themeAssetPath(asset) || !EMBEDDED_THEME_ASSETS.contains(asset)) {
                return json(404, RailixValue.object(Map.of("status", RailixValue.string("not-found"))));
            }
            final byte[] source = embeddedThemeFile(asset);
            return source == null ? json(404, RailixValue.object(Map.of("status", RailixValue.string("not-found"))))
                    : new Response(200, themeContentType(asset), source);
        }
        final String file = switch (path) {
            case "/", "/index.html" -> "index.html";
            case "/app.css" -> "app.css";
            case "/app.js" -> "app.js";
            case "/world.js" -> "world.js";
            case "/world-canvas.js" -> "world-canvas.js";
            case "/audio.js" -> "audio.js";
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
                            : file.endsWith(".txt") ? "text/plain; charset=utf-8" : "text/javascript; charset=utf-8",
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
                || "/api/creator".equals(path)
                || "/api/themes".equals(path)
                || "/api/sounds".equals(path)
                || "/api/settings".equals(path));
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

    private boolean themeAssetAuthorized(final HttpExchange exchange) {
        return authorized(exchange) || "GET".equals(exchange.getRequestMethod()) && themeAssetCookieValid(exchange);
    }

    private boolean themeAssetFetchAllowed(final HttpExchange exchange) {
        final List<String> sites = exchange.getRequestHeaders().getOrDefault("Sec-Fetch-Site", List.of());
        return sites.isEmpty() || sites.size() == 1 && ("same-origin".equals(sites.getFirst()) || "none".equals(sites.getFirst()));
    }

    private boolean themeAssetCookieValid(final HttpExchange exchange) {
        String value = null;
        for (final String header : exchange.getRequestHeaders().getOrDefault("Cookie", List.of())) {
            for (final String part : header.split(";")) {
                final String cookie = part.strip();
                if (!cookie.startsWith(themeAssetCookieName + "=")) continue;
                if (value != null) return false;
                value = cookie.substring(themeAssetCookieName.length() + 1);
            }
        }
        return value != null && MessageDigest.isEqual(
                themeAssetSecret.getBytes(StandardCharsets.US_ASCII),
                value.getBytes(StandardCharsets.US_ASCII)
        );
    }

    private String themeAssetCookie() {
        return themeAssetCookieName + "=" + themeAssetSecret
                + "; Path=/api/themes/files/; HttpOnly; SameSite=Strict";
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
        if (response.contentType().startsWith("text/html;")) {
            exchange.getResponseHeaders().set("Content-Security-Policy",
                    "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
                            + "img-src 'self' data: blob:; font-src 'self' data:; media-src 'none'; connect-src 'self'; object-src 'none'; "
                            + "base-uri 'none'; form-action 'self'; frame-src 'none'");
        }
        if (response.contentType().startsWith("image/svg+xml")) {
            exchange.getResponseHeaders().set("Content-Security-Policy",
                    "default-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'");
        }
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
        try {
            return Files.readString(project, StandardCharsets.UTF_8);
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

    private static final class SoundRevisionConflict extends IllegalArgumentException {
        private SoundRevisionConflict(final String message) { super(message); }
    }

    private static final class SettingsRevisionConflict extends IllegalArgumentException { }

    private static final class ThemeTooLarge extends IllegalArgumentException {
        private ThemeTooLarge(final String message) { super(message); }
    }

    private record SettingsSnapshot(String revision, RailixValue.ObjectValue values, List<RailixValue> diagnostics) {
        private SettingsSnapshot {
            diagnostics = List.copyOf(diagnostics);
        }
    }

    private record ThemeSpec(
            String id,
            String name,
            String defaultVariant,
            String stylesheet,
            List<RailixValue.ObjectValue> variants,
            List<String> files,
            boolean descriptor
    ) {
        private ThemeSpec {
            variants = List.copyOf(variants);
            files = List.copyOf(files);
        }
    }

    private record SoundPath(Path root, String relative) { }

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
