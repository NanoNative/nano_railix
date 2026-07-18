package dev.nanonative.railix.kernel.runtime;

import dev.nanonative.railix.kernel.model.RailixValue;
import dev.nanonative.railix.kernel.model.PermissionSet;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable executable flow definition. Control edges live in each Step invocation's outcome map; data edges are
 * explicit {@link Connection connections} between named typed ports.
 *
 * @param appId stable application identifier
 * @param flowId stable flow identifier
 * @param triggerStepId first Step invocation
 * @param permissions app-level permission grants
 * @param steps Step invocations in deterministic declaration order
 * @param connections typed data connections in deterministic declaration order
 */
public record AppPlan(
        String appId,
        String flowId,
        String triggerStepId,
        PermissionSet permissions,
        List<StepInvocation> steps,
        List<Connection> connections
) {
    public AppPlan {
        appId = requireNonBlank(appId, "appId");
        flowId = requireNonBlank(flowId, "flowId");
        triggerStepId = requireNonBlank(triggerStepId, "triggerStepId");
        permissions = Objects.requireNonNull(permissions, "permissions");
        steps = List.copyOf(steps);
        connections = List.copyOf(connections);
        final Map<String, StepInvocation> byId = new LinkedHashMap<>();
        for (final StepInvocation step : steps) {
            if (byId.put(step.id(), step) != null) {
                throw new IllegalArgumentException("Duplicate step id: " + step.id());
            }
        }
        if (!byId.containsKey(triggerStepId)) {
            throw new IllegalArgumentException("Unknown trigger step id: " + triggerStepId);
        }
        for (final StepInvocation step : steps) {
            for (final String target : step.next().values()) {
                if (!byId.containsKey(target)) {
                    throw new IllegalArgumentException("Unknown next step id: " + target);
                }
            }
        }
        final Map<String, Connection> connectionsById = new LinkedHashMap<>();
        for (final Connection connection : connections) {
            if (connectionsById.put(connection.id(), connection) != null) {
                throw new IllegalArgumentException("Duplicate connection id: " + connection.id());
            }
            if (!byId.containsKey(connection.from().stepId())) {
                throw new IllegalArgumentException("Unknown connection source step id: " + connection.from().stepId());
            }
            if (!byId.containsKey(connection.to().stepId())) {
                throw new IllegalArgumentException("Unknown connection target step id: " + connection.to().stepId());
            }
        }
    }

    /**
     * Creates a plan without typed data connections.
     *
     * @param appId application identifier
     * @param flowId flow identifier
     * @param triggerStepId first Step invocation
     * @param permissions app-level permissions
     * @param steps Step invocations
     */
    public AppPlan(
            final String appId,
            final String flowId,
            final String triggerStepId,
            final PermissionSet permissions,
            final List<StepInvocation> steps
    ) {
        this(appId, flowId, triggerStepId, permissions, steps, List.of());
    }

    /**
     * Creates a plan without typed data connections or app-level permissions.
     *
     * @param appId application identifier
     * @param flowId flow identifier
     * @param triggerStepId first Step invocation
     * @param steps Step invocations
     */
    public AppPlan(final String appId, final String flowId, final String triggerStepId, final List<StepInvocation> steps) {
        this(appId, flowId, triggerStepId, PermissionSet.none(), steps, List.of());
    }

    /**
     * Resolves a Step invocation by its plan-local identifier.
     *
     * @param stepId plan-local Step identifier
     * @return matching invocation
     * @throws IllegalArgumentException when the identifier is unknown
     */
    public StepInvocation step(final String stepId) {
        for (final StepInvocation step : steps) {
            if (step.id().equals(stepId)) {
                return step;
            }
        }
        throw new IllegalArgumentException("Unknown step id: " + stepId);
    }

    /**
     * Resolves the next control Step for an outcome, falling back to the {@code *} transition.
     *
     * @param stepId completed Step identifier
     * @param outcome completed Step outcome
     * @return next Step identifier, or empty when the flow ends
     */
    public Optional<String> nextStepId(final String stepId, final String outcome) {
        final Map<String, String> next = step(stepId).next();
        if (next.containsKey(outcome)) {
            return Optional.of(next.get(outcome));
        }
        if (next.containsKey("*")) {
            return Optional.of(next.get("*"));
        }
        return Optional.empty();
    }

    /**
     * @param id plan-local Step identifier
     * @param use dependency identifier resolved to a trusted Java Step
     * @param config declared configuration before typed defaults are materialized
     * @param next outcome-to-Step control transitions; {@code *} is the fallback outcome
     */
    public record StepInvocation(String id, String use, RailixValue.ObjectValue config, Map<String, String> next) {
        public StepInvocation {
            id = requireNonBlank(id, "id");
            use = requireNonBlank(use, "use");
            config = Objects.requireNonNull(config, "config");
            next = Map.copyOf(next);
            for (final Map.Entry<String, String> entry : next.entrySet()) {
                requireNonBlank(entry.getKey(), "outcome");
                requireNonBlank(entry.getValue(), "next step id");
            }
        }

        public StepInvocation(final String id, final String use, final Map<String, String> next) {
            this(id, use, new RailixValue.ObjectValue(Map.of()), next);
        }
    }

    /**
     * Ordered data edge between one source output and one target input.
     *
     * @param id plan-local connection identifier
     * @param from source Step output
     * @param to target Step input
     * @param operators pure built-in operator pipeline applied in declaration order
     */
    public record Connection(String id, PortRef from, PortRef to, List<OperatorInvocation> operators) {
        public Connection {
            id = requireNonBlank(id, "connection id");
            from = Objects.requireNonNull(from, "from");
            to = Objects.requireNonNull(to, "to");
            operators = List.copyOf(operators);
        }
    }

    /**
     * @param stepId plan-local Step invocation identifier
     * @param port named input or output port
     */
    public record PortRef(String stepId, String port) {
        public PortRef {
            stepId = requireNonBlank(stepId, "portRef.stepId");
            port = requireNonBlank(port, "portRef.port");
        }
    }

    /**
     * @param use stable built-in operator identifier
     * @param config operator configuration object
     */
    public record OperatorInvocation(String use, RailixValue.ObjectValue config) {
        public OperatorInvocation {
            use = requireNonBlank(use, "operator use");
            config = Objects.requireNonNull(config, "config");
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
