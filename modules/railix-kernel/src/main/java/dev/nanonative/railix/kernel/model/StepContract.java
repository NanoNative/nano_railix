package dev.nanonative.railix.kernel.model;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Versioned, reflection-free declaration of a Step's data ports, configuration, outcomes, and execution semantics.
 * Contract version {@value #CURRENT_CONTRACT_VERSION} is the typed contract used by compiled connections; version
 * {@value #LEGACY_CONTRACT_VERSION} preserves persisted pre-typed Step packs.
 *
 * @param id stable dependency identifier resolved by an app plan
 * @param version Step implementation version
 * @param displayName human-readable name
 * @param description human-readable behavior description
 * @param kind Step execution role
 * @param inputs named data inputs
 * @param outputs named data outputs
 * @param outcomes declared control outcomes
 * @param settings requested settings
 * @param permissions requested permissions
 * @param timeout execution timeout declaration
 * @param retryPolicy retry declaration
 * @param cachePolicy cache declaration
 * @param resources resource limits
 * @param metrics emitted metric declarations
 * @param ui presentation metadata
 * @param contractVersion Step contract schema version
 * @param config named typed configuration fields
 * @param semantics deterministic effects, state, idempotency, and concurrency declarations
 */
public record StepContract(
        String id,
        String version,
        String displayName,
        String description,
        Kind kind,
        List<Port> inputs,
        List<Port> outputs,
        Map<String, Outcome> outcomes,
        Settings settings,
        PermissionSet permissions,
        Timeout timeout,
        RetryPolicy retryPolicy,
        CachePolicy cachePolicy,
        Resources resources,
        Metrics metrics,
        Map<String, RailixValue> ui,
        int contractVersion,
        Map<String, ConfigField> config,
        Semantics semantics
) {

    /** Pre-typed compatibility contract version. */
    public static final int LEGACY_CONTRACT_VERSION = 0;

    /** Current typed Step contract version. */
    public static final int CURRENT_CONTRACT_VERSION = 1;

    public StepContract {
        id = requireNonBlank(id, "id");
        version = requireNonBlank(version, "version");
        displayName = requireNonBlank(displayName, "displayName");
        description = requireNonBlank(description, "description");
        kind = Objects.requireNonNull(kind, "kind");
        inputs = List.copyOf(inputs);
        outputs = List.copyOf(outputs);
        outcomes = Map.copyOf(outcomes);
        settings = Objects.requireNonNull(settings, "settings");
        permissions = Objects.requireNonNull(permissions, "permissions");
        timeout = Objects.requireNonNull(timeout, "timeout");
        retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
        cachePolicy = Objects.requireNonNull(cachePolicy, "cachePolicy");
        resources = Objects.requireNonNull(resources, "resources");
        metrics = Objects.requireNonNull(metrics, "metrics");
        ui = Map.copyOf(ui);
        if (contractVersion < LEGACY_CONTRACT_VERSION || contractVersion > CURRENT_CONTRACT_VERSION) {
            throw new IllegalArgumentException("Unsupported contractVersion: " + contractVersion);
        }
        config = Map.copyOf(config);
        semantics = Objects.requireNonNull(semantics, "semantics");
        requireUniquePorts(inputs, "input");
        requireUniquePorts(outputs, "output");
    }

    /**
     * Creates a compatibility contract for Step packs persisted before typed ports and semantics were introduced.
     * Legacy port type strings are deterministically mapped to their nearest structural {@link Shape}.
     *
     * @param id stable dependency identifier
     * @param version Step implementation version
     * @param displayName human-readable name
     * @param description human-readable description
     * @param kind Step role
     * @param inputs legacy input declarations
     * @param outputs legacy output declarations
     * @param outcomes control outcomes
     * @param settings requested settings
     * @param permissions requested permissions
     * @param timeout timeout declaration
     * @param retryPolicy retry declaration
     * @param cachePolicy cache declaration
     * @param resources resource declaration
     * @param metrics emitted metrics
     * @param ui presentation metadata
     */
    public StepContract(
            final String id,
            final String version,
            final String displayName,
            final String description,
            final Kind kind,
            final List<Port> inputs,
            final List<Port> outputs,
            final Map<String, Outcome> outcomes,
            final Settings settings,
            final PermissionSet permissions,
            final Timeout timeout,
            final RetryPolicy retryPolicy,
            final CachePolicy cachePolicy,
            final Resources resources,
            final Metrics metrics,
            final Map<String, RailixValue> ui
    ) {
        this(
                id,
                version,
                displayName,
                description,
                kind,
                inputs,
                outputs,
                outcomes,
                settings,
                permissions,
                timeout,
                retryPolicy,
                cachePolicy,
                resources,
                metrics,
                ui,
                LEGACY_CONTRACT_VERSION,
                Map.of(),
                Semantics.undeclared()
        );
    }

    /**
     * Creates a pure typed Step Contract 1.0 with no typed configuration fields.
     *
     * @param id stable dependency identifier
     * @param version Step implementation version
     * @param displayName human-readable name
     * @param description human-readable behavior description
     * @param kind Step role
     * @param inputs typed input ports
     * @param outputs typed output ports
     * @param outcomes declared control outcomes
     * @return immutable typed contract
     */
    public static StepContract typed(
            final String id,
            final String version,
            final String displayName,
            final String description,
            final Kind kind,
            final List<Port> inputs,
            final List<Port> outputs,
            final Map<String, Outcome> outcomes
    ) {
        return typed(
                id,
                version,
                displayName,
                description,
                kind,
                inputs,
                outputs,
                Map.of(),
                outcomes,
                Semantics.pure()
        );
    }

    /**
     * Creates a typed Step Contract 1.0 with explicit configuration and execution semantics.
     *
     * @param id stable dependency identifier
     * @param version Step implementation version
     * @param displayName human-readable name
     * @param description human-readable behavior description
     * @param kind Step role
     * @param inputs typed input ports
     * @param outputs typed output ports
     * @param config typed configuration fields
     * @param outcomes declared control outcomes
     * @param semantics execution semantics
     * @return immutable typed contract
     */
    public static StepContract typed(
            final String id,
            final String version,
            final String displayName,
            final String description,
            final Kind kind,
            final List<Port> inputs,
            final List<Port> outputs,
            final Map<String, ConfigField> config,
            final Map<String, Outcome> outcomes,
            final Semantics semantics
    ) {
        return new StepContract(
                id,
                version,
                displayName,
                description,
                kind,
                inputs,
                outputs,
                outcomes,
                new Settings(List.of()),
                PermissionSet.none(),
                new Timeout(Duration.ofSeconds(30)),
                new RetryPolicy(1, Duration.ZERO),
                new CachePolicy(CachePolicy.Mode.NONE, "", Duration.ZERO),
                new Resources(new Limits(RailixValue.NULL, RailixValue.NULL)),
                new Metrics(List.of()),
                Map.of(),
                CURRENT_CONTRACT_VERSION,
                config,
                semantics
        );
    }

    public enum Kind { NORMAL, TRIGGER, CONTROLLER, ADAPTER }

    /**
     * Named Step data port. The legacy fields remain serialized for compatibility; {@code shape} is authoritative for
     * Step Contract 1.0 compilation and runtime validation.
     *
     * @param name port name unique within its input or output collection
     * @param type legacy type spelling
     * @param binding legacy binding metadata
     * @param required whether the value must be supplied
     * @param values legacy constrained values
     * @param shape structural Railix value shape
     */
    public record Port(String name, String type, String binding, boolean required, List<String> values, Shape shape) {
        public Port {
            name = requireNonBlank(name, "port.name");
            type = requireNonBlank(type, "port.type");
            binding = Objects.requireNonNull(binding, "port.binding");
            values = List.copyOf(values);
            shape = Objects.requireNonNull(shape, "port.shape");
        }

        /**
         * Creates a legacy-compatible port and infers its structural shape from the type spelling.
         *
         * @param name port name
         * @param type legacy type spelling
         * @param binding legacy binding metadata
         * @param required whether the value is required
         * @param values legacy constrained values
         */
        public Port(
                final String name,
                final String type,
                final String binding,
                final boolean required,
                final List<String> values
        ) {
            this(name, type, binding, required, values, legacyShape(type));
        }

        /**
         * Creates a typed Step Contract 1.0 port.
         *
         * @param name port name
         * @param shape structural value shape
         * @param required whether the value must be supplied
         */
        public Port(final String name, final Shape shape, final boolean required) {
            this(name, ShapeCompatibility.describe(shape), "", required, List.of(), shape);
        }
    }

    public record Outcome(String description) {}

    /**
     * @param shape accepted runtime value shape
     * @param required whether the resolved configuration must contain the field
     * @param defaultValue explicit default state, including an explicit null default
     */
    public record ConfigField(Shape shape, boolean required, DefaultValue defaultValue) {
        public ConfigField {
            shape = Objects.requireNonNull(shape, "shape");
            defaultValue = Objects.requireNonNull(defaultValue, "defaultValue");
            if (defaultValue.present()) {
                ShapeCompatibility.requireValue(defaultValue.value(), shape, "config default");
            }
        }
    }

    /**
     * Distinguishes an absent default from a present default whose value is explicit JSON null.
     *
     * @param present whether a default is declared
     * @param value default value, or {@link RailixValue#NULL} when absent
     */
    public record DefaultValue(boolean present, RailixValue value) {
        public DefaultValue {
            value = present ? Objects.requireNonNull(value, "value") : RailixValue.NULL;
        }

        /**
         * @return canonical absent default
         */
        public static DefaultValue none() {
            return new DefaultValue(false, RailixValue.NULL);
        }

        /**
         * @param value declared default, including {@link RailixValue#NULL}
         * @return present default
         */
        public static DefaultValue of(final RailixValue value) {
            return new DefaultValue(true, value);
        }
    }

    /**
     * Machine-readable execution properties used by validation, scheduling, and future sandbox/state layers.
     *
     * @param determinism determinism declaration
     * @param effects external effects declaration
     * @param stateScope state ownership declaration
     * @param idempotency idempotency declaration
     * @param concurrency safe invocation concurrency
     */
    public record Semantics(
            Determinism determinism,
            Effects effects,
            StateScope stateScope,
            Idempotency idempotency,
            Concurrency concurrency
    ) {
        public Semantics {
            determinism = Objects.requireNonNull(determinism, "determinism");
            effects = Objects.requireNonNull(effects, "effects");
            stateScope = Objects.requireNonNull(stateScope, "stateScope");
            idempotency = Objects.requireNonNull(idempotency, "idempotency");
            concurrency = Objects.requireNonNull(concurrency, "concurrency");
        }

        /**
         * @return deterministic, side-effect-free, stateless, intrinsically idempotent, reentrant semantics
         */
        public static Semantics pure() {
            return new Semantics(
                    Determinism.DETERMINISTIC,
                    Effects.PURE,
                    StateScope.NONE,
                    Idempotency.INTRINSIC,
                    Concurrency.REENTRANT
            );
        }

        /**
         * @return compatibility semantics for legacy contracts that made no machine-readable declaration
         */
        public static Semantics undeclared() {
            return new Semantics(
                    Determinism.UNDECLARED,
                    Effects.UNDECLARED,
                    StateScope.UNDECLARED,
                    Idempotency.UNDECLARED,
                    Concurrency.UNDECLARED
            );
        }
    }

    public enum Determinism { UNDECLARED, DETERMINISTIC, NONDETERMINISTIC }

    public enum Effects { UNDECLARED, PURE, READ_EXTERNAL, WRITE_EXTERNAL }

    public enum StateScope { UNDECLARED, NONE, EXTERNAL, AFFINED }

    public enum Idempotency { UNDECLARED, INTRINSIC, KEYED, NONE }

    public enum Concurrency { UNDECLARED, REENTRANT, SERIALIZED }

    public record Settings(List<String> requested) {
        public Settings {
            requested = List.copyOf(requested);
        }
    }

    public record Timeout(Duration defaultValue) {}

    public record RetryPolicy(int maxAttempts, Duration backoff) {}

    public record CachePolicy(Mode mode, String key, Duration ttl) {
        public enum Mode { NONE, BY_INPUT, BY_KEY, TTL, IDEMPOTENCY_KEY, MANUAL, ARTIFACT_ONLY }
    }

    public record Resources(Limits limits) {}

    public record Limits(RailixValue memory, RailixValue cpu) {}

    public record Metrics(List<MetricDefinition> emitted) {
        public Metrics {
            emitted = List.copyOf(emitted);
        }
    }

    public record MetricDefinition(String name, String type, String unit, List<String> labels) {
        public MetricDefinition {
            labels = List.copyOf(labels);
        }
    }

    private static Shape legacyShape(final String type) {
        return switch (requireNonBlank(type, "port.type")) {
            case "bool", "boolean" -> Shape.bool();
            case "number" -> Shape.number();
            case "string", "enum", "duration" -> Shape.string();
            case "list", "patch-list", "mapping-list" -> Shape.list(Shape.any());
            case "document", "object", "envelope" -> Shape.openObject();
            case "file-ref" -> new Shape.RefShape(Shape.Kind.FILE_REF, new Shape.ObjectShape(Map.of(), true));
            default -> Shape.any();
        };
    }

    private static void requireUniquePorts(final List<Port> ports, final String kind) {
        final Set<String> names = new LinkedHashSet<>();
        for (final Port port : ports) {
            if (!names.add(port.name())) {
                throw new IllegalArgumentException("Duplicate " + kind + " port: " + port.name());
            }
        }
    }

    private static String requireNonBlank(final String value, final String fieldName) {
        Objects.requireNonNull(value, fieldName);
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
