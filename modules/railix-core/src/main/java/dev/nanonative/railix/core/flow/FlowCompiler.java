package dev.nanonative.railix.core.flow;

import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Parses and fully resolves a flow before runtime can receive it. */
public final class FlowCompiler {
    private static final String END = ControlGraph.END;
    private static final List<String> SUPPORTED_TRIGGERS =
            List.of("cli", "startup", "http", "socket", "scheduled");
    private static final Set<String> CLI_TRIGGER_CONFIG_FIELDS = Set.of("stdin", "arguments");
    private static final Set<String> HTTP_TRIGGER_CONFIG_FIELDS = Set.of("port", "path", "flow", "step");
    private static final Set<String> SOCKET_TRIGGER_CONFIG_FIELDS =
            Set.of("port", "timeoutMillis", "maxConnections");
    private static final Set<String> SCHEDULED_TRIGGER_CONFIG_FIELDS =
            Set.of("intervalMillis", "initialDelayMillis", "maxConcurrentRuns");
    private static final Pattern URL_SEGMENT = Pattern.compile("[A-Za-z0-9._~-]+");
    private static final Pattern HTTP_PATH = Pattern.compile(
            "/(?:[A-Za-z0-9._~-]+(?:/[A-Za-z0-9._~-]+)*)?"
    );
    private static final List<String> SUPPORTED_CONVERSIONS = Arrays.stream(CompiledFlow.Conversion.values())
            .map(CompiledFlow.Conversion::encoded)
            .filter(encoded -> !encoded.isEmpty())
            .toList();

    private FlowCompiler() {
    }

    /** Returns the exact lossy conversions accepted in flow connections. */
    public static List<String> supportedConversions() {
        return SUPPORTED_CONVERSIONS;
    }

    /** Returns the exact trigger types accepted by the current compiler. */
    public static List<String> supportedTriggers() {
        return SUPPORTED_TRIGGERS;
    }

    public static CompileResult compile(final String source, final StepCatalog catalog) {
        if (source == null) {
            return new CompileResult.Rejected(List.of(new Diagnostic(
                    "FLOW_JSON_INVALID",
                    "JSON source is missing.",
                    "$",
                    1,
                    1
            )));
        }
        if (source.length() > RailixData.DEFAULT_MAX_SOURCE_BYTES) {
            return new CompileResult.Rejected(List.of(Diagnostic.atPath(
                    "FLOW_SOURCE_TOO_LARGE",
                    "Flow source exceeds the " + RailixData.DEFAULT_MAX_SOURCE_BYTES + "-byte limit.",
                    "$"
            )));
        }
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(source)) {
            return new CompileResult.Rejected(List.of(new Diagnostic(
                    "FLOW_JSON_INVALID",
                    "Unpaired Unicode surrogate is not allowed in JSON strings.",
                    "$",
                    1,
                    1
            )));
        }
        final RailixData.Result normalized = RailixData.normalize(
                RailixData.Format.JSON,
                source.getBytes(StandardCharsets.UTF_8)
        );
        if (normalized instanceof RailixData.Invalid invalid) {
            return new CompileResult.Rejected(List.of(flowDiagnostic(invalid)));
        }
        if (catalog == null) {
            return new CompileResult.Rejected(List.of(Diagnostic.atPath(
                    "STEP_CATALOG_REQUIRED",
                    "A Step catalog is required.",
                    "catalog"
            )));
        }
        final RailixData.Normalized document = (RailixData.Normalized) normalized;
        final RailixValue sourceValue = document.value();
        final FlowSourceReader.Result read = FlowSourceReader.read(sourceValue);
        if (!read.diagnostics().isEmpty()) {
            return new CompileResult.Rejected(read.diagnostics());
        }
        return analyze(read.flow(), catalog, document.canonicalJson());
    }

    private static Diagnostic flowDiagnostic(final RailixData.Invalid invalid) {
        final String code = switch (invalid.code()) {
            case "DATA_DEPTH_EXCEEDED" -> "FLOW_DEPTH_EXCEEDED";
            case "DATA_NUMBER_LIMIT_EXCEEDED" -> "FLOW_NUMBER_LIMIT_EXCEEDED";
            case "DATA_SOURCE_TOO_LARGE" -> "FLOW_SOURCE_TOO_LARGE";
            default -> "FLOW_JSON_INVALID";
        };
        final String message = "DATA_SOURCE_TOO_LARGE".equals(invalid.code())
                ? "Flow source exceeds the " + RailixData.DEFAULT_MAX_SOURCE_BYTES + "-byte limit."
                : invalid.message();
        return new Diagnostic(code, message, "$", invalid.line(), invalid.column());
    }

    /** Compiles a flow only when its committed dependency lock matches exactly. */
    public static CompileResult compile(
            final String source,
            final StepCatalog catalog,
            final String expectedLock
    ) {
        final CompileResult result = compile(source, catalog);
        if (result instanceof CompileResult.Rejected) {
            return result;
        }
        final CompileResult.Compiled compiled = (CompileResult.Compiled) result;
        final List<Diagnostic> diagnostics = StepLock.verify(expectedLock, compiled.lock());
        return diagnostics.isEmpty() ? compiled : new CompileResult.Rejected(diagnostics);
    }

    private static CompileResult analyze(
            final DraftFlow flow,
            final StepCatalog catalog,
            final String canonicalSource
    ) {
        final List<Diagnostic> diagnostics = new ArrayList<>();
        final List<CompiledFlow.Trigger> triggers = triggers(flow, diagnostics);
        if (!diagnostics.isEmpty()) {
            return new CompileResult.Rejected(diagnostics);
        }
        final Map<String, StepDefinition> definitions = definitions(catalog, diagnostics);
        if (!diagnostics.isEmpty()) {
            return new CompileResult.Rejected(diagnostics);
        }
        final Map<String, DraftFlow.DraftStep> invocations = invocations(flow, diagnostics);

        if (!invocations.containsKey(flow.entry())) {
            diagnostics.add(Diagnostic.atPath(
                    "FLOW_ENTRY_UNKNOWN",
                    "Entry Step does not exist: " + flow.entry(),
                    "entry"
            ));
        }

        final Map<String, StepDefinition> resolved = new LinkedHashMap<>();
        for (final DraftFlow.DraftStep invocation : flow.steps()) {
            if (!definitions.containsKey(invocation.use())) {
                diagnostics.add(Diagnostic.atPath(
                        "FLOW_STEP_UNKNOWN",
                        "Unknown Step dependency: " + invocation.use(),
                        "steps." + invocation.id() + ".use"
                ));
            } else {
                resolved.put(invocation.id(), definitions.get(invocation.use()));
            }
        }
        if (!diagnostics.isEmpty()) {
            return new CompileResult.Rejected(diagnostics);
        }

        final Map<String, Map<String, RailixValue>> config = configurations(flow, resolved, diagnostics);
        if (!diagnostics.isEmpty()) {
            return new CompileResult.Rejected(diagnostics);
        }

        validateTransitions(invocations, resolved, diagnostics);
        validateControlGraph(flow.entry(), invocations, diagnostics);
        if (!diagnostics.isEmpty()) {
            return new CompileResult.Rejected(diagnostics);
        }

        final Map<String, Map<String, List<CompiledFlow.Mapping>>> stepMappings = new LinkedHashMap<>();
        final Map<String, List<CompiledFlow.Mapping>> outputMappings = new LinkedHashMap<>();
        final ControlGraph controlGraph = ControlGraph.analyze(flow.entry(), invocations);

        for (final DraftFlow.DraftConnection connection : flow.connections()) {
            final String path = "connections[" + connection.index() + "]";
            final Optional<SourceEndpoint> source = sourceEndpoint(
                    connection.from(), flow, resolved, diagnostics, path + ".from"
            );
            final Optional<TargetEndpoint> target = targetEndpoint(
                    connection.to(), flow, resolved, diagnostics, path + ".to"
            );
            if (source.isEmpty() || target.isEmpty()) {
                continue;
            }
            final SourceEndpoint sourceEndpoint = source.get();
            final TargetEndpoint targetEndpoint = target.get();
            final Optional<CompiledFlow.Conversion> conversion = conversion(
                    connection.conversion(), diagnostics, path + ".convert"
            );
            if (conversion.isEmpty() || !validateMapping(
                    connection,
                    sourceEndpoint.shape(),
                    targetEndpoint.shape(),
                    conversion.get(),
                    diagnostics,
                    path
            )) {
                continue;
            }
            final String consumer = targetEndpoint.kind() == EndpointKind.STEP_INPUT
                    ? targetEndpoint.owner()
                    : END;
            if (sourceEndpoint.kind() == EndpointKind.STEP_OUTPUT
                    && !controlGraph.outputAvailableAt(sourceEndpoint.owner(), consumer)) {
                final String consumerName = END.equals(consumer) ? "flow completion" : consumer;
                diagnostics.add(Diagnostic.atPath(
                        "FLOW_DATA_SOURCE_UNAVAILABLE",
                        "Step output " + connection.from()
                                + " is not available whenever " + consumerName + " executes.",
                        path
                ));
                continue;
            }
            final CompiledFlow.Mapping mapping = new CompiledFlow.Mapping(
                    sourceEndpoint.source(),
                    connection.sourcePath(),
                    connection.defaultValue(),
                    conversion.get(),
                    connection.targetPath(),
                    connection.index()
            );
            if (targetEndpoint.kind() == EndpointKind.STEP_INPUT) {
                stepMappings.computeIfAbsent(targetEndpoint.owner(), ignored -> new LinkedHashMap<>())
                        .computeIfAbsent(targetEndpoint.port(), ignored -> new ArrayList<>())
                        .add(mapping);
            } else {
                outputMappings.computeIfAbsent(targetEndpoint.port(), ignored -> new ArrayList<>()).add(mapping);
            }
        }
        validateTargetMappings(stepMappings, outputMappings, diagnostics);
        if (!diagnostics.isEmpty()) {
            return new CompileResult.Rejected(diagnostics);
        }

        for (final Map.Entry<String, StepDefinition> entry : resolved.entrySet()) {
            final Map<String, List<CompiledFlow.Mapping>> bindings = stepMappings.getOrDefault(
                    entry.getKey(), Map.of()
            );
            for (final StepDefinition.Port port : sortedPorts(entry.getValue().inputs())) {
                if (!bindings.containsKey(port.name())) {
                    diagnostics.add(Diagnostic.atPath(
                            "FLOW_REQUIRED_INPUT_UNMAPPED",
                            "Required Step input is not mapped: " + port.name(),
                            "steps." + entry.getKey() + ".inputs." + port.name()
                    ));
                }
            }
        }
        for (final String output : flow.outputs().keySet().stream().sorted().toList()) {
            if (!outputMappings.containsKey(output)) {
                diagnostics.add(Diagnostic.atPath(
                        "FLOW_OUTPUT_UNMAPPED",
                        "Flow output is not mapped: " + output,
                        "outputs." + output
                ));
            }
        }
        validateStepEventSources(triggers, invocations, stepMappings, outputMappings, diagnostics);

        if (!diagnostics.isEmpty()) {
            return new CompileResult.Rejected(diagnostics);
        }

        final Map<String, CompiledFlow.Node> nodes = new LinkedHashMap<>();
        for (final Map.Entry<String, DraftFlow.DraftStep> entry : invocations.entrySet()) {
            final StepDefinition definition = resolved.get(entry.getKey());
            nodes.put(entry.getKey(), new CompiledFlow.Node(
                    entry.getKey(),
                    definition.outputs().stream()
                            .sorted((left, right) -> left.name().compareTo(right.name()))
                            .map(port -> new CompiledFlow.Output(port.name(), port.shape().orElseThrow()))
                            .toList(),
                    Set.copyOf(definition.outcomes()),
                    definition.handler().orElseThrow(),
                    config.get(entry.getKey()),
                    bindings(definition.inputs(), stepMappings.getOrDefault(entry.getKey(), Map.of())),
                    entry.getValue().transitions()
            ));
        }
        return new CompileResult.Compiled(new CompiledFlow(
                flow.id(),
                triggers,
                flow.entry(),
                flow.inputs(),
                flow.outputs(),
                nodes,
                bindings(flow.outputs(), outputMappings)
        ), canonicalSource, StepLock.derive(canonicalSource, resolved.values()).source());
    }

    private static List<CompiledFlow.Trigger> triggers(
            final DraftFlow flow,
            final List<Diagnostic> diagnostics
    ) {
        final List<CompiledFlow.Trigger> result = new ArrayList<>(flow.triggers().size());
        final Set<String> httpRoutes = new HashSet<>();
        int httpPort = 0;
        int socketPort = 0;
        int socketDeclarations = 0;
        for (final DraftFlow.DraftTrigger trigger : flow.triggers()) {
            final int firstDiagnostic = diagnostics.size();
            Optional<HttpIngress> http = Optional.empty();
            OptionalLong socket = OptionalLong.empty();
            switch (trigger.type()) {
                case "cli" -> validateCliTrigger(trigger, flow.inputs(), diagnostics);
                case "startup" -> validateStartupTrigger(trigger, flow.inputs(), diagnostics);
                case "http" -> http = validateHttpTrigger(trigger, flow, diagnostics);
                case "socket" -> {
                    socketDeclarations++;
                    socket = validateSocketTrigger(trigger, diagnostics);
                    if (socketDeclarations > 1) {
                        diagnostics.add(Diagnostic.atPath(
                                "FLOW_TRIGGER_SOCKET_DUPLICATE",
                                "A flow can declare only one socket trigger.",
                                triggerPath(trigger) + ".type"
                        ));
                    }
                }
                case "scheduled" -> validateScheduledTrigger(trigger, flow.inputs(), diagnostics);
                default -> diagnostics.add(Diagnostic.atPath(
                        "FLOW_TRIGGER_TYPE_UNSUPPORTED",
                        "Trigger type is not implemented: " + trigger.type() + ".",
                        triggerPath(trigger) + ".type"
                ));
            }
            if (http.isPresent()) {
                final HttpIngress ingress = http.get();
                if (httpPort == 0) {
                    httpPort = ingress.port();
                } else if (httpPort != ingress.port()) {
                    diagnostics.add(Diagnostic.atPath(
                            "FLOW_TRIGGER_HTTP_PORT_CONFLICT",
                            "HTTP trigger port must match the first HTTP trigger port: " + httpPort + ".",
                            triggerPath(trigger) + ".config.port"
                    ));
                }
                if (!httpRoutes.add(ingress.route())) {
                    diagnostics.add(Diagnostic.atPath(
                            "FLOW_TRIGGER_HTTP_ROUTE_DUPLICATE",
                            "HTTP route is already declared: " + ingress.route() + ".",
                            triggerPath(trigger) + ".config"
                    ));
                }
                if (socketPort == ingress.port()) {
                    diagnostics.add(Diagnostic.atPath(
                            "FLOW_TRIGGER_NETWORK_PORT_CONFLICT",
                            "HTTP trigger port conflicts with the socket listener port: "
                                    + socketPort + ".",
                            triggerPath(trigger) + ".config.port"
                    ));
                }
            }
            if (socket.isPresent()) {
                socketPort = (int) socket.getAsLong();
                if (httpPort == socketPort) {
                    diagnostics.add(Diagnostic.atPath(
                            "FLOW_TRIGGER_NETWORK_PORT_CONFLICT",
                            "Socket trigger port conflicts with the HTTP listener port: "
                                    + httpPort + ".",
                            triggerPath(trigger) + ".config.port"
                    ));
                }
            }
            if (diagnostics.size() == firstDiagnostic) {
                result.add(new CompiledFlow.Trigger(trigger.id(), trigger.type(), trigger.config()));
            }
        }
        return List.copyOf(result);
    }

    private static void validateCliTrigger(
            final DraftFlow.DraftTrigger trigger,
            final Map<String, ValueShape> inputs,
            final List<Diagnostic> diagnostics
    ) {
        final int firstDiagnostic = diagnostics.size();
        validateTriggerConfigFields(trigger, CLI_TRIGGER_CONFIG_FIELDS, diagnostics);
        final RailixValue stdinValue = trigger.config().values().get("stdin");
        final boolean stdin;
        if (stdinValue instanceof RailixValue.BooleanValue value) {
            stdin = value.value();
        } else {
            stdin = false;
            diagnostics.add(Diagnostic.atPath(
                    "FLOW_TRIGGER_CLI_STDIN_REQUIRED",
                    "CLI trigger config field stdin must be a boolean.",
                    triggerPath(trigger) + ".config.stdin"
            ));
        }

        String argumentsInput = "";
        if (trigger.config().values().containsKey("arguments")) {
            final RailixValue arguments = trigger.config().values().get("arguments");
            if (arguments instanceof RailixValue.StringValue value && !value.value().isBlank()) {
                argumentsInput = value.value();
            } else {
                diagnostics.add(Diagnostic.atPath(
                        "FLOW_TRIGGER_CLI_ARGUMENTS_INPUT_REQUIRED",
                        "CLI trigger config field arguments must name a flow input.",
                        triggerPath(trigger) + ".config.arguments"
                ));
            }
        }
        if (diagnostics.size() != firstDiagnostic) {
            return;
        }

        if (!argumentsInput.isEmpty()) {
            final ValueShape shape = inputs.get(argumentsInput);
            if (shape == null) {
                diagnostics.add(Diagnostic.atPath(
                        "FLOW_TRIGGER_CLI_ARGUMENTS_INPUT_UNKNOWN",
                        "CLI arguments input does not exist: " + argumentsInput + ".",
                        triggerPath(trigger) + ".config.arguments"
                ));
            } else if (shape != ValueShape.ARRAY && shape != ValueShape.ANY) {
                diagnostics.add(Diagnostic.atPath(
                        "FLOW_TRIGGER_CLI_ARGUMENTS_INPUT_TYPE_MISMATCH",
                        "CLI arguments input must be array or any: " + argumentsInput + ".",
                        triggerPath(trigger) + ".config.arguments"
                ));
            }
        }
        if (diagnostics.size() != firstDiagnostic) {
            return;
        }

        if (!stdin) {
            for (final String input : inputs.keySet().stream().sorted().toList()) {
                if (!input.equals(argumentsInput)) {
                    diagnostics.add(Diagnostic.atPath(
                            "FLOW_TRIGGER_CLI_INPUT_UNAVAILABLE",
                            "CLI trigger cannot supply flow input without stdin: " + input + ".",
                            triggerPath(trigger) + ".config.stdin"
                    ));
                }
            }
        }
    }

    private static void validateStartupTrigger(
            final DraftFlow.DraftTrigger trigger,
            final Map<String, ValueShape> inputs,
            final List<Diagnostic> diagnostics
    ) {
        validateTriggerConfigFields(trigger, Set.of(), diagnostics);
        if (!inputs.isEmpty()) {
            diagnostics.add(Diagnostic.atPath(
                    "FLOW_TRIGGER_STARTUP_INPUTS_UNSUPPORTED",
                    "Startup trigger requires an inputless flow.",
                    triggerPath(trigger) + ".config"
            ));
        }
    }

    private static Optional<HttpIngress> validateHttpTrigger(
            final DraftFlow.DraftTrigger trigger,
            final DraftFlow flow,
            final List<Diagnostic> diagnostics
    ) {
        final int firstDiagnostic = diagnostics.size();
        validateTriggerConfigFields(trigger, HTTP_TRIGGER_CONFIG_FIELDS, diagnostics);
        if (diagnostics.size() != firstDiagnostic) {
            return Optional.empty();
        }

        final RailixValue portValue = trigger.config().values().get("port");
        final int port;
        if (portValue instanceof RailixValue.NumberValue number) {
            try {
                port = number.value().intValueExact();
            } catch (final ArithmeticException exception) {
                diagnostics.add(Diagnostic.atPath(
                        "FLOW_TRIGGER_HTTP_PORT_REQUIRED",
                        "HTTP trigger config field port must be an integer.",
                        triggerPath(trigger) + ".config.port"
                ));
                return Optional.empty();
            }
        } else {
            diagnostics.add(Diagnostic.atPath(
                    "FLOW_TRIGGER_HTTP_PORT_REQUIRED",
                    "HTTP trigger config field port must be an integer.",
                    triggerPath(trigger) + ".config.port"
            ));
            return Optional.empty();
        }
        if (port < 1 || port > 65535) {
            diagnostics.add(Diagnostic.atPath(
                    "FLOW_TRIGGER_HTTP_PORT_OUT_OF_RANGE",
                    "HTTP trigger port must be between 1 and 65535.",
                    triggerPath(trigger) + ".config.port"
            ));
            return Optional.empty();
        }

        final long selectors = List.of("path", "flow", "step").stream()
                .filter(trigger.config().values()::containsKey)
                .count();
        if (selectors != 1) {
            diagnostics.add(Diagnostic.atPath(
                    "FLOW_TRIGGER_HTTP_SELECTOR_REQUIRED",
                    "HTTP trigger requires exactly one of path, flow, or step.",
                    triggerPath(trigger) + ".config"
            ));
            return Optional.empty();
        }

        if (trigger.config().values().containsKey("path")) {
            final RailixValue pathValue = trigger.config().values().get("path");
            if (!(pathValue instanceof RailixValue.StringValue path)
                    || !validHttpPath(path.value())) {
                diagnostics.add(Diagnostic.atPath(
                        "FLOW_TRIGGER_HTTP_PATH_INVALID",
                        "HTTP trigger path must be a static ASCII path without a trailing slash.",
                        triggerPath(trigger) + ".config.path"
                ));
                return Optional.empty();
            }
            if (path.value().equals("/v1") || path.value().startsWith("/v1/")) {
                diagnostics.add(Diagnostic.atPath(
                        "FLOW_TRIGGER_HTTP_PATH_RESERVED",
                        "HTTP trigger path cannot use the reserved /v1 API prefix.",
                        triggerPath(trigger) + ".config.path"
                ));
                return Optional.empty();
            }
            return Optional.of(new HttpIngress(port, path.value()));
        }

        if (!URL_SEGMENT.matcher(flow.id()).matches()) {
            diagnostics.add(Diagnostic.atPath(
                    "FLOW_TRIGGER_HTTP_FLOW_ID_UNSAFE",
                    "HTTP event flow id must be a URL-safe segment: " + flow.id() + ".",
                    "id"
            ));
            return Optional.empty();
        }
        if (trigger.config().values().containsKey("flow")) {
            if (!(trigger.config().values().get("flow") instanceof RailixValue.BooleanValue enabled)
                    || !enabled.value()) {
                diagnostics.add(Diagnostic.atPath(
                        "FLOW_TRIGGER_HTTP_FLOW_REQUIRED",
                        "HTTP trigger config field flow must be true.",
                        triggerPath(trigger) + ".config.flow"
                ));
                return Optional.empty();
            }
            return Optional.of(new HttpIngress(port, "/v1/flows/" + flow.id() + "/events"));
        }

        final RailixValue stepValue = trigger.config().values().get("step");
        if (!(stepValue instanceof RailixValue.StringValue step) || step.value().isBlank()) {
            diagnostics.add(Diagnostic.atPath(
                    "FLOW_TRIGGER_HTTP_STEP_REQUIRED",
                    "HTTP trigger config field step must name a Step.",
                    triggerPath(trigger) + ".config.step"
            ));
            return Optional.empty();
        }
        if (flow.steps().stream().noneMatch(candidate -> candidate.id().equals(step.value()))) {
            diagnostics.add(Diagnostic.atPath(
                    "FLOW_TRIGGER_HTTP_STEP_UNKNOWN",
                    "HTTP event Step does not exist: " + step.value() + ".",
                    triggerPath(trigger) + ".config.step"
            ));
            return Optional.empty();
        }
        if (!URL_SEGMENT.matcher(step.value()).matches()) {
            diagnostics.add(Diagnostic.atPath(
                    "FLOW_TRIGGER_HTTP_STEP_ID_UNSAFE",
                    "HTTP event Step id must be a URL-safe segment: " + step.value() + ".",
                    triggerPath(trigger) + ".config.step"
            ));
            return Optional.empty();
        }
        return Optional.of(new HttpIngress(
                port,
                "/v1/flows/" + flow.id() + "/steps/" + step.value() + "/events"
        ));
    }

    private static OptionalLong validateSocketTrigger(
            final DraftFlow.DraftTrigger trigger,
            final List<Diagnostic> diagnostics
    ) {
        final int firstDiagnostic = diagnostics.size();
        validateTriggerConfigFields(trigger, SOCKET_TRIGGER_CONFIG_FIELDS, diagnostics);
        if (diagnostics.size() != firstDiagnostic) {
            return OptionalLong.empty();
        }

        final OptionalLong port = triggerInteger(
                trigger,
                "port",
                "FLOW_TRIGGER_SOCKET_PORT_REQUIRED",
                "Socket trigger config field port must be an integer.",
                diagnostics
        );
        if (port.isEmpty()) {
            return OptionalLong.empty();
        }
        if (port.getAsLong() < 1 || port.getAsLong() > 65535) {
            diagnostics.add(Diagnostic.atPath(
                    "FLOW_TRIGGER_SOCKET_PORT_OUT_OF_RANGE",
                    "Socket trigger port must be between 1 and 65535.",
                    triggerPath(trigger) + ".config.port"
            ));
            return OptionalLong.empty();
        }

        final OptionalLong timeout = triggerInteger(
                trigger,
                "timeoutMillis",
                "FLOW_TRIGGER_SOCKET_TIMEOUT_REQUIRED",
                "Socket trigger config field timeoutMillis must be an integer.",
                diagnostics
        );
        if (timeout.isEmpty()) {
            return OptionalLong.empty();
        }
        if (timeout.getAsLong() < 1 || timeout.getAsLong() > 300_000) {
            diagnostics.add(Diagnostic.atPath(
                    "FLOW_TRIGGER_SOCKET_TIMEOUT_OUT_OF_RANGE",
                    "Socket trigger timeoutMillis must be between 1 and 300000.",
                    triggerPath(trigger) + ".config.timeoutMillis"
            ));
            return OptionalLong.empty();
        }

        final OptionalLong maxConnections = triggerInteger(
                trigger,
                "maxConnections",
                "FLOW_TRIGGER_SOCKET_MAX_CONNECTIONS_REQUIRED",
                "Socket trigger config field maxConnections must be an integer.",
                diagnostics
        );
        if (maxConnections.isEmpty()) {
            return OptionalLong.empty();
        }
        if (maxConnections.getAsLong() < 1 || maxConnections.getAsLong() > 256) {
            diagnostics.add(Diagnostic.atPath(
                    "FLOW_TRIGGER_SOCKET_MAX_CONNECTIONS_OUT_OF_RANGE",
                    "Socket trigger maxConnections must be between 1 and 256.",
                    triggerPath(trigger) + ".config.maxConnections"
            ));
            return OptionalLong.empty();
        }
        return port;
    }

    private static void validateScheduledTrigger(
            final DraftFlow.DraftTrigger trigger,
            final Map<String, ValueShape> inputs,
            final List<Diagnostic> diagnostics
    ) {
        final int firstDiagnostic = diagnostics.size();
        validateTriggerConfigFields(trigger, SCHEDULED_TRIGGER_CONFIG_FIELDS, diagnostics);
        if (diagnostics.size() != firstDiagnostic) {
            return;
        }

        final OptionalLong interval = triggerInteger(
                trigger,
                "intervalMillis",
                "FLOW_TRIGGER_SCHEDULED_INTERVAL_REQUIRED",
                "Scheduled trigger config field intervalMillis must be an integer.",
                diagnostics
        );
        if (interval.isEmpty()) {
            return;
        }
        if (interval.getAsLong() <= 0) {
            diagnostics.add(Diagnostic.atPath(
                    "FLOW_TRIGGER_SCHEDULED_INTERVAL_OUT_OF_RANGE",
                    "Scheduled trigger intervalMillis must be greater than zero.",
                    triggerPath(trigger) + ".config.intervalMillis"
            ));
            return;
        }

        final OptionalLong initialDelay = triggerInteger(
                trigger,
                "initialDelayMillis",
                "FLOW_TRIGGER_SCHEDULED_INITIAL_DELAY_REQUIRED",
                "Scheduled trigger config field initialDelayMillis must be an integer.",
                diagnostics
        );
        if (initialDelay.isEmpty()) {
            return;
        }
        if (initialDelay.getAsLong() < 0) {
            diagnostics.add(Diagnostic.atPath(
                    "FLOW_TRIGGER_SCHEDULED_INITIAL_DELAY_OUT_OF_RANGE",
                    "Scheduled trigger initialDelayMillis cannot be negative.",
                    triggerPath(trigger) + ".config.initialDelayMillis"
            ));
            return;
        }

        final OptionalLong maxConcurrentRuns = triggerInteger(
                trigger,
                "maxConcurrentRuns",
                "FLOW_TRIGGER_SCHEDULED_MAX_CONCURRENT_RUNS_REQUIRED",
                "Scheduled trigger config field maxConcurrentRuns must be an integer.",
                diagnostics
        );
        if (maxConcurrentRuns.isEmpty()) {
            return;
        }
        if (maxConcurrentRuns.getAsLong() < 1 || maxConcurrentRuns.getAsLong() > 256) {
            diagnostics.add(Diagnostic.atPath(
                    "FLOW_TRIGGER_SCHEDULED_MAX_CONCURRENT_RUNS_OUT_OF_RANGE",
                    "Scheduled trigger maxConcurrentRuns must be between 1 and 256.",
                    triggerPath(trigger) + ".config.maxConcurrentRuns"
            ));
            return;
        }
        if (!inputs.isEmpty()) {
            diagnostics.add(Diagnostic.atPath(
                    "FLOW_TRIGGER_SCHEDULED_INPUTS_UNSUPPORTED",
                    "Scheduled trigger requires an inputless flow.",
                    triggerPath(trigger) + ".config"
            ));
        }
    }

    private static OptionalLong triggerInteger(
            final DraftFlow.DraftTrigger trigger,
            final String field,
            final String code,
            final String message,
            final List<Diagnostic> diagnostics
    ) {
        final RailixValue value = trigger.config().values().get(field);
        if (value instanceof RailixValue.NumberValue number) {
            try {
                return OptionalLong.of(number.value().longValueExact());
            } catch (final ArithmeticException ignored) {
                // The compiler reports one stable field diagnostic below.
            }
        }
        diagnostics.add(Diagnostic.atPath(
                code,
                message,
                triggerPath(trigger) + ".config." + field
        ));
        return OptionalLong.empty();
    }

    private static boolean validHttpPath(final String path) {
        if (!HTTP_PATH.matcher(path).matches() || (path.length() > 1 && path.endsWith("/"))) {
            return false;
        }
        return Arrays.stream(path.split("/", -1))
                .noneMatch(segment -> segment.equals(".") || segment.equals(".."));
    }

    private static void validateTriggerConfigFields(
            final DraftFlow.DraftTrigger trigger,
            final Set<String> allowed,
            final List<Diagnostic> diagnostics
    ) {
        for (final String field : trigger.config().values().keySet().stream().sorted().toList()) {
            if (!allowed.contains(field)) {
                diagnostics.add(Diagnostic.atPath(
                        "FLOW_TRIGGER_CONFIG_FIELD_UNKNOWN",
                        "Unknown " + trigger.type() + " trigger config field: " + field,
                        triggerPath(trigger) + ".config." + field
                ));
            }
        }
    }

    private static String triggerPath(final DraftFlow.DraftTrigger trigger) {
        return "triggers[" + trigger.index() + "]";
    }

    private static void validateStepEventSources(
            final List<CompiledFlow.Trigger> triggers,
            final Map<String, DraftFlow.DraftStep> invocations,
            final Map<String, Map<String, List<CompiledFlow.Mapping>>> stepMappings,
            final Map<String, List<CompiledFlow.Mapping>> outputMappings,
            final List<Diagnostic> diagnostics
    ) {
        for (final CompiledFlow.Trigger trigger : triggers) {
            if (!"http".equals(trigger.type())
                    || !(trigger.config().values().get("step") instanceof RailixValue.StringValue eventStep)) {
                continue;
            }
            final ControlGraph graph = ControlGraph.analyze(eventStep.value(), invocations);
            for (final Map.Entry<String, Map<String, List<CompiledFlow.Mapping>>> target : stepMappings.entrySet()) {
                if (target.getKey().equals(eventStep.value()) || !graph.reaches(target.getKey())) {
                    continue;
                }
                for (final List<CompiledFlow.Mapping> mappings : target.getValue().values()) {
                    for (final CompiledFlow.Mapping mapping : mappings) {
                        validateStepEventSource(
                                eventStep.value(), target.getKey(), mapping, graph, diagnostics
                        );
                    }
                }
            }
            for (final List<CompiledFlow.Mapping> mappings : outputMappings.values()) {
                for (final CompiledFlow.Mapping mapping : mappings) {
                    validateStepEventSource(eventStep.value(), END, mapping, graph, diagnostics);
                }
            }
        }
    }

    private static void validateStepEventSource(
            final String eventStep,
            final String consumer,
            final CompiledFlow.Mapping mapping,
            final ControlGraph graph,
            final List<Diagnostic> diagnostics
    ) {
        final String destination = END.equals(consumer) ? "flow completion" : "downstream Step " + consumer;
        final String message;
        if (mapping.source() instanceof CompiledFlow.FlowInput input) {
            message = "Step event " + eventStep + " cannot supply flow input " + input.name()
                    + " to " + destination + ".";
        } else {
            final CompiledFlow.StepOutput output = (CompiledFlow.StepOutput) mapping.source();
            if (graph.outputAvailableAt(output.stepId(), consumer)) {
                return;
            }
            message = "Step event " + eventStep + " skips required Step output "
                    + output.stepId() + "." + output.port() + " for " + destination + ".";
        }
        diagnostics.add(Diagnostic.atPath(
                "FLOW_TRIGGER_HTTP_STEP_SOURCE_UNAVAILABLE",
                message,
                "connections[" + mapping.connectionIndex() + "]"
        ));
    }

    private static Optional<CompiledFlow.Conversion> conversion(
            final String encoded,
            final List<Diagnostic> diagnostics,
            final String path
    ) {
        for (final CompiledFlow.Conversion conversion : CompiledFlow.Conversion.values()) {
            if (conversion.encoded().equals(encoded)) {
                return Optional.of(conversion);
            }
        }
        diagnostics.add(Diagnostic.atPath(
                "FLOW_CONNECTION_CONVERSION_INVALID",
                "Unknown conversion: " + encoded + ". Supported conversions: "
                        + String.join(", ", SUPPORTED_CONVERSIONS) + ".",
                path
        ));
        return Optional.empty();
    }

    private static boolean validateMapping(
            final DraftFlow.DraftConnection connection,
            final ValueShape sourceShape,
            final ValueShape targetShape,
            final CompiledFlow.Conversion conversion,
            final List<Diagnostic> diagnostics,
            final String path
    ) {
        boolean valid = validRootPath(
                connection.sourcePath(), sourceShape, true, diagnostics, path + ".sourcePath"
        );
        valid &= validRootPath(
                connection.targetPath(), targetShape, false, diagnostics, path + ".targetPath"
        );
        if (!connection.defaultValue().isEmpty() && connection.sourcePath().elements().isEmpty()) {
            diagnostics.add(Diagnostic.atPath(
                    "FLOW_CONNECTION_DEFAULT_REQUIRES_SOURCE_PATH",
                    "default requires a sourcePath.",
                    path + ".default"
            ));
            valid = false;
        }
        if (conversion != CompiledFlow.Conversion.NONE
                && connection.sourcePath().elements().isEmpty()
                && sourceShape != ValueShape.ANY
                && sourceShape != conversion.source()) {
            diagnostics.add(Diagnostic.atPath(
                    "FLOW_CONNECTION_CONVERSION_TYPE_MISMATCH",
                    "Conversion " + conversion.encoded() + " requires " + conversion.source()
                            + " but source is " + sourceShape + ".",
                    path + ".convert"
            ));
            valid = false;
        }
        if (connection.targetPath().elements().isEmpty()) {
            final ValueShape mappedShape = conversion == CompiledFlow.Conversion.NONE
                    ? sourceShape
                    : conversion.target();
            final boolean shapeKnown = connection.sourcePath().elements().isEmpty()
                    || conversion != CompiledFlow.Conversion.NONE;
            if (shapeKnown && !targetShape.accepts(mappedShape)) {
                diagnostics.add(Diagnostic.atPath(
                        "FLOW_CONNECTION_TYPE_MISMATCH",
                        "Cannot connect " + mappedShape + " to " + targetShape + ".",
                        path
                ));
                valid = false;
            }
        }
        if (!connection.defaultValue().isEmpty()) {
            final ValueShape requiredDefault = conversion == CompiledFlow.Conversion.NONE
                    ? connection.targetPath().elements().isEmpty() ? targetShape : ValueShape.ANY
                    : conversion.target();
            final ValueShape defaultShape = ValueShape.shapeOf(connection.defaultValue().getFirst());
            if (!requiredDefault.accepts(defaultShape)) {
                diagnostics.add(Diagnostic.atPath(
                        "FLOW_CONNECTION_DEFAULT_TYPE_MISMATCH",
                        "Default requires " + requiredDefault + " but is " + defaultShape + ".",
                        path + ".default"
                ));
                valid = false;
            }
        }
        return valid;
    }

    private static boolean validRootPath(
            final DraftFlow.Path path,
            final ValueShape shape,
            final boolean source,
            final List<Diagnostic> diagnostics,
            final String diagnosticPath
    ) {
        if (path.elements().isEmpty() || shape == ValueShape.ANY) {
            return true;
        }
        final ValueShape required = path.elements().getFirst() instanceof DraftFlow.Path.Field
                ? ValueShape.OBJECT
                : ValueShape.ARRAY;
        if (shape != ValueShape.OBJECT && shape != ValueShape.ARRAY) {
            diagnostics.add(Diagnostic.atPath(
                    source
                            ? "FLOW_CONNECTION_SOURCE_PATH_TYPE_MISMATCH"
                            : "FLOW_CONNECTION_TARGET_PATH_TYPE_MISMATCH",
                    source
                            ? "Cannot select a sourcePath from " + shape + "."
                            : "Cannot assemble a targetPath into " + shape + ".",
                    diagnosticPath
            ));
            return false;
        }
        if (shape != required) {
            diagnostics.add(Diagnostic.atPath(
                    source
                            ? "FLOW_CONNECTION_SOURCE_PATH_TYPE_MISMATCH"
                            : "FLOW_CONNECTION_TARGET_PATH_TYPE_MISMATCH",
                    source
                            ? "Selector root requires " + required + " but source is " + shape + "."
                            : "Target path root requires " + required + " but target is " + shape + ".",
                    diagnosticPath
            ));
            return false;
        }
        return true;
    }

    private static void validateTargetMappings(
            final Map<String, Map<String, List<CompiledFlow.Mapping>>> stepMappings,
            final Map<String, List<CompiledFlow.Mapping>> outputMappings,
            final List<Diagnostic> diagnostics
    ) {
        for (final Map.Entry<String, Map<String, List<CompiledFlow.Mapping>>> step : stepMappings.entrySet()) {
            for (final Map.Entry<String, List<CompiledFlow.Mapping>> port : step.getValue().entrySet()) {
                validateTargetMappings(step.getKey() + "." + port.getKey(), port.getValue(), diagnostics);
            }
        }
        for (final Map.Entry<String, List<CompiledFlow.Mapping>> output : outputMappings.entrySet()) {
            validateTargetMappings("output." + output.getKey(), output.getValue(), diagnostics);
        }
    }

    private static void validateTargetMappings(
            final String endpoint,
            final List<CompiledFlow.Mapping> mappings,
            final List<Diagnostic> diagnostics
    ) {
        final List<CompiledFlow.Mapping> ordered = mappings.stream()
                .sorted((left, right) -> left.targetPath().compareTo(right.targetPath()))
                .toList();
        for (int index = 1; index < ordered.size(); index++) {
            final CompiledFlow.Mapping left = ordered.get(index - 1);
            final CompiledFlow.Mapping right = ordered.get(index);
            final CompiledFlow.Mapping offender = left.connectionIndex() > right.connectionIndex() ? left : right;
            if (left.targetPath().equals(right.targetPath())) {
                final String message = right.targetPath().elements().isEmpty()
                        ? "Connection target is already mapped: " + endpoint
                        : "Target path is already mapped: " + right.targetPath().json() + ".";
                diagnostics.add(Diagnostic.atPath(
                        "FLOW_CONNECTION_TARGET_DUPLICATE",
                        message,
                        "connections[" + offender.connectionIndex() + "]"
                ));
                return;
            }
            if (right.targetPath().startsWith(left.targetPath())) {
                final CompiledFlow.Mapping first = left.connectionIndex() < right.connectionIndex() ? left : right;
                final CompiledFlow.Mapping second = first == left ? right : left;
                diagnostics.add(Diagnostic.atPath(
                        "FLOW_CONNECTION_TARGET_CONFLICT",
                        "Target paths overlap: " + first.targetPath().json()
                                + " and " + second.targetPath().json() + ".",
                        "connections[" + offender.connectionIndex() + "]"
                ));
                return;
            }
            final int common = commonPrefix(left.targetPath(), right.targetPath());
            final DraftFlow.Path.Element leftElement = left.targetPath().elements().get(common);
            final DraftFlow.Path.Element rightElement = right.targetPath().elements().get(common);
            if ((leftElement instanceof DraftFlow.Path.Field && rightElement instanceof DraftFlow.Path.Index)
                    || (leftElement instanceof DraftFlow.Path.Index && rightElement instanceof DraftFlow.Path.Field)) {
                diagnostics.add(Diagnostic.atPath(
                        "FLOW_CONNECTION_TARGET_CONFLICT",
                        "Target path requires both ARRAY and OBJECT at "
                                + left.targetPath().prefix(common).json() + ".",
                        "connections[" + offender.connectionIndex() + "]"
                ));
                return;
            }
        }

        final Map<DraftFlow.Path, TreeMap<Integer, CompiledFlow.Mapping>> arrays = new LinkedHashMap<>();
        for (final CompiledFlow.Mapping mapping : mappings) {
            for (int index = 0; index < mapping.targetPath().elements().size(); index++) {
                final DraftFlow.Path.Element element = mapping.targetPath().elements().get(index);
                if (element instanceof DraftFlow.Path.Index arrayIndex) {
                    arrays.computeIfAbsent(mapping.targetPath().prefix(index), ignored -> new TreeMap<>())
                            .putIfAbsent(arrayIndex.value(), mapping);
                }
            }
        }
        for (final Map.Entry<DraftFlow.Path, TreeMap<Integer, CompiledFlow.Mapping>> array : arrays.entrySet()) {
            int expected = 0;
            for (final Map.Entry<Integer, CompiledFlow.Mapping> index : array.getValue().entrySet()) {
                if (index.getKey() != expected) {
                    diagnostics.add(Diagnostic.atPath(
                            "FLOW_CONNECTION_TARGET_ARRAY_HOLE",
                            "Target array at " + array.getKey().json() + " is missing index " + expected
                                    + " before index " + index.getKey() + ".",
                            "connections[" + index.getValue().connectionIndex() + "].targetPath"
                    ));
                    return;
                }
                expected++;
            }
        }
    }

    private static int commonPrefix(final DraftFlow.Path left, final DraftFlow.Path right) {
        final int limit = Math.min(left.elements().size(), right.elements().size());
        int index = 0;
        while (index < limit && left.elements().get(index).equals(right.elements().get(index))) {
            index++;
        }
        return index;
    }

    private static Map<String, CompiledFlow.Binding> bindings(
            final List<StepDefinition.Port> ports,
            final Map<String, List<CompiledFlow.Mapping>> mappings
    ) {
        final Map<String, CompiledFlow.Binding> result = new LinkedHashMap<>();
        for (final StepDefinition.Port port : sortedPorts(ports)) {
            result.put(port.name(), new CompiledFlow.Binding(
                    port.shape().orElseThrow(), mappings.get(port.name())
            ));
        }
        return result;
    }

    private static Map<String, CompiledFlow.Binding> bindings(
            final Map<String, ValueShape> ports,
            final Map<String, List<CompiledFlow.Mapping>> mappings
    ) {
        final Map<String, CompiledFlow.Binding> result = new LinkedHashMap<>();
        for (final String name : ports.keySet().stream().sorted().toList()) {
            result.put(name, new CompiledFlow.Binding(ports.get(name), mappings.get(name)));
        }
        return result;
    }

    private static List<StepDefinition.Port> sortedPorts(final List<StepDefinition.Port> ports) {
        return ports.stream().sorted((left, right) -> left.name().compareTo(right.name())).toList();
    }

    private static Map<String, StepDefinition> definitions(
            final StepCatalog catalog,
            final List<Diagnostic> diagnostics
    ) {
        final Map<String, StepDefinition> result = new LinkedHashMap<>();
        int index = 0;
        for (final StepDefinition definition : catalog.definitions()) {
            final String path = "catalog[" + index++ + "]";
            if (blank(definition.id())) {
                diagnostics.add(Diagnostic.atPath("STEP_ID_REQUIRED", "Step id must be non-blank.", path + ".id"));
                continue;
            }
            if (!jsonSafe(RailixValue.string(definition.id()))) {
                diagnostics.add(Diagnostic.atPath(
                        "STEP_ID_INVALID",
                        "Step id must contain valid Unicode.",
                        path + ".id"
                ));
                continue;
            }
            if (result.containsKey(definition.id())) {
                diagnostics.add(Diagnostic.atPath(
                        "STEP_ID_DUPLICATE",
                        "Duplicate Step dependency: " + definition.id(),
                        path + ".id"
                ));
            } else {
                result.put(definition.id(), definition);
            }
            validateDefinition(definition, path, diagnostics);
        }
        return result;
    }

    private static void validateDefinition(
            final StepDefinition definition,
            final String path,
            final List<Diagnostic> diagnostics
    ) {
        if (blank(definition.version())) {
            diagnostics.add(Diagnostic.atPath(
                    "STEP_VERSION_REQUIRED",
                    "Step version must be non-blank.",
                    path + ".version"
            ));
        } else if (!jsonSafe(RailixValue.string(definition.version()))) {
            diagnostics.add(Diagnostic.atPath(
                    "STEP_VERSION_INVALID",
                    "Step version must contain valid Unicode.",
                    path + ".version"
            ));
        }
        if (definition.handler().isEmpty()) {
            diagnostics.add(Diagnostic.atPath("STEP_HANDLER_REQUIRED", "Step handler is missing.", path + ".handler"));
        }
        uniquePorts(definition.inputs(), path + ".inputs", diagnostics);
        uniquePorts(definition.outputs(), path + ".outputs", diagnostics);
        uniqueConfig(definition.config(), path + ".config", diagnostics);
        final Set<String> outcomes = new HashSet<>();
        for (final String outcome : definition.outcomes()) {
            if (blank(outcome)) {
                diagnostics.add(Diagnostic.atPath("STEP_OUTCOME_REQUIRED", "Outcome must be non-blank.", path + ".outcomes"));
            } else if (!jsonSafe(RailixValue.string(outcome))) {
                diagnostics.add(Diagnostic.atPath(
                        "STEP_OUTCOME_INVALID",
                        "Step outcome must contain valid Unicode.",
                        path + ".outcomes"
                ));
            } else if (!outcomes.add(outcome)) {
                diagnostics.add(Diagnostic.atPath(
                        "STEP_OUTCOME_DUPLICATE",
                        "Duplicate Step outcome: " + outcome,
                        path + ".outcomes." + outcome
                ));
            }
        }
        if (definition.outcomes().isEmpty()) {
            diagnostics.add(Diagnostic.atPath("STEP_OUTCOME_REQUIRED", "Step must declare an outcome.", path + ".outcomes"));
        }
    }

    private static void uniqueConfig(
            final List<StepDefinition.Config> config,
            final String path,
            final List<Diagnostic> diagnostics
    ) {
        final Set<String> names = new HashSet<>();
        for (final StepDefinition.Config value : config) {
            if (blank(value.name()) || value.shape().isEmpty()) {
                diagnostics.add(Diagnostic.atPath(
                        "STEP_CONFIG_INVALID",
                        "Step configuration needs a name and shape.",
                        path
                ));
                continue;
            }
            if (!jsonSafe(RailixValue.string(value.name()))) {
                diagnostics.add(Diagnostic.atPath(
                        "STEP_CONFIG_INVALID",
                        "Step configuration name must contain valid Unicode.",
                        path
                ));
                continue;
            }
            if (!names.add(value.name())) {
                diagnostics.add(Diagnostic.atPath(
                        "STEP_CONFIG_DUPLICATE",
                        "Duplicate Step configuration: " + value.name(),
                        path + "." + value.name()
                ));
                continue;
            }
            if (value.format().isPresent()
                    && value.shape().orElseThrow() != value.format().orElseThrow().shape()) {
                diagnostics.add(Diagnostic.atPath(
                        "STEP_CONFIG_FORMAT_SHAPE_MISMATCH",
                        "Step configuration format " + value.format().orElseThrow().wireName()
                                + " requires " + value.format().orElseThrow().shape() + " shape.",
                        path + "." + value.name() + ".format"
                ));
                continue;
            }
            if (value.defaultValue().isPresent() && !jsonSafe(value.defaultValue().orElseThrow())) {
                diagnostics.add(Diagnostic.atPath(
                        "STEP_CONFIG_DEFAULT_INVALID",
                        "Step configuration default must contain valid JSON data.",
                        path + "." + value.name() + ".default"
                ));
            } else if (value.defaultValue().isPresent()
                    && !value.shape().orElseThrow().accepts(value.defaultValue().orElseThrow())) {
                diagnostics.add(Diagnostic.atPath(
                        "STEP_CONFIG_DEFAULT_TYPE_MISMATCH",
                        "Step configuration default " + value.name() + " requires "
                                + value.shape().orElseThrow() + " but received "
                                + ValueShape.shapeOf(value.defaultValue().orElseThrow()) + ".",
                        path + "." + value.name() + ".default"
                ));
            } else if (value.defaultValue().isPresent()
                    && value.format().isPresent()
                    && !value.format().orElseThrow().accepts(value.defaultValue().orElseThrow())) {
                diagnostics.add(Diagnostic.atPath(
                        "STEP_CONFIG_DEFAULT_FORMAT_MISMATCH",
                        "Step configuration default " + value.name() + " requires format "
                                + value.format().orElseThrow().wireName() + ".",
                        path + "." + value.name() + ".default"
                ));
            }
        }
    }

    private static Map<String, Map<String, RailixValue>> configurations(
            final DraftFlow flow,
            final Map<String, StepDefinition> resolved,
            final List<Diagnostic> diagnostics
    ) {
        final Map<String, Map<String, RailixValue>> result = new LinkedHashMap<>();
        for (final DraftFlow.DraftStep invocation : flow.steps()) {
            final StepDefinition definition = resolved.get(invocation.id());
            final Map<String, StepDefinition.Config> declared = new LinkedHashMap<>();
            final Map<String, RailixValue> values = new LinkedHashMap<>();
            for (final StepDefinition.Config config : definition.config()) {
                declared.put(config.name(), config);
                config.defaultValue().ifPresent(value -> values.put(config.name(), value));
            }
            for (final Map.Entry<String, RailixValue> override : invocation.config().entrySet()) {
                final StepDefinition.Config config = declared.get(override.getKey());
                final String path = "steps." + invocation.id() + ".config." + override.getKey();
                if (config == null) {
                    diagnostics.add(Diagnostic.atPath(
                            "FLOW_STEP_CONFIG_UNKNOWN",
                            "Unknown Step configuration: " + override.getKey(),
                            path
                    ));
                } else if (!config.shape().orElseThrow().accepts(override.getValue())) {
                    diagnostics.add(Diagnostic.atPath(
                            "FLOW_STEP_CONFIG_TYPE_MISMATCH",
                            "Step configuration " + override.getKey() + " requires "
                                    + config.shape().orElseThrow() + " but received "
                                    + ValueShape.shapeOf(override.getValue()) + ".",
                            path
                    ));
                } else if (config.format().isPresent()
                        && !config.format().orElseThrow().accepts(override.getValue())) {
                    diagnostics.add(Diagnostic.atPath(
                            "FLOW_STEP_CONFIG_FORMAT_MISMATCH",
                            "Step configuration " + override.getKey() + " requires format "
                                    + config.format().orElseThrow().wireName() + ".",
                            path
                    ));
                } else {
                    values.put(override.getKey(), override.getValue());
                }
            }
            for (final StepDefinition.Config config : definition.config()) {
                if (config.required()
                        && !values.containsKey(config.name())
                        && !invocation.config().containsKey(config.name())) {
                    diagnostics.add(Diagnostic.atPath(
                            "FLOW_STEP_CONFIG_REQUIRED",
                            "Required Step configuration is missing: " + config.name(),
                            "steps." + invocation.id() + ".config." + config.name()
                    ));
                }
            }
            result.put(invocation.id(), values);
        }
        return result;
    }

    private static void uniquePorts(
            final List<StepDefinition.Port> ports,
            final String path,
            final List<Diagnostic> diagnostics
    ) {
        final Set<String> names = new HashSet<>();
        for (final StepDefinition.Port port : ports) {
            if (blank(port.name()) || port.shape().isEmpty()) {
                diagnostics.add(Diagnostic.atPath("STEP_PORT_INVALID", "Step port needs a name and shape.", path));
            } else if (!jsonSafe(RailixValue.string(port.name()))) {
                diagnostics.add(Diagnostic.atPath(
                        "STEP_PORT_INVALID",
                        "Step port name must contain valid Unicode.",
                        path
                ));
            } else if (!names.add(port.name())) {
                diagnostics.add(Diagnostic.atPath(
                        "STEP_PORT_DUPLICATE",
                        "Duplicate Step port: " + port.name(),
                        path + "." + port.name()
                ));
            }
        }
    }

    private static boolean jsonSafe(final RailixValue value) {
        try {
            RailixJson.write(value);
            return true;
        } catch (final IllegalArgumentException exception) {
            return false;
        }
    }

    private static Map<String, DraftFlow.DraftStep> invocations(
            final DraftFlow flow,
            final List<Diagnostic> diagnostics
    ) {
        final Map<String, DraftFlow.DraftStep> result = new LinkedHashMap<>();
        for (final DraftFlow.DraftStep step : flow.steps()) {
            if (END.equals(step.id())) {
                diagnostics.add(Diagnostic.atPath(
                        "FLOW_STEP_ID_RESERVED",
                        "Flow Step id is reserved: " + END,
                        "steps." + END + ".id"
                ));
                result.putIfAbsent(step.id(), step);
            } else if (result.containsKey(step.id())) {
                diagnostics.add(Diagnostic.atPath(
                        "FLOW_STEP_ID_DUPLICATE",
                        "Duplicate flow Step id: " + step.id(),
                        "steps." + step.id()
                ));
            } else {
                result.put(step.id(), step);
            }
        }
        return result;
    }

    private static void validateTransitions(
            final Map<String, DraftFlow.DraftStep> invocations,
            final Map<String, StepDefinition> resolved,
            final List<Diagnostic> diagnostics
    ) {
        final Map<String, String> incomingSources = new HashMap<>();
        for (final Map.Entry<String, StepDefinition> entry : resolved.entrySet()) {
            final String stepId = entry.getKey();
            final DraftFlow.DraftStep invocation = invocations.get(stepId);
            for (final String outcome : entry.getValue().outcomes()) {
                if (!invocation.transitions().containsKey(outcome)) {
                    diagnostics.add(Diagnostic.atPath(
                            "FLOW_OUTCOME_UNHANDLED",
                            "Outcome must connect to another Step or end: " + outcome,
                            "steps." + stepId + ".on." + outcome
                    ));
                }
            }
            for (final Map.Entry<String, String> transition : invocation.transitions().entrySet()) {
                if (!entry.getValue().outcomes().contains(transition.getKey())) {
                    diagnostics.add(Diagnostic.atPath(
                            "FLOW_OUTCOME_UNKNOWN",
                            "Transition uses an undeclared outcome: " + transition.getKey(),
                            "steps." + stepId + ".on." + transition.getKey()
                    ));
                }
                final boolean targetExists = END.equals(transition.getValue())
                        || invocations.containsKey(transition.getValue());
                if (!targetExists) {
                    diagnostics.add(Diagnostic.atPath(
                            "FLOW_TRANSITION_TARGET_UNKNOWN",
                            "Transition target does not exist: " + transition.getValue(),
                            "steps." + stepId + ".on." + transition.getKey()
                    ));
                }
                if (!END.equals(transition.getValue())
                        && targetExists
                        && entry.getValue().outcomes().contains(transition.getKey())) {
                    final String existingSource = incomingSources.putIfAbsent(
                            transition.getValue(),
                            stepId
                    );
                    if (existingSource == null || existingSource.equals(stepId)) {
                        continue;
                    }
                    diagnostics.add(Diagnostic.atPath(
                            "FLOW_CONTROL_FAN_IN_UNSUPPORTED",
                            "Step cannot have more than one control predecessor: "
                                    + transition.getValue(),
                            "steps." + stepId + ".on." + transition.getKey()
                    ));
                }
            }
        }
    }

    private static void validateControlGraph(
            final String entry,
            final Map<String, DraftFlow.DraftStep> invocations,
            final List<Diagnostic> diagnostics
    ) {
        final Set<String> reachable = new LinkedHashSet<>();
        final Set<String> visiting = new HashSet<>();
        visit(entry, invocations, reachable, visiting, diagnostics);
        for (final String stepId : invocations.keySet()) {
            if (!reachable.contains(stepId)) {
                diagnostics.add(Diagnostic.atPath(
                        "FLOW_STEP_UNREACHABLE",
                        "Step is unreachable from entry: " + stepId,
                        "steps." + stepId
                ));
            }
        }
    }

    private static void visit(
            final String stepId,
            final Map<String, DraftFlow.DraftStep> invocations,
            final Set<String> reachable,
            final Set<String> visiting,
            final List<Diagnostic> diagnostics
    ) {
        if (visiting.contains(stepId)) {
            diagnostics.add(Diagnostic.atPath(
                    "FLOW_CONTROL_CYCLE_UNSUPPORTED",
                    "Control cycles are not supported.",
                    "steps." + stepId
            ));
            return;
        }
        if (!reachable.add(stepId)) {
            return;
        }
        visiting.add(stepId);
        for (final String target : invocations.get(stepId).transitions().values()) {
            if (!END.equals(target) && invocations.containsKey(target)) {
                visit(target, invocations, reachable, visiting, diagnostics);
            }
        }
        visiting.remove(stepId);
    }

    private static Optional<SourceEndpoint> sourceEndpoint(
            final String encoded,
            final DraftFlow flow,
            final Map<String, StepDefinition> resolved,
            final List<Diagnostic> diagnostics,
            final String path
    ) {
        if (encoded.startsWith("input.")) {
            final String port = encoded.substring("input.".length());
            if (!flow.inputs().containsKey(port)) {
                diagnostics.add(Diagnostic.atPath("FLOW_SOURCE_UNKNOWN", "Unknown flow input: " + port, path));
                return Optional.empty();
            }
            return Optional.of(new SourceEndpoint(
                    EndpointKind.FLOW_INPUT,
                    "input",
                    flow.inputs().get(port),
                    new CompiledFlow.FlowInput(port)
            ));
        }
        final Optional<EndpointParts> decoded = endpointParts(encoded, diagnostics, path);
        if (decoded.isEmpty()) {
            return Optional.empty();
        }
        final EndpointParts parts = decoded.get();
        if (!resolved.containsKey(parts.owner())) {
            diagnostics.add(Diagnostic.atPath("FLOW_SOURCE_UNKNOWN", "Unknown source Step: " + parts.owner(), path));
            return Optional.empty();
        }
        final Optional<StepDefinition.Port> output = port(resolved.get(parts.owner()).outputs(), parts.port());
        if (output.isEmpty()) {
            diagnostics.add(Diagnostic.atPath("FLOW_SOURCE_PORT_UNKNOWN", "Unknown Step output: " + encoded, path));
            return Optional.empty();
        }
        final StepDefinition.Port port = output.get();
        return Optional.of(new SourceEndpoint(
                EndpointKind.STEP_OUTPUT,
                parts.owner(),
                port.shape().orElseThrow(),
                new CompiledFlow.StepOutput(parts.owner(), parts.port())
        ));
    }

    private static Optional<TargetEndpoint> targetEndpoint(
            final String encoded,
            final DraftFlow flow,
            final Map<String, StepDefinition> resolved,
            final List<Diagnostic> diagnostics,
            final String path
    ) {
        if (encoded.startsWith("output.")) {
            final String port = encoded.substring("output.".length());
            if (!flow.outputs().containsKey(port)) {
                diagnostics.add(Diagnostic.atPath("FLOW_TARGET_UNKNOWN", "Unknown flow output: " + port, path));
                return Optional.empty();
            }
            return Optional.of(new TargetEndpoint(
                    EndpointKind.FLOW_OUTPUT,
                    "output",
                    port,
                    flow.outputs().get(port)
            ));
        }
        final Optional<EndpointParts> decoded = endpointParts(encoded, diagnostics, path);
        if (decoded.isEmpty()) {
            return Optional.empty();
        }
        final EndpointParts parts = decoded.get();
        if (!resolved.containsKey(parts.owner())) {
            diagnostics.add(Diagnostic.atPath("FLOW_TARGET_UNKNOWN", "Unknown target Step: " + parts.owner(), path));
            return Optional.empty();
        }
        final Optional<StepDefinition.Port> input = port(resolved.get(parts.owner()).inputs(), parts.port());
        if (input.isEmpty()) {
            diagnostics.add(Diagnostic.atPath("FLOW_TARGET_PORT_UNKNOWN", "Unknown Step input: " + encoded, path));
            return Optional.empty();
        }
        final StepDefinition.Port port = input.get();
        return Optional.of(new TargetEndpoint(
                EndpointKind.STEP_INPUT,
                parts.owner(),
                parts.port(),
                port.shape().orElseThrow()
        ));
    }

    private static Optional<EndpointParts> endpointParts(
            final String encoded,
            final List<Diagnostic> diagnostics,
            final String path
    ) {
        final int separator = encoded.lastIndexOf('.');
        if (separator <= 0 || separator == encoded.length() - 1) {
            diagnostics.add(Diagnostic.atPath("FLOW_ENDPOINT_INVALID", "Endpoint must be owner.port: " + encoded, path));
            return Optional.empty();
        }
        return Optional.of(new EndpointParts(
                encoded.substring(0, separator),
                encoded.substring(separator + 1)
        ));
    }

    private static Optional<StepDefinition.Port> port(
            final List<StepDefinition.Port> ports,
            final String name
    ) {
        for (final StepDefinition.Port port : ports) {
            if (name.equals(port.name())) {
                return Optional.of(port);
            }
        }
        return Optional.empty();
    }

    private static boolean blank(final String value) {
        return value.isBlank();
    }

    private enum EndpointKind {
        FLOW_INPUT,
        STEP_OUTPUT,
        STEP_INPUT,
        FLOW_OUTPUT
    }

    private record SourceEndpoint(
            EndpointKind kind,
            String owner,
            ValueShape shape,
            CompiledFlow.ValueSource source
    ) {
    }

    private record TargetEndpoint(
            EndpointKind kind,
            String owner,
            String port,
            ValueShape shape
    ) {
    }

    private record HttpIngress(int port, String route) {
    }

    private record EndpointParts(String owner, String port) {
    }
}
