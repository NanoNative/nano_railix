package dev.nanonative.railix.kernel.model;

import dev.nanonative.railix.kernel.runtime.AppPlan;
import dev.nanonative.railix.kernel.runtime.RunSignal;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Converts kernel contracts to a deterministic primitive UI model and renders that model as stable JSON or YAML.
 * The UI model uses only maps, lists, strings, booleans, and null.
 */
public final class KernelContractCodec {

    private KernelContractCodec() {}

    /**
     * Converts a {@link RailixValue} into a primitive UI model.
     *
     * @param value value to encode
     * @return deterministic UI model representation
     */
    public static Map<String, Object> toUiModel(final RailixValue value) {
        return encodeRailixValue(value);
    }

    /**
     * Reconstructs a {@link RailixValue} from a previously encoded UI model.
     *
     * @param model encoded value model
     * @return decoded value
     */
    public static RailixValue railixValueFromUiModel(final Map<String, Object> model) {
        return decodeRailixValue(model);
    }

    /**
     * Converts a {@link Selector} into a primitive UI model.
     *
     * @param selector selector to encode
     * @return deterministic UI model representation
     */
    public static Map<String, Object> toUiModel(final Selector selector) {
        return orderedMap(Map.entry("expression", selector.expression()));
    }

    /**
     * Reconstructs a {@link Selector} from its UI model.
     *
     * @param model encoded selector model
     * @return decoded selector
     */
    public static Selector selectorFromUiModel(final Map<String, Object> model) {
        return new Selector(requireString(model, "expression"));
    }

    /**
     * Converts a {@link Patch} into a primitive UI model.
     *
     * @param patch patch to encode
     * @return deterministic UI model representation
     */
    public static Map<String, Object> toUiModel(final Patch patch) {
        return encodePatch(patch);
    }

    /**
     * Reconstructs a {@link Patch} from its UI model.
     *
     * @param model encoded patch model
     * @return decoded patch
     */
    public static Patch patchFromUiModel(final Map<String, Object> model) {
        return decodePatch(model);
    }

    /**
     * Converts a {@link Shape} into a primitive UI model.
     *
     * @param shape shape to encode
     * @return deterministic UI model representation
     */
    public static Map<String, Object> toUiModel(final Shape shape) {
        return encodeShape(shape);
    }

    /**
     * Reconstructs a {@link Shape} from its UI model.
     *
     * @param model encoded shape model
     * @return decoded shape
     */
    public static Shape shapeFromUiModel(final Map<String, Object> model) {
        return decodeShape(model);
    }

    /**
     * Converts a {@link SettingsTree} into a primitive UI model.
     *
     * @param settingsTree settings tree to encode
     * @return deterministic UI model representation
     */
    public static Map<String, Object> toUiModel(final SettingsTree settingsTree) {
        final List<Object> entries = new ArrayList<>();
        final List<Map.Entry<RailixPath, SettingsTree.Entry>> sortedEntries = new ArrayList<>(settingsTree.entries().entrySet());
        sortedEntries.sort(Comparator.comparing(entry -> entry.getKey().toString()));
        for (final Map.Entry<RailixPath, SettingsTree.Entry> entry : sortedEntries) {
            entries.add(orderedMap(
                    Map.entry("path", entry.getKey().toString()),
                    Map.entry("type", entry.getValue().type()),
                    Map.entry("value", encodeSettingsValue(entry.getValue())),
                    Map.entry("required", entry.getValue().required()),
                    Map.entry("secret", entry.getValue().secret()),
                    Map.entry("encrypted", entry.getValue().encrypted()),
                    Map.entry("source", entry.getValue().source()),
                    Map.entry("visibility", toKebab(entry.getValue().visibility())),
                    Map.entry("audit", toKebab(entry.getValue().audit())),
                    Map.entry("overridePolicy", toKebab(entry.getValue().overridePolicy()))
            ));
        }
        return orderedMap(
                Map.entry("description", settingsTree.description()),
                Map.entry("scopes", enumList(settingsTree.scopes())),
                Map.entry("precedence", enumList(settingsTree.precedence())),
                Map.entry("entries", entries)
        );
    }

    /**
     * Reconstructs a {@link SettingsTree} from its UI model.
     *
     * @param model encoded settings tree model
     * @return decoded settings tree
     */
    public static SettingsTree settingsTreeFromUiModel(final Map<String, Object> model) {
        final Map<RailixPath, SettingsTree.Entry> entries = new LinkedHashMap<>();
        for (final Object entryObject : requireList(model, "entries")) {
            final Map<String, Object> entryModel = requireMap(entryObject, "entry");
            final RailixPath path = RailixPath.parse(requireString(entryModel, "path"));
            entries.put(path, new SettingsTree.Entry(
                    path,
                    requireString(entryModel, "type"),
                    decodeSettingsValue(requireMap(entryModel.get("value"), "value")),
                    requireBoolean(entryModel, "required"),
                    requireBoolean(entryModel, "secret"),
                    requireBoolean(entryModel, "encrypted"),
                    requireString(entryModel, "source"),
                    fromKebab(SettingsTree.Visibility.class, requireString(entryModel, "visibility")),
                    fromKebab(SettingsTree.Audit.class, requireString(entryModel, "audit")),
                    fromKebab(SettingsTree.OverridePolicy.class, requireString(entryModel, "overridePolicy"))
            ));
        }
        return new SettingsTree(
                requireString(model, "description"),
                entries,
                decodeEnumList(SettingsTree.Scope.class, requireList(model, "scopes")),
                decodeEnumList(SettingsTree.SourceLayer.class, requireList(model, "precedence"))
        );
    }

    /**
     * Converts a {@link PermissionSet} into a primitive UI model.
     *
     * @param permissionSet permission set to encode
     * @return deterministic UI model representation
     */
    public static Map<String, Object> toUiModel(final PermissionSet permissionSet) {
        final List<Object> decisions = new ArrayList<>();
        for (final PermissionSet.Decision decision : permissionSet.decisions()) {
            decisions.add(orderedMap(
                    Map.entry("permission", decision.permission()),
                    Map.entry("resource", decision.resource()),
                    Map.entry("decision", toKebab(decision.decision())),
                    Map.entry("reason", decision.reason())
            ));
        }
        return orderedMap(
                Map.entry("requested", encodePermissionMap(permissionSet.requested())),
                Map.entry("granted", encodePermissionMap(permissionSet.granted())),
                Map.entry("decisions", decisions)
        );
    }

    /**
     * Reconstructs a {@link PermissionSet} from its UI model.
     *
     * @param model encoded permission set model
     * @return decoded permission set
     */
    public static PermissionSet permissionSetFromUiModel(final Map<String, Object> model) {
        final List<PermissionSet.Decision> decisions = new ArrayList<>();
        for (final Object decisionObject : requireList(model, "decisions")) {
            final Map<String, Object> decisionModel = requireMap(decisionObject, "decision");
            decisions.add(new PermissionSet.Decision(
                    requireString(decisionModel, "permission"),
                    requireString(decisionModel, "resource"),
                    fromKebab(PermissionSet.DecisionResult.class, requireString(decisionModel, "decision")),
                    requireString(decisionModel, "reason")
            ));
        }
        return new PermissionSet(
                decodePermissionMap(requireMap(model.get("requested"), "requested")),
                decodePermissionMap(requireMap(model.get("granted"), "granted")),
                decisions
        );
    }

    /**
     * Converts an {@link AppPlan} into a primitive UI model.
     *
     * @param appPlan app plan to encode
     * @return deterministic UI model representation
     */
    public static Map<String, Object> toUiModel(final AppPlan appPlan) {
        return orderedMap(
                Map.entry("appId", appPlan.appId()),
                Map.entry("flowId", appPlan.flowId()),
                Map.entry("triggerStepId", appPlan.triggerStepId()),
                Map.entry("permissions", toUiModel(appPlan.permissions())),
                Map.entry("steps", encodeStepInvocations(appPlan.steps())),
                Map.entry("connections", encodeConnections(appPlan.connections()))
        );
    }

    /**
     * Reconstructs an {@link AppPlan} from its UI model.
     *
     * @param model encoded app plan model
     * @return decoded app plan
     */
    public static AppPlan appPlanFromUiModel(final Map<String, Object> model) {
        return new AppPlan(
                requireString(model, "appId"),
                requireString(model, "flowId"),
                requireString(model, "triggerStepId"),
                decodeAppPlanPermissions(model),
                decodeStepInvocations(requireList(model, "steps")),
                decodeConnections(model)
        );
    }

    /**
     * Converts a {@link StepContract} into a primitive UI model.
     *
     * @param stepContract step contract to encode
     * @return deterministic UI model representation
     */
    public static Map<String, Object> toUiModel(final StepContract stepContract) {
        return orderedMap(
                Map.entry("contractVersion", stepContract.contractVersion()),
                Map.entry("id", stepContract.id()),
                Map.entry("version", stepContract.version()),
                Map.entry("displayName", stepContract.displayName()),
                Map.entry("description", stepContract.description()),
                Map.entry("kind", toKebab(stepContract.kind())),
                Map.entry("inputs", encodePorts(stepContract.inputs())),
                Map.entry("outputs", encodePorts(stepContract.outputs())),
                Map.entry("config", encodeStepConfigFields(stepContract.config())),
                Map.entry("outcomes", encodeOutcomes(stepContract.outcomes())),
                Map.entry("semantics", encodeStepSemantics(stepContract.semantics())),
                Map.entry("settings", orderedMap(Map.entry("requested", List.copyOf(stepContract.settings().requested())))),
                Map.entry("permissions", toUiModel(stepContract.permissions())),
                Map.entry("timeout", orderedMap(Map.entry("default", stepContract.timeout().defaultValue().toString()))),
                Map.entry("retry", orderedMap(Map.entry("default", orderedMap(
                        Map.entry("maxAttempts", stepContract.retryPolicy().maxAttempts()),
                        Map.entry("backoff", stepContract.retryPolicy().backoff().toString())
                )))),
                Map.entry("cache", orderedMap(Map.entry("default", orderedMap(
                        Map.entry("mode", toKebab(stepContract.cachePolicy().mode())),
                        Map.entry("key", stepContract.cachePolicy().key()),
                        Map.entry("ttl", stepContract.cachePolicy().ttl().toString())
                )))),
                Map.entry("resources", orderedMap(Map.entry("limits", orderedMap(
                        Map.entry("memory", encodeRailixValue(stepContract.resources().limits().memory())),
                        Map.entry("cpu", encodeRailixValue(stepContract.resources().limits().cpu()))
                )))),
                Map.entry("metrics", orderedMap(Map.entry("emitted", encodeMetricDefinitions(stepContract.metrics().emitted())))),
                Map.entry("ui", encodeRailixValueMap(stepContract.ui()))
        );
    }

    /**
     * Reconstructs a {@link StepContract} from its UI model.
     *
     * @param model encoded step contract model
     * @return decoded step contract
     */
    public static StepContract stepContractFromUiModel(final Map<String, Object> model) {
        final Map<String, Object> timeoutModel = requireMap(model.get("timeout"), "timeout");
        final Map<String, Object> retryModel = requireMap(requireMap(model.get("retry"), "retry").get("default"), "retry.default");
        final Map<String, Object> cacheModel = requireMap(requireMap(model.get("cache"), "cache").get("default"), "cache.default");
        final Map<String, Object> resourcesModel = requireMap(requireMap(model.get("resources"), "resources").get("limits"), "resources.limits");
        final Map<String, Object> settingsModel = requireMap(model.get("settings"), "settings");
        final int contractVersion = model.containsKey("contractVersion")
                ? requireInt(model, "contractVersion")
                : StepContract.LEGACY_CONTRACT_VERSION;
        return new StepContract(
                requireString(model, "id"),
                requireString(model, "version"),
                requireString(model, "displayName"),
                requireString(model, "description"),
                fromKebab(StepContract.Kind.class, requireString(model, "kind")),
                decodePorts(requireList(model, "inputs")),
                decodePorts(requireList(model, "outputs")),
                decodeOutcomes(requireMap(model.get("outcomes"), "outcomes")),
                new StepContract.Settings(stringList(requireList(settingsModel, "requested"))),
                permissionSetFromUiModel(requireMap(model.get("permissions"), "permissions")),
                new StepContract.Timeout(Duration.parse(requireString(timeoutModel, "default"))),
                new StepContract.RetryPolicy(requireInt(retryModel, "maxAttempts"), Duration.parse(requireString(retryModel, "backoff"))),
                new StepContract.CachePolicy(
                        fromKebab(StepContract.CachePolicy.Mode.class, requireString(cacheModel, "mode")),
                        requireString(cacheModel, "key"),
                        Duration.parse(requireString(cacheModel, "ttl"))
                ),
                new StepContract.Resources(new StepContract.Limits(
                        decodeRailixValue(requireMap(resourcesModel.get("memory"), "resources.memory")),
                        decodeRailixValue(requireMap(resourcesModel.get("cpu"), "resources.cpu"))
                )),
                new StepContract.Metrics(decodeMetricDefinitions(requireList(requireMap(model.get("metrics"), "metrics"), "emitted"))),
                decodeRailixValueMap(requireMap(model.get("ui"), "ui")),
                contractVersion,
                decodeStepConfigFields(model),
                decodeStepSemantics(model, contractVersion)
        );
    }

    /**
     * Converts an {@link OperatorContract} into a primitive UI model.
     *
     * @param operatorContract operator contract to encode
     * @return deterministic UI model representation
     */
    public static Map<String, Object> toUiModel(final OperatorContract operatorContract) {
        return orderedMap(
                Map.entry("id", operatorContract.id()),
                Map.entry("version", operatorContract.version()),
                Map.entry("displayName", operatorContract.displayName()),
                Map.entry("category", operatorContract.category()),
                Map.entry("inputs", encodeOperatorInputs(operatorContract.inputs())),
                Map.entry("config", encodeOperatorConfigs(operatorContract.config())),
                Map.entry("outputs", encodeOperatorOutputs(operatorContract.outputs())),
                Map.entry("ui", encodeRailixValueMap(operatorContract.ui()))
        );
    }

    /**
     * Reconstructs an {@link OperatorContract} from its UI model.
     *
     * @param model encoded operator contract model
     * @return decoded operator contract
     */
    public static OperatorContract operatorContractFromUiModel(final Map<String, Object> model) {
        return new OperatorContract(
                requireString(model, "id"),
                requireString(model, "version"),
                requireString(model, "displayName"),
                requireString(model, "category"),
                decodeOperatorInputs(requireMap(model.get("inputs"), "inputs")),
                decodeOperatorConfigs(requireMap(model.get("config"), "config")),
                decodeOperatorOutputs(requireMap(model.get("outputs"), "outputs")),
                decodeRailixValueMap(requireMap(model.get("ui"), "ui"))
        );
    }

    /**
     * Converts an {@link Envelope} into a primitive UI model.
     *
     * @param envelope envelope to encode
     * @return deterministic UI model representation
     */
    public static Map<String, Object> toUiModel(final Envelope envelope) {
        return orderedMap(
                Map.entry("source", envelope.source()),
                Map.entry("protocol", envelope.protocol()),
                Map.entry("payload", encodeRailixValue(envelope.payload())),
                Map.entry("metadata", encodeRailixValue(envelope.metadata())),
                Map.entry("refs", encodeRailixValueMap(envelope.refs())),
                Map.entry("replyChannel", orderedMap(
                        Map.entry("supported", envelope.replyChannel().supported()),
                        Map.entry("modes", enumList(envelope.replyChannel().modes()))
                ))
        );
    }

    /**
     * Reconstructs an {@link Envelope} from its UI model.
     *
     * @param model encoded envelope model
     * @return decoded envelope
     */
    public static Envelope envelopeFromUiModel(final Map<String, Object> model) {
        final Map<String, Object> replyChannelModel = requireMap(model.get("replyChannel"), "replyChannel");
        return new Envelope(
                requireString(model, "source"),
                requireString(model, "protocol"),
                requireObjectValue(model, "payload"),
                requireObjectValue(model, "metadata"),
                decodeRailixValueMap(requireMap(model.get("refs"), "refs")),
                new Envelope.ReplyChannel(
                        requireBoolean(replyChannelModel, "supported"),
                        decodeEnumList(Reply.Mode.class, requireList(replyChannelModel, "modes"))
                )
        );
    }

    /**
     * Converts a {@link Reply} into a primitive UI model.
     *
     * @param reply reply to encode
     * @return deterministic UI model representation
     */
    public static Map<String, Object> toUiModel(final Reply reply) {
        return orderedMap(
                Map.entry("mode", toKebab(reply.mode())),
                Map.entry("status", encodeRailixValue(reply.status())),
                Map.entry("metadata", encodeRailixValue(reply.metadata())),
                Map.entry("payload", encodeRailixValue(reply.payload())),
                Map.entry("file", encodeRailixValue(reply.file())),
                Map.entry("stream", encodeRailixValue(reply.stream())),
                Map.entry("session", encodeRailixValue(reply.session())),
                Map.entry("deferred", encodeRailixValue(reply.deferred()))
        );
    }

    /**
     * Reconstructs a {@link Reply} from its UI model.
     *
     * @param model encoded reply model
     * @return decoded reply
     */
    public static Reply replyFromUiModel(final Map<String, Object> model) {
        return new Reply(
                fromKebab(Reply.Mode.class, requireString(model, "mode")),
                decodeRailixValue(requireMap(model.get("status"), "status")),
                requireObjectValue(model, "metadata"),
                decodeRailixValue(requireMap(model.get("payload"), "payload")),
                decodeRailixValue(requireMap(model.get("file"), "file")),
                decodeRailixValue(requireMap(model.get("stream"), "stream")),
                decodeRailixValue(requireMap(model.get("session"), "session")),
                decodeRailixValue(requireMap(model.get("deferred"), "deferred"))
        );
    }

    /**
     * Converts a {@link RunSignal} into a primitive UI model.
     *
     * @param runSignal run signal to encode
     * @return deterministic UI model representation
     */
    public static Map<String, Object> toUiModel(final RunSignal runSignal) {
        return encodeRunSignal(runSignal);
    }

    /**
     * Reconstructs a {@link RunSignal} from its UI model.
     *
     * @param model encoded run signal model
     * @return decoded run signal
     */
    public static RunSignal runSignalFromUiModel(final Map<String, Object> model) {
        return decodeRunSignal(model);
    }

    /**
     * Renders a primitive UI model as deterministic JSON text.
     *
     * @param uiModel primitive UI model
     * @return stable JSON text
     */
    public static String toStableJson(final Object uiModel) {
        final StringBuilder builder = new StringBuilder();
        writeJson(uiModel, builder);
        return builder.toString();
    }

    /**
     * Parses deterministic JSON text produced for the UI model boundary.
     *
     * @param source stable JSON source
     * @return decoded primitive UI model value
     */
    public static Object parseStableJson(final String source) {
        return StableJsonParser.parse(source);
    }

    /**
     * Parses deterministic JSON text and requires the root value to be an object.
     *
     * @param source stable JSON source
     * @return decoded object model
     */
    public static Map<String, Object> parseStableJsonObject(final String source) {
        return requireMap(parseStableJson(source), "root");
    }

    /**
     * Renders a primitive UI model as deterministic YAML text.
     *
     * @param uiModel primitive UI model
     * @return stable YAML text
     */
    public static String toStableYaml(final Object uiModel) {
        final StringBuilder builder = new StringBuilder();
        writeYaml(uiModel, builder, 0);
        return builder.toString();
    }

    private static Map<String, Object> encodeRailixValue(final RailixValue value) {
        return switch (value) {
            case RailixValue.NullValue ignored -> orderedMap(Map.entry("kind", "null"));
            case RailixValue.BoolValue boolValue -> orderedMap(Map.entry("kind", "bool"), Map.entry("value", boolValue.value()));
            case RailixValue.NumberValue numberValue -> orderedMap(Map.entry("kind", "number"), Map.entry("value", numberValue.value().toPlainString()));
            case RailixValue.StringValue stringValue -> orderedMap(Map.entry("kind", "string"), Map.entry("value", stringValue.value()));
            case RailixValue.ListValue listValue -> orderedMap(Map.entry("kind", "list"), Map.entry("value", encodeRailixValueList(listValue.values())));
            case RailixValue.ObjectValue objectValue -> orderedMap(Map.entry("kind", "object"), Map.entry("value", encodeRailixValueMap(objectValue.values())));
            case RailixValue.BlobRef blobRef -> orderedMap(
                    Map.entry("kind", "blob-ref"),
                    Map.entry("id", blobRef.id()),
                    Map.entry("mediaType", blobRef.mediaType()),
                    Map.entry("digest", blobRef.digest()),
                    Map.entry("size", blobRef.size())
            );
            case RailixValue.FileRef fileRef -> orderedMap(
                    Map.entry("kind", "file-ref"),
                    Map.entry("id", fileRef.id()),
                    Map.entry("path", fileRef.path()),
                    Map.entry("mediaType", fileRef.mediaType()),
                    Map.entry("digest", fileRef.digest()),
                    Map.entry("size", fileRef.size())
            );
            case RailixValue.StreamRef streamRef -> orderedMap(
                    Map.entry("kind", "stream-ref"),
                    Map.entry("id", streamRef.id()),
                    Map.entry("itemType", streamRef.itemType()),
                    Map.entry("metadata", encodeRailixValueMap(streamRef.metadata()))
            );
            case RailixValue.SessionRef sessionRef -> orderedMap(
                    Map.entry("kind", "session-ref"),
                    Map.entry("id", sessionRef.id()),
                    Map.entry("protocol", sessionRef.protocol()),
                    Map.entry("metadata", encodeRailixValueMap(sessionRef.metadata()))
            );
            case RailixValue.DeferredRef deferredRef -> orderedMap(
                    Map.entry("kind", "deferred-ref"),
                    Map.entry("id", deferredRef.id()),
                    Map.entry("statusPath", deferredRef.statusPath()),
                    Map.entry("metadata", encodeRailixValueMap(deferredRef.metadata()))
            );
            case RailixValue.SecretRef secretRef -> orderedMap(
                    Map.entry("kind", "secret-ref"),
                    Map.entry("path", secretRef.path().toString())
            );
        };
    }

    private static RailixValue decodeRailixValue(final Map<String, Object> model) {
        final String kind = requireString(model, "kind");
        return switch (kind) {
            case "null" -> RailixValue.NULL;
            case "bool" -> new RailixValue.BoolValue(requireBoolean(model, "value"));
            case "number" -> new RailixValue.NumberValue(new BigDecimal(requireString(model, "value")));
            case "string" -> new RailixValue.StringValue(requireString(model, "value"));
            case "list" -> new RailixValue.ListValue(decodeRailixValueList(requireList(model, "value")));
            case "object" -> new RailixValue.ObjectValue(decodeRailixValueMap(requireMap(model.get("value"), "value")));
            case "blob-ref" -> new RailixValue.BlobRef(
                    requireString(model, "id"),
                    requireString(model, "mediaType"),
                    requireString(model, "digest"),
                    requireLong(model, "size")
            );
            case "file-ref" -> new RailixValue.FileRef(
                    requireString(model, "id"),
                    requireString(model, "path"),
                    requireString(model, "mediaType"),
                    requireString(model, "digest"),
                    requireLong(model, "size")
            );
            case "stream-ref" -> new RailixValue.StreamRef(
                    requireString(model, "id"),
                    requireString(model, "itemType"),
                    decodeRailixValueMap(requireMap(model.get("metadata"), "metadata"))
            );
            case "session-ref" -> new RailixValue.SessionRef(
                    requireString(model, "id"),
                    requireString(model, "protocol"),
                    decodeRailixValueMap(requireMap(model.get("metadata"), "metadata"))
            );
            case "deferred-ref" -> new RailixValue.DeferredRef(
                    requireString(model, "id"),
                    requireString(model, "statusPath"),
                    decodeRailixValueMap(requireMap(model.get("metadata"), "metadata"))
            );
            case "secret-ref" -> new RailixValue.SecretRef(RailixPath.parse(requireString(model, "path")));
            default -> throw new IllegalArgumentException("Unsupported RailixValue kind: " + kind);
        };
    }

    private static Map<String, Object> encodePatch(final Patch patch) {
        return switch (patch) {
            case Patch.Set set -> orderedMap(
                    Map.entry("op", "set"),
                    Map.entry("path", set.path().toString()),
                    Map.entry("source", encodePatchSource(set.source()))
            );
            case Patch.Remove remove -> orderedMap(
                    Map.entry("op", "remove"),
                    Map.entry("path", remove.path().toString())
            );
            case Patch.Append append -> orderedMap(
                    Map.entry("op", "append"),
                    Map.entry("path", append.path().toString()),
                    Map.entry("source", encodePatchSource(append.source()))
            );
            case Patch.Merge merge -> orderedMap(
                    Map.entry("op", "merge"),
                    Map.entry("path", merge.path().toString()),
                    Map.entry("source", encodePatchSource(merge.source())),
                    Map.entry("strategy", toKebab(merge.strategy()))
            );
            case Patch.Copy copy -> orderedMap(
                    Map.entry("op", "copy"),
                    Map.entry("from", copy.from().toString()),
                    Map.entry("to", copy.to().toString())
            );
            case Patch.Move move -> orderedMap(
                    Map.entry("op", "move"),
                    Map.entry("from", move.from().toString()),
                    Map.entry("to", move.to().toString())
            );
            case Patch.Clear clear -> orderedMap(
                    Map.entry("op", "clear"),
                    Map.entry("path", clear.path().toString())
            );
        };
    }

    private static Patch decodePatch(final Map<String, Object> model) {
        final String op = requireString(model, "op");
        return switch (op) {
            case "set" -> new Patch.Set(
                    RailixPath.parse(requireString(model, "path")),
                    decodePatchSource(requireMap(model.get("source"), "source"))
            );
            case "remove" -> new Patch.Remove(RailixPath.parse(requireString(model, "path")));
            case "append" -> new Patch.Append(
                    RailixPath.parse(requireString(model, "path")),
                    decodePatchSource(requireMap(model.get("source"), "source"))
            );
            case "merge" -> new Patch.Merge(
                    RailixPath.parse(requireString(model, "path")),
                    decodePatchSource(requireMap(model.get("source"), "source")),
                    fromKebab(Patch.Strategy.class, requireString(model, "strategy"))
            );
            case "copy" -> new Patch.Copy(
                    RailixPath.parse(requireString(model, "from")),
                    RailixPath.parse(requireString(model, "to"))
            );
            case "move" -> new Patch.Move(
                    RailixPath.parse(requireString(model, "from")),
                    RailixPath.parse(requireString(model, "to"))
            );
            case "clear" -> new Patch.Clear(RailixPath.parse(requireString(model, "path")));
            default -> throw new IllegalArgumentException("Unsupported patch op: " + op);
        };
    }

    private static Map<String, Object> encodePatchSource(final Patch.Source source) {
        return switch (source) {
            case Patch.LiteralSource literalSource -> orderedMap(
                    Map.entry("kind", "literal"),
                    Map.entry("value", encodeRailixValue(literalSource.value()))
            );
            case Patch.ExpressionSource expressionSource -> orderedMap(
                    Map.entry("kind", "expression"),
                    Map.entry("value", encodePatchExpression(expressionSource.expression()))
            );
        };
    }

    private static Patch.Source decodePatchSource(final Map<String, Object> model) {
        final String kind = requireString(model, "kind");
        return switch (kind) {
            case "literal" -> new Patch.LiteralSource(decodeRailixValue(requireMap(model.get("value"), "value")));
            case "expression" -> new Patch.ExpressionSource(decodePatchExpression(requireMap(model.get("value"), "value")));
            default -> throw new IllegalArgumentException("Unsupported patch source kind: " + kind);
        };
    }

    private static Map<String, Object> encodePatchExpression(final Patch.Expression expression) {
        return switch (expression) {
            case Patch.PathExpression pathExpression -> orderedMap(
                    Map.entry("kind", "path"),
                    Map.entry("path", pathExpression.path().toString())
            );
            case Patch.LiteralExpression literalExpression -> orderedMap(
                    Map.entry("kind", "literal"),
                    Map.entry("value", encodeRailixValue(literalExpression.value()))
            );
            case Patch.OperationExpression operationExpression -> {
                final Map<String, Object> arguments = new LinkedHashMap<>();
                final List<String> keys = new ArrayList<>(operationExpression.arguments().keySet());
                keys.sort(String::compareTo);
                for (final String key : keys) {
                    arguments.put(key, encodePatchExpression(operationExpression.arguments().get(key)));
                }
                yield orderedMap(
                        Map.entry("kind", "operation"),
                        Map.entry("op", operationExpression.op()),
                        Map.entry("arguments", arguments)
                );
            }
        };
    }

    private static Patch.Expression decodePatchExpression(final Map<String, Object> model) {
        final String kind = requireString(model, "kind");
        return switch (kind) {
            case "path" -> new Patch.PathExpression(RailixPath.parse(requireString(model, "path")));
            case "literal" -> new Patch.LiteralExpression(decodeRailixValue(requireMap(model.get("value"), "value")));
            case "operation" -> {
                final Map<String, Patch.Expression> arguments = new LinkedHashMap<>();
                for (final Map.Entry<String, Object> entry : requireMap(model.get("arguments"), "arguments").entrySet()) {
                    arguments.put(entry.getKey(), decodePatchExpression(requireMap(entry.getValue(), entry.getKey())));
                }
                yield new Patch.OperationExpression(requireString(model, "op"), arguments);
            }
            default -> throw new IllegalArgumentException("Unsupported patch expression kind: " + kind);
        };
    }

    private static Map<String, Object> encodeShape(final Shape shape) {
        return switch (shape) {
            case Shape.AnyShape ignored -> orderedMap(Map.entry("kind", "any"));
            case Shape.ScalarShape scalarShape -> orderedMap(
                    Map.entry("kind", "scalar"),
                    Map.entry("type", toKebab(scalarShape.kind()))
            );
            case Shape.ListShape listShape -> orderedMap(
                    Map.entry("kind", "list"),
                    Map.entry("itemShape", encodeShape(listShape.itemShape()))
            );
            case Shape.ObjectShape objectShape -> orderedMap(
                    Map.entry("kind", "object"),
                    Map.entry("open", objectShape.open()),
                    Map.entry("fields", encodeShapeFields(objectShape.fields()))
            );
            case Shape.RefShape refShape -> orderedMap(
                    Map.entry("kind", "ref"),
                    Map.entry("type", toKebab(refShape.kind())),
                    Map.entry("metadataShape", encodeShape(refShape.metadataShape()))
            );
            case Shape.UnionShape unionShape -> orderedMap(
                    Map.entry("kind", "union"),
                    Map.entry("variants", encodeShapes(unionShape.variants()))
            );
        };
    }

    private static Shape decodeShape(final Map<String, Object> model) {
        final String kind = requireString(model, "kind");
        return switch (kind) {
            case "any" -> Shape.any();
            case "scalar" -> new Shape.ScalarShape(fromKebab(Shape.Kind.class, requireString(model, "type")));
            case "list" -> new Shape.ListShape(decodeShape(requireMap(model.get("itemShape"), "itemShape")));
            case "object" -> new Shape.ObjectShape(
                    decodeShapeFields(requireMap(model.get("fields"), "fields")),
                    requireBoolean(model, "open")
            );
            case "ref" -> new Shape.RefShape(
                    fromKebab(Shape.Kind.class, requireString(model, "type")),
                    requireObjectShape(model, "metadataShape")
            );
            case "union" -> new Shape.UnionShape(decodeShapes(requireList(model, "variants")));
            default -> throw new IllegalArgumentException("Unsupported shape kind: " + kind);
        };
    }

    private static Map<String, Object> encodeRunSignal(final RunSignal runSignal) {
        final Map<String, Object> model = orderedMap(
                Map.entry("type", runSignal.type()),
                Map.entry("signalId", runSignal.signalId()),
                Map.entry("timestamp", runSignal.timestamp().toString()),
                Map.entry("app", runSignal.appId()),
                Map.entry("flow", runSignal.flowId()),
                Map.entry("run", runSignal.runId())
        );
        runSignal.stepId().ifPresent(step -> model.put("step", step));
        if (runSignal.attempt().isPresent()) {
            model.put("attempt", runSignal.attempt().getAsInt());
        }
        switch (runSignal) {
            case RunSignal.RunStarted runStarted -> model.put("inputEnvelopeSummary", encodeRailixValue(runStarted.inputEnvelopeSummary()));
            case RunSignal.StepStarted ignored -> { }
            case RunSignal.StepFinished stepFinished -> {
                model.put("outcome", stepFinished.outcome());
                model.put("durationMs", stepFinished.durationMs());
            }
            case RunSignal.StepFailed stepFailed -> model.put("errorSummary", encodeRailixValue(stepFailed.errorSummary()));
            case RunSignal.ContextPatched contextPatched -> {
                model.put("patchSummary", encodeRailixValue(contextPatched.patchSummary()));
                final List<Object> changedPaths = new ArrayList<>();
                for (final RailixPath changedPath : contextPatched.changedPaths()) {
                    changedPaths.add(changedPath.toString());
                }
                model.put("changedPaths", changedPaths);
            }
            case RunSignal.PermissionDecided permissionDecided -> {
                model.put("permission", permissionDecided.permission());
                model.put("resource", permissionDecided.resource());
                model.put("decision", permissionDecided.decision());
                model.put("reason", permissionDecided.reason());
            }
            case RunSignal.SettingRead settingRead -> {
                model.put("path", settingRead.path().toString());
                model.put("secret", settingRead.secret());
                model.put("materialized", settingRead.materialized());
            }
            case RunSignal.MetricEmitted metricEmitted -> {
                model.put("name", metricEmitted.name());
                model.put("value", encodeRailixValue(metricEmitted.value()));
                model.put("unit", metricEmitted.unit());
                model.put("labels", orderedStringMap(metricEmitted.labels()));
            }
            case RunSignal.AuditEmitted auditEmitted -> {
                model.put("eventName", auditEmitted.eventName());
                model.put("data", encodeRailixValue(auditEmitted.data()));
            }
            case RunSignal.ResourceCreated resourceCreated -> {
                model.put("refId", resourceCreated.refId());
                model.put("refType", resourceCreated.refType());
                model.put("metadata", encodeRailixValue(resourceCreated.metadata()));
            }
            case RunSignal.ReplyProduced replyProduced -> {
                model.put("mode", replyProduced.mode());
                model.put("status", encodeRailixValue(replyProduced.status()));
                model.put("metadata", encodeRailixValue(replyProduced.metadata()));
            }
            case RunSignal.RunFinished runFinished -> {
                model.put("outcome", runFinished.outcome());
                model.put("durationMs", runFinished.durationMs());
            }
        }
        return model;
    }

    private static RunSignal decodeRunSignal(final Map<String, Object> model) {
        final String type = requireString(model, "type");
        final String signalId = requireString(model, "signalId");
        final Instant timestamp = Instant.parse(requireString(model, "timestamp"));
        final String app = requireString(model, "app");
        final String flow = requireString(model, "flow");
        final String run = requireString(model, "run");
        return switch (type) {
            case "run.started" -> new RunSignal.RunStarted(
                    signalId,
                    timestamp,
                    app,
                    flow,
                    run,
                    decodeRailixValue(requireMap(model.get("inputEnvelopeSummary"), "inputEnvelopeSummary"))
            );
            case "step.started" -> new RunSignal.StepStarted(
                    signalId,
                    timestamp,
                    app,
                    flow,
                    run,
                    requireString(model, "step"),
                    requireInt(model, "attempt")
            );
            case "step.finished" -> new RunSignal.StepFinished(
                    signalId,
                    timestamp,
                    app,
                    flow,
                    run,
                    requireString(model, "step"),
                    requireInt(model, "attempt"),
                    requireString(model, "outcome"),
                    requireLong(model, "durationMs")
            );
            case "step.failed" -> new RunSignal.StepFailed(
                    signalId,
                    timestamp,
                    app,
                    flow,
                    run,
                    requireString(model, "step"),
                    requireInt(model, "attempt"),
                    decodeRailixValue(requireMap(model.get("errorSummary"), "errorSummary"))
            );
            case "context.patched" -> new RunSignal.ContextPatched(
                    signalId,
                    timestamp,
                    app,
                    flow,
                    run,
                    requireString(model, "step"),
                    requireInt(model, "attempt"),
                    decodeRailixValue(requireMap(model.get("patchSummary"), "patchSummary")),
                    decodePaths(requireList(model, "changedPaths"))
            );
            case "permission.decided" -> new RunSignal.PermissionDecided(
                    signalId,
                    timestamp,
                    app,
                    flow,
                    run,
                    requireString(model, "step"),
                    requireInt(model, "attempt"),
                    requireString(model, "permission"),
                    requireString(model, "resource"),
                    requireString(model, "decision"),
                    requireString(model, "reason")
            );
            case "setting.read" -> new RunSignal.SettingRead(
                    signalId,
                    timestamp,
                    app,
                    flow,
                    run,
                    requireString(model, "step"),
                    requireInt(model, "attempt"),
                    RailixPath.parse(requireString(model, "path")),
                    requireBoolean(model, "secret"),
                    requireBoolean(model, "materialized")
            );
            case "metric.emitted" -> new RunSignal.MetricEmitted(
                    signalId,
                    timestamp,
                    app,
                    flow,
                    run,
                    requireString(model, "step"),
                    requireInt(model, "attempt"),
                    requireString(model, "name"),
                    decodeRailixValue(requireMap(model.get("value"), "value")),
                    requireString(model, "unit"),
                    decodeStringMap(requireMap(model.get("labels"), "labels"))
            );
            case "audit.emitted" -> new RunSignal.AuditEmitted(
                    signalId,
                    timestamp,
                    app,
                    flow,
                    run,
                    requireString(model, "step"),
                    requireInt(model, "attempt"),
                    requireString(model, "eventName"),
                    requireObjectValue(model, "data")
            );
            case "resource.created" -> new RunSignal.ResourceCreated(
                    signalId,
                    timestamp,
                    app,
                    flow,
                    run,
                    requireString(model, "step"),
                    requireInt(model, "attempt"),
                    requireString(model, "refId"),
                    requireString(model, "refType"),
                    requireObjectValue(model, "metadata")
            );
            case "reply.produced" -> new RunSignal.ReplyProduced(
                    signalId,
                    timestamp,
                    app,
                    flow,
                    run,
                    requireString(model, "mode"),
                    decodeRailixValue(requireMap(model.get("status"), "status")),
                    requireObjectValue(model, "metadata")
            );
            case "run.finished" -> new RunSignal.RunFinished(
                    signalId,
                    timestamp,
                    app,
                    flow,
                    run,
                    requireString(model, "outcome"),
                    requireLong(model, "durationMs")
            );
            default -> throw new IllegalArgumentException("Unsupported run signal type: " + type);
        };
    }

    private static Map<String, Object> encodePermissionMap(final Map<String, List<String>> permissions) {
        final Map<String, Object> encoded = new LinkedHashMap<>();
        final List<String> keys = new ArrayList<>(permissions.keySet());
        keys.sort(String::compareTo);
        for (final String key : keys) {
            encoded.put(key, List.copyOf(permissions.get(key)));
        }
        return encoded;
    }

    private static List<Object> encodeStepInvocations(final List<AppPlan.StepInvocation> steps) {
        final List<Object> encoded = new ArrayList<>();
        for (final AppPlan.StepInvocation step : steps) {
            encoded.add(orderedMap(
                    Map.entry("id", step.id()),
                    Map.entry("use", step.use()),
                    Map.entry("config", encodeRailixValue(step.config())),
                    Map.entry("next", orderedStringMap(step.next()))
            ));
        }
        return encoded;
    }

    private static List<AppPlan.StepInvocation> decodeStepInvocations(final List<Object> models) {
        final List<AppPlan.StepInvocation> decoded = new ArrayList<>();
        for (final Object object : models) {
            final Map<String, Object> model = requireMap(object, "step");
            decoded.add(new AppPlan.StepInvocation(
                    requireString(model, "id"),
                    requireString(model, "use"),
                    decodeStepConfig(model),
                    decodeStringMap(requireMap(model.get("next"), "next"))
            ));
        }
        return decoded;
    }

    private static List<Object> encodeConnections(final List<AppPlan.Connection> connections) {
        final List<Object> encoded = new ArrayList<>();
        for (final AppPlan.Connection connection : connections) {
            final List<Object> operators = new ArrayList<>();
            for (final AppPlan.OperatorInvocation operator : connection.operators()) {
                operators.add(orderedMap(
                        Map.entry("use", operator.use()),
                        Map.entry("config", encodeRailixValue(operator.config()))
                ));
            }
            encoded.add(orderedMap(
                    Map.entry("id", connection.id()),
                    Map.entry("from", encodePortRef(connection.from())),
                    Map.entry("to", encodePortRef(connection.to())),
                    Map.entry("operators", operators)
            ));
        }
        return encoded;
    }

    private static List<AppPlan.Connection> decodeConnections(final Map<String, Object> model) {
        if (!model.containsKey("connections")) {
            return List.of();
        }
        final List<AppPlan.Connection> connections = new ArrayList<>();
        for (final Object object : requireList(model, "connections")) {
            final Map<String, Object> connection = requireMap(object, "connection");
            final List<AppPlan.OperatorInvocation> operators = new ArrayList<>();
            for (final Object operatorObject : requireList(connection, "operators")) {
                final Map<String, Object> operator = requireMap(operatorObject, "operator");
                final RailixValue config = decodeRailixValue(requireMap(operator.get("config"), "operator.config"));
                if (!(config instanceof RailixValue.ObjectValue objectValue)) {
                    throw new IllegalArgumentException("operator.config must decode to an object");
                }
                operators.add(new AppPlan.OperatorInvocation(requireString(operator, "use"), objectValue));
            }
            connections.add(new AppPlan.Connection(
                    requireString(connection, "id"),
                    decodePortRef(requireMap(connection.get("from"), "connection.from")),
                    decodePortRef(requireMap(connection.get("to"), "connection.to")),
                    operators
            ));
        }
        return List.copyOf(connections);
    }

    private static Map<String, Object> encodePortRef(final AppPlan.PortRef ref) {
        return orderedMap(
                Map.entry("stepId", ref.stepId()),
                Map.entry("port", ref.port())
        );
    }

    private static AppPlan.PortRef decodePortRef(final Map<String, Object> model) {
        return new AppPlan.PortRef(requireString(model, "stepId"), requireString(model, "port"));
    }

    private static RailixValue.ObjectValue decodeStepConfig(final Map<String, Object> model) {
        if (!model.containsKey("config")) {
            return new RailixValue.ObjectValue(Map.of());
        }
        final RailixValue value = decodeRailixValue(requireMap(model.get("config"), "config"));
        if (value instanceof RailixValue.ObjectValue objectValue) {
            return objectValue;
        }
        throw new IllegalArgumentException("config must decode to an object");
    }

    private static PermissionSet decodeAppPlanPermissions(final Map<String, Object> model) {
        if (!model.containsKey("permissions")) {
            return PermissionSet.none();
        }
        return permissionSetFromUiModel(requireMap(model.get("permissions"), "permissions"));
    }

    private static Map<String, List<String>> decodePermissionMap(final Map<String, Object> model) {
        final Map<String, List<String>> decoded = new LinkedHashMap<>();
        final List<String> keys = new ArrayList<>(model.keySet());
        keys.sort(String::compareTo);
        for (final String key : keys) {
            decoded.put(key, stringList(requireList(model, key)));
        }
        return decoded;
    }

    private static List<Object> encodePorts(final List<StepContract.Port> ports) {
        final List<Object> encoded = new ArrayList<>();
        for (final StepContract.Port port : ports) {
            encoded.add(orderedMap(
                    Map.entry("name", port.name()),
                    Map.entry("type", port.type()),
                    Map.entry("binding", port.binding()),
                    Map.entry("required", port.required()),
                    Map.entry("values", List.copyOf(port.values())),
                    Map.entry("shape", encodeShape(port.shape()))
            ));
        }
        return encoded;
    }

    private static List<StepContract.Port> decodePorts(final List<Object> models) {
        final List<StepContract.Port> ports = new ArrayList<>();
        for (final Object object : models) {
            final Map<String, Object> model = requireMap(object, "port");
            final String name = requireString(model, "name");
            final String type = requireString(model, "type");
            final String binding = requireString(model, "binding");
            final boolean required = requireBoolean(model, "required");
            final List<String> values = stringList(requireList(model, "values"));
            ports.add(model.containsKey("shape")
                    ? new StepContract.Port(
                            name,
                            type,
                            binding,
                            required,
                            values,
                            decodeShape(requireMap(model.get("shape"), "port.shape"))
                    )
                    : new StepContract.Port(name, type, binding, required, values));
        }
        return ports;
    }

    private static Map<String, Object> encodeStepConfigFields(final Map<String, StepContract.ConfigField> fields) {
        final Map<String, Object> encoded = new LinkedHashMap<>();
        final List<String> names = new ArrayList<>(fields.keySet());
        names.sort(String::compareTo);
        for (final String name : names) {
            final StepContract.ConfigField field = fields.get(name);
            encoded.put(name, orderedMap(
                    Map.entry("shape", encodeShape(field.shape())),
                    Map.entry("required", field.required()),
                    Map.entry("default", orderedMap(
                            Map.entry("present", field.defaultValue().present()),
                            Map.entry("value", encodeRailixValue(field.defaultValue().value()))
                    ))
            ));
        }
        return encoded;
    }

    private static Map<String, StepContract.ConfigField> decodeStepConfigFields(final Map<String, Object> model) {
        if (!model.containsKey("config")) {
            return Map.of();
        }
        final Map<String, Object> fieldsModel = requireMap(model.get("config"), "config");
        final Map<String, StepContract.ConfigField> fields = new LinkedHashMap<>();
        final List<String> names = new ArrayList<>(fieldsModel.keySet());
        names.sort(String::compareTo);
        for (final String name : names) {
            final Map<String, Object> field = requireMap(fieldsModel.get(name), "config." + name);
            final Map<String, Object> defaultModel = requireMap(field.get("default"), "config." + name + ".default");
            fields.put(name, new StepContract.ConfigField(
                    decodeShape(requireMap(field.get("shape"), "config." + name + ".shape")),
                    requireBoolean(field, "required"),
                    new StepContract.DefaultValue(
                            requireBoolean(defaultModel, "present"),
                            decodeRailixValue(requireMap(defaultModel.get("value"), "config." + name + ".default.value"))
                    )
            ));
        }
        return Map.copyOf(fields);
    }

    private static Map<String, Object> encodeStepSemantics(final StepContract.Semantics semantics) {
        return orderedMap(
                Map.entry("determinism", toKebab(semantics.determinism())),
                Map.entry("effects", toKebab(semantics.effects())),
                Map.entry("stateScope", toKebab(semantics.stateScope())),
                Map.entry("idempotency", toKebab(semantics.idempotency())),
                Map.entry("concurrency", toKebab(semantics.concurrency()))
        );
    }

    private static StepContract.Semantics decodeStepSemantics(
            final Map<String, Object> model,
            final int contractVersion
    ) {
        if (!model.containsKey("semantics")) {
            return contractVersion == StepContract.CURRENT_CONTRACT_VERSION
                    ? StepContract.Semantics.pure()
                    : StepContract.Semantics.undeclared();
        }
        final Map<String, Object> semantics = requireMap(model.get("semantics"), "semantics");
        return new StepContract.Semantics(
                fromKebab(StepContract.Determinism.class, requireString(semantics, "determinism")),
                fromKebab(StepContract.Effects.class, requireString(semantics, "effects")),
                fromKebab(StepContract.StateScope.class, requireString(semantics, "stateScope")),
                fromKebab(StepContract.Idempotency.class, requireString(semantics, "idempotency")),
                fromKebab(StepContract.Concurrency.class, requireString(semantics, "concurrency"))
        );
    }

    private static Map<String, Object> encodeOutcomes(final Map<String, StepContract.Outcome> outcomes) {
        final Map<String, Object> encoded = new LinkedHashMap<>();
        final List<String> keys = new ArrayList<>(outcomes.keySet());
        keys.sort(String::compareTo);
        for (final String key : keys) {
            encoded.put(key, orderedMap(Map.entry("description", outcomes.get(key).description())));
        }
        return encoded;
    }

    private static Map<String, StepContract.Outcome> decodeOutcomes(final Map<String, Object> model) {
        final Map<String, StepContract.Outcome> outcomes = new LinkedHashMap<>();
        final List<String> keys = new ArrayList<>(model.keySet());
        keys.sort(String::compareTo);
        for (final String key : keys) {
            outcomes.put(key, new StepContract.Outcome(requireString(requireMap(model.get(key), key), "description")));
        }
        return outcomes;
    }

    private static List<Object> encodeMetricDefinitions(final List<StepContract.MetricDefinition> definitions) {
        final List<Object> encoded = new ArrayList<>();
        for (final StepContract.MetricDefinition definition : definitions) {
            encoded.add(orderedMap(
                    Map.entry("name", definition.name()),
                    Map.entry("type", definition.type()),
                    Map.entry("unit", definition.unit()),
                    Map.entry("labels", List.copyOf(definition.labels()))
            ));
        }
        return encoded;
    }

    private static List<StepContract.MetricDefinition> decodeMetricDefinitions(final List<Object> models) {
        final List<StepContract.MetricDefinition> definitions = new ArrayList<>();
        for (final Object object : models) {
            final Map<String, Object> model = requireMap(object, "metricDefinition");
            definitions.add(new StepContract.MetricDefinition(
                    requireString(model, "name"),
                    requireString(model, "type"),
                    requireString(model, "unit"),
                    stringList(requireList(model, "labels"))
            ));
        }
        return definitions;
    }

    private static Map<String, Object> encodeOperatorInputs(final Map<String, OperatorContract.Input> inputs) {
        final Map<String, Object> encoded = new LinkedHashMap<>();
        final List<String> keys = new ArrayList<>(inputs.keySet());
        keys.sort(String::compareTo);
        for (final String key : keys) {
            final OperatorContract.Input input = inputs.get(key);
            encoded.put(key, orderedMap(
                    Map.entry("type", input.type()),
                    Map.entry("required", input.required()),
                    Map.entry("dynamic", input.dynamic()),
                    Map.entry("min", input.min())
            ));
        }
        return encoded;
    }

    private static Map<String, OperatorContract.Input> decodeOperatorInputs(final Map<String, Object> model) {
        final Map<String, OperatorContract.Input> inputs = new LinkedHashMap<>();
        final List<String> keys = new ArrayList<>(model.keySet());
        keys.sort(String::compareTo);
        for (final String key : keys) {
            final Map<String, Object> inputModel = requireMap(model.get(key), key);
            inputs.put(key, new OperatorContract.Input(
                    requireString(inputModel, "type"),
                    requireBoolean(inputModel, "required"),
                    requireBoolean(inputModel, "dynamic"),
                    requireInt(inputModel, "min")
            ));
        }
        return inputs;
    }

    private static Map<String, Object> encodeOperatorConfigs(final Map<String, OperatorContract.Config> configs) {
        final Map<String, Object> encoded = new LinkedHashMap<>();
        final List<String> keys = new ArrayList<>(configs.keySet());
        keys.sort(String::compareTo);
        for (final String key : keys) {
            final OperatorContract.Config config = configs.get(key);
            encoded.put(key, orderedMap(
                    Map.entry("type", config.type()),
                    Map.entry("required", config.required()),
                    Map.entry("defaultValue", encodeRailixValue(config.defaultValue()))
            ));
        }
        return encoded;
    }

    private static Map<String, OperatorContract.Config> decodeOperatorConfigs(final Map<String, Object> model) {
        final Map<String, OperatorContract.Config> configs = new LinkedHashMap<>();
        final List<String> keys = new ArrayList<>(model.keySet());
        keys.sort(String::compareTo);
        for (final String key : keys) {
            final Map<String, Object> configModel = requireMap(model.get(key), key);
            configs.put(key, new OperatorContract.Config(
                    requireString(configModel, "type"),
                    requireBoolean(configModel, "required"),
                    decodeRailixValue(requireMap(configModel.get("defaultValue"), "defaultValue"))
            ));
        }
        return configs;
    }

    private static Map<String, Object> encodeOperatorOutputs(final Map<String, OperatorContract.Output> outputs) {
        final Map<String, Object> encoded = new LinkedHashMap<>();
        final List<String> keys = new ArrayList<>(outputs.keySet());
        keys.sort(String::compareTo);
        for (final String key : keys) {
            encoded.put(key, orderedMap(Map.entry("type", outputs.get(key).type())));
        }
        return encoded;
    }

    private static Map<String, OperatorContract.Output> decodeOperatorOutputs(final Map<String, Object> model) {
        final Map<String, OperatorContract.Output> outputs = new LinkedHashMap<>();
        final List<String> keys = new ArrayList<>(model.keySet());
        keys.sort(String::compareTo);
        for (final String key : keys) {
            outputs.put(key, new OperatorContract.Output(requireString(requireMap(model.get(key), key), "type")));
        }
        return outputs;
    }

    private static Map<String, Object> encodeShapeFields(final Map<String, Shape.Field> fields) {
        final Map<String, Object> encoded = new LinkedHashMap<>();
        final List<String> keys = new ArrayList<>(fields.keySet());
        keys.sort(String::compareTo);
        for (final String key : keys) {
            final Shape.Field field = fields.get(key);
            encoded.put(key, orderedMap(
                    Map.entry("shape", encodeShape(field.shape())),
                    Map.entry("presence", toKebab(field.presence())),
                    Map.entry("confidence", orderedMap(
                            Map.entry("observedCount", field.confidence().observedCount()),
                            Map.entry("sampleCount", field.confidence().sampleCount()),
                            Map.entry("conflictingKinds", enumList(field.confidence().conflictingKinds()))
                    ))
            ));
        }
        return encoded;
    }

    private static Map<String, Shape.Field> decodeShapeFields(final Map<String, Object> model) {
        final Map<String, Shape.Field> fields = new LinkedHashMap<>();
        final List<String> keys = new ArrayList<>(model.keySet());
        keys.sort(String::compareTo);
        for (final String key : keys) {
            final Map<String, Object> fieldModel = requireMap(model.get(key), key);
            final Map<String, Object> confidenceModel = requireMap(fieldModel.get("confidence"), "confidence");
            fields.put(key, new Shape.Field(
                    decodeShape(requireMap(fieldModel.get("shape"), "shape")),
                    fromKebab(Shape.Presence.class, requireString(fieldModel, "presence")),
                    new Shape.Confidence(
                            requireInt(confidenceModel, "observedCount"),
                            requireInt(confidenceModel, "sampleCount"),
                            decodeEnumList(Shape.Kind.class, requireList(confidenceModel, "conflictingKinds"))
                    )
            ));
        }
        return fields;
    }

    private static List<Object> encodeShapes(final List<Shape> shapes) {
        final List<Object> encoded = new ArrayList<>();
        for (final Shape shape : shapes) {
            encoded.add(encodeShape(shape));
        }
        return encoded;
    }

    private static List<Shape> decodeShapes(final List<Object> models) {
        final List<Shape> shapes = new ArrayList<>();
        for (final Object object : models) {
            shapes.add(decodeShape(requireMap(object, "shape")));
        }
        return shapes;
    }

    private static Map<String, Object> encodeSettingsValue(final SettingsTree.Entry entry) {
        if (entry.secret()
                && (entry.value() instanceof SettingsTree.PlainValue || entry.value() instanceof SettingsTree.ReferenceValue)) {
            throw new IllegalArgumentException("Secret setting " + entry.path() + " cannot be exported as a UI model value");
        }
        return switch (entry.value()) {
            case SettingsTree.PlainValue plainValue -> orderedMap(
                    Map.entry("kind", "plain"),
                    Map.entry("value", encodeRailixValue(plainValue.value()))
            );
            case SettingsTree.EncryptedValue encryptedValue -> orderedMap(
                    Map.entry("kind", "encrypted"),
                    Map.entry("cipherText", encryptedValue.cipherText())
            );
            case SettingsTree.ReferenceValue referenceValue -> orderedMap(
                    Map.entry("kind", "reference"),
                    Map.entry("value", encodeRailixValue(referenceValue.value()))
            );
        };
    }

    private static SettingsTree.Value decodeSettingsValue(final Map<String, Object> model) {
        final String kind = requireString(model, "kind");
        return switch (kind) {
            case "plain" -> new SettingsTree.PlainValue(decodeRailixValue(requireMap(model.get("value"), "value")));
            case "encrypted" -> new SettingsTree.EncryptedValue(requireString(model, "cipherText"));
            case "reference" -> new SettingsTree.ReferenceValue(decodeRailixValue(requireMap(model.get("value"), "value")));
            default -> throw new IllegalArgumentException("Unsupported settings value kind: " + kind);
        };
    }

    private static RailixValue.ObjectValue requireObjectValue(final Map<String, Object> model, final String fieldName) {
        final RailixValue value = decodeRailixValue(requireMap(model.get(fieldName), fieldName));
        if (!(value instanceof RailixValue.ObjectValue objectValue)) {
            throw new IllegalArgumentException(fieldName + " must decode to an object value");
        }
        return objectValue;
    }

    private static Shape.ObjectShape requireObjectShape(final Map<String, Object> model, final String fieldName) {
        final Shape value = decodeShape(requireMap(model.get(fieldName), fieldName));
        if (!(value instanceof Shape.ObjectShape objectShape)) {
            throw new IllegalArgumentException(fieldName + " must decode to an object shape");
        }
        return objectShape;
    }

    private static Map<String, Object> encodeRailixValueMap(final Map<String, RailixValue> values) {
        final Map<String, Object> encoded = new LinkedHashMap<>();
        final List<String> keys = new ArrayList<>(values.keySet());
        keys.sort(String::compareTo);
        for (final String key : keys) {
            encoded.put(key, encodeRailixValue(values.get(key)));
        }
        return encoded;
    }

    private static Map<String, RailixValue> decodeRailixValueMap(final Map<String, Object> model) {
        final Map<String, RailixValue> decoded = new LinkedHashMap<>();
        final List<String> keys = new ArrayList<>(model.keySet());
        keys.sort(String::compareTo);
        for (final String key : keys) {
            decoded.put(key, decodeRailixValue(requireMap(model.get(key), key)));
        }
        return decoded;
    }

    private static List<Object> encodeRailixValueList(final List<RailixValue> values) {
        final List<Object> encoded = new ArrayList<>();
        for (final RailixValue value : values) {
            encoded.add(encodeRailixValue(value));
        }
        return encoded;
    }

    private static List<RailixValue> decodeRailixValueList(final List<Object> models) {
        final List<RailixValue> decoded = new ArrayList<>();
        for (final Object object : models) {
            decoded.add(decodeRailixValue(requireMap(object, "railixValue")));
        }
        return decoded;
    }

    private static List<RailixPath> decodePaths(final List<Object> models) {
        final List<RailixPath> paths = new ArrayList<>();
        for (final Object object : models) {
            paths.add(RailixPath.parse(requireString(object, "path")));
        }
        return paths;
    }

    private static Map<String, String> orderedStringMap(final Map<String, String> input) {
        final Map<String, String> ordered = new LinkedHashMap<>();
        final List<String> keys = new ArrayList<>(input.keySet());
        keys.sort(String::compareTo);
        for (final String key : keys) {
            ordered.put(key, input.get(key));
        }
        return ordered;
    }

    private static Map<String, String> decodeStringMap(final Map<String, Object> input) {
        final Map<String, String> decoded = new LinkedHashMap<>();
        final List<String> keys = new ArrayList<>(input.keySet());
        keys.sort(String::compareTo);
        for (final String key : keys) {
            decoded.put(key, requireString(input.get(key), key));
        }
        return decoded;
    }

    @SafeVarargs
    private static Map<String, Object> orderedMap(final Map.Entry<String, Object>... entries) {
        final Map<String, Object> map = new LinkedHashMap<>();
        for (final Map.Entry<String, Object> entry : entries) {
            map.put(entry.getKey(), entry.getValue());
        }
        return map;
    }

    private static List<Object> enumList(final List<? extends Enum<?>> enums) {
        final List<Object> values = new ArrayList<>();
        for (final Enum<?> value : enums) {
            values.add(toKebab(value));
        }
        return values;
    }

    private static <E extends Enum<E>> List<E> decodeEnumList(final Class<E> type, final List<Object> values) {
        final List<E> decoded = new ArrayList<>();
        for (final Object value : values) {
            decoded.add(fromKebab(type, requireString(value, type.getSimpleName())));
        }
        return decoded;
    }

    private static String toKebab(final Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static <E extends Enum<E>> E fromKebab(final Class<E> type, final String value) {
        return Enum.valueOf(type, value.toUpperCase(Locale.ROOT).replace('-', '_'));
    }

    private static void writeJson(final Object value, final StringBuilder builder) {
        if (value == null) {
            builder.append("null");
            return;
        }
        if (value instanceof String stringValue) {
            writeJsonString(stringValue, builder);
            return;
        }
        if (value instanceof Boolean || value instanceof Number) {
            builder.append(value);
            return;
        }
        if (value instanceof Map<?, ?> mapValue) {
            builder.append('{');
            final List<String> keys = sortedKeys(mapValue);
            for (int index = 0; index < keys.size(); index++) {
                if (index > 0) {
                    builder.append(',');
                }
                writeJsonString(keys.get(index), builder);
                builder.append(':');
                writeJson(mapValue.get(keys.get(index)), builder);
            }
            builder.append('}');
            return;
        }
        if (value instanceof List<?> listValue) {
            builder.append('[');
            for (int index = 0; index < listValue.size(); index++) {
                if (index > 0) {
                    builder.append(',');
                }
                writeJson(listValue.get(index), builder);
            }
            builder.append(']');
            return;
        }
        throw new IllegalArgumentException("Unsupported JSON value type: " + value.getClass().getName());
    }

    private static void writeYaml(final Object value, final StringBuilder builder, final int indent) {
        if (value instanceof Map<?, ?> mapValue) {
            if (mapValue.isEmpty()) {
                indent(builder, indent);
                builder.append("{}\n");
                return;
            }
            final List<String> keys = sortedKeys(mapValue);
            for (int index = 0; index < keys.size(); index++) {
                final String key = keys.get(index);
                final Object nestedValue = mapValue.get(key);
                indent(builder, indent);
                writeJsonString(key, builder);
                builder.append(':');
                if (isInlineYamlValue(nestedValue)) {
                    builder.append(' ');
                    writeYamlInlineValue(nestedValue, builder);
                    builder.append('\n');
                } else {
                    builder.append('\n');
                    writeYaml(nestedValue, builder, indent + 2);
                }
            }
            return;
        }
        if (value instanceof List<?> listValue) {
            if (listValue.isEmpty()) {
                indent(builder, indent);
                builder.append("[]\n");
                return;
            }
            for (final Object nestedValue : listValue) {
                indent(builder, indent);
                builder.append('-');
                if (isInlineYamlValue(nestedValue)) {
                    builder.append(' ');
                    writeYamlInlineValue(nestedValue, builder);
                    builder.append('\n');
                } else {
                    builder.append('\n');
                    writeYaml(nestedValue, builder, indent + 2);
                }
            }
            return;
        }
        indent(builder, indent);
        writeYamlScalar(value, builder);
        builder.append('\n');
    }

    private static boolean isScalar(final Object value) {
        return value == null || value instanceof String || value instanceof Boolean || value instanceof Number;
    }

    private static boolean isInlineYamlValue(final Object value) {
        return isScalar(value)
                || value instanceof Map<?, ?> mapValue && mapValue.isEmpty()
                || value instanceof List<?> listValue && listValue.isEmpty();
    }

    private static void writeYamlInlineValue(final Object value, final StringBuilder builder) {
        if (value instanceof Map<?, ?> mapValue && mapValue.isEmpty()) {
            builder.append("{}");
            return;
        }
        if (value instanceof List<?> listValue && listValue.isEmpty()) {
            builder.append("[]");
            return;
        }
        writeYamlScalar(value, builder);
    }

    private static void writeYamlScalar(final Object value, final StringBuilder builder) {
        if (value == null) {
            builder.append("null");
        } else if (value instanceof String stringValue) {
            writeJsonString(stringValue, builder);
        } else {
            builder.append(value);
        }
    }

    private static void writeJsonString(final String value, final StringBuilder builder) {
        builder.append('"');
        for (int index = 0; index < value.length(); index++) {
            final char current = value.charAt(index);
            switch (current) {
                case '\\' -> builder.append("\\\\");
                case '"' -> builder.append("\\\"");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (current < 0x20) {
                        builder.append("\\u");
                        final String hex = Integer.toHexString(current);
                        for (int padding = hex.length(); padding < 4; padding++) {
                            builder.append('0');
                        }
                        builder.append(hex);
                    } else {
                        builder.append(current);
                    }
                }
            }
        }
        builder.append('"');
    }

    private static void indent(final StringBuilder builder, final int indent) {
        for (int index = 0; index < indent; index++) {
            builder.append(' ');
        }
    }

    private static List<String> sortedKeys(final Map<?, ?> map) {
        final List<String> keys = new ArrayList<>();
        for (final Object key : map.keySet()) {
            keys.add(String.valueOf(key));
        }
        keys.sort(String::compareTo);
        return keys;
    }

    private static String requireString(final Map<String, Object> model, final String fieldName) {
        return requireString(model.get(fieldName), fieldName);
    }

    private static String requireString(final Object value, final String fieldName) {
        if (!(value instanceof String stringValue)) {
            throw new IllegalArgumentException(fieldName + " must be a string");
        }
        return stringValue;
    }

    private static boolean requireBoolean(final Map<String, Object> model, final String fieldName) {
        final Object value = model.get(fieldName);
        if (!(value instanceof Boolean booleanValue)) {
            throw new IllegalArgumentException(fieldName + " must be a boolean");
        }
        return booleanValue;
    }

    private static int requireInt(final Map<String, Object> model, final String fieldName) {
        final Object value = model.get(fieldName);
        if (value instanceof Integer integerValue) {
            return integerValue;
        }
        if (value instanceof Long longValue) {
            if (longValue < Integer.MIN_VALUE || longValue > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(fieldName + " must fit in a 32-bit integer");
            }
            return longValue.intValue();
        }
        throw new IllegalArgumentException(fieldName + " must be an integer");
    }

    private static long requireLong(final Map<String, Object> model, final String fieldName) {
        final Object value = model.get(fieldName);
        if (value instanceof Integer integerValue) {
            return integerValue.longValue();
        }
        if (value instanceof Long longValue) {
            return longValue;
        }
        throw new IllegalArgumentException(fieldName + " must be a long");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requireMap(final Object value, final String fieldName) {
        if (!(value instanceof Map<?, ?> mapValue)) {
            throw new IllegalArgumentException(fieldName + " must be a map");
        }
        return (Map<String, Object>) mapValue;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> requireList(final Map<String, Object> model, final String fieldName) {
        final Object value = model.get(fieldName);
        if (!(value instanceof List<?> listValue)) {
            throw new IllegalArgumentException(fieldName + " must be a list");
        }
        return (List<Object>) listValue;
    }

    private static List<String> stringList(final List<Object> values) {
        final List<String> result = new ArrayList<>();
        for (final Object value : values) {
            result.add(requireString(value, "list item"));
        }
        return result;
    }

    private static final class StableJsonParser {
        private final String source;
        private int index;

        private StableJsonParser(final String source) {
            this.source = source;
        }

        private static Object parse(final String source) {
            final StableJsonParser parser = new StableJsonParser(Objects.requireNonNull(source, "source"));
            final Object value = parser.parseValue();
            parser.skipWhitespace();
            if (!parser.isEnd()) {
                throw new IllegalArgumentException("Unexpected trailing JSON content");
            }
            return value;
        }

        private Object parseValue() {
            skipWhitespace();
            if (isEnd()) {
                throw new IllegalArgumentException("Unexpected end of JSON input");
            }
            return switch (current()) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> parseTrue();
                case 'f' -> parseFalse();
                case 'n' -> parseNull();
                default -> parseNumber();
            };
        }

        private Map<String, Object> parseObject() {
            expect('{');
            skipWhitespace();
            final Map<String, Object> values = new LinkedHashMap<>();
            if (consumeIf('}')) {
                return values;
            }
            while (true) {
                skipWhitespace();
                final String key = parseString();
                skipWhitespace();
                expect(':');
                values.put(key, parseValue());
                skipWhitespace();
                if (consumeIf('}')) {
                    return values;
                }
                expect(',');
            }
        }

        private List<Object> parseArray() {
            expect('[');
            skipWhitespace();
            final List<Object> values = new ArrayList<>();
            if (consumeIf(']')) {
                return values;
            }
            while (true) {
                values.add(parseValue());
                skipWhitespace();
                if (consumeIf(']')) {
                    return values;
                }
                expect(',');
            }
        }

        private String parseString() {
            expect('"');
            final StringBuilder builder = new StringBuilder();
            while (!isEnd()) {
                final char current = current();
                index++;
                if (current == '"') {
                    return builder.toString();
                }
                if (current == '\\') {
                    if (isEnd()) {
                        throw new IllegalArgumentException("Incomplete JSON escape sequence");
                    }
                    final char escaped = current();
                    index++;
                    switch (escaped) {
                        case '"', '\\', '/' -> builder.append(escaped);
                        case 'b' -> builder.append('\b');
                        case 'f' -> builder.append('\f');
                        case 'n' -> builder.append('\n');
                        case 'r' -> builder.append('\r');
                        case 't' -> builder.append('\t');
                        case 'u' -> builder.append(parseUnicodeEscape());
                        default -> throw new IllegalArgumentException("Unsupported JSON escape: \\" + escaped);
                    }
                    continue;
                }
                builder.append(current);
            }
            throw new IllegalArgumentException("Unterminated JSON string");
        }

        private char parseUnicodeEscape() {
            if (index + 4 > source.length()) {
                throw new IllegalArgumentException("Incomplete unicode escape");
            }
            final String hex = source.substring(index, index + 4);
            index += 4;
            try {
                return (char) Integer.parseInt(hex, 16);
            } catch (final NumberFormatException exception) {
                throw new IllegalArgumentException("Invalid unicode escape: " + hex, exception);
            }
        }

        private Boolean parseTrue() {
            expectKeyword("true");
            return Boolean.TRUE;
        }

        private Boolean parseFalse() {
            expectKeyword("false");
            return Boolean.FALSE;
        }

        private Object parseNull() {
            expectKeyword("null");
            return null;
        }

        private Number parseNumber() {
            final int start = index;
            if (current() == '-') {
                index++;
            }
            if (isEnd() || !Character.isDigit(current())) {
                throw new IllegalArgumentException("Invalid JSON number");
            }
            if (current() == '0') {
                index++;
            } else {
                while (!isEnd() && Character.isDigit(current())) {
                    index++;
                }
            }
            var decimal = false;
            var exponent = false;
            if (!isEnd() && current() == '.') {
                decimal = true;
                index++;
                if (isEnd() || !Character.isDigit(current())) {
                    throw new IllegalArgumentException("Invalid JSON number");
                }
                while (!isEnd() && Character.isDigit(current())) {
                    index++;
                }
            }
            if (!isEnd() && (current() == 'e' || current() == 'E')) {
                exponent = true;
                index++;
                if (!isEnd() && (current() == '+' || current() == '-')) {
                    index++;
                }
                if (isEnd() || !Character.isDigit(current())) {
                    throw new IllegalArgumentException("Invalid JSON number");
                }
                while (!isEnd() && Character.isDigit(current())) {
                    index++;
                }
            }
            final String value = source.substring(start, index);
            try {
                if (decimal || exponent) {
                    return new BigDecimal(value);
                }
                final long longValue = Long.parseLong(value);
                if (longValue >= Integer.MIN_VALUE && longValue <= Integer.MAX_VALUE) {
                    return (int) longValue;
                }
                return longValue;
            } catch (final NumberFormatException exception) {
                try {
                    return new BigDecimal(value);
                } catch (final NumberFormatException decimalException) {
                    throw new IllegalArgumentException("Invalid JSON number: " + value, decimalException);
                }
            }
        }

        private void expectKeyword(final String keyword) {
            if (!source.startsWith(keyword, index)) {
                throw new IllegalArgumentException("Expected JSON token: " + keyword);
            }
            index += keyword.length();
        }

        private void expect(final char expected) {
            skipWhitespace();
            if (isEnd() || current() != expected) {
                throw new IllegalArgumentException("Expected JSON character: " + expected);
            }
            index++;
        }

        private boolean consumeIf(final char expected) {
            if (!isEnd() && current() == expected) {
                index++;
                return true;
            }
            return false;
        }

        private void skipWhitespace() {
            while (!isEnd() && Character.isWhitespace(current())) {
                index++;
            }
        }

        private char current() {
            return source.charAt(index);
        }

        private boolean isEnd() {
            return index >= source.length();
        }
    }
}
