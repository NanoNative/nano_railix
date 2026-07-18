package dev.nanonative.railix.kernel.runtime;

import dev.nanonative.railix.kernel.model.RailixValue;
import dev.nanonative.railix.kernel.model.Shape;
import dev.nanonative.railix.kernel.model.ShapeCompatibility;
import dev.nanonative.railix.kernel.model.StepContract;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

final class ConnectionPlan {

    private static final String TRIM = "railix.op.string.trim";
    private static final String LOWERCASE = "railix.op.string.lowercase";
    private final Map<String, Step> steps;
    private final Map<String, StepContract> contracts;
    private final Map<String, List<CompiledConnection>> incoming;
    private final Map<String, RailixValue.ObjectValue> configs;
    private final Set<String> outputSources;
    private final Optional<StepResolver> lazyResolver;

    private ConnectionPlan(
            final Map<String, Step> steps,
            final Map<String, StepContract> contracts,
            final Map<String, List<CompiledConnection>> incoming,
            final Map<String, RailixValue.ObjectValue> configs,
            final Set<String> outputSources,
            final Optional<StepResolver> lazyResolver
    ) {
        this.steps = Map.copyOf(steps);
        this.contracts = Map.copyOf(contracts);
        final Map<String, List<CompiledConnection>> copiedIncoming = new LinkedHashMap<>();
        incoming.forEach((stepId, connections) -> copiedIncoming.put(stepId, List.copyOf(connections)));
        this.incoming = Map.copyOf(copiedIncoming);
        this.configs = Map.copyOf(configs);
        this.outputSources = Set.copyOf(outputSources);
        this.lazyResolver = Objects.requireNonNull(lazyResolver, "lazyResolver");
    }

    static ConnectionPlan forExecution(final AppPlan plan, final StepResolver resolver) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(resolver, "resolver");
        if (!plan.connections().isEmpty()) {
            return compile(plan, resolver);
        }
        final Map<String, Step> resolvedByUse = new LinkedHashMap<>();
        for (final AppPlan.StepInvocation invocation : plan.steps()) {
            if (resolvedByUse.containsKey(invocation.use())) {
                continue;
            }
            final Step step;
            try {
                step = resolver.resolve(invocation.use());
            } catch (final RuntimeException exception) {
                return lazy(resolver);
            }
            if (step == null) {
                return lazy(resolver);
            }
            resolvedByUse.put(invocation.use(), step);
        }
        return compile(plan, resolvedByUse::get);
    }

    private static ConnectionPlan lazy(final StepResolver resolver) {
        return new ConnectionPlan(Map.of(), Map.of(), Map.of(), Map.of(), Set.of(), Optional.of(resolver));
    }

    static ConnectionPlan compile(final AppPlan plan, final StepResolver resolver) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(resolver, "resolver");
        final Map<String, Step> steps = new LinkedHashMap<>();
        final Map<String, StepContract> contracts = new LinkedHashMap<>();
        final Map<String, RailixValue.ObjectValue> configs = new LinkedHashMap<>();
        for (final AppPlan.StepInvocation invocation : plan.steps()) {
            final Step step = Objects.requireNonNull(
                    resolver.resolve(invocation.use()),
                    "No step resolved for invocation: " + invocation.id() + " [" + invocation.use() + "]"
            );
            final StepContract contract = Objects.requireNonNull(
                    step.contract(),
                    "No contract declared for invocation: " + invocation.id() + " [" + invocation.use() + "]"
            );
            if (!contract.id().equals(invocation.use())) {
                throw new IllegalArgumentException("step " + invocation.id() + " resolved use " + invocation.use()
                        + " to contract " + contract.id());
            }
            steps.put(invocation.id(), step);
            contracts.put(invocation.id(), contract);
            configs.put(invocation.id(), resolveConfig(invocation, contract));
        }

        final Map<String, List<CompiledConnection>> incoming = new LinkedHashMap<>();
        final Map<String, String> targetOwners = new LinkedHashMap<>();
        final Map<String, String> targetSources = new LinkedHashMap<>();
        final Set<String> outputSources = new LinkedHashSet<>();
        for (final AppPlan.Connection connection : plan.connections()) {
            final Step sourceStep = steps.get(connection.from().stepId());
            final Step targetStep = steps.get(connection.to().stepId());
            if (!plan.step(connection.from().stepId()).next().containsValue(connection.to().stepId())) {
                throw new IllegalArgumentException("connection " + connection.id()
                        + " source step " + connection.from().stepId()
                        + " must transition directly to target step " + connection.to().stepId());
            }
            if (connection.to().stepId().equals(plan.triggerStepId())) {
                throw new IllegalArgumentException("connected target step " + connection.to().stepId()
                        + " cannot be the flow trigger");
            }
            final String previousSource = targetSources.putIfAbsent(
                    connection.to().stepId(),
                    connection.from().stepId()
            );
            if (previousSource != null && !previousSource.equals(connection.from().stepId())) {
                throw new IllegalArgumentException("connected target step " + connection.to().stepId()
                        + " cannot consume inputs from multiple source steps");
            }
            final StepContract sourceContract = contracts.get(connection.from().stepId());
            final StepContract targetContract = contracts.get(connection.to().stepId());
            requireTypedContract(sourceContract, connection.id(), "source");
            requireTypedContract(targetContract, connection.id(), "target");
            final StepContract.Port sourcePort = requirePort(
                    sourceContract.outputs(),
                    connection.from().port(),
                    connection.id(),
                    "source output"
            );
            final StepContract.Port targetPort = requirePort(
                    targetContract.inputs(),
                    connection.to().port(),
                    connection.id(),
                    "target input"
            );
            final String targetKey = connection.to().stepId() + "/" + connection.to().port();
            final String previousOwner = targetOwners.put(targetKey, connection.id());
            if (previousOwner != null) {
                throw new IllegalArgumentException("connection " + connection.id()
                        + " duplicates target " + targetKey + " already owned by connection " + previousOwner);
            }

            Shape currentShape = sourcePort.shape();
            final List<BuiltinOperator> operators = new ArrayList<>();
            for (final AppPlan.OperatorInvocation invocation : connection.operators()) {
                final BuiltinOperator operator = BuiltinOperator.resolve(invocation, connection.id());
                ShapeCompatibility.requireAssignable(
                        currentShape,
                        operator.inputShape(),
                        "connection " + connection.id() + " operator " + invocation.use()
                );
                currentShape = operator.outputShape();
                operators.add(operator);
            }
            ShapeCompatibility.requireAssignable(
                    currentShape,
                    targetPort.shape(),
                    "connection " + connection.id() + " target " + targetKey
            );
            incoming.computeIfAbsent(connection.to().stepId(), ignored -> new ArrayList<>()).add(
                    new CompiledConnection(connection, sourcePort, targetPort, operators)
            );
            outputSources.add(connection.from().stepId());
        }

        for (final Map.Entry<String, StepContract> stepEntry : contracts.entrySet()) {
            final StepContract contract = stepEntry.getValue();
            if (contract.contractVersion() != StepContract.CURRENT_CONTRACT_VERSION) {
                continue;
            }
            final List<CompiledConnection> stepConnections = incoming.getOrDefault(stepEntry.getKey(), List.of());
            for (final StepContract.Port input : contract.inputs()) {
                if (input.required() && stepConnections.stream().noneMatch(connection -> connection.targetPort().name().equals(input.name()))) {
                    throw new IllegalArgumentException("step " + stepEntry.getKey()
                            + " requires input " + input.name() + " but no connection supplies it");
                }
            }
        }
        for (final Map.Entry<String, String> targetSource : targetSources.entrySet()) {
            for (final AppPlan.StepInvocation possiblePredecessor : plan.steps()) {
                if (possiblePredecessor.next().containsValue(targetSource.getKey())
                        && !possiblePredecessor.id().equals(targetSource.getValue())) {
                    throw new IllegalArgumentException("connected target step " + targetSource.getKey()
                            + " has an alternate predecessor " + possiblePredecessor.id());
                }
            }
        }
        return new ConnectionPlan(steps, contracts, incoming, configs, outputSources, Optional.empty());
    }

    Step step(final AppPlan.StepInvocation invocation) {
        if (lazyResolver.isPresent()) {
            return lazyResolver.orElseThrow().resolve(invocation.use());
        }
        return steps.get(invocation.id());
    }

    StepContract contract(final AppPlan.StepInvocation invocation, final Step step) {
        if (lazyResolver.isPresent()) {
            final StepContract contract = Objects.requireNonNull(
                    step.contract(),
                    "No contract declared for invocation: " + invocation.id()
            );
            if (!contract.id().equals(invocation.use())) {
                throw new IllegalArgumentException("step " + invocation.id() + " resolved use " + invocation.use()
                        + " to contract " + contract.id());
            }
            return contract;
        }
        return Objects.requireNonNull(contracts.get(invocation.id()), "No compiled contract for: " + invocation.id());
    }

    RailixValue.ObjectValue config(final AppPlan.StepInvocation invocation) {
        if (lazyResolver.isPresent()) {
            return invocation.config();
        }
        return configs.get(invocation.id());
    }

    Map<String, RailixValue> inputs(
            final String stepId,
            final Map<String, Map<String, RailixValue>> outputsByStep
    ) {
        final Map<String, RailixValue> inputs = new LinkedHashMap<>();
        for (final CompiledConnection connection : incoming.getOrDefault(stepId, List.of())) {
            final Map<String, RailixValue> sourceOutputs = Objects.requireNonNull(
                    outputsByStep.get(connection.sourceStepId()),
                    "connection " + connection.id() + " source step has not produced outputs: "
                            + connection.sourceStepId()
            );
            RailixValue value = sourceOutputs.get(connection.sourcePort().name());
            if (value == null) {
                throw new IllegalStateException("connection " + connection.id()
                        + " source step omitted output " + connection.sourcePort().name());
            }
            for (final BuiltinOperator operator : connection.operators()) {
                value = operator.apply(value, connection.id());
            }
            ShapeCompatibility.requireValue(
                    value,
                    connection.targetPort().shape(),
                    "connection " + connection.id() + " target input " + connection.targetPort().name()
            );
            inputs.put(connection.targetPort().name(), value);
        }
        return Map.copyOf(inputs);
    }

    void validateOutputs(final String stepId, final Step.Result result) {
        if (lazyResolver.isPresent()) {
            return;
        }
        final StepContract contract = contracts.get(stepId);
        if (contract.contractVersion() != StepContract.CURRENT_CONTRACT_VERSION) {
            return;
        }
        final Map<String, StepContract.Port> ports = new LinkedHashMap<>();
        contract.outputs().forEach(port -> ports.put(port.name(), port));
        for (final StepContract.Port port : contract.outputs()) {
            final RailixValue value = result.outputs().get(port.name());
            if (value == null) {
                if (port.required()) {
                    throw new IllegalStateException("step " + stepId
                            + " omitted required output " + port.name());
                }
                continue;
            }
            ShapeCompatibility.requireValue(value, port.shape(), "step " + stepId + " output " + port.name());
        }
        for (final String outputName : new TreeSet<>(result.outputs().keySet())) {
            if (!ports.containsKey(outputName)) {
                throw new IllegalStateException("step " + stepId
                        + " returned undeclared output " + outputName);
            }
        }
    }

    boolean capturesOutputs(final String stepId) {
        return outputSources.contains(stepId);
    }

    private static void requireTypedContract(
            final StepContract contract,
            final String connectionId,
            final String role
    ) {
        if (contract.contractVersion() != StepContract.CURRENT_CONTRACT_VERSION) {
            throw new IllegalArgumentException("connection " + connectionId + " " + role
                    + " step must use Step Contract " + StepContract.CURRENT_CONTRACT_VERSION);
        }
    }

    private static StepContract.Port requirePort(
            final List<StepContract.Port> ports,
            final String name,
            final String connectionId,
            final String role
    ) {
        return ports.stream()
                .filter(port -> port.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("connection " + connectionId
                        + " references unknown " + role + " port " + name));
    }

    private static RailixValue.ObjectValue resolveConfig(
            final AppPlan.StepInvocation invocation,
            final StepContract contract
    ) {
        if (contract.contractVersion() != StepContract.CURRENT_CONTRACT_VERSION) {
            return invocation.config();
        }
        final Map<String, RailixValue> resolved = new LinkedHashMap<>(invocation.config().values());
        for (final String key : new TreeSet<>(resolved.keySet())) {
            if (!contract.config().containsKey(key)) {
                throw new IllegalArgumentException("step " + invocation.id()
                        + " has undeclared config field " + key);
            }
        }
        for (final String fieldName : new TreeSet<>(contract.config().keySet())) {
            final StepContract.ConfigField field = contract.config().get(fieldName);
            RailixValue value = resolved.get(fieldName);
            if (value == null) {
                if (field.defaultValue().present()) {
                    value = field.defaultValue().value();
                    resolved.put(fieldName, value);
                } else if (field.required()) {
                    throw new IllegalArgumentException("step " + invocation.id()
                            + " requires config field " + fieldName);
                } else {
                    continue;
                }
            }
            ShapeCompatibility.requireValue(
                    value,
                    field.shape(),
                    "step " + invocation.id() + " config " + fieldName
            );
        }
        return new RailixValue.ObjectValue(resolved);
    }

    private record CompiledConnection(
            AppPlan.Connection connection,
            StepContract.Port sourcePort,
            StepContract.Port targetPort,
            List<BuiltinOperator> operators
    ) {
        private CompiledConnection {
            connection = Objects.requireNonNull(connection, "connection");
            sourcePort = Objects.requireNonNull(sourcePort, "sourcePort");
            targetPort = Objects.requireNonNull(targetPort, "targetPort");
            operators = List.copyOf(operators);
        }

        private String id() {
            return connection.id();
        }

        private String sourceStepId() {
            return connection.from().stepId();
        }
    }

    private enum BuiltinOperator {
        STRING_TRIM(TRIM),
        STRING_LOWERCASE(LOWERCASE);

        private final String use;

        BuiltinOperator(final String use) {
            this.use = use;
        }

        private static BuiltinOperator resolve(
                final AppPlan.OperatorInvocation invocation,
                final String connectionId
        ) {
            if (!invocation.config().values().isEmpty()) {
                throw new IllegalArgumentException("connection " + connectionId + " operator "
                        + invocation.use() + " does not accept config");
            }
            for (final BuiltinOperator operator : values()) {
                if (operator.use.equals(invocation.use())) {
                    return operator;
                }
            }
            throw new IllegalArgumentException("connection " + connectionId
                    + " uses unsupported operator " + invocation.use());
        }

        private Shape inputShape() {
            return Shape.string();
        }

        private Shape outputShape() {
            return Shape.string();
        }

        private RailixValue apply(final RailixValue value, final String connectionId) {
            ShapeCompatibility.requireValue(value, inputShape(), "connection " + connectionId + " operator " + use);
            final String string = ((RailixValue.StringValue) value).value();
            return switch (this) {
                case STRING_TRIM -> new RailixValue.StringValue(string.strip());
                case STRING_LOWERCASE -> new RailixValue.StringValue(string.toLowerCase(Locale.ROOT));
            };
        }
    }
}
