package dev.nanonative.railix.kernel.runtime;

import dev.nanonative.railix.kernel.model.Envelope;
import dev.nanonative.railix.kernel.model.Patch;
import dev.nanonative.railix.kernel.model.RailixPath;
import dev.nanonative.railix.kernel.model.RailixValue;
import dev.nanonative.railix.kernel.model.Reply;
import dev.nanonative.railix.kernel.model.StepContract;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Trusted Java dependency unit executed by Railix. Implementations declare a reflection-free immutable contract and
 * return all control, data, context, and reply effects explicitly from {@link #execute(ExecutionInput)}.
 */
public interface Step {
    /**
     * @return immutable Step contract; implementations must return semantically identical data for the Step lifetime
     */
    StepContract contract();

    /**
     * Executes one deterministic invocation with no shared kernel state exposed.
     *
     * @param input immutable invocation capabilities and values
     * @return explicit outcome, named outputs, context patches, and optional reply
     */
    Result execute(ExecutionInput input);

    /**
     * Invocation boundary passed to a Step.
     *
     * @param envelope ingress envelope
     * @param context read-only context snapshot
     * @param inputs named values supplied by compiled connections
     * @param config resolved typed configuration including defaults
     * @param settings audited settings view
     * @param resources run-scoped resource ownership
     * @param metrics metric emission scope
     * @param audits audit emission scope
     * @param permissions runtime permission checks
     */
    record ExecutionInput(
            Envelope envelope,
            ContextDoc context,
            Map<String, RailixValue> inputs,
            RailixValue.ObjectValue config,
            SettingsView settings,
            ResourceScope resources,
            MetricScope metrics,
            AuditScope audits,
            PermissionScope permissions
    ) {
        public ExecutionInput {
            envelope = Objects.requireNonNull(envelope, "envelope");
            context = Objects.requireNonNull(context, "context");
            inputs = Map.copyOf(inputs);
            config = Objects.requireNonNull(config, "config");
            settings = Objects.requireNonNull(settings, "settings");
            resources = Objects.requireNonNull(resources, "resources");
            metrics = Objects.requireNonNull(metrics, "metrics");
            audits = Objects.requireNonNull(audits, "audits");
            permissions = Objects.requireNonNull(permissions, "permissions");
        }

        public ExecutionInput(
                final Envelope envelope,
                final ContextDoc context,
                final RailixValue.ObjectValue config,
                final SettingsView settings,
                final ResourceScope resources,
                final MetricScope metrics,
                final AuditScope audits,
                final PermissionScope permissions
        ) {
            this(envelope, context, Map.of(), config, settings, resources, metrics, audits, permissions);
        }

        public ExecutionInput(
                final Envelope envelope,
                final ContextDoc context,
                final RailixValue.ObjectValue config,
                final SettingsView settings,
                final ResourceScope resources,
                final MetricScope metrics,
                final AuditScope audits
        ) {
            this(envelope, context, config, settings, resources, metrics, audits, PermissionScope.none());
        }

        public ExecutionInput(
                final Envelope envelope,
                final ContextDoc context,
                final SettingsView settings,
                final ResourceScope resources,
                final MetricScope metrics,
                final AuditScope audits,
                final PermissionScope permissions
        ) {
            this(envelope, context, emptyConfig(), settings, resources, metrics, audits, permissions);
        }

        public ExecutionInput(
                final Envelope envelope,
                final ContextDoc context,
                final RailixValue.ObjectValue config,
                final SettingsView settings,
                final ResourceScope resources,
                final MetricScope metrics
        ) {
            this(envelope, context, config, settings, resources, metrics, AuditScope.none());
        }

        public ExecutionInput(
                final Envelope envelope,
                final ContextDoc context,
                final SettingsView settings,
                final ResourceScope resources,
                final MetricScope metrics,
                final AuditScope audits
        ) {
            this(envelope, context, emptyConfig(), settings, resources, metrics, audits);
        }

        public ExecutionInput(
                final Envelope envelope,
                final ContextDoc context,
                final RailixValue.ObjectValue config,
                final SettingsView settings,
                final ResourceScope resources
        ) {
            this(envelope, context, config, settings, resources, MetricScope.none(), AuditScope.none());
        }

        public ExecutionInput(
                final Envelope envelope,
                final ContextDoc context,
                final SettingsView settings,
                final ResourceScope resources,
                final MetricScope metrics
        ) {
            this(envelope, context, emptyConfig(), settings, resources, metrics, AuditScope.none());
        }

        public ExecutionInput(final Envelope envelope, final ContextDoc context, final RailixValue.ObjectValue config, final SettingsView settings) {
            this(envelope, context, config, settings, ResourceScope.none(), MetricScope.none(), AuditScope.none());
        }

        public ExecutionInput(final Envelope envelope, final ContextDoc context, final RailixValue.ObjectValue config) {
            this(envelope, context, config, SettingsView.none(), ResourceScope.none(), MetricScope.none(), AuditScope.none());
        }

        public ExecutionInput(final Envelope envelope, final ContextDoc context, final SettingsView settings, final ResourceScope resources) {
            this(envelope, context, emptyConfig(), settings, resources, MetricScope.none(), AuditScope.none());
        }

        public ExecutionInput(final Envelope envelope, final ContextDoc context, final SettingsView settings) {
            this(envelope, context, emptyConfig(), settings, ResourceScope.none(), MetricScope.none(), AuditScope.none());
        }

        public ExecutionInput(final Envelope envelope, final ContextDoc context) {
            this(envelope, context, emptyConfig(), SettingsView.none(), ResourceScope.none(), MetricScope.none(), AuditScope.none());
        }

        /**
         * Reads one required named input.
         *
         * @param name declared input port name
         * @return connected runtime value
         * @throws IllegalArgumentException when the name is blank
         * @throws IllegalStateException when no value was supplied
         */
        public RailixValue requireInput(final String name) {
            final String normalizedName = Objects.requireNonNull(name, "name");
            if (normalizedName.isBlank()) {
                throw new IllegalArgumentException("name must not be blank");
            }
            final RailixValue value = inputs.get(normalizedName);
            if (value == null) {
                throw new IllegalStateException("Missing execution input: " + normalizedName);
            }
            return value;
        }

        private static RailixValue.ObjectValue emptyConfig() {
            return new RailixValue.ObjectValue(Map.of());
        }
    }

    /**
     * Explicit result of one Step invocation.
     *
     * @param outcome declared control outcome
     * @param outputs named values exposed to data connections
     * @param patches context mutations applied atomically after validation
     * @param reply optional protocol reply
     */
    record Result(String outcome, Map<String, RailixValue> outputs, List<Patch> patches, Reply reply) {
        public Result {
            Objects.requireNonNull(outcome, "outcome");
            outputs = Map.copyOf(outputs);
            patches = List.copyOf(patches);
            reply = Objects.requireNonNull(reply, "reply");
        }

        public Result(final String outcome, final Map<String, RailixValue> outputs, final List<Patch> patches) {
            this(outcome, outputs, patches, Reply.none());
        }

        public Result(final String outcome, final List<Patch> patches, final Reply reply) {
            this(outcome, Map.of(), patches, reply);
        }

        public Result(final String outcome, final List<Patch> patches) {
            this(outcome, Map.of(), patches, Reply.none());
        }
    }

    interface SettingsView {
        Optional<ReadValue> read(RailixPath path);

        default ReadValue require(final RailixPath path) {
            return read(path).orElseThrow(() -> new IllegalStateException("Missing setting for execution: " + path));
        }

        static SettingsView none() {
            return path -> Optional.empty();
        }
    }

    interface ResourceScope {
        RegisteredResource register(String refType, RailixValue.ObjectValue metadata, AutoCloseable resource);

        default RegisteredResource registerMetadata(final String refType, final RailixValue.ObjectValue metadata) {
            return register(refType, metadata, () -> {
            });
        }

        default Optional<RegisteredResource> find(final String refId) {
            Objects.requireNonNull(refId, "refId");
            return Optional.empty();
        }

        static ResourceScope none() {
            return new ResourceScope() {
                @Override
                public RegisteredResource register(final String refType, final RailixValue.ObjectValue metadata, final AutoCloseable resource) {
                    throw new IllegalStateException("Resource registration is not available for this execution");
                }

                @Override
                public RegisteredResource registerMetadata(final String refType, final RailixValue.ObjectValue metadata) {
                    throw new IllegalStateException("Resource registration is not available for this execution");
                }

                @Override
                public Optional<RegisteredResource> find(final String refId) {
                    Objects.requireNonNull(refId, "refId");
                    return Optional.empty();
                }
            };
        }
    }

    interface MetricScope {
        void emit(String name, RailixValue value, Map<String, String> labels);

        static MetricScope none() {
            return (name, value, labels) -> {
                throw new IllegalStateException("Metric emission is not available for this execution");
            };
        }
    }

    interface AuditScope {
        void emit(String eventName, RailixValue.ObjectValue data);

        static AuditScope none() {
            return (eventName, data) -> {
                throw new IllegalStateException("Audit emission is not available for this execution");
            };
        }
    }

    interface PermissionScope {
        void require(String permission, String resource);

        static PermissionScope none() {
            return (permission, resource) -> {
                throw new IllegalStateException("Permission checks are not available for this execution");
            };
        }
    }

    record ReadValue(RailixPath path, RailixValue value, boolean materialized, boolean secret) {
        public ReadValue {
            path = Objects.requireNonNull(path, "path");
            value = Objects.requireNonNull(value, "value");
        }
    }

    record RegisteredResource(String refId, String refType, RailixValue.ObjectValue metadata) {
        public RegisteredResource {
            Objects.requireNonNull(refId, "refId");
            if (refId.isBlank()) {
                throw new IllegalArgumentException("refId must not be blank");
            }
            Objects.requireNonNull(refType, "refType");
            if (refType.isBlank()) {
                throw new IllegalArgumentException("refType must not be blank");
            }
            metadata = Objects.requireNonNull(metadata, "metadata");
        }
    }
}
