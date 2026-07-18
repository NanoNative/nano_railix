package dev.nanonative.railix.kernel.runtime;

import dev.nanonative.railix.kernel.model.Envelope;
import dev.nanonative.railix.kernel.model.KernelContractCodec;
import dev.nanonative.railix.kernel.model.PermissionSet;
import dev.nanonative.railix.kernel.model.RailixPath;
import dev.nanonative.railix.kernel.model.RailixValue;
import dev.nanonative.railix.kernel.model.Reply;
import dev.nanonative.railix.kernel.model.SettingsTree;
import dev.nanonative.railix.kernel.model.StepContract;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class LocalExecutionKernel {

    private final Clock clock;

    public LocalExecutionKernel() {
        this(Clock.systemUTC());
    }

    public LocalExecutionKernel(final Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public RunRecord run(final AppPlan plan, final RunRequest request, final StepResolver resolver) {
        return run(plan, request, ConnectionPlan.forExecution(plan, resolver));
    }

    RunRecord run(
            final AppPlan plan,
            final RunRequest request,
            final ConnectionPlan connectionPlan
    ) {
        final Path runFolder = request.runsRoot().resolve(plan.appId()).resolve(plan.flowId()).resolve(request.runId());
        try {
            Files.createDirectories(runFolder);
        } catch (final IOException exception) {
            throw new UncheckedIOException(exception);
        }
        final RunSignalLog signalLog = new RunSignalLog(runFolder.resolve("signals.ndjson"));
        InMemoryContextDoc context = InMemoryContextDoc.fromEnvelope(request.envelope());
        final SignalCursor signalCursor = new SignalCursor(request.runId());
        final ResourceCursor resourceCursor = new ResourceCursor(request.runId());
        final ManagedResources runResources = new ManagedResources();
        final RegisteredResources runRegisteredResources = new RegisteredResources();
        final Map<String, Map<String, RailixValue>> outputsByStep = new LinkedHashMap<>();
        final Instant runStartedAt = clock.instant();
        signalLog.append(new RunSignal.RunStarted(
                signalCursor.nextId(),
                runStartedAt,
                plan.appId(),
                plan.flowId(),
                request.runId(),
                request.envelope().payload()
        ));
        String stepId = plan.triggerStepId();
        String outcome = "ok";
        int executedSteps = 0;
        Reply reply = Reply.none();
        while (stepId != null) {
            final AppPlan.StepInvocation invocation = plan.step(stepId);
            final Step step;
            final StepContract contract;
            try {
                step = requireResolvedStep(connectionPlan.step(invocation), invocation.use());
                contract = connectionPlan.contract(invocation, step);
            } catch (final RuntimeException exception) {
                return failRun(
                        plan,
                        request,
                        runFolder,
                        context,
                        signalLog,
                        signalCursor,
                        runResources,
                        runStartedAt,
                        executedSteps,
                        invocation.id(),
                        1,
                        exception
                );
            }
            final StepContract.RetryPolicy retryPolicy = requireRetryPolicy(contract.retryPolicy());
            boolean completed = false;
            for (int attemptNumber = 1; attemptNumber <= retryPolicy.maxAttempts(); attemptNumber++) {
                final Instant stepStartedAt = clock.instant();
                final ManagedResources attemptResources = new ManagedResources();
                final RegisteredResources attemptRegisteredResources = new RegisteredResources();
                try {
                    signalLog.append(new RunSignal.StepStarted(
                            signalCursor.nextId(),
                            stepStartedAt,
                            plan.appId(),
                            plan.flowId(),
                            request.runId(),
                            invocation.id(),
                            attemptNumber
                    ));
                    final Map<String, RailixValue> inputs = connectionPlan.inputs(invocation.id(), outputsByStep);
                    final Step.Result result = Objects.requireNonNull(step.execute(new Step.ExecutionInput(
                            request.envelope(),
                            context,
                            inputs,
                            connectionPlan.config(invocation),
                            settingsView(plan, request, contract, invocation.id(), signalLog, signalCursor, attemptNumber),
                            resourceScope(
                                    plan,
                                    request,
                                    invocation.id(),
                                    signalLog,
                                    signalCursor,
                                    resourceCursor,
                                    attemptNumber,
                                    attemptResources,
                                    attemptRegisteredResources,
                                    runRegisteredResources
                            ),
                            metricScope(plan, request, contract, invocation.id(), signalLog, signalCursor, attemptNumber),
                            auditScope(plan, request, invocation.id(), signalLog, signalCursor, attemptNumber),
                            permissionScope(plan, request, contract, invocation.id(), signalLog, signalCursor, attemptNumber)
                    )), "result");
                    validateOutcome(contract, result.outcome());
                    connectionPlan.validateOutputs(invocation.id(), result);
                    final InMemoryContextDoc updatedContext;
                    final ContextDoc.ContextDiff diff;
                    try {
                        updatedContext = (InMemoryContextDoc) context.applyAll(result.patches());
                        diff = context.diff(updatedContext);
                    } catch (final RuntimeException exception) {
                        throw new NonRetryableExecutionException("Step produced invalid context patches", exception);
                    }
                    if (!diff.changedPaths().isEmpty()) {
                        signalLog.append(new RunSignal.ContextPatched(
                                signalCursor.nextId(),
                                clock.instant(),
                                plan.appId(),
                                plan.flowId(),
                                request.runId(),
                                invocation.id(),
                                attemptNumber,
                                patchSummary(result.patches()),
                                diff.changedPaths()
                        ));
                    }
                    context = updatedContext;
                    if (connectionPlan.capturesOutputs(invocation.id())) {
                        outputsByStep.put(invocation.id(), result.outputs());
                    }
                    outcome = result.outcome();
                    if (result.reply().mode() != Reply.Mode.NONE) {
                        reply = result.reply();
                    }
                    executedSteps++;
                    signalLog.append(new RunSignal.StepFinished(
                            signalCursor.nextId(),
                            clock.instant(),
                            plan.appId(),
                            plan.flowId(),
                            request.runId(),
                            invocation.id(),
                            attemptNumber,
                            outcome,
                            Duration.between(stepStartedAt, clock.instant()).toMillis()
                    ));
                    emitRetryMetricsIfNeeded(
                            plan,
                            request,
                            signalLog,
                            signalCursor,
                            invocation.id(),
                            attemptNumber,
                            "success"
                    );
                    final String nextStepId = plan.nextStepId(invocation.id(), outcome).orElse(null);
                    attemptResources.promoteTo(runResources);
                    attemptRegisteredResources.promoteTo(runRegisteredResources);
                    stepId = nextStepId;
                    completed = true;
                    break;
                } catch (final RuntimeException exception) {
                    RuntimeException finalAttemptFailure = exception;
                    final RuntimeException cleanupFailure = attemptResources.closeAll();
                    if (cleanupFailure != null) {
                        finalAttemptFailure = combineWithCleanupFailure(exception, cleanupFailure);
                    }
                    signalLog.append(new RunSignal.StepFailed(
                            signalCursor.nextId(),
                            clock.instant(),
                            plan.appId(),
                            plan.flowId(),
                            request.runId(),
                            invocation.id(),
                            attemptNumber,
                            errorSummary(finalAttemptFailure)
                    ));
                    if (cleanupFailure != null) {
                        return failRun(
                                plan,
                                request,
                                runFolder,
                                context,
                                signalLog,
                                signalCursor,
                                runResources,
                                runStartedAt,
                                executedSteps,
                                null,
                                null,
                                finalAttemptFailure
                        );
                    }
                    if (!isRetryableAttemptFailure(finalAttemptFailure) || attemptNumber == retryPolicy.maxAttempts()) {
                        emitRetryMetricsIfNeeded(
                                plan,
                                request,
                                signalLog,
                                signalCursor,
                                invocation.id(),
                                attemptNumber,
                                "failure"
                        );
                        return failRun(
                                plan,
                                request,
                                runFolder,
                                context,
                                signalLog,
                                signalCursor,
                                runResources,
                                runStartedAt,
                                executedSteps,
                                null,
                                null,
                                finalAttemptFailure
                        );
                    }
                    try {
                        sleepBackoff(retryPolicy.backoff());
                    } catch (final RuntimeException backoffException) {
                        return failRun(
                                plan,
                                request,
                                runFolder,
                                context,
                                signalLog,
                                signalCursor,
                                runResources,
                                runStartedAt,
                                executedSteps,
                                null,
                                null,
                                backoffException
                        );
                    }
                }
            }
            if (!completed) {
                return failRun(
                        plan,
                        request,
                        runFolder,
                        context,
                        signalLog,
                        signalCursor,
                        runResources,
                        runStartedAt,
                        executedSteps,
                        null,
                        null,
                        new IllegalStateException("Step did not complete after retry loop: " + invocation.id())
                );
            }
        }
        try {
            if (reply.mode() == Reply.Mode.NONE) {
                reply = context.reply();
            }
            validateReplyChannel(request.envelope().replyChannel(), reply);
        } catch (final RuntimeException exception) {
            return failTerminalRun(
                    plan,
                    request,
                    runFolder,
                    context,
                    signalLog,
                    signalCursor,
                    runResources,
                    runStartedAt,
                    executedSteps,
                    exception,
                    "reply.validation",
                    false
            );
        }
        final RuntimeException cleanupFailure = runResources.closeAll();
        if (cleanupFailure != null) {
            return failTerminalRun(
                    plan,
                    request,
                    runFolder,
                    context,
                    signalLog,
                    signalCursor,
                    runResources,
                    runStartedAt,
                    executedSteps,
                    cleanupFailure,
                    "run.cleanup",
                    true
            );
        }
        signalLog.append(new RunSignal.ReplyProduced(
                signalCursor.nextId(),
                clock.instant(),
                plan.appId(),
                plan.flowId(),
                request.runId(),
                reply.mode().name().toLowerCase(),
                reply.status(),
                reply.metadata()
        ));
        signalLog.append(new RunSignal.RunFinished(
                signalCursor.nextId(),
                clock.instant(),
                plan.appId(),
                plan.flowId(),
                request.runId(),
                outcome,
                Duration.between(runStartedAt, clock.instant()).toMillis()
        ));
        final RunRecord record = new RunRecord(plan.appId(), plan.flowId(), request.runId(), outcome, runFolder, context, reply, signalLog, executedSteps);
        writeSummary(record, Map.of());
        return record;
    }

    private RunRecord failTerminalRun(
            final AppPlan plan,
            final RunRequest request,
            final Path runFolder,
            final InMemoryContextDoc context,
            final RunSignalLog signalLog,
            final SignalCursor signalCursor,
            final ManagedResources runResources,
            final Instant runStartedAt,
            final int executedSteps,
            final RuntimeException exception,
            final String phase,
            final boolean resourcesAlreadyClosed
    ) {
        RuntimeException finalException = Objects.requireNonNull(exception, "exception");
        String finalPhase = requireNonBlank(phase, "phase");
        if (!resourcesAlreadyClosed) {
            final RuntimeException cleanupFailure = runResources.closeAll();
            if (cleanupFailure != null) {
                finalException = combineWithCleanupFailure(exception, cleanupFailure);
                finalPhase = "run.cleanup";
            }
        }
        signalLog.append(new RunSignal.RunFinished(
                signalCursor.nextId(),
                clock.instant(),
                plan.appId(),
                plan.flowId(),
                request.runId(),
                "failed",
                Duration.between(runStartedAt, clock.instant()).toMillis()
        ));
        final RunRecord failedRecord = new RunRecord(plan.appId(), plan.flowId(), request.runId(), "failed", runFolder, context, Reply.none(), signalLog, executedSteps);
        writeSummary(failedRecord, terminalFailureSummary(finalPhase, finalException));
        return failedRecord;
    }

    public record RunRequest(String runId, Path runsRoot, Envelope envelope, SettingsTree settingsTree) {
        public RunRequest {
            runId = requireNonBlank(runId, "runId");
            runsRoot = Objects.requireNonNull(runsRoot, "runsRoot");
            envelope = Objects.requireNonNull(envelope, "envelope");
            settingsTree = Objects.requireNonNull(settingsTree, "settingsTree");
        }

        public RunRequest(final String runId, final Path runsRoot, final Envelope envelope) {
            this(runId, runsRoot, envelope, SettingsTree.empty());
        }
    }

    public record RunRecord(
            String appId,
            String flowId,
            String runId,
            String outcome,
            Path runFolder,
            InMemoryContextDoc context,
            Reply reply,
            RunSignalLog signalLog,
            int executedSteps
    ) {
        public RunRecord {
            appId = requireNonBlank(appId, "appId");
            flowId = requireNonBlank(flowId, "flowId");
            runId = requireNonBlank(runId, "runId");
            outcome = requireNonBlank(outcome, "outcome");
            runFolder = Objects.requireNonNull(runFolder, "runFolder");
            context = Objects.requireNonNull(context, "context");
            reply = Objects.requireNonNull(reply, "reply");
            signalLog = Objects.requireNonNull(signalLog, "signalLog");
            if (executedSteps < 0) {
                throw new IllegalArgumentException("executedSteps must be >= 0");
            }
        }
    }

    private static void validateOutcome(final StepContract contract, final String outcome) {
        if (!contract.outcomes().isEmpty() && !contract.outcomes().containsKey(outcome)) {
            throw new NonRetryableExecutionException("Step returned undeclared outcome: " + outcome);
        }
    }

    private static Step requireResolvedStep(final Step step, final String use) {
        if (step == null) {
            throw new IllegalArgumentException("Unknown step use: " + use);
        }
        return step;
    }

    private static void validateReplyChannel(final Envelope.ReplyChannel replyChannel, final Reply reply) {
        if (reply.mode() == Reply.Mode.NONE) {
            return;
        }
        if (!replyChannel.supported()) {
            throw new IllegalStateException("Reply channel does not support replies");
        }
        if (!replyChannel.modes().contains(reply.mode())) {
            throw new IllegalStateException("Reply mode is not supported by the envelope: " + reply.mode());
        }
    }

    private static RailixValue patchSummary(final List<dev.nanonative.railix.kernel.model.Patch> patches) {
        final List<RailixValue> ops = patches.stream()
                .map(patch -> new RailixValue.StringValue(patch.getClass().getSimpleName().toLowerCase()))
                .map(RailixValue.class::cast)
                .toList();
        return new RailixValue.ObjectValue(Map.of(
                "count", new RailixValue.NumberValue(java.math.BigDecimal.valueOf(patches.size())),
                "ops", new RailixValue.ListValue(ops)
        ));
    }

    private static RailixValue.ObjectValue errorSummary(final RuntimeException exception) {
        final Map<String, RailixValue> values = new LinkedHashMap<>();
        values.put("type", new RailixValue.StringValue(exception.getClass().getName()));
        values.put("message", new RailixValue.StringValue(exception.getMessage() == null ? "" : exception.getMessage()));
        return new RailixValue.ObjectValue(values);
    }

    private static Map<String, StepContract.MetricDefinition> metricDefinitionsByName(final StepContract contract) {
        return contract.metrics().emitted().stream()
                .collect(Collectors.toUnmodifiableMap(
                        StepContract.MetricDefinition::name,
                        Function.identity(),
                        (left, right) -> {
                            throw new IllegalStateException("Duplicate metric definition declared for " + left.name());
                        }
                ));
    }

    private static StepContract.MetricDefinition requireMetricDefinition(
            final Map<String, StepContract.MetricDefinition> definitionsByName,
            final String name
    ) {
        final StepContract.MetricDefinition definition = definitionsByName.get(name);
        if (definition == null) {
            throw new IllegalStateException("Metric is not declared by the step contract: " + name);
        }
        return definition;
    }

    private static Map<String, String> sanitizeMetricLabels(
            final StepContract.MetricDefinition definition,
            final Map<String, String> labels
    ) {
        final Map<String, String> sanitized = new LinkedHashMap<>();
        for (final Map.Entry<String, String> entry : Objects.requireNonNull(labels, "labels").entrySet()) {
            final String key = requireNonBlank(entry.getKey(), "Metric label key");
            if (!definition.labels().contains(key)) {
                throw new IllegalStateException("Metric label is not declared by the step contract: " + key);
            }
            sanitized.put(key, requireNonBlank(entry.getValue(), "Metric label value"));
        }
        return Map.copyOf(sanitized);
    }

    private void emitRetryMetricsIfNeeded(
            final AppPlan plan,
            final RunRequest request,
            final RunSignalLog signalLog,
            final SignalCursor signalCursor,
            final String stepId,
            final int attemptNumber,
            final String result
    ) {
        if (attemptNumber <= 1) {
            return;
        }
        final Map<String, String> labels = Map.of("result", result);
        signalLog.append(new RunSignal.MetricEmitted(
                signalCursor.nextId(),
                clock.instant(),
                plan.appId(),
                plan.flowId(),
                request.runId(),
                stepId,
                attemptNumber,
                "step.attempt",
                new RailixValue.NumberValue(BigDecimal.valueOf(attemptNumber)),
                "attempts",
                labels
        ));
        signalLog.append(new RunSignal.MetricEmitted(
                signalCursor.nextId(),
                clock.instant(),
                plan.appId(),
                plan.flowId(),
                request.runId(),
                stepId,
                attemptNumber,
                "step.retryCount",
                new RailixValue.NumberValue(BigDecimal.valueOf(attemptNumber - 1L)),
                "retries",
                labels
        ));
    }

    private Step.MetricScope metricScope(
            final AppPlan plan,
            final RunRequest request,
            final StepContract contract,
            final String stepId,
            final RunSignalLog signalLog,
            final SignalCursor signalCursor,
            final int attemptNumber
    ) {
        final Map<String, StepContract.MetricDefinition> definitionsByName = metricDefinitionsByName(contract);
        return (name, value, labels) -> {
            final StepContract.MetricDefinition definition = requireMetricDefinition(definitionsByName, name);
            signalLog.append(new RunSignal.MetricEmitted(
                    signalCursor.nextId(),
                    clock.instant(),
                    plan.appId(),
                    plan.flowId(),
                    request.runId(),
                    stepId,
                    attemptNumber,
                    name,
                    value,
                    definition.unit(),
                    sanitizeMetricLabels(definition, labels)
            ));
        };
    }

    private Step.AuditScope auditScope(
            final AppPlan plan,
            final RunRequest request,
            final String stepId,
            final RunSignalLog signalLog,
            final SignalCursor signalCursor,
            final int attemptNumber
    ) {
        return (eventName, data) -> signalLog.append(new RunSignal.AuditEmitted(
                signalCursor.nextId(),
                clock.instant(),
                plan.appId(),
                plan.flowId(),
                request.runId(),
                stepId,
                attemptNumber,
                eventName,
                Objects.requireNonNull(data, "data")
        ));
    }

    private Step.ResourceScope resourceScope(
            final AppPlan plan,
            final RunRequest request,
            final String stepId,
            final RunSignalLog signalLog,
            final SignalCursor signalCursor,
            final ResourceCursor resourceCursor,
            final int attemptNumber,
            final ManagedResources attemptResources,
            final RegisteredResources attemptRegisteredResources,
            final RegisteredResources runRegisteredResources
    ) {
        return new Step.ResourceScope() {
            @Override
            public Step.RegisteredResource register(final String refType, final RailixValue.ObjectValue metadata, final AutoCloseable resource) {
                final Step.RegisteredResource registeredResource = new Step.RegisteredResource(
                        resourceCursor.nextId(),
                        refType,
                        metadata
                );
                attemptResources.register(registeredResource, resource);
                attemptRegisteredResources.register(registeredResource);
                appendResourceCreated(
                        plan,
                        request,
                        stepId,
                        signalLog,
                        signalCursor,
                        attemptNumber,
                        registeredResource
                );
                return registeredResource;
            }

            @Override
            public Step.RegisteredResource registerMetadata(final String refType, final RailixValue.ObjectValue metadata) {
                final Step.RegisteredResource registeredResource = new Step.RegisteredResource(
                        resourceCursor.nextId(),
                        refType,
                        metadata
                );
                attemptRegisteredResources.register(registeredResource);
                appendResourceCreated(
                        plan,
                        request,
                        stepId,
                        signalLog,
                        signalCursor,
                        attemptNumber,
                        registeredResource
                );
                return registeredResource;
            }

            @Override
            public Optional<Step.RegisteredResource> find(final String refId) {
                return attemptRegisteredResources.find(refId)
                        .or(() -> runRegisteredResources.find(refId));
            }
        };
    }

    private void appendResourceCreated(
            final AppPlan plan,
            final RunRequest request,
            final String stepId,
            final RunSignalLog signalLog,
            final SignalCursor signalCursor,
            final int attemptNumber,
            final Step.RegisteredResource registeredResource
    ) {
        signalLog.append(new RunSignal.ResourceCreated(
                signalCursor.nextId(),
                clock.instant(),
                plan.appId(),
                plan.flowId(),
                request.runId(),
                stepId,
                attemptNumber,
                registeredResource.refId(),
                registeredResource.refType(),
                registeredResource.metadata()
        ));
    }

    private Step.SettingsView settingsView(
            final AppPlan plan,
            final RunRequest request,
            final StepContract contract,
            final String stepId,
            final RunSignalLog signalLog,
            final SignalCursor signalCursor,
            final int attemptNumber
    ) {
        return path -> {
            requireSettingDeclaration(contract, path);
            final SettingsTree settingsTree = request.settingsTree();
            final String permission = permissionFor(contract.permissions(), settingsTree.entries().get(path), path);
            final PermissionDecision permissionDecision = requirePermission(
                    plan,
                    request,
                    stepId,
                    signalLog,
                    signalCursor,
                    attemptNumber,
                    contract.permissions(),
                    permission,
                    path.toString()
            );
            final SettingsTree.Entry entry = settingsTree.entries().get(path);
            if (entry == null) {
                return Optional.empty();
            }
            final String pendingSignalId = signalCursor.peekNextId();
            final Optional<SettingsTree.ReadResult> readResult = settingsTree.readForExecution(
                    path,
                    new SettingsTree.ReadContext(
                            pendingSignalId,
                            clock.instant(),
                            plan.appId(),
                            plan.flowId(),
                            request.runId(),
                            stepId,
                            attemptNumber
                    )
            );
            final SettingsTree.ReadResult materializedRead = readResult.orElseThrow();
            if (materializedRead.auditSignal().isPresent()) {
                signalLog.append(materializedRead.auditSignal().orElseThrow());
                signalCursor.advance();
            }
            return Optional.of(new Step.ReadValue(
                    materializedRead.entry().path(),
                    materializeSettingValue(materializedRead.entry()),
                    materializedRead.materialized(),
                    materializedRead.entry().secret()
            ));
        };
    }

    private Step.PermissionScope permissionScope(
            final AppPlan plan,
            final RunRequest request,
            final StepContract contract,
            final String stepId,
            final RunSignalLog signalLog,
            final SignalCursor signalCursor,
            final int attemptNumber
    ) {
        return (permission, resource) -> requirePermission(
                plan,
                request,
                stepId,
                signalLog,
                signalCursor,
                attemptNumber,
                contract.permissions(),
                permission,
                resource
        );
    }

    private PermissionDecision requirePermission(
            final AppPlan plan,
            final RunRequest request,
            final String stepId,
            final RunSignalLog signalLog,
            final SignalCursor signalCursor,
            final int attemptNumber,
            final PermissionSet stepPermissions,
            final String permission,
            final String resource
    ) {
        final PermissionDecision permissionDecision = permissionDecision(
                stepPermissions,
                plan.permissions(),
                permission,
                resource
        );
        signalLog.append(new RunSignal.PermissionDecided(
                signalCursor.nextId(),
                clock.instant(),
                plan.appId(),
                plan.flowId(),
                request.runId(),
                stepId,
                attemptNumber,
                permission,
                resource,
                permissionDecision.decision(),
                permissionDecision.reason()
        ));
        if (!permissionDecision.allowed()) {
            throw new NonRetryableExecutionException("Permission denied for " + permission + ": " + resource);
        }
        return permissionDecision;
    }

    private static void requireSettingDeclaration(final dev.nanonative.railix.kernel.model.StepContract stepContract, final RailixPath path) {
        if (!stepContract.settings().requested().contains(path.toString())) {
            throw new NonRetryableExecutionException("Step tried to read undeclared setting: " + path);
        }
    }

    private static PermissionDecision permissionDecision(
            final PermissionSet stepPermissions,
            final PermissionSet planPermissions,
            final String permission,
            final String resource
    ) {
        final List<String> requested = stepPermissions.requested().getOrDefault(permission, List.of());
        if (!matchesAny(requested, resource)) {
            return new PermissionDecision(false, "denied", "permission-not-declared");
        }
        final List<String> stepGranted = stepPermissions.granted().getOrDefault(permission, List.of());
        if (matchesAny(stepGranted, resource)) {
            return new PermissionDecision(true, "granted", "permission-granted");
        }
        final List<String> planGranted = planPermissions.granted().getOrDefault(permission, List.of());
        if (matchesAny(planGranted, resource)) {
            return new PermissionDecision(true, "granted", "permission-granted-by-plan");
        }
        return new PermissionDecision(false, "denied", "permission-not-granted");
    }

    private static String permissionFor(
            final PermissionSet permissionSet,
            final SettingsTree.Entry entry,
            final RailixPath path
    ) {
        final String resource = path.toString();
        if (permissionSet.requested().getOrDefault("settings.secret", List.of()).contains(resource)) {
            return "settings.secret";
        }
        if (entry != null && entry.secret()) {
            return "settings.secret";
        }
        return "settings.read";
    }

    private static boolean isRetryableAttemptFailure(final RuntimeException exception) {
        return !(exception instanceof NonRetryableExecutionException);
    }

    private static boolean matchesAny(final List<String> patterns, final String resource) {
        return patterns.stream().anyMatch(pattern -> matchesResource(pattern, resource));
    }

    private static boolean matchesResource(final String pattern, final String resource) {
        if (pattern.equals(resource) || "*".equals(pattern)) {
            return true;
        }
        if (pattern.endsWith("/*")) {
            final String prefix = pattern.substring(0, pattern.length() - 1);
            return resource.startsWith(prefix);
        }
        return false;
    }

    private static StepContract.RetryPolicy requireRetryPolicy(final StepContract.RetryPolicy retryPolicy) {
        Objects.requireNonNull(retryPolicy, "retryPolicy");
        if (retryPolicy.maxAttempts() < 1) {
            throw new NonRetryableExecutionException("retryPolicy.maxAttempts must be >= 1");
        }
        if (retryPolicy.backoff() == null) {
            throw new NonRetryableExecutionException("retryPolicy.backoff must not be null");
        }
        if (retryPolicy.backoff().isNegative()) {
            throw new NonRetryableExecutionException("retryPolicy.backoff must not be negative");
        }
        return retryPolicy;
    }

    private static RailixValue materializeSettingValue(final SettingsTree.Entry entry) {
        return switch (entry.value()) {
            case SettingsTree.PlainValue plainValue -> plainValue.value();
            case SettingsTree.ReferenceValue referenceValue -> referenceValue.value();
            case SettingsTree.EncryptedValue ignored ->
                    throw new NonRetryableExecutionException("Setting is not materialized for execution: " + entry.path());
        };
    }

    private RunRecord failRun(
            final AppPlan plan,
            final RunRequest request,
            final Path runFolder,
            final InMemoryContextDoc context,
            final RunSignalLog signalLog,
            final SignalCursor signalCursor,
            final ManagedResources runResources,
            final Instant runStartedAt,
            final int executedSteps,
            final String stepId,
            final Integer attemptNumber,
            final RuntimeException exception
    ) {
        final RuntimeException finalException = combineWithCleanupFailure(exception, runResources.closeAll());
        if (stepId != null && attemptNumber != null) {
            signalLog.append(new RunSignal.StepFailed(
                    signalCursor.nextId(),
                    clock.instant(),
                    plan.appId(),
                    plan.flowId(),
                    request.runId(),
                    stepId,
                    attemptNumber,
                    errorSummary(finalException)
            ));
        }
        signalLog.append(new RunSignal.RunFinished(
                signalCursor.nextId(),
                clock.instant(),
                plan.appId(),
                plan.flowId(),
                request.runId(),
                "failed",
                Duration.between(runStartedAt, clock.instant()).toMillis()
        ));
        final RunRecord failedRecord = new RunRecord(plan.appId(), plan.flowId(), request.runId(), "failed", runFolder, context, Reply.none(), signalLog, executedSteps);
        writeSummary(failedRecord, Map.of());
        return failedRecord;
    }

    private static RuntimeException combineWithCleanupFailure(
            final RuntimeException primaryFailure,
            final RuntimeException cleanupFailure
    ) {
        if (cleanupFailure == null) {
            return primaryFailure;
        }
        cleanupFailure.addSuppressed(primaryFailure);
        return cleanupFailure;
    }

    private static String signalId(final String runId, final int counter) {
        return runId + "-sig-" + counter;
    }

    private record PermissionDecision(boolean allowed, String decision, String reason) {}

    private static void sleepBackoff(final Duration backoff) {
        if (backoff.isZero()) {
            return;
        }
        try {
            Thread.sleep(backoff.toMillis());
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new NonRetryableExecutionException("Retry backoff interrupted", exception);
        }
    }

    private static final class NonRetryableExecutionException extends RuntimeException {
        private NonRetryableExecutionException(final String message) {
            super(message);
        }

        private NonRetryableExecutionException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }

    private static final class SignalCursor {
        private final String runId;
        private int counter;

        private SignalCursor(final String runId) {
            this.runId = runId;
        }

        private String nextId() {
            counter++;
            return signalId(runId, counter);
        }

        private String peekNextId() {
            return signalId(runId, counter + 1);
        }

        private void advance() {
            counter++;
        }
    }

    private static final class ResourceCursor {
        private final String runId;
        private int counter;

        private ResourceCursor(final String runId) {
            this.runId = runId;
        }

        private String nextId() {
            counter++;
            return runId + "-res-" + counter;
        }
    }

    private static final class ManagedResources {
        private final List<ManagedResource> resources = new ArrayList<>();

        private Step.RegisteredResource register(final Step.RegisteredResource registeredResource, final AutoCloseable resource) {
            resources.add(new ManagedResource(
                    Objects.requireNonNull(registeredResource, "registeredResource"),
                    Objects.requireNonNull(resource, "resource")
            ));
            return registeredResource;
        }

        private void promoteTo(final ManagedResources runResources) {
            runResources.resources.addAll(resources);
            resources.clear();
        }

        private RuntimeException closeAll() {
            RuntimeException failure = null;
            for (int index = resources.size() - 1; index >= 0; index--) {
                final ManagedResource managedResource = resources.get(index);
                try {
                    managedResource.resource().close();
                } catch (final Exception exception) {
                    final RuntimeException closeFailure = new RuntimeException(
                            "Failed to close resource: " + managedResource.registeredResource().refId(),
                            exception
                    );
                    if (failure == null) {
                        failure = closeFailure;
                    } else {
                        failure.addSuppressed(closeFailure);
                    }
                }
            }
            resources.clear();
            return failure;
        }
    }

    private record ManagedResource(Step.RegisteredResource registeredResource, AutoCloseable resource) {}

    private static final class RegisteredResources {
        private final Map<String, Step.RegisteredResource> resources = new LinkedHashMap<>();

        private Step.RegisteredResource register(final Step.RegisteredResource registeredResource) {
            resources.put(Objects.requireNonNull(registeredResource, "registeredResource").refId(), registeredResource);
            return registeredResource;
        }

        private Optional<Step.RegisteredResource> find(final String refId) {
            return Optional.ofNullable(resources.get(Objects.requireNonNull(refId, "refId")));
        }

        private void promoteTo(final RegisteredResources runRegisteredResources) {
            runRegisteredResources.resources.putAll(resources);
            resources.clear();
        }
    }

    private static Map<String, Object> terminalFailureSummary(final String phase, final Throwable throwable) {
        final Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("phase", requireNonBlank(phase, "phase"));
        summary.put("type", throwable.getClass().getName());
        summary.put("message", throwable.getMessage() == null ? "" : throwable.getMessage());
        if (throwable.getSuppressed().length > 0) {
            final List<Map<String, Object>> suppressed = new ArrayList<>();
            for (final Throwable suppressedThrowable : throwable.getSuppressed()) {
                suppressed.add(throwableSummary(suppressedThrowable));
            }
            summary.put("suppressed", List.copyOf(suppressed));
        }
        return Map.copyOf(summary);
    }

    private static Map<String, Object> throwableSummary(final Throwable throwable) {
        final Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("type", throwable.getClass().getName());
        summary.put("message", throwable.getMessage() == null ? "" : throwable.getMessage());
        if (throwable.getSuppressed().length > 0) {
            final List<Map<String, Object>> suppressed = new ArrayList<>();
            for (final Throwable suppressedThrowable : throwable.getSuppressed()) {
                suppressed.add(throwableSummary(suppressedThrowable));
            }
            summary.put("suppressed", List.copyOf(suppressed));
        }
        return Map.copyOf(summary);
    }

    private static void writeSummary(final RunRecord record, final Map<String, Object> terminalFailure) {
        final Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("appId", record.appId());
        summary.put("flowId", record.flowId());
        summary.put("runId", record.runId());
        summary.put("outcome", record.outcome());
        summary.put("executedSteps", record.executedSteps());
        summary.put("replyMode", record.reply().mode().name().toLowerCase());
        summary.put("signalCount", record.signalLog().signalCount());
        if (!terminalFailure.isEmpty()) {
            summary.put("terminalFailure", terminalFailure);
        }
        try {
            Files.writeString(record.runFolder().resolve("summary.json"), KernelContractCodec.toStableJson(summary));
        } catch (final IOException exception) {
            throw new UncheckedIOException(exception);
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
