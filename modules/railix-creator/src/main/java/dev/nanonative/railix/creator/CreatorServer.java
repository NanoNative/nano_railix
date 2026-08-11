package dev.nanonative.railix.creator;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.nanonative.railix.core.project.CompileResult;
import dev.nanonative.railix.core.project.Diagnostic;
import dev.nanonative.railix.core.project.ProjectCompiler;
import dev.nanonative.railix.core.step.StepCatalog;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Creator HTTP server, project workspace, and rolling development-application owner. */
public final class CreatorServer implements AutoCloseable {
    private static final String WEB_ROOT = "/dev/nanonative/railix/creator/web/";
    private static final int MAX_PROJECT_BYTES = RailixData.DEFAULT_MAX_SOURCE_BYTES;
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
    private final java.util.concurrent.ExecutorService executor;
    private final StepCatalog catalog;
    private final IconLibrary icons;
    private final Path projectFile;
    private final Path creatorFile;
    private final Object applicationLock = new Object();
    private final CountDownLatch closed = new CountDownLatch(1);
    private final AtomicBoolean open = new AtomicBoolean(true);
    private DevelopmentApplication application;
    private String source;
    private String executableSource;
    private RailixValue.ObjectValue creatorValue;
    private List<Diagnostic> creatorDiagnostics;
    private long nextGeneration;

    private CreatorServer(
            final HttpServer server,
            final java.util.concurrent.ExecutorService executor,
            final StepCatalog catalog,
            final IconLibrary icons,
            final Path projectFile,
            final Path creatorFile,
            final DevelopmentApplication application,
            final String source,
            final String executableSource,
            final RailixValue.ObjectValue creatorValue,
            final List<Diagnostic> creatorDiagnostics,
            final long nextGeneration
    ) {
        this.server = server;
        this.executor = executor;
        this.catalog = catalog;
        this.icons = icons;
        this.projectFile = projectFile;
        this.creatorFile = creatorFile;
        this.application = application;
        this.source = source;
        this.executableSource = executableSource;
        this.creatorValue = creatorValue;
        this.creatorDiagnostics = List.copyOf(creatorDiagnostics);
        this.nextGeneration = nextGeneration;
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
        final StepCatalog catalog = StandardLibrary.catalog();
        final IconLibrary icons = new IconLibrary(railixHome);
        final Path absoluteProject = projectFile.toAbsolutePath().normalize();
        final String requested = Files.exists(absoluteProject)
                ? readProject(absoluteProject)
                : defaultProject(absoluteProject);
        final CompileResult result = ProjectCompiler.compile(requested, catalog);
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
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        DevelopmentApplication application = null;
        try {
            persist(absoluteProject, canonical);
            if (!creatorExists || requestedCreatorResult.diagnostics().isEmpty()) {
                persist(creatorFile, creatorResult.source());
            }
            application = DevelopmentApplication.start(1, canonical, compiled.executableSource());
            final var executor = Executors.newVirtualThreadPerTaskExecutor();
            final CreatorServer creator = new CreatorServer(
                    server,
                    executor,
                    catalog,
                    icons,
                    absoluteProject,
                    creatorFile,
                    application,
                    canonical,
                    compiled.executableSource(),
                    creatorResult.value(),
                    requestedCreatorResult.diagnostics(),
                    2
            );
            server.createContext("/", creator::handle);
            server.setExecutor(executor);
            server.start();
            return creator;
        } catch (final IOException | RuntimeException exception) {
            server.stop(0);
            if (application != null) {
                try {
                    application.close();
                } catch (final RuntimeException cleanup) {
                    exception.addSuppressed(cleanup);
                }
            }
            throw exception;
        }
    }

    public URI baseUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    public CreatorServer awaitClose() throws InterruptedException {
        closed.await();
        return this;
    }

    @Override
    public void close() {
        boolean interrupted = Thread.interrupted();
        try {
            if (open.compareAndSet(true, false)) {
                server.stop(0);
                interrupted |= Thread.interrupted();
                executor.shutdownNow();
                interrupted |= Thread.interrupted();
            }
            synchronized (applicationLock) {
                application.close();
            }
            interrupted |= Thread.interrupted();
            closed.countDown();
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void handle(final HttpExchange exchange) {
        try {
            final Response response = route(exchange);
            send(exchange, response);
        } catch (final IOException | RuntimeException exception) {
            sendSafely(exchange, json(500, RailixValue.object(Map.of(
                    "status", RailixValue.string("failed"),
                    "message", RailixValue.string("Creator request failed.")
            ))));
        }
    }

    private Response route(final HttpExchange exchange) throws IOException {
        final String path = exchange.getRequestURI().getPath();
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
        final BodyRead body = body(exchange, MAX_PROJECT_BYTES);
        if (!body.diagnostics().isEmpty()) {
            return json(413, diagnostics(body.diagnostics(), application()));
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
        final CompileResult result = ProjectCompiler.compile(projectSource, catalog);
        if (result instanceof CompileResult.Rejected rejected) {
            return json(422, diagnostics(rejected.diagnostics(), application()));
        }
        return accept((CompileResult.Compiled) result);
    }

    private Response creator(final HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            return methodNotAllowed();
        }
        final BodyRead body = body(exchange, MAX_PROJECT_BYTES);
        if (!body.diagnostics().isEmpty()) {
            return json(413, diagnostics(body.diagnostics(), application()));
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
                return json(422, diagnostics(result.diagnostics(), application.snapshot()));
            }
            try {
                persist(creatorFile, result.source());
            } catch (final IOException exception) {
                return json(500, RailixValue.object(Map.of(
                        "status", RailixValue.string("failed"),
                        "message", RailixValue.string("Creator metadata could not be persisted."),
                        "application", application.snapshot()
                )));
            }
            creatorValue = result.value();
            creatorDiagnostics = List.of();
            return json(200, projectPayloadLocked());
        }
    }

    private Response accept(final CompileResult.Compiled compiled) throws IOException {
        synchronized (applicationLock) {
            return acceptLocked(compiled);
        }
    }

    private Response acceptLocked(final CompileResult.Compiled compiled) throws IOException {
        final String canonical = compiled.source();
        if (executableSource.equals(compiled.executableSource())) {
            try {
                persist(projectFile, canonical);
            } catch (final IOException exception) {
                return json(500, RailixValue.object(Map.of(
                        "status", RailixValue.string("failed"),
                        "message", RailixValue.string("Project could not be persisted."),
                        "application", application.snapshot()
                )));
            }
            source = canonical;
            return json(200, projectPayload());
        }
        final DevelopmentApplication candidate;
        try {
            candidate = DevelopmentApplication.start(nextGeneration, canonical, compiled.executableSource());
        } catch (final IOException exception) {
            return json(503, RailixValue.object(Map.of(
                    "status", RailixValue.string("failed"),
                    "message", RailixValue.string("Development application did not start."),
                    "application", application.snapshot()
            )));
        }
        try {
            persist(projectFile, canonical);
        } catch (final IOException exception) {
            candidate.close();
            return json(500, RailixValue.object(Map.of(
                    "status", RailixValue.string("failed"),
                    "message", RailixValue.string("Project could not be persisted."),
                    "application", application.snapshot()
            )));
        }
        final DevelopmentApplication previous = application;
        try {
            previous.close();
        } catch (final RuntimeException failure) {
            try {
                candidate.close();
            } finally {
                persist(projectFile, source);
            }
            return json(503, RailixValue.object(Map.of(
                    "status", RailixValue.string("failed"),
                    "message", RailixValue.string("Previous development application did not stop."),
                    "application", previous.snapshot()
            )));
        }
        application = candidate;
        source = canonical;
        executableSource = compiled.executableSource();
        nextGeneration++;
        return json(200, projectPayload());
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
        final BodyRead body = body(exchange, RailixData.DEFAULT_MAX_SOURCE_BYTES);
        if (!body.diagnostics().isEmpty()) {
            return json(413, diagnostics(body.diagnostics(), application()));
        }
        synchronized (applicationLock) {
            final DevelopmentApplication.Response response = preview
                    ? application.preview(target[0], target[1], body.value(), true)
                    : application.run(target[0], body.value(), true);
            return new Response(
                    response.status(),
                    "application/json; charset=utf-8",
                    response.body().getBytes(StandardCharsets.UTF_8)
            );
        }
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
                "application", application(),
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
            return application.snapshot();
        }
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
        final Map<String, RailixValue> value = new LinkedHashMap<>();
        value.put("id", RailixValue.string(definition.id()));
        value.put("version", RailixValue.string(definition.version()));
        value.put("display_name", RailixValue.string(definition.displayName()));
        value.put("search_terms", RailixValue.array(definition.searchTerms().stream()
                .<RailixValue>map(RailixValue::string)
                .toList()));
        value.put("kind", RailixValue.string(definition.kind().name().toLowerCase(Locale.ROOT)));
        value.put("primary_outcome", RailixValue.string(definition.primaryOutcome()));
        value.put("maximum_instances", RailixValue.number(definition.maximumInstances()));
        definition.source().ifPresent(source -> value.put("source", RailixValue.object(Map.of(
                "name", RailixValue.string(source.name()),
                "responses", RailixValue.object(source.responses().entrySet().stream().collect(
                        LinkedHashMap::new,
                        (responses, entry) -> responses.put(entry.getKey(), RailixValue.string(entry.getValue())),
                        LinkedHashMap::putAll
                ))
        ))));
        definition.exampleTarget().ifPresent(target -> value.put("example_target", RailixValue.string(target)));
        value.put("examples", RailixValue.array(definition.examples().stream()
                .<RailixValue>map(CreatorServer::example)
                .toList()));
        value.put("receives", ports(definition.receives()));
        value.put("returns", ports(definition.returns()));
        value.put("inputs", inputs(definition.inputs()));
        value.put("outcomes", RailixValue.array(definition.outcomes().stream()
                .<RailixValue>map(RailixValue::string)
                .toList()));
        value.put("results", RailixValue.array(definition.results().stream()
                .<RailixValue>map(result -> {
                    final Map<String, RailixValue> field = new LinkedHashMap<>();
                    field.put("name", RailixValue.string(result.name()));
                    field.put("shape", RailixValue.string(result.shape().name().toLowerCase(Locale.ROOT)));
                    result.defaultValue().ifPresent(defaultValue -> field.put("default", defaultValue));
                    return RailixValue.object(field);
                })
                .toList()));
        return RailixValue.object(value);
    }

    private static RailixValue example(final StepDefinition.Example example) {
        final Map<String, RailixValue> value = new LinkedHashMap<>();
        value.put("name", RailixValue.string(example.name()));
        value.put("payload", example.payload());
        if (!example.context().values().isEmpty()) {
            value.put("context", example.context());
        }
        return RailixValue.object(value);
    }

    private static RailixValue.ArrayValue inputs(final List<StepDefinition.Field> inputs) {
        return RailixValue.array(inputs.stream().<RailixValue>map(field -> {
            final Map<String, RailixValue> value = new LinkedHashMap<>();
            value.put("name", RailixValue.string(field.name()));
            value.putAll(input(field.input()));
            return RailixValue.object(value);
        }).toList());
    }

    private static Map<String, RailixValue> input(final StepDefinition.Input input) {
        final Map<String, RailixValue> value = new LinkedHashMap<>();
        switch (input) {
            case StepDefinition.JsonInput json -> {
                value.put("type", RailixValue.string("json"));
                value.put("shape", RailixValue.string(json.shape().name().toLowerCase(Locale.ROOT)));
                value.put("required", RailixValue.bool(json.required()));
                json.defaultValue().ifPresent(defaultValue -> value.put("default", defaultValue));
                if (!json.range().isEmpty()) {
                    value.put("minimum", json.range().getFirst());
                    value.put("maximum", json.range().getLast());
                }
            }
            case StepDefinition.PathInput path -> {
                value.put("type", RailixValue.string("path"));
                value.put("access", RailixValue.string(path.access().name().toLowerCase(Locale.ROOT)));
                value.put("required", RailixValue.bool(path.required()));
                path.defaultValue().ifPresent(defaultValue -> value.put("default", defaultValue));
            }
            case StepDefinition.OptionsInput options -> {
                value.put("type", RailixValue.string("options"));
                value.put("required", RailixValue.bool(options.required()));
                options.defaultOption().ifPresent(option -> value.put("default", RailixValue.string(option)));
                value.put("options", options(options.options()));
            }
            case StepDefinition.CandidatesInput candidates -> {
                value.put("type", RailixValue.string("candidates"));
                candidates.defaultCandidate().ifPresent(option ->
                        value.put("default", RailixValue.string(option))
                );
                value.put("options", options(candidates.options()));
            }
            case StepDefinition.MatcherGroupsInput matcherGroups -> {
                value.put("type", RailixValue.string("matcher_groups"));
                value.put("options", options(matcherGroups.options()));
            }
            case StepDefinition.StepsInput steps -> {
                value.put("type", RailixValue.string("steps"));
                value.put("value_source", RailixValue.object(Map.of(
                        "input", RailixValue.string(steps.valueSource().input())
                )));
            }
        }
        return value;
    }

    private static RailixValue.ArrayValue options(final List<StepDefinition.Option> options) {
        return RailixValue.array(options.stream().<RailixValue>map(option -> {
            final Map<String, RailixValue> item = new LinkedHashMap<>();
            item.put("name", RailixValue.string(option.name()));
            item.put("inputs", inputs(option.inputs()));
            option.valueSource().ifPresent(source -> item.put(
                    "value_source",
                    RailixValue.object(Map.of(
                            "scope", RailixValue.string(source.scope().name().toLowerCase(Locale.ROOT)),
                            "input", RailixValue.string(source.input())
                    ))
            ));
            return RailixValue.object(item);
        }).toList());
    }

    private static RailixValue ports(final List<StepDefinition.Port> ports) {
        return RailixValue.array(ports.stream().<RailixValue>map(port -> {
            final Map<String, RailixValue> value = new LinkedHashMap<>();
            value.put("name", RailixValue.string(port.name()));
            value.put("shape", RailixValue.string(port.shape().name().toLowerCase(Locale.ROOT)));
            if (port.refinement().canonicalValues()) {
                value.put("canonical", RailixValue.bool(true));
            }
            if (port.refinement().maxDepth() > 0) {
                value.put("max_depth", RailixValue.number(port.refinement().maxDepth()));
            }
            if (port.refinement().maxJsonBytes() > 0) {
                value.put("max_json_bytes", RailixValue.number(port.refinement().maxJsonBytes()));
            }
            return RailixValue.object(value);
        }).toList());
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

    private static BodyRead body(final HttpExchange exchange, final int limit) throws IOException {
        final byte[] value = exchange.getRequestBody().readNBytes(limit + 1);
        if (value.length > limit) {
            return new BodyRead(
                    new byte[0],
                    List.of(Diagnostic.atPath(
                            "REQUEST_TOO_LARGE",
                            "Request exceeds the " + limit + "-byte limit.",
                            ""
                    ))
            );
        }
        return new BodyRead(value, List.of());
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

    private record BodyRead(byte[] value, List<Diagnostic> diagnostics) {
        private BodyRead {
            value = value.clone();
            diagnostics = List.copyOf(diagnostics);
        }
    }

    private record Response(int status, String contentType, byte[] body) {
        private Response {
            body = body.clone();
        }
    }
}
