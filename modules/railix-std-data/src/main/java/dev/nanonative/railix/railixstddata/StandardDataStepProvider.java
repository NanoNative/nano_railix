package dev.nanonative.railix.railixstddata;

import dev.nanonative.railix.kernel.model.Patch;
import dev.nanonative.railix.kernel.model.PermissionSet;
import dev.nanonative.railix.kernel.model.RailixPath;
import dev.nanonative.railix.kernel.model.RailixValue;
import dev.nanonative.railix.kernel.model.Reply;
import dev.nanonative.railix.kernel.model.Selector;
import dev.nanonative.railix.kernel.model.StepContract;
import dev.nanonative.railix.kernel.runtime.ContextDoc;
import dev.nanonative.railix.kernel.runtime.Step;
import dev.nanonative.railix.kernel.runtime.StepProvider;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal standard data pack provider used by the local execution kernel.
 */
public final class StandardDataStepProvider implements StepProvider {

    private static final String DATA_TRANSFORM_USE = "railix.std.data.DataTransform";
    private static final String DATA_VALIDATE_USE = "railix.std.data.DataValidate";
    private static final String DATA_ROUTE_USE = "railix.std.data.DataRoute";
    private static final String DATA_FOR_EACH_USE = "railix.std.data.DataForEach";
    private static final String DATA_AGGREGATE_USE = "railix.std.data.DataAggregate";
    private static final String CAPTURE_PAYLOAD_USE = "std.data.capture-payload";
    private static final String CAPTURE_SETTING_USE = "std.data.capture-setting";
    private static final RailixPath APP_MODE_PATH = RailixPath.parse("settings.app.mode");
    private static final RailixPath CONTEXT_MODE_PATH = RailixPath.parse("ctx.settings.mode");
    private static final RailixPath DEFAULT_FOR_EACH_STATE_PATH = RailixPath.parse("ctx.foreach");
    private static final String FOR_EACH_NEXT_INDEX_FIELD = "_nextIndex";
    private static final String FOR_EACH_TOTAL_FIELD = "_total";
    private static final String FOR_EACH_SELECTOR_FIELD = "_selector";
    private static final String FOR_EACH_ALIAS_FIELD = "_alias";
    private static final Set<String> FOR_EACH_RESERVED_ALIAS_NAMES = Set.of(
            FOR_EACH_NEXT_INDEX_FIELD,
            FOR_EACH_TOTAL_FIELD,
            FOR_EACH_SELECTOR_FIELD,
            FOR_EACH_ALIAS_FIELD
    );
    private static final Pattern TEMPLATE_PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");
    private static final String REPEAT_WILDCARD = "[*]";
    private static final Set<String> RESERVED_ROOT_NAMES = Set.of("payload", "metadata", "refs", "ctx", "reply", "settings");

    @Override
    public Optional<Step> resolve(final String use) {
        return switch (use) {
            case DATA_TRANSFORM_USE -> Optional.of(dataTransformStep());
            case DATA_VALIDATE_USE -> Optional.of(dataValidateStep());
            case DATA_ROUTE_USE -> Optional.of(dataRouteStep());
            case DATA_FOR_EACH_USE -> Optional.of(dataForEachStep());
            case DATA_AGGREGATE_USE -> Optional.of(dataAggregateStep());
            case CAPTURE_PAYLOAD_USE -> Optional.of(capturePayloadStep());
            case CAPTURE_SETTING_USE -> Optional.of(captureSettingStep());
            default -> Optional.empty();
        };
    }

    @Override
    public List<String> supportedUses() {
        return List.of(
                DATA_TRANSFORM_USE,
                DATA_VALIDATE_USE,
                DATA_ROUTE_USE,
                DATA_FOR_EACH_USE,
                DATA_AGGREGATE_USE,
                CAPTURE_PAYLOAD_USE,
                CAPTURE_SETTING_USE
        );
    }

    private static Step dataTransformStep() {
        return new Step() {
            @Override
            public StepContract contract() {
                return new StepContract(
                        DATA_TRANSFORM_USE,
                        "0.1.0",
                        "Data Transform",
                        "Maps payload and context values into context and reply through structured mapping config.",
                        StepContract.Kind.NORMAL,
                        List.of(
                                new StepContract.Port("payload", "document", "payload", false, List.of()),
                                new StepContract.Port("ctx", "document", "ctx", false, List.of())
                        ),
                        List.of(
                                new StepContract.Port("patches", "patch-list", "ctx|reply", true, List.of()),
                                new StepContract.Port("outcome", "enum", "ok|invalid|error", true, List.of("ok", "invalid", "error"))
                        ),
                        Map.of(
                                "ok", new StepContract.Outcome("Transform completed."),
                                "invalid", new StepContract.Outcome("Input did not match required shape."),
                                "error", new StepContract.Outcome("Unexpected failure.")
                        ),
                        new StepContract.Settings(List.of()),
                        PermissionSet.none(),
                        new StepContract.Timeout(Duration.ofSeconds(30)),
                        new StepContract.RetryPolicy(1, Duration.ZERO),
                        new StepContract.CachePolicy(StepContract.CachePolicy.Mode.NONE, "", Duration.ZERO),
                        new StepContract.Resources(new StepContract.Limits(RailixValue.NULL, RailixValue.NULL)),
                        new StepContract.Metrics(List.of()),
                        Map.of("editor", new RailixValue.StringValue("dataworkbench.transform"))
                );
            }

            @Override
            public Result execute(final ExecutionInput input) {
                final RailixValue mappingsValue = input.config().values().get("mappings");
                final RailixValue.ListValue mappings = requireList(mappingsValue, "config.mappings");
                final List<Patch> patches = new ArrayList<>();
                ContextDoc workingContext = input.context();
                for (int index = 0; index < mappings.values().size(); index++) {
                    final RailixValue mappingValue = mappings.values().get(index);
                    workingContext = applyMapping(
                            requireObject(mappingValue, "config.mappings[" + index + "]"),
                            "config.mappings[" + index + "]",
                            workingContext,
                            patches,
                            Map.of()
                    );
                }
                return new Result("ok", List.copyOf(patches));
            }
        };
    }

    private static Step dataValidateStep() {
        return new Step() {
            @Override
            public StepContract contract() {
                return new StepContract(
                        DATA_VALIDATE_USE,
                        "0.1.0",
                        "Data Validate",
                        "Validates that required paths resolve to non-null values before downstream routing.",
                        StepContract.Kind.NORMAL,
                        List.of(
                                new StepContract.Port("payload", "document", "payload", false, List.of()),
                                new StepContract.Port("ctx", "document", "ctx", false, List.of())
                        ),
                        List.of(new StepContract.Port("outcome", "enum", "valid|invalid", true, List.of("valid", "invalid"))),
                        Map.of(
                                "valid", new StepContract.Outcome("Required values were present."),
                                "invalid", new StepContract.Outcome("One or more required values were missing.")
                        ),
                        new StepContract.Settings(List.of()),
                        PermissionSet.none(),
                        new StepContract.Timeout(Duration.ofSeconds(30)),
                        new StepContract.RetryPolicy(1, Duration.ZERO),
                        new StepContract.CachePolicy(StepContract.CachePolicy.Mode.NONE, "", Duration.ZERO),
                        new StepContract.Resources(new StepContract.Limits(RailixValue.NULL, RailixValue.NULL)),
                        new StepContract.Metrics(List.of()),
                        Map.of("editor", new RailixValue.StringValue("dataworkbench.validate"))
                );
            }

            @Override
            public Result execute(final ExecutionInput input) {
                final RailixValue.ListValue requiredPaths = requireList(input.config().values().get("required"), "config.required");
                for (int index = 0; index < requiredPaths.values().size(); index++) {
                    final String path = requireString(requiredPaths.values().get(index), "config.required[" + index + "]");
                    if (input.context().get(RailixPath.parse(path)) == RailixValue.NULL) {
                        return new Result("invalid", List.of());
                    }
                }
                return new Result("valid", List.of());
            }
        };
    }

    private static Step dataRouteStep() {
        return new Step() {
            @Override
            public StepContract contract() {
                return new StepContract(
                        DATA_ROUTE_USE,
                        "0.1.0",
                        "Data Route",
                        "Routes execution to the first configured outcome whose condition evaluates to true.",
                        StepContract.Kind.NORMAL,
                        List.of(
                                new StepContract.Port("payload", "document", "payload", false, List.of()),
                                new StepContract.Port("ctx", "document", "ctx", false, List.of())
                        ),
                        List.of(new StepContract.Port("outcome", "enum", "configured-route", true, List.of())),
                        Map.of(),
                        new StepContract.Settings(List.of()),
                        PermissionSet.none(),
                        new StepContract.Timeout(Duration.ofSeconds(30)),
                        new StepContract.RetryPolicy(1, Duration.ZERO),
                        new StepContract.CachePolicy(StepContract.CachePolicy.Mode.NONE, "", Duration.ZERO),
                        new StepContract.Resources(new StepContract.Limits(RailixValue.NULL, RailixValue.NULL)),
                        new StepContract.Metrics(List.of()),
                        Map.of("editor", new RailixValue.StringValue("dataworkbench.route"))
                );
            }

            @Override
            public Result execute(final ExecutionInput input) {
                final RailixValue.ListValue routes = requireList(input.config().values().get("routes"), "config.routes");
                for (int index = 0; index < routes.values().size(); index++) {
                    final RailixValue.ObjectValue route = requireObject(routes.values().get(index), "config.routes[" + index + "]");
                    final String outcome = requireNonBlankString(route.values().get("outcome"), "config.routes[" + index + "].outcome");
                    final RailixValue.ObjectValue when = requireObject(route.values().get("when"), "config.routes[" + index + "].when");
                    if (evaluateRouteCondition(when, input.context())) {
                        return new Result(outcome, List.of());
                    }
                }
                throw new IllegalArgumentException("DataRoute config.routes did not match any configured route");
            }
        };
    }

    private static Step dataForEachStep() {
        return new Step() {
            @Override
            public StepContract contract() {
                return new StepContract(
                        DATA_FOR_EACH_USE,
                        "0.1.0",
                        "Data For Each",
                        "Prepares the next selector match in context and emits loop outcomes for the plan graph.",
                        StepContract.Kind.NORMAL,
                        List.of(
                                new StepContract.Port("payload", "document", "payload", false, List.of()),
                                new StepContract.Port("ctx", "document", "ctx", false, List.of())
                        ),
                        List.of(
                                new StepContract.Port("patches", "patch-list", "ctx", true, List.of()),
                                new StepContract.Port("outcome", "enum", "item|done|empty", true, List.of("item", "done", "empty"))
                        ),
                        Map.of(
                                "item", new StepContract.Outcome("Prepared the next selected item in context."),
                                "done", new StepContract.Outcome("All selected items were already iterated."),
                                "empty", new StepContract.Outcome("Selector resolved no items.")
                        ),
                        new StepContract.Settings(List.of()),
                        PermissionSet.none(),
                        new StepContract.Timeout(Duration.ofSeconds(30)),
                        new StepContract.RetryPolicy(1, Duration.ZERO),
                        new StepContract.CachePolicy(StepContract.CachePolicy.Mode.NONE, "", Duration.ZERO),
                        new StepContract.Resources(new StepContract.Limits(RailixValue.NULL, RailixValue.NULL)),
                        new StepContract.Metrics(List.of()),
                        Map.of("editor", new RailixValue.StringValue("dataworkbench.foreach"))
                );
            }

            @Override
            public Result execute(final ExecutionInput input) {
                final String alias = input.config().values().containsKey("as")
                        ? requireNonBlankString(input.config().values().get("as"), "config.as")
                        : "item";
                validateAliasName(alias, "config.as", new LinkedHashSet<>(), FOR_EACH_RESERVED_ALIAS_NAMES);
                final RailixPath statePath = input.config().values().containsKey("statePath")
                        ? requireContextStatePath(input.config().values().get("statePath"), "config.statePath")
                        : DEFAULT_FOR_EACH_STATE_PATH;
                final String selectorExpression = requireString(input.config().values().get("selector"), "config.selector");
                assertForEachStateMatches(input.context(), statePath, selectorExpression, alias);
                final SelectorBinding selectorBinding = buildSelectorBinding(
                        alias,
                        new Selector(selectorExpression),
                        "config.as",
                        input.context()
                );
                if (selectorBinding.paths().isEmpty()) {
                    return new Result("empty", clearForEachState(statePath));
                }
                final Map<String, SelectorBinding> parentAliases = resolveParentAliases(
                        input.config().values().get("parentAliases"),
                        "config.parentAliases",
                        input.context(),
                        selectorBinding.alias(),
                        FOR_EACH_RESERVED_ALIAS_NAMES
                );
                final int nextIndex = readForEachNextIndex(input.context(), statePath);
                if (nextIndex >= selectorBinding.paths().size()) {
                    return new Result("done", clearForEachState(statePath));
                }
                final RailixPath selectedPath = selectorBinding.paths().get(nextIndex);
                final List<Patch> patches = new ArrayList<>();
                patches.add(new Patch.Set(
                        appendStatePath(statePath, FOR_EACH_NEXT_INDEX_FIELD),
                        new Patch.LiteralSource(new RailixValue.NumberValue(BigDecimal.valueOf(nextIndex + 1L)))
                ));
                patches.add(new Patch.Set(
                        appendStatePath(statePath, FOR_EACH_TOTAL_FIELD),
                        new Patch.LiteralSource(new RailixValue.NumberValue(BigDecimal.valueOf(selectorBinding.paths().size())))
                ));
                patches.add(new Patch.Set(
                        appendStatePath(statePath, FOR_EACH_SELECTOR_FIELD),
                        new Patch.LiteralSource(new RailixValue.StringValue(selectorExpression))
                ));
                patches.add(new Patch.Set(
                        appendStatePath(statePath, FOR_EACH_ALIAS_FIELD),
                        new Patch.LiteralSource(new RailixValue.StringValue(alias))
                ));
                patches.add(new Patch.Set(
                        appendStatePath(statePath, alias),
                        new Patch.LiteralSource(input.context().get(selectedPath))
                ));
                for (final Map.Entry<String, SelectorBinding> entry : parentAliases.entrySet()) {
                    final RailixPath ancestorPath = resolveAncestorAlias(entry.getValue(), selectedPath, "config.parentAliases");
                    patches.add(new Patch.Set(
                            appendStatePath(statePath, entry.getKey()),
                            new Patch.LiteralSource(input.context().get(ancestorPath))
                    ));
                }
                return new Result("item", List.copyOf(patches));
            }
        };
    }

    private static Step dataAggregateStep() {
        return new Step() {
            @Override
            public StepContract contract() {
                return new StepContract(
                        DATA_AGGREGATE_USE,
                        "0.1.0",
                        "Data Aggregate",
                        "Aggregates selector results into ctx patches through count, sum, min, max, and collect operations.",
                        StepContract.Kind.NORMAL,
                        List.of(
                                new StepContract.Port("payload", "document", "payload", false, List.of()),
                                new StepContract.Port("ctx", "document", "ctx", false, List.of())
                        ),
                        List.of(
                                new StepContract.Port("patches", "patch-list", "ctx", true, List.of()),
                                new StepContract.Port("outcome", "enum", "ok|empty", true, List.of("ok", "empty"))
                        ),
                        Map.of(
                                "ok", new StepContract.Outcome("Aggregations produced ctx patches."),
                                "empty", new StepContract.Outcome("Selector resolved no items.")
                        ),
                        new StepContract.Settings(List.of()),
                        PermissionSet.none(),
                        new StepContract.Timeout(Duration.ofSeconds(30)),
                        new StepContract.RetryPolicy(1, Duration.ZERO),
                        new StepContract.CachePolicy(StepContract.CachePolicy.Mode.NONE, "", Duration.ZERO),
                        new StepContract.Resources(new StepContract.Limits(RailixValue.NULL, RailixValue.NULL)),
                        new StepContract.Metrics(List.of()),
                        Map.of("editor", new RailixValue.StringValue("dataworkbench.aggregate"))
                );
            }

            @Override
            public Result execute(final ExecutionInput input) {
                final String alias = input.config().values().containsKey("as")
                        ? requireNonBlankString(input.config().values().get("as"), "config.as")
                        : "item";
                validateAliasName(alias, "config.as", new LinkedHashSet<>());
                final String selectorExpression = requireString(input.config().values().get("selector"), "config.selector");
                final SelectorBinding selectorBinding = buildSelectorBinding(
                        alias,
                        new Selector(selectorExpression),
                        "config.as",
                        input.context()
                );
                if (selectorBinding.paths().isEmpty()) {
                    return new Result("empty", List.of());
                }
                final RailixValue.ListValue aggregations = requireList(input.config().values().get("aggregations"), "config.aggregations");
                final List<Patch> patches = new ArrayList<>();
                for (int index = 0; index < aggregations.values().size(); index++) {
                    final String fieldName = "config.aggregations[" + index + "]";
                    final RailixValue.ObjectValue aggregation = requireObject(aggregations.values().get(index), fieldName);
                    final RailixPath target = RailixPath.parse(requireString(aggregation.values().get("target"), fieldName + ".target"));
                    validateContextTargetRoot(target, "DataAggregate");
                    patches.add(new Patch.Set(
                            target,
                            new Patch.LiteralSource(applyAggregation(selectorBinding, aggregation, fieldName, input.context()))
                    ));
                }
                return new Result("ok", List.copyOf(patches));
            }
        };
    }

    private static Step capturePayloadStep() {
        return new Step() {
            @Override
            public StepContract contract() {
                return new StepContract(
                        CAPTURE_PAYLOAD_USE,
                        "0.1.0",
                        "Capture payload",
                        "Copies the incoming payload into ctx.payload for downstream steps.",
                        StepContract.Kind.NORMAL,
                        List.of(),
                        List.of(),
                        Map.of("ok", new StepContract.Outcome("Payload captured into context")),
                        new StepContract.Settings(List.of()),
                        PermissionSet.none(),
                        new StepContract.Timeout(Duration.ofSeconds(30)),
                        new StepContract.RetryPolicy(1, Duration.ZERO),
                        new StepContract.CachePolicy(StepContract.CachePolicy.Mode.NONE, "", Duration.ZERO),
                        new StepContract.Resources(new StepContract.Limits(RailixValue.NULL, RailixValue.NULL)),
                        new StepContract.Metrics(List.of()),
                        Map.of()
                );
            }

            @Override
            public Result execute(final ExecutionInput input) {
                return new Result(
                        "ok",
                        List.of(new Patch.Set(
                                RailixPath.parse("ctx.payload"),
                                new Patch.ExpressionSource(new Patch.PathExpression(RailixPath.parse("payload")))
                        ))
                );
            }
        };
    }

    private static Step captureSettingStep() {
        return new Step() {
            @Override
            public StepContract contract() {
                return new StepContract(
                        CAPTURE_SETTING_USE,
                        "0.1.0",
                        "Capture setting",
                        "Copies settings.app.mode into ctx.settings.mode for launcher and packaging smoke tests.",
                        StepContract.Kind.NORMAL,
                        List.of(),
                        List.of(),
                        Map.of("ok", new StepContract.Outcome("Setting captured into context")),
                        new StepContract.Settings(List.of(APP_MODE_PATH.toString())),
                        PermissionSet.requestedOnly(Map.of("settings.read", List.of(APP_MODE_PATH.toString()))),
                        new StepContract.Timeout(Duration.ofSeconds(30)),
                        new StepContract.RetryPolicy(1, Duration.ZERO),
                        new StepContract.CachePolicy(StepContract.CachePolicy.Mode.NONE, "", Duration.ZERO),
                        new StepContract.Resources(new StepContract.Limits(RailixValue.NULL, RailixValue.NULL)),
                        new StepContract.Metrics(List.of()),
                        Map.of()
                );
            }

            @Override
            public Result execute(final ExecutionInput input) {
                final RailixValue resolvedMode = input.settings().require(APP_MODE_PATH).value();
                return new Result(
                        "ok",
                        List.of(new Patch.Set(
                                CONTEXT_MODE_PATH,
                                new Patch.LiteralSource(resolvedMode)
                        )),
                        new Reply(
                                Reply.Mode.IMMEDIATE,
                                RailixValue.NULL,
                                new RailixValue.ObjectValue(Map.of("resolvedMode", resolvedMode)),
                                RailixValue.NULL,
                                RailixValue.NULL,
                                RailixValue.NULL,
                                RailixValue.NULL,
                                RailixValue.NULL
                        )
                );
            }
        };
    }

    private static ContextDoc applyMapping(
            final RailixValue.ObjectValue mapping,
            final String fieldName,
            final ContextDoc workingContext,
            final List<Patch> patches,
            final Map<String, RailixPath> aliases
    ) {
        if (mapping.values().containsKey("repeat")) {
            if (!aliases.isEmpty()) {
                throw new IllegalArgumentException("Nested repeated mappings are not supported yet");
            }
            return applyRepeatMapping(requireObject(mapping.values().get("repeat"), fieldName + ".repeat"), fieldName + ".repeat", workingContext, patches);
        }
        final RailixPath target = RailixPath.parse(requireString(mapping.values().get("target"), fieldName + ".target"));
        validateTargetRoot(target);
        return applyResolvedMapping(target, requireObject(mapping.values().get("expression"), fieldName + ".expression"), workingContext, patches, aliases);
    }

    private static ContextDoc applyRepeatMapping(
            final RailixValue.ObjectValue repeat,
            final String fieldName,
            final ContextDoc workingContext,
            final List<Patch> patches
    ) {
        final SelectorBinding selectorBinding = buildSelectorBinding(
                requireString(repeat.values().get("as"), fieldName + ".as"),
                new Selector(requireString(repeat.values().get("selector"), fieldName + ".selector")),
                fieldName + ".as",
                workingContext
        );
        final Map<String, SelectorBinding> parentAliases = resolveParentAliases(
                repeat.values().get("parentAliases"),
                fieldName + ".parentAliases",
                workingContext,
                selectorBinding.alias(),
                Set.of()
        );
        final RailixValue.ListValue mappings = requireList(repeat.values().get("mappings"), fieldName + ".mappings");
        return applySelectorMappings(selectorBinding, parentAliases, mappings, fieldName + ".mappings", workingContext, patches);
    }

    private static ContextDoc applySelectorMappings(
            final SelectorBinding selectorBinding,
            final Map<String, SelectorBinding> parentAliases,
            final RailixValue.ListValue mappings,
            final String fieldName,
            final ContextDoc workingContext,
            final List<Patch> patches
    ) {
        ContextDoc currentContext = workingContext;
        for (int selectedIndex = 0; selectedIndex < selectorBinding.paths().size(); selectedIndex++) {
            final RailixPath selectedPath = selectorBinding.paths().get(selectedIndex);
            final Map<String, RailixPath> aliases = new LinkedHashMap<>();
            aliases.put(selectorBinding.alias(), selectedPath);
            for (final Map.Entry<String, SelectorBinding> entry : parentAliases.entrySet()) {
                aliases.put(entry.getKey(), resolveAncestorAlias(entry.getValue(), selectedPath, fieldName));
            }
            for (int mappingIndex = 0; mappingIndex < mappings.values().size(); mappingIndex++) {
                final String nestedFieldName = fieldName + "[" + mappingIndex + "]";
                final RailixValue.ObjectValue nestedMapping = requireObject(mappings.values().get(mappingIndex), nestedFieldName);
                if (nestedMapping.values().containsKey("repeat")) {
                    throw new IllegalArgumentException("Nested repeated mappings are not supported yet");
                }
                final String targetTemplate = requireString(nestedMapping.values().get("target"), nestedFieldName + ".target");
                final RailixPath target = materializeRepeatTarget(targetTemplate, selectedIndex, nestedFieldName + ".target");
                validateTargetRoot(target);
                currentContext = applyResolvedMapping(
                        target,
                        requireObject(nestedMapping.values().get("expression"), nestedFieldName + ".expression"),
                        currentContext,
                        patches,
                        aliases
                );
            }
        }
        return currentContext;
    }

    private static ContextDoc applyResolvedMapping(
            final RailixPath target,
            final RailixValue.ObjectValue expression,
            final ContextDoc workingContext,
            final List<Patch> patches,
            final Map<String, RailixPath> aliases
    ) {
        final RailixValue resolvedValue = evaluateExpression(expression, workingContext, aliases);
        final Patch.Set patch = new Patch.Set(target, new Patch.LiteralSource(resolvedValue));
        patches.add(patch);
        return workingContext.apply(patch);
    }

    private static RailixValue evaluateExpression(
            final RailixValue.ObjectValue expression,
            final ContextDoc context,
            final Map<String, RailixPath> aliases
    ) {
        if (expression.values().containsKey("path")) {
            return resolvePathValue(requireString(expression.values().get("path"), "expression.path"), context, aliases);
        }
        if (expression.values().containsKey("const")) {
            return expression.values().get("const");
        }
        final String op = requireString(expression.values().get("op"), "expression.op");
        return switch (op) {
            case "trim" -> trim(evaluateNestedExpression(expression, "input", context, aliases));
            case "lower" -> lower(evaluateNestedExpression(expression, "input", context, aliases));
            case "default" -> defaultValue(
                    evaluateNestedExpression(expression, "value", context, aliases),
                    evaluateNestedExpression(expression, "default", context, aliases)
            );
            case "template" -> new RailixValue.StringValue(applyTemplate(
                    requireString(expression.values().get("template"), "expression.template"),
                    context,
                    aliases
            ));
            case "toInt" -> toInt(evaluateNestedExpression(expression, "input", context, aliases));
            case "multiply" -> multiply(
                    evaluateNestedExpression(expression, "left", context, aliases),
                    evaluateNestedExpression(expression, "right", context, aliases)
            );
            default -> throw new IllegalArgumentException("Unsupported data transform operator: " + op);
        };
    }

    private static boolean evaluateRouteCondition(
            final RailixValue.ObjectValue expression,
            final ContextDoc context
    ) {
        if (expression.values().containsKey("const")) {
            final RailixValue value = expression.values().get("const");
            if (value instanceof RailixValue.BoolValue boolValue) {
                return boolValue.value();
            }
            throw new IllegalArgumentException("DataRoute const conditions must be boolean");
        }
        final String op = requireString(expression.values().get("op"), "condition.op");
        return switch (op) {
            case "greaterThan" -> toBigDecimal(
                    evaluateNestedExpression(expression, "left", context, Map.of()),
                    "condition.left"
            ).compareTo(toBigDecimal(
                    evaluateNestedExpression(expression, "right", context, Map.of()),
                    "condition.right"
            )) > 0;
            default -> throw new IllegalArgumentException("Unsupported data route operator: " + op);
        };
    }

    private static RailixValue evaluateNestedExpression(
            final RailixValue.ObjectValue expression,
            final String fieldName,
            final ContextDoc context,
            final Map<String, RailixPath> aliases
    ) {
        return evaluateExpression(requireObject(expression.values().get(fieldName), "expression." + fieldName), context, aliases);
    }

    private static RailixValue trim(final RailixValue value) {
        if (value == RailixValue.NULL) {
            return RailixValue.NULL;
        }
        return new RailixValue.StringValue(requireString(value, "trim.input").trim());
    }

    private static RailixValue lower(final RailixValue value) {
        if (value == RailixValue.NULL) {
            return RailixValue.NULL;
        }
        return new RailixValue.StringValue(requireString(value, "lower.input").toLowerCase(Locale.ROOT));
    }

    private static RailixValue defaultValue(final RailixValue value, final RailixValue fallback) {
        return value == RailixValue.NULL ? fallback : value;
    }

    private static RailixValue toInt(final RailixValue value) {
        return new RailixValue.NumberValue(canonicalNumber(new BigDecimal(toBigDecimal(value, "toInt.input").toBigIntegerExact())));
    }

    private static RailixValue multiply(final RailixValue left, final RailixValue right) {
        return new RailixValue.NumberValue(
                canonicalNumber(toBigDecimal(left, "multiply.left").multiply(toBigDecimal(right, "multiply.right")))
        );
    }

    private static BigDecimal toBigDecimal(final RailixValue value, final String fieldName) {
        if (value instanceof RailixValue.NumberValue numberValue) {
            return numberValue.value();
        }
        if (value instanceof RailixValue.StringValue stringValue) {
            try {
                return new BigDecimal(stringValue.value().trim());
            } catch (final NumberFormatException exception) {
                throw new IllegalArgumentException(fieldName + " must be numeric", exception);
            }
        }
        throw new IllegalArgumentException(fieldName + " must be a number or numeric string");
    }

    private static BigDecimal canonicalNumber(final BigDecimal value) {
        return new BigDecimal(value.stripTrailingZeros().toPlainString());
    }

    private static void validateTargetRoot(final RailixPath target) {
        final String root = ((RailixPath.KeyToken) target.tokens().getFirst()).key();
        if (!"ctx".equals(root) && !"reply".equals(root)) {
            throw new IllegalArgumentException("DataTransform targets must start with ctx or reply: " + target);
        }
    }

    private static void validateContextTargetRoot(final RailixPath target, final String stepName) {
        final String root = ((RailixPath.KeyToken) target.tokens().getFirst()).key();
        if (!"ctx".equals(root)) {
            throw new IllegalArgumentException(stepName + " targets must start with ctx: " + target);
        }
    }

    private static String applyTemplate(
            final String template,
            final ContextDoc context,
            final Map<String, RailixPath> aliases
    ) {
        final Matcher matcher = TEMPLATE_PLACEHOLDER.matcher(template);
        final StringBuilder rendered = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            rendered.append(template, last, matcher.start());
            final RailixValue value = resolvePathValue(matcher.group(1).trim(), context, aliases);
            rendered.append(stringifyTemplateValue(value));
            last = matcher.end();
        }
        rendered.append(template.substring(last));
        return rendered.toString();
    }

    private static String stringifyTemplateValue(final RailixValue value) {
        if (value == RailixValue.NULL) {
            return "";
        }
        if (value instanceof RailixValue.StringValue stringValue) {
            return stringValue.value();
        }
        if (value instanceof RailixValue.NumberValue numberValue) {
            return numberValue.value().stripTrailingZeros().toPlainString();
        }
        if (value instanceof RailixValue.BoolValue boolValue) {
            return Boolean.toString(boolValue.value());
        }
        throw new IllegalArgumentException("Template placeholders must resolve to scalar values");
    }

    private static RailixValue.ObjectValue requireObject(final RailixValue value, final String fieldName) {
        if (value instanceof RailixValue.ObjectValue objectValue) {
            return objectValue;
        }
        throw new IllegalArgumentException(fieldName + " must be an object");
    }

    private static RailixValue.ListValue requireList(final RailixValue value, final String fieldName) {
        if (value instanceof RailixValue.ListValue listValue) {
            return listValue;
        }
        throw new IllegalArgumentException(fieldName + " must be a list");
    }

    private static String requireString(final RailixValue value, final String fieldName) {
        if (value instanceof RailixValue.StringValue stringValue) {
            return stringValue.value();
        }
        throw new IllegalArgumentException(fieldName + " must be a string");
    }

    private static String requireNonBlankString(final RailixValue value, final String fieldName) {
        final String resolved = requireString(value, fieldName);
        if (resolved.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return resolved;
    }

    private static RailixPath requireContextStatePath(final RailixValue value, final String fieldName) {
        final RailixPath path = RailixPath.parse(requireNonBlankString(value, fieldName));
        final String root = ((RailixPath.KeyToken) path.tokens().getFirst()).key();
        if (!"ctx".equals(root)) {
            throw new IllegalArgumentException(fieldName + " must start with ctx: " + path);
        }
        return path;
    }

    private static RailixValue resolvePathValue(
            final String rawPath,
            final ContextDoc context,
            final Map<String, RailixPath> aliases
    ) {
        return context.get(resolvePath(rawPath, aliases));
    }

    private static RailixPath resolvePath(final String rawPath, final Map<String, RailixPath> aliases) {
        final String rootName = rootSegment(rawPath);
        final RailixPath aliasPath = aliases.get(rootName);
        if (aliasPath != null) {
            final String suffix = rawPath.substring(rootName.length());
            return suffix.isEmpty() ? aliasPath : RailixPath.parse(aliasPath + suffix);
        }
        if (!aliases.isEmpty() && !RESERVED_ROOT_NAMES.contains(rootName)) {
            throw new IllegalArgumentException("Unknown repeat alias or root path: " + rawPath);
        }
        return RailixPath.parse(rawPath);
    }

    private static String rootSegment(final String path) {
        final StringBuilder builder = new StringBuilder();
        var escaped = false;
        for (int index = 0; index < path.length(); index++) {
            final char current = path.charAt(index);
            if (escaped) {
                builder.append(current);
                escaped = false;
                continue;
            }
            if (current == '\\') {
                escaped = true;
                continue;
            }
            if (current == '.' || current == '[') {
                break;
            }
            builder.append(current);
        }
        if (builder.isEmpty()) {
            throw new IllegalArgumentException("Path must contain a root segment: " + path);
        }
        return builder.toString();
    }

    private static SelectorBinding buildSelectorBinding(
            final String alias,
            final Selector selector,
            final String fieldName,
            final ContextDoc context
    ) {
        validateAliasName(alias, fieldName, new LinkedHashSet<>());
        return new SelectorBinding(alias, context.selectedPaths(selector));
    }

    private static RailixValue applyAggregation(
            final SelectorBinding selectorBinding,
            final RailixValue.ObjectValue aggregation,
            final String fieldName,
            final ContextDoc context
    ) {
        final String op = requireNonBlankString(aggregation.values().get("op"), fieldName + ".op").toLowerCase(Locale.ROOT);
        return switch (op) {
            case "count" -> aggregateCount(selectorBinding, aggregation, fieldName);
            case "collect" -> aggregateCollect(selectorBinding, aggregation, fieldName, context);
            case "sum" -> new RailixValue.NumberValue(sumNumericAggregation(selectorBinding, aggregation, fieldName, context));
            case "min" -> new RailixValue.NumberValue(minNumericAggregation(selectorBinding, aggregation, fieldName, context));
            case "max" -> new RailixValue.NumberValue(maxNumericAggregation(selectorBinding, aggregation, fieldName, context));
            default -> throw new IllegalArgumentException("Unsupported data aggregate op: " + op);
        };
    }

    private static RailixValue aggregateCount(
            final SelectorBinding selectorBinding,
            final RailixValue.ObjectValue aggregation,
            final String fieldName
    ) {
        if (aggregation.values().containsKey("path")) {
            throw new IllegalArgumentException(fieldName + ".path is not supported for count");
        }
        return new RailixValue.NumberValue(BigDecimal.valueOf(selectorBinding.paths().size()));
    }

    private static RailixValue aggregateCollect(
            final SelectorBinding selectorBinding,
            final RailixValue.ObjectValue aggregation,
            final String fieldName,
            final ContextDoc context
    ) {
        final String pathExpression = aggregation.values().containsKey("path")
                ? requireString(aggregation.values().get("path"), fieldName + ".path")
                : null;
        final List<RailixValue> values = new ArrayList<>();
        for (final RailixPath selectedPath : selectorBinding.paths()) {
            values.add(pathExpression == null
                    ? context.get(selectedPath)
                    : resolvePathValue(pathExpression, context, Map.of(selectorBinding.alias(), selectedPath)));
        }
        return new RailixValue.ListValue(List.copyOf(values));
    }

    private static BigDecimal sumNumericAggregation(
            final SelectorBinding selectorBinding,
            final RailixValue.ObjectValue aggregation,
            final String fieldName,
            final ContextDoc context
    ) {
        BigDecimal sum = BigDecimal.ZERO;
        for (final BigDecimal value : resolveNumericAggregationValues(selectorBinding, aggregation, fieldName, context)) {
            sum = sum.add(value);
        }
        return sum;
    }

    private static BigDecimal minNumericAggregation(
            final SelectorBinding selectorBinding,
            final RailixValue.ObjectValue aggregation,
            final String fieldName,
            final ContextDoc context
    ) {
        BigDecimal min = null;
        for (final BigDecimal value : resolveNumericAggregationValues(selectorBinding, aggregation, fieldName, context)) {
            min = min == null || value.compareTo(min) < 0 ? value : min;
        }
        return min;
    }

    private static BigDecimal maxNumericAggregation(
            final SelectorBinding selectorBinding,
            final RailixValue.ObjectValue aggregation,
            final String fieldName,
            final ContextDoc context
    ) {
        BigDecimal max = null;
        for (final BigDecimal value : resolveNumericAggregationValues(selectorBinding, aggregation, fieldName, context)) {
            max = max == null || value.compareTo(max) > 0 ? value : max;
        }
        return max;
    }

    private static List<BigDecimal> resolveNumericAggregationValues(
            final SelectorBinding selectorBinding,
            final RailixValue.ObjectValue aggregation,
            final String fieldName,
            final ContextDoc context
    ) {
        final String pathExpression = requireString(aggregation.values().get("path"), fieldName + ".path");
        final List<BigDecimal> values = new ArrayList<>();
        for (final RailixPath selectedPath : selectorBinding.paths()) {
            values.add(coerceNumber(
                    resolvePathValue(pathExpression, context, Map.of(selectorBinding.alias(), selectedPath)),
                    fieldName + ".path"
            ));
        }
        return List.copyOf(values);
    }

    private static BigDecimal coerceNumber(final RailixValue value, final String fieldName) {
        if (value instanceof RailixValue.NumberValue numberValue) {
            return numberValue.value();
        }
        if (value instanceof RailixValue.StringValue stringValue) {
            try {
                return new BigDecimal(stringValue.value());
            } catch (final NumberFormatException exception) {
                throw new IllegalArgumentException(fieldName + " must resolve to numbers", exception);
            }
        }
        throw new IllegalArgumentException(fieldName + " must resolve to numbers");
    }

    private static void assertForEachStateMatches(
            final ContextDoc context,
            final RailixPath statePath,
            final String selectorExpression,
            final String alias
    ) {
        final RailixValue stateValue = context.get(statePath);
        if (stateValue == RailixValue.NULL) {
            return;
        }
        if (!(stateValue instanceof RailixValue.ObjectValue)) {
            throw new IllegalArgumentException(
                    "config.statePath must resolve to an empty or foreach-owned object subtree: " + statePath
            );
        }
        final RailixValue selectorValue = context.get(appendStatePath(statePath, FOR_EACH_SELECTOR_FIELD));
        final RailixValue aliasValue = context.get(appendStatePath(statePath, FOR_EACH_ALIAS_FIELD));
        if (selectorValue == RailixValue.NULL && aliasValue == RailixValue.NULL) {
            throw new IllegalArgumentException(
                    "config.statePath must resolve to an empty or foreach-owned object subtree: " + statePath
            );
        }
        if (selectorValue == RailixValue.NULL) {
            throw new IllegalArgumentException("Existing foreach state is missing config.selector metadata: " + statePath);
        }
        if (!new RailixValue.StringValue(selectorExpression).equals(selectorValue)) {
            throw new IllegalArgumentException("Existing foreach state does not match config.selector: " + statePath);
        }
        if (aliasValue == RailixValue.NULL) {
            throw new IllegalArgumentException("Existing foreach state is missing config.as metadata: " + statePath);
        }
        if (!new RailixValue.StringValue(alias).equals(aliasValue)) {
            throw new IllegalArgumentException("Existing foreach state does not match config.as: " + statePath);
        }
    }

    private static int readForEachNextIndex(final ContextDoc context, final RailixPath statePath) {
        final RailixValue nextIndexValue = context.get(appendStatePath(statePath, FOR_EACH_NEXT_INDEX_FIELD));
        if (nextIndexValue == RailixValue.NULL) {
            return 0;
        }
        if (nextIndexValue instanceof RailixValue.NumberValue numberValue) {
            try {
                final int nextIndex = numberValue.value().intValueExact();
                if (nextIndex < 0) {
                    throw new IllegalArgumentException("Existing foreach state index must be >= 0: " + statePath);
                }
                return nextIndex;
            } catch (final ArithmeticException exception) {
                throw new IllegalArgumentException("Existing foreach state index must be an integer: " + statePath, exception);
            }
        }
        throw new IllegalArgumentException("Existing foreach state index must be numeric: " + statePath);
    }

    private static List<Patch> clearForEachState(final RailixPath statePath) {
        return List.of(new Patch.Remove(statePath));
    }

    private static RailixPath appendStatePath(final RailixPath statePath, final String childField) {
        return RailixPath.parse(statePath + "." + childField);
    }

    private static Map<String, SelectorBinding> resolveParentAliases(
            final RailixValue parentAliasesValue,
            final String fieldName,
            final ContextDoc context,
            final String currentAlias,
            final Set<String> disallowedAliases
    ) {
        if (parentAliasesValue == null) {
            return Map.of();
        }
        final RailixValue.ObjectValue parentAliases = requireObject(parentAliasesValue, fieldName);
        final Map<String, SelectorBinding> bindings = new LinkedHashMap<>();
        final LinkedHashSet<String> seenAliases = new LinkedHashSet<>();
        seenAliases.add(currentAlias);
        for (final Map.Entry<String, RailixValue> entry : parentAliases.values().entrySet()) {
            validateAliasName(entry.getKey(), fieldName + "." + entry.getKey(), seenAliases, disallowedAliases);
            bindings.put(
                    entry.getKey(),
                    new SelectorBinding(
                            entry.getKey(),
                            context.selectedPaths(new Selector(requireString(entry.getValue(), fieldName + "." + entry.getKey())))
                    )
            );
        }
        return Map.copyOf(bindings);
    }

    private static void validateAliasName(
            final String alias,
            final String fieldName,
            final Set<String> seenAliases
    ) {
        validateAliasName(alias, fieldName, seenAliases, Set.of());
    }

    private static void validateAliasName(
            final String alias,
            final String fieldName,
            final Set<String> seenAliases,
            final Set<String> disallowedAliases
    ) {
        if (alias.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        if (RESERVED_ROOT_NAMES.contains(alias)) {
            throw new IllegalArgumentException(fieldName + " must not reuse a root namespace: " + alias);
        }
        if (disallowedAliases.contains(alias)) {
            throw new IllegalArgumentException(fieldName + " must not reuse a foreach cursor field: " + alias);
        }
        if (!seenAliases.add(alias)) {
            throw new IllegalArgumentException(fieldName + " must be unique: " + alias);
        }
    }

    private static RailixPath resolveAncestorAlias(
            final SelectorBinding binding,
            final RailixPath selectedPath,
            final String fieldName
    ) {
        RailixPath bestMatch = null;
        for (final RailixPath candidate : binding.paths()) {
            if (!isStrictAncestor(candidate, selectedPath)) {
                continue;
            }
            if (bestMatch == null || candidate.tokens().size() > bestMatch.tokens().size()) {
                bestMatch = candidate;
            }
        }
        if (bestMatch == null) {
            throw new IllegalArgumentException(
                    fieldName + " parent alias '" + binding.alias() + "' must resolve to an ancestor of " + selectedPath
            );
        }
        return bestMatch;
    }

    private static boolean isStrictAncestor(final RailixPath candidate, final RailixPath selectedPath) {
        if (candidate.tokens().size() >= selectedPath.tokens().size()) {
            return false;
        }
        for (int index = 0; index < candidate.tokens().size(); index++) {
            if (!candidate.tokens().get(index).equals(selectedPath.tokens().get(index))) {
                return false;
            }
        }
        return true;
    }

    private static RailixPath materializeRepeatTarget(
            final String targetTemplate,
            final int selectedIndex,
            final String fieldName
    ) {
        if (countOccurrences(targetTemplate, REPEAT_WILDCARD) != 1) {
            throw new IllegalArgumentException(fieldName + " must contain exactly one [*]: " + targetTemplate);
        }
        return RailixPath.parse(targetTemplate.replace(REPEAT_WILDCARD, "[" + selectedIndex + "]"));
    }

    private static int countOccurrences(final String value, final String token) {
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
    }

    private record SelectorBinding(String alias, List<RailixPath> paths) {
        private SelectorBinding {
            paths = List.copyOf(paths);
        }
    }
}
