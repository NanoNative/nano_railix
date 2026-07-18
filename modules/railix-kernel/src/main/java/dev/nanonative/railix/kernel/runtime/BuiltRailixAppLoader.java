package dev.nanonative.railix.kernel.runtime;

import dev.nanonative.railix.kernel.model.Envelope;
import dev.nanonative.railix.kernel.model.KernelContractCodec;
import dev.nanonative.railix.kernel.model.RailixPath;
import dev.nanonative.railix.kernel.model.RailixValue;
import dev.nanonative.railix.kernel.model.Reply;
import dev.nanonative.railix.kernel.model.SettingsTree;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Loads persisted built-app runtime inputs from deterministic JSON artifacts.
 */
public final class BuiltRailixAppLoader {

    private static final String CLASSPATH_PREFIX = "classpath:";
    private static final String PACKAGED_DEFAULTS_LOCATION = "classpath:/railix/settings/defaults.json";
    private static final String PROFILE_LOCATION_PREFIX = "classpath:/railix/settings/profiles/";
    private static final String PROFILE_LOCATION_SUFFIX = ".json";
    private static final String ENVIRONMENT_OVERRIDE_PREFIX = "RAILIX_SETTING__";
    private static final String SYSTEM_PROPERTY_OVERRIDE_PREFIX = "railix.setting.";

    private BuiltRailixAppLoader() {}

    /**
     * Loads a built app from a persisted app plan location.
     *
     * @param planLocation filesystem path or {@code classpath:} location
     * @return built app ready for execution
     */
    public static BuiltRailixApp loadApp(final String planLocation) {
        return new BuiltRailixApp(loadPlan(planLocation));
    }

    /**
     * Loads an app plan from a persisted JSON or supported YAML location.
     *
     * @param planLocation filesystem path or {@code classpath:} location
     * @return decoded app plan
     */
    public static AppPlan loadPlan(final String planLocation) {
        final String location = requireNonBlank(planLocation, "planLocation");
        if (isYamlLocation(location)) {
            return decodeAuthoringAppPlan(parseYamlObject(location, "plan"));
        }
        return loadJsonModel(planLocation, "plan", KernelContractCodec::appPlanFromUiModel);
    }

    /**
     * Loads an envelope from a persisted JSON or supported YAML location.
     *
     * @param envelopeLocation filesystem path or {@code classpath:} location
     * @return decoded envelope
     */
    public static Envelope loadEnvelope(final String envelopeLocation) {
        final String location = requireNonBlank(envelopeLocation, "envelopeLocation");
        if (isYamlLocation(location)) {
            return decodeEnvelope(parseYamlObject(location, "envelope"));
        }
        return loadJsonModel(envelopeLocation, "envelope", KernelContractCodec::envelopeFromUiModel);
    }

    /**
     * Loads a settings tree from a persisted JSON location.
     *
     * @param settingsLocation filesystem path or {@code classpath:} location
     * @return decoded settings tree
     */
    public static SettingsTree loadSettingsTree(final String settingsLocation) {
        return loadJsonModel(settingsLocation, "settings", KernelContractCodec::settingsTreeFromUiModel);
    }

    /**
     * Loads and overlays multiple settings trees in the order provided.
     *
     * @param settingsLocations filesystem paths or {@code classpath:} locations
     * @return merged settings tree, or {@link SettingsTree#empty()} when no locations are provided
     */
    public static SettingsTree loadSettingsTree(final List<String> settingsLocations) {
        Objects.requireNonNull(settingsLocations, "settingsLocations");
        SettingsTree merged = SettingsTree.empty();
        for (final String settingsLocation : settingsLocations) {
            merged = merged.overlay(loadSettingsTree(settingsLocation));
        }
        return merged;
    }

    /**
     * Resolves the packaged settings resources that should be applied automatically by the launcher.
     *
     * @param profileName optional packaged profile name
     * @return deterministic settings locations in precedence order
     */
    public static List<String> packagedSettingsLocations(final Optional<String> profileName) {
        Objects.requireNonNull(profileName, "profileName");
        final java.util.ArrayList<String> locations = new java.util.ArrayList<>();
        if (classpathResourceExists(PACKAGED_DEFAULTS_LOCATION)) {
            locations.add(PACKAGED_DEFAULTS_LOCATION);
        }
        profileName.ifPresent(profile -> {
            final String normalizedProfile = requireProfileName(profile);
            final String location = PROFILE_LOCATION_PREFIX + normalizedProfile + PROFILE_LOCATION_SUFFIX;
            if (!classpathResourceExists(location)) {
                throw new IllegalArgumentException("Classpath resource not found: "
                        + normalizeClasspathResource(location.substring(CLASSPATH_PREFIX.length())));
            }
            locations.add(location);
        });
        return List.copyOf(locations);
    }

    /**
     * Resolves runtime settings overrides from prefixed environment variables.
     *
     * <p>Variables must use the {@code RAILIX_SETTING__} prefix and encode dotted path separators as {@code __},
     * for example {@code RAILIX_SETTING__settings__app__mode=prod}.
     *
     * @param environment process environment
     * @return environment override tree, or {@link SettingsTree#empty()} when no matching variables are present
     */
    public static SettingsTree environmentOverrideSettings(final Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        final Map<RailixPath, SettingsTree.Entry> entries = new LinkedHashMap<>();
        for (final Map.Entry<String, String> variable : new TreeMap<>(environment).entrySet()) {
            final String variableName = requireNonBlank(variable.getKey(), "environmentVariableName");
            if (!variableName.startsWith(ENVIRONMENT_OVERRIDE_PREFIX)) {
                continue;
            }
            final RailixPath path = parseEnvironmentOverridePath(variableName);
            if (entries.containsKey(path)) {
                throw new IllegalArgumentException("Duplicate environment override for path: " + path);
            }
            entries.put(path, overrideEntry(
                    path,
                    variable.getValue(),
                    variableName
            ));
        }
        return overrideSettingsTree(
                "Environment variable overrides",
                entries,
                SettingsTree.SourceLayer.ENVIRONMENT_VARIABLES
        );
    }

    /**
     * Resolves runtime settings overrides from repeatable CLI assignments.
     *
     * <p>Assignments must use the form {@code railix.path=value}, for example {@code settings.app.mode=prod}.
     *
     * @param assignments repeatable CLI assignments
     * @return CLI override tree, or {@link SettingsTree#empty()} when no assignments are provided
     */
    public static SettingsTree cliOverrideSettings(final List<String> assignments) {
        Objects.requireNonNull(assignments, "assignments");
        final Map<RailixPath, SettingsTree.Entry> entries = new LinkedHashMap<>();
        for (final String assignment : assignments) {
            final OverrideAssignment parsed = parseOverrideAssignment(assignment, "--set");
            if (entries.containsKey(parsed.path())) {
                throw new IllegalArgumentException("Duplicate CLI override for path: " + parsed.path());
            }
            entries.put(parsed.path(), overrideEntry(
                    parsed.path(),
                    parsed.value(),
                    "--set"
            ));
        }
        return overrideSettingsTree(
                "CLI argument overrides",
                entries,
                SettingsTree.SourceLayer.CLI_ARGS
        );
    }

    /**
     * Resolves runtime settings overrides from JVM system properties.
     *
     * <p>Properties must use the {@code railix.setting.} prefix followed by the full Railix path,
     * for example {@code -Drailix.setting.settings.app.mode=prod}.
     *
     * @param systemProperties JVM system properties
     * @return system property override tree, or {@link SettingsTree#empty()} when no matching properties are present
     */
    public static SettingsTree systemPropertyOverrideSettings(final Map<String, String> systemProperties) {
        Objects.requireNonNull(systemProperties, "systemProperties");
        final Map<RailixPath, SettingsTree.Entry> entries = new LinkedHashMap<>();
        for (final Map.Entry<String, String> property : new TreeMap<>(systemProperties).entrySet()) {
            final String propertyName = requireNonBlank(property.getKey(), "systemPropertyName");
            if (!propertyName.startsWith(SYSTEM_PROPERTY_OVERRIDE_PREFIX)) {
                continue;
            }
            final RailixPath path = parseSystemPropertyOverridePath(propertyName);
            if (entries.containsKey(path)) {
                throw new IllegalArgumentException("Duplicate system property override for path: " + path);
            }
            entries.put(path, overrideEntry(
                    path,
                    property.getValue(),
                    propertyName
            ));
        }
        return overrideSettingsTree(
                "JVM system property overrides",
                entries,
                SettingsTree.SourceLayer.SYSTEM_PROPERTIES
        );
    }

    private static <T> T loadJsonModel(
            final String location,
            final String artifactName,
            final Function<Map<String, Object>, T> decoder
    ) {
        Objects.requireNonNull(decoder, "decoder");
        final String json = readLocation(requireNonBlank(location, artifactName + "Location"));
        return decoder.apply(KernelContractCodec.parseStableJsonObject(json));
    }

    private static String readLocation(final String location) {
        if (location.startsWith(CLASSPATH_PREFIX)) {
            return readClasspathResource(location.substring(CLASSPATH_PREFIX.length()));
        }
        try {
            return Files.readString(Path.of(location));
        } catch (final IOException exception) {
            throw new UncheckedIOException("Failed to read file: " + location, exception);
        }
    }

    private static String readClasspathResource(final String resourceLocation) {
        final String normalized = normalizeClasspathResource(resourceLocation);
        try (InputStream stream = BuiltRailixAppLoader.class.getResourceAsStream(normalized)) {
            if (stream == null) {
                throw new IllegalArgumentException("Classpath resource not found: " + normalized);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException exception) {
            throw new UncheckedIOException("Failed to read classpath resource: " + normalized, exception);
        }
    }

    private static boolean classpathResourceExists(final String resourceLocation) {
        final String normalized = normalizeClasspathResource(resourceLocation.substring(CLASSPATH_PREFIX.length()));
        try (InputStream stream = BuiltRailixAppLoader.class.getResourceAsStream(normalized)) {
            return stream != null;
        } catch (final IOException exception) {
            throw new UncheckedIOException("Failed to read classpath resource: " + normalized, exception);
        }
    }

    private static SettingsTree overrideSettingsTree(
            final String description,
            final Map<RailixPath, SettingsTree.Entry> entries,
            final SettingsTree.SourceLayer sourceLayer
    ) {
        if (entries.isEmpty()) {
            return SettingsTree.empty();
        }
        return new SettingsTree(
                description,
                entries,
                List.of(SettingsTree.Scope.APP),
                List.of(sourceLayer)
        );
    }

    private static RailixPath parseEnvironmentOverridePath(final String variableName) {
        final String encodedPath = requireNonBlank(
                variableName.substring(ENVIRONMENT_OVERRIDE_PREFIX.length()),
                "environmentOverridePath"
        );
        return RailixPath.parse(encodedPath.replace("__", "."));
    }

    private static RailixPath parseSystemPropertyOverridePath(final String propertyName) {
        final String path = requireNonBlank(
                propertyName.substring(SYSTEM_PROPERTY_OVERRIDE_PREFIX.length()),
                "systemPropertyOverridePath"
        );
        return RailixPath.parse(path);
    }

    private static OverrideAssignment parseOverrideAssignment(final String assignment, final String sourceName) {
        final String value = requireNonBlank(assignment, sourceName);
        final int separatorIndex = value.indexOf('=');
        if (separatorIndex < 1) {
            throw new IllegalArgumentException(sourceName + " must use <path>=<value>");
        }
        final RailixPath path = RailixPath.parse(value.substring(0, separatorIndex));
        return new OverrideAssignment(path, value.substring(separatorIndex + 1));
    }

    private static SettingsTree.Entry overrideEntry(
            final RailixPath path,
            final String value,
            final String source
    ) {
        return new SettingsTree.Entry(
                path,
                "string",
                new SettingsTree.PlainValue(new RailixValue.StringValue(value)),
                false,
                false,
                false,
                source,
                SettingsTree.Visibility.NORMAL,
                SettingsTree.Audit.NEVER,
                SettingsTree.OverridePolicy.ALLOW
        );
    }

    private static String normalizeClasspathResource(final String resourceLocation) {
        final String value = requireNonBlank(resourceLocation, "classpathResource");
        return value.startsWith("/") ? value : "/" + value;
    }

    private static boolean isYamlLocation(final String location) {
        final String normalized = requireNonBlank(location, "location").toLowerCase(Locale.ROOT);
        return normalized.endsWith(".yaml") || normalized.endsWith(".yml");
    }

    private static Map<String, Object> parseYamlObject(final String location, final String artifactName) {
        final Object root = new MinimalYamlParser(readLocation(location), location).parse();
        if (root instanceof Map<?, ?> map) {
            return castStringObjectMap(map, artifactName + " root");
        }
        throw new IllegalArgumentException(artifactName + " root must be a map");
    }

    private static AppPlan decodeAuthoringAppPlan(final Map<String, Object> root) {
        final Map<String, Object> app = requiredObject(root, "app", "plan");
        if (app.containsKey("permissions")) {
            throw new IllegalArgumentException("YAML authoring app specs do not support app.permissions yet");
        }
        final List<Object> flows = requiredList(app, "flows", "plan");
        if (flows.size() != 1) {
            throw new IllegalArgumentException("YAML authoring app specs must declare exactly one flow");
        }
        final Map<String, Object> flow = requiredObject(flows.getFirst(), "app.flows[0]", "plan");
        final List<Object> rawSteps = requiredList(flow, "steps", "plan");
        final List<AppPlan.StepInvocation> steps = new ArrayList<>(rawSteps.size());
        for (int index = 0; index < rawSteps.size(); index++) {
            steps.add(decodeStepInvocation(requiredObject(rawSteps.get(index), "app.flows[0].steps[" + index + "]", "plan")));
        }
        return new AppPlan(
                requiredString(app, "id", "plan"),
                requiredString(flow, "id", "plan"),
                requiredString(flow, "trigger", "plan"),
                dev.nanonative.railix.kernel.model.PermissionSet.none(),
                steps,
                decodeConnections(flow.get("connections"))
        );
    }

    private static AppPlan.StepInvocation decodeStepInvocation(final Map<String, Object> step) {
        return new AppPlan.StepInvocation(
                requiredString(step, "id", "step"),
                requiredString(step, "use", "step"),
                optionalObjectValue(step.get("config"), "step.config"),
                decodeNext(step.get("next"))
        );
    }

    private static Map<String, String> decodeNext(final Object rawNext) {
        if (rawNext == null) {
            return Map.of();
        }
        if (rawNext instanceof String nextStepId) {
            if ("end".equals(nextStepId)) {
                return Map.of();
            }
            return Map.of("*", requireNonBlank(nextStepId, "next"));
        }
        final Map<String, Object> mappedNext = requiredObject(rawNext, "next", "step");
        final LinkedHashMap<String, String> next = new LinkedHashMap<>();
        for (final Map.Entry<String, Object> entry : mappedNext.entrySet()) {
            final String outcome = requireNonBlank(entry.getKey(), "next outcome");
            final String target = requiredStringValue(entry.getValue(), "next target");
            if (!"end".equals(target)) {
                next.put(outcome, target);
            }
        }
        return Map.copyOf(next);
    }

    private static List<AppPlan.Connection> decodeConnections(final Object rawConnections) {
        if (rawConnections == null) {
            return List.of();
        }
        if (!(rawConnections instanceof List<?> list)) {
            throw new IllegalArgumentException("app.flows[0].connections must be a list");
        }
        final List<AppPlan.Connection> connections = new ArrayList<>();
        for (int index = 0; index < list.size(); index++) {
            final Map<String, Object> connection = requiredObject(
                    list.get(index),
                    "app.flows[0].connections[" + index + "]",
                    "plan"
            );
            connections.add(new AppPlan.Connection(
                    requiredString(connection, "id", "connection"),
                    decodePortRef(requiredObject(connection, "from", "connection"), "connection.from"),
                    decodePortRef(requiredObject(connection, "to", "connection"), "connection.to"),
                    decodeOperators(connection.get("operators"))
            ));
        }
        return List.copyOf(connections);
    }

    private static AppPlan.PortRef decodePortRef(final Map<String, Object> ref, final String fieldName) {
        return new AppPlan.PortRef(
                requiredString(ref, "stepId", fieldName),
                requiredString(ref, "port", fieldName)
        );
    }

    private static List<AppPlan.OperatorInvocation> decodeOperators(final Object rawOperators) {
        if (rawOperators == null) {
            return List.of();
        }
        if (!(rawOperators instanceof List<?> list)) {
            throw new IllegalArgumentException("connection.operators must be a list");
        }
        final List<AppPlan.OperatorInvocation> operators = new ArrayList<>();
        for (int index = 0; index < list.size(); index++) {
            final Map<String, Object> operator = requiredObject(
                    list.get(index),
                    "connection.operators[" + index + "]",
                    "plan"
            );
            operators.add(new AppPlan.OperatorInvocation(
                    requiredString(operator, "use", "operator"),
                    optionalObjectValue(operator.get("config"), "operator.config")
            ));
        }
        return List.copyOf(operators);
    }

    private static Envelope decodeEnvelope(final Map<String, Object> root) {
        final Map<String, Object> envelope = requiredObject(root, "envelope", "envelope");
        return new Envelope(
                requiredString(envelope, "source", "envelope"),
                requiredString(envelope, "protocol", "envelope"),
                optionalObjectValue(envelope.get("payload"), "envelope.payload"),
                optionalObjectValue(envelope.get("metadata"), "envelope.metadata"),
                optionalRefs(envelope.get("refs"), "envelope.refs"),
                decodeReplyChannel(envelope.get("replyChannel"))
        );
    }

    private static Envelope.ReplyChannel decodeReplyChannel(final Object rawReplyChannel) {
        if (rawReplyChannel == null) {
            return new Envelope.ReplyChannel(false, List.of());
        }
        final Map<String, Object> replyChannel = requiredObject(rawReplyChannel, "replyChannel", "envelope");
        final Object rawSupported = replyChannel.get("supported");
        if (!(rawSupported instanceof Boolean supported)) {
            throw new IllegalArgumentException("replyChannel.supported must be a boolean");
        }
        final List<Object> rawModes = requiredList(replyChannel, "modes", "replyChannel");
        final List<Reply.Mode> modes = new ArrayList<>(rawModes.size());
        for (int index = 0; index < rawModes.size(); index++) {
            final String mode = requiredStringValue(rawModes.get(index), "replyChannel.modes[" + index + "]");
            modes.add(Reply.Mode.valueOf(mode.toUpperCase(Locale.ROOT)));
        }
        return new Envelope.ReplyChannel(supported, modes);
    }

    private static RailixValue.ObjectValue optionalObjectValue(final Object value, final String fieldName) {
        if (value == null) {
            return new RailixValue.ObjectValue(Map.of());
        }
        final Map<String, Object> rawMap = requiredObject(value, fieldName, fieldName);
        final LinkedHashMap<String, RailixValue> values = new LinkedHashMap<>();
        for (final Map.Entry<String, Object> entry : rawMap.entrySet()) {
            values.put(entry.getKey(), toRailixValue(entry.getValue(), fieldName + "." + entry.getKey()));
        }
        return new RailixValue.ObjectValue(values);
    }

    private static Map<String, RailixValue> optionalRefs(final Object value, final String fieldName) {
        if (value == null) {
            return Map.of();
        }
        final Map<String, Object> rawMap = requiredObject(value, fieldName, fieldName);
        final LinkedHashMap<String, RailixValue> refs = new LinkedHashMap<>();
        for (final Map.Entry<String, Object> entry : rawMap.entrySet()) {
            refs.put(entry.getKey(), toRailixValue(entry.getValue(), fieldName + "." + entry.getKey()));
        }
        return Map.copyOf(refs);
    }

    private static RailixValue toRailixValue(final Object value, final String fieldName) {
        if (value == null) {
            return RailixValue.NULL;
        }
        if (value instanceof RailixValue railixValue) {
            return railixValue;
        }
        if (value instanceof Boolean booleanValue) {
            return new RailixValue.BoolValue(booleanValue);
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Double || value instanceof Float || value instanceof BigDecimal) {
            return new RailixValue.NumberValue(new BigDecimal(String.valueOf(value)));
        }
        if (value instanceof String stringValue) {
            return new RailixValue.StringValue(stringValue);
        }
        if (value instanceof List<?> listValue) {
            final ArrayList<RailixValue> values = new ArrayList<>(listValue.size());
            for (int index = 0; index < listValue.size(); index++) {
                values.add(toRailixValue(listValue.get(index), fieldName + "[" + index + "]"));
            }
            return new RailixValue.ListValue(values);
        }
        if (value instanceof Map<?, ?> mapValue) {
            final LinkedHashMap<String, RailixValue> values = new LinkedHashMap<>();
            for (final Map.Entry<?, ?> entry : mapValue.entrySet()) {
                final Object rawKey = entry.getKey();
                if (!(rawKey instanceof String key)) {
                    throw new IllegalArgumentException(fieldName + " map keys must be strings");
                }
                values.put(key, toRailixValue(entry.getValue(), fieldName + "." + key));
            }
            return new RailixValue.ObjectValue(values);
        }
        throw new IllegalArgumentException(fieldName + " contains unsupported YAML value type: " + value.getClass().getName());
    }

    private static Map<String, Object> requiredObject(
            final Map<String, Object> values,
            final String key,
            final String context
    ) {
        return requiredObject(values.get(key), key, context);
    }

    private static Map<String, Object> requiredObject(
            final Object value,
            final String fieldName,
            final String context
    ) {
        if (value instanceof Map<?, ?> map) {
            return castStringObjectMap(map, fieldName);
        }
        throw new IllegalArgumentException(context + " field " + fieldName + " must be a map");
    }

    private static List<Object> requiredList(
            final Map<String, Object> values,
            final String key,
            final String context
    ) {
        final Object value = values.get(key);
        if (value instanceof List<?> list) {
            return List.copyOf(list);
        }
        throw new IllegalArgumentException(context + " field " + key + " must be a list");
    }

    private static String requiredString(
            final Map<String, Object> values,
            final String key,
            final String context
    ) {
        return requiredStringValue(values.get(key), context + " field " + key);
    }

    private static String requiredStringValue(final Object value, final String fieldName) {
        if (value instanceof String stringValue) {
            return requireNonBlank(stringValue, fieldName);
        }
        throw new IllegalArgumentException(fieldName + " must be a string");
    }

    private static Map<String, Object> castStringObjectMap(final Map<?, ?> rawMap, final String fieldName) {
        final LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (final Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException(fieldName + " map keys must be strings");
            }
            map.put(key, entry.getValue());
        }
        return immutableNullableMap(map);
    }

    private static <K, V> Map<K, V> immutableNullableMap(final Map<K, V> values) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    private static <T> List<T> immutableNullableList(final List<T> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static String requireProfileName(final String value) {
        final String profile = requireNonBlank(value, "profileName");
        if (!profile.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("profileName must match [A-Za-z0-9._-]+");
        }
        return profile;
    }

    private static String requireNonBlank(final String value, final String fieldName) {
        Objects.requireNonNull(value, fieldName);
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    private record OverrideAssignment(RailixPath path, String value) {
        private OverrideAssignment {
            path = Objects.requireNonNull(path, "path");
            value = Objects.requireNonNull(value, "value");
        }
    }

    private static final class MinimalYamlParser {
        private final List<YamlLine> lines;
        private final String location;
        private int index;

        private MinimalYamlParser(final String yaml, final String location) {
            this.lines = normalizeLines(yaml, location);
            this.location = Objects.requireNonNull(location, "location");
            this.index = 0;
        }

        private Object parse() {
            if (lines.isEmpty()) {
                throw new IllegalArgumentException("YAML file is empty: " + location);
            }
            final Object root = parseBlock(0);
            if (index != lines.size()) {
                final YamlLine next = lines.get(index);
                throw error(next, "unexpected trailing content");
            }
            return root;
        }

        private Object parseBlock(final int indent) {
            if (index >= lines.size()) {
                throw new IllegalArgumentException("Unexpected end of YAML input in " + location);
            }
            final YamlLine line = lines.get(index);
            if (line.indent() != indent) {
                throw error(line, "expected indentation " + indent + " but found " + line.indent());
            }
            if (line.content().startsWith("- ")) {
                return parseList(indent);
            }
            return parseMap(indent);
        }

        private Map<String, Object> parseMap(final int indent) {
            final LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            while (index < lines.size()) {
                final YamlLine line = lines.get(index);
                if (line.indent() < indent) {
                    break;
                }
                if (line.indent() > indent) {
                    throw error(line, "unexpected indentation inside map");
                }
                if (line.content().startsWith("- ")) {
                    throw error(line, "list item is not valid in a map without a key");
                }
                index++;
                parseMapEntryInto(map, line.content(), indent, line);
            }
            return immutableNullableMap(map);
        }

        private List<Object> parseList(final int indent) {
            final ArrayList<Object> values = new ArrayList<>();
            while (index < lines.size()) {
                final YamlLine line = lines.get(index);
                if (line.indent() < indent) {
                    break;
                }
                if (line.indent() > indent) {
                    throw error(line, "unexpected indentation inside list");
                }
                if (!line.content().startsWith("- ")) {
                    break;
                }
                index++;
                final String item = line.content().substring(2).trim();
                if (item.isEmpty()) {
                    values.add(parseNestedBlock(indent, line, "list item"));
                } else if (findTopLevelColon(item) >= 0) {
                    final LinkedHashMap<String, Object> mapItem = new LinkedHashMap<>();
                    parseMapEntryInto(mapItem, item, indent + 2, line);
                    while (index < lines.size()) {
                        final YamlLine nextLine = lines.get(index);
                        if (nextLine.indent() <= indent) {
                            break;
                        }
                        if (nextLine.indent() != indent + 2) {
                            throw error(nextLine, "unexpected indentation inside list item map");
                        }
                        if (nextLine.content().startsWith("- ")) {
                            throw error(nextLine, "nested list items require an owning key");
                        }
                        index++;
                        parseMapEntryInto(mapItem, nextLine.content(), indent + 2, nextLine);
                    }
                    values.add(immutableNullableMap(mapItem));
                } else {
                    values.add(parseValue(item, line));
                }
            }
            return immutableNullableList(values);
        }

        private void parseMapEntryInto(
                final Map<String, Object> target,
                final String content,
                final int indent,
                final YamlLine line
        ) {
            final int colonIndex = findTopLevelColon(content);
            if (colonIndex < 1) {
                throw error(line, "map entries must use key: value syntax");
            }
            final String key = parseKey(content.substring(0, colonIndex).trim(), line);
            final String remainder = content.substring(colonIndex + 1).trim();
            if (target.containsKey(key)) {
                throw error(line, "duplicate key: " + key);
            }
            if (remainder.isEmpty()) {
                target.put(key, parseNestedBlock(indent, line, key));
                return;
            }
            if ("|".equals(remainder) || ">".equals(remainder)) {
                throw error(line, "block scalar YAML is not supported");
            }
            target.put(key, parseValue(remainder, line));
        }

        private Object parseNestedBlock(final int indent, final YamlLine line, final String fieldName) {
            if (index >= lines.size()) {
                throw error(line, "missing nested block for " + fieldName);
            }
            final YamlLine nextLine = lines.get(index);
            if (nextLine.indent() != indent + 2) {
                throw error(nextLine, "nested block for " + fieldName + " must be indented by 2 spaces");
            }
            return parseBlock(indent + 2);
        }

        private Object parseValue(final String value, final YamlLine line) {
            if (value.startsWith("{")) {
                return parseInlineMap(value, line);
            }
            if (value.startsWith("[")) {
                return parseInlineList(value, line);
            }
            if ("true".equals(value)) {
                return true;
            }
            if ("false".equals(value)) {
                return false;
            }
            if ("null".equals(value)) {
                return null;
            }
            if (isQuoted(value)) {
                return parseQuotedString(value, line);
            }
            if (value.matches("-?(0|[1-9][0-9]*)")) {
                return Long.parseLong(value);
            }
            if (value.matches("-?(0|[1-9][0-9]*)\\.[0-9]+")) {
                return new BigDecimal(value);
            }
            return value;
        }

        private Map<String, Object> parseInlineMap(final String value, final YamlLine line) {
            if (!value.endsWith("}")) {
                throw error(line, "inline map must end with }");
            }
            final String inner = value.substring(1, value.length() - 1).trim();
            final LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            if (inner.isEmpty()) {
                return Map.of();
            }
            for (final String entry : splitTopLevel(inner, ',', line)) {
                parseMapEntryInto(map, entry.trim(), 0, line);
            }
            return immutableNullableMap(map);
        }

        private List<Object> parseInlineList(final String value, final YamlLine line) {
            if (!value.endsWith("]")) {
                throw error(line, "inline list must end with ]");
            }
            final String inner = value.substring(1, value.length() - 1).trim();
            if (inner.isEmpty()) {
                return List.of();
            }
            final ArrayList<Object> values = new ArrayList<>();
            for (final String item : splitTopLevel(inner, ',', line)) {
                values.add(parseValue(item.trim(), line));
            }
            return immutableNullableList(values);
        }

        private String parseKey(final String key, final YamlLine line) {
            final String normalized = key.trim();
            if (normalized.isEmpty()) {
                throw error(line, "map key must not be blank");
            }
            if (isQuoted(normalized)) {
                return parseQuotedString(normalized, line);
            }
            return normalized;
        }

        private static List<YamlLine> normalizeLines(final String yaml, final String location) {
            final ArrayList<YamlLine> lines = new ArrayList<>();
            final String[] rawLines = Objects.requireNonNull(yaml, "yaml").replace("\r\n", "\n").replace('\r', '\n').split("\n");
            for (int index = 0; index < rawLines.length; index++) {
                final String rawLine = rawLines[index];
                if (rawLine.isBlank()) {
                    continue;
                }
                final int indent = countLeadingSpaces(rawLine, index + 1, location);
                final String content = rawLine.substring(indent);
                if (content.startsWith("#")) {
                    continue;
                }
                lines.add(new YamlLine(indent, content, index + 1));
            }
            return List.copyOf(lines);
        }

        private static int countLeadingSpaces(final String rawLine, final int lineNumber, final String location) {
            int indent = 0;
            while (indent < rawLine.length() && rawLine.charAt(indent) == ' ') {
                indent++;
            }
            if (indent < rawLine.length() && rawLine.charAt(indent) == '\t') {
                throw new IllegalArgumentException("Tabs are not supported in YAML at " + location + ":" + lineNumber);
            }
            if ((indent % 2) != 0) {
                throw new IllegalArgumentException("YAML indentation must use 2-space steps at " + location + ":" + lineNumber);
            }
            return indent;
        }

        private static int findTopLevelColon(final String value) {
            return findTopLevelCharacter(value, ':');
        }

        private static List<String> splitTopLevel(final String value, final char delimiter, final YamlLine line) {
            final ArrayList<String> parts = new ArrayList<>();
            int start = 0;
            int depthBraces = 0;
            int depthBrackets = 0;
            boolean inSingleQuote = false;
            boolean inDoubleQuote = false;
            for (int index = 0; index < value.length(); index++) {
                final char character = value.charAt(index);
                if (character == '\'' && !inDoubleQuote) {
                    inSingleQuote = !inSingleQuote;
                } else if (character == '"' && !inSingleQuote && !isEscaped(value, index)) {
                    inDoubleQuote = !inDoubleQuote;
                } else if (!inSingleQuote && !inDoubleQuote) {
                    if (character == '{') {
                        depthBraces++;
                    } else if (character == '}') {
                        depthBraces--;
                    } else if (character == '[') {
                        depthBrackets++;
                    } else if (character == ']') {
                        depthBrackets--;
                    } else if (character == delimiter && depthBraces == 0 && depthBrackets == 0) {
                        parts.add(value.substring(start, index));
                        start = index + 1;
                    }
                }
                if (depthBraces < 0 || depthBrackets < 0) {
                    throw errorStatic(line, "malformed inline YAML structure");
                }
            }
            if (inSingleQuote || inDoubleQuote || depthBraces != 0 || depthBrackets != 0) {
                throw errorStatic(line, "unterminated inline YAML structure");
            }
            parts.add(value.substring(start));
            return List.copyOf(parts);
        }

        private static int findTopLevelCharacter(final String value, final char target) {
            int depthBraces = 0;
            int depthBrackets = 0;
            boolean inSingleQuote = false;
            boolean inDoubleQuote = false;
            for (int index = 0; index < value.length(); index++) {
                final char character = value.charAt(index);
                if (character == '\'' && !inDoubleQuote) {
                    inSingleQuote = !inSingleQuote;
                    continue;
                }
                if (character == '"' && !inSingleQuote && !isEscaped(value, index)) {
                    inDoubleQuote = !inDoubleQuote;
                    continue;
                }
                if (inSingleQuote || inDoubleQuote) {
                    continue;
                }
                if (character == '{') {
                    depthBraces++;
                    continue;
                }
                if (character == '}') {
                    depthBraces--;
                    continue;
                }
                if (character == '[') {
                    depthBrackets++;
                    continue;
                }
                if (character == ']') {
                    depthBrackets--;
                    continue;
                }
                if (character == target && depthBraces == 0 && depthBrackets == 0) {
                    return index;
                }
            }
            return -1;
        }

        private static boolean isQuoted(final String value) {
            return value.length() >= 2
                    && ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'")));
        }

        private static String parseQuotedString(final String value, final YamlLine line) {
            if (!isQuoted(value)) {
                throw errorStatic(line, "quoted string is not terminated");
            }
            if (value.startsWith("'")) {
                return value.substring(1, value.length() - 1);
            }
            final StringBuilder builder = new StringBuilder();
            for (int index = 1; index < value.length() - 1; index++) {
                final char character = value.charAt(index);
                if (character != '\\') {
                    builder.append(character);
                    continue;
                }
                if (index + 1 >= value.length() - 1) {
                    throw errorStatic(line, "invalid escape sequence");
                }
                final char escaped = value.charAt(++index);
                switch (escaped) {
                    case '\\' -> builder.append('\\');
                    case '"' -> builder.append('"');
                    case 'n' -> builder.append('\n');
                    case 'r' -> builder.append('\r');
                    case 't' -> builder.append('\t');
                    default -> throw errorStatic(line, "unsupported escape sequence: \\" + escaped);
                }
            }
            return builder.toString();
        }

        private RuntimeException error(final YamlLine line, final String message) {
            return errorStatic(line, message);
        }

        private static RuntimeException errorStatic(final YamlLine line, final String message) {
            return new IllegalArgumentException("Invalid YAML at line " + line.lineNumber() + ": " + message);
        }

        private static boolean isEscaped(final String value, final int index) {
            int backslashes = 0;
            int cursor = index - 1;
            while (cursor >= 0 && value.charAt(cursor) == '\\') {
                backslashes++;
                cursor--;
            }
            return (backslashes % 2) == 1;
        }
    }

    private record YamlLine(int indent, String content, int lineNumber) {
        private YamlLine {
            content = Objects.requireNonNull(content, "content");
            if (indent < 0) {
                throw new IllegalArgumentException("indent must be >= 0");
            }
            if (lineNumber < 1) {
                throw new IllegalArgumentException("lineNumber must be >= 1");
            }
        }
    }
}
