package dev.nanonative.railix.core.project;

import dev.nanonative.railix.core.runtime.RunFailure;
import dev.nanonative.railix.core.runtime.RunResult;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.step.StepInput;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueRefinement;
import dev.nanonative.railix.core.value.ValueShape;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;


/**
 * Stateless execution semantics used by generated applications.
 * Graph selection and routing stay outside this class.
 */
public final class WorkflowRuntime {
    private static final List<RunResult.StepExecution> NO_HISTORY = List.of();
    private static final int ABORTED = -1;
    private static final int WRITE_OK = 0;
    private static final int WRITE_CONFLICT = 1;
    private static final int WRITE_SPARSE = 2;
    private static final int MAX_ARRAY_GAP = 1_024;
    private static final ValueRefinement CANONICAL_VALUE = ValueRefinement.canonical();

    private WorkflowRuntime() {
    }

    static Execution execution(
            final String triggerId,
            final List<ResultPlan> results,
            final RailixValue.ObjectValue context,
            final boolean test,
            final boolean history
    ) {
        return new Execution(triggerId, results, context, test, history);
    }

    static Optional<RunResult> validateSource(
            final ExecutableStep step,
            final Map<String, RailixValue> values,
            final String path,
            final List<RunResult.StepExecution> history
    ) {
        return validateReceives(step, values, path, history);
    }

    static RunResult rejectedResult(
            final String code,
            final String message,
            final String path,
            final List<RunResult.StepExecution> history
    ) {
        return rejected(code, message, path, history);
    }

    static RunResult failedResult(
            final String code,
            final String message,
            final String step,
            final List<RunResult.StepExecution> history
    ) {
        return failed(code, message, step, history);
    }

    static final class Execution {
        private EventFrame frame;
        private RailixValue.ObjectValue frozenContext;
        private RunResult aborted;
        private final List<ResultPlan> results;
        private final List<RunResult.StepExecution> history;

        private Execution(
                final String triggerId,
                final List<ResultPlan> results,
                final RailixValue.ObjectValue streamContext,
                final boolean test,
                final boolean history
        ) {
            if (streamContext == null) {
                throw new IllegalArgumentException("Workflow context cannot be Java null.");
            }
            this.results = results;
            this.history = history ? new ArrayList<>() : NO_HISTORY;
            final Map<String, RailixValue> context = new LinkedHashMap<>(streamContext.values());
            for (final ResultPlan result : results) {
                context.remove(result.name());
                if (!result.defaultValue().isEmpty()) {
                    context.put(result.name(), result.defaultValue().getFirst());
                }
            }
            context.put("runtime", RailixValue.object(Map.of(
                    "test", RailixValue.bool(test),
                    "trigger", RailixValue.string(triggerId)
            )));
            frame = new EventFrame(context);
        }

        int call(
                final CallPlan plan,
                final StepCall implementation,
                final Map<String, RailixValue> received
        ) {
            return call(plan, implementation, received, null);
        }

        int observe(
                final CallPlan plan,
                final StepCall implementation,
                final Map<String, RailixValue> received,
                final Capture capture
        ) {
            if (capture == null) {
                throw new IllegalArgumentException("Workflow observation capture cannot be Java null.");
            }
            capture.inputContext(frame.snapshot());
            return call(plan, implementation, received, capture);
        }

        private int call(
                final CallPlan plan,
                final StepCall implementation,
                final Map<String, RailixValue> received,
                final Capture capture
        ) {
            return WorkflowRuntime.call(
                    plan,
                    implementation,
                    this,
                    received,
                    history,
                    capture
            );
        }

        int abort(final RunResult result) {
            if (result == null) {
                throw new IllegalArgumentException("Aborted execution result cannot be Java null.");
            }
            aborted = result;
            return ABORTED;
        }

        void record(final String stepId, final String outcome) {
            WorkflowRuntime.record(history, stepId, outcome);
        }

        RunResult finish() {
            if (aborted != null) {
                return aborted;
            }
            if (Thread.currentThread().isInterrupted()) {
                return new RunResult.Cancelled(history);
            }
            final RailixValue.ObjectValue context = context();
            for (final ResultPlan result : results) {
                final RailixValue value = context.values().get(result.name());
                if (value == null) {
                    return rejected(
                            "RUN_RESULT_REQUIRED",
                            "Trigger result is missing: " + result.name() + ".",
                            "context." + result.name(),
                            history
                    );
                }
                if (!result.shape().accepts(value)) {
                    return rejected(
                            "RUN_RESULT_INCOMPATIBLE",
                            "Trigger result " + result.name() + " requires " + shape(result.shape())
                                    + " but receives " + shape(ValueShape.shapeOf(value)) + ".",
                            "context." + result.name(),
                            history
                    );
                }
                final Optional<String> rejection = canonicalResultRejection(value);
                if (rejection.isPresent()) {
                    return rejected(
                            "RUN_RESULT_INCOMPATIBLE",
                            "Trigger result " + result.name() + " is incompatible: " + rejection.get(),
                            "context." + result.name(),
                            history
                    );
                }
            }
            return new RunResult.Succeeded(context, history);
        }

        Map<String, RailixValue> responses(final Map<String, String> slots) {
            final RailixValue.ObjectValue context = context();
            final Map<String, RailixValue> responses = new LinkedHashMap<>();
            for (final Map.Entry<String, String> slot : slots.entrySet()) {
                responses.put(slot.getKey(), context.values().get(slot.getValue()));
            }
            return Collections.unmodifiableMap(responses);
        }

        List<RunResult.StepExecution> history() {
            return List.copyOf(history);
        }

        private RailixValue.ObjectValue context() {
            if (frozenContext == null) {
                frozenContext = frame.snapshot();
            }
            return frozenContext;
        }
    }

    private static int call(
            final CallPlan plan,
            final StepCall implementation,
            final Execution execution,
            final Map<String, RailixValue> received,
            final List<RunResult.StepExecution> history,
            final Capture capture
    ) {
        final EventFrame frame = execution.frame;
        if (Thread.currentThread().isInterrupted()) {
            return execution.abort(new RunResult.Cancelled(history));
        }
        Map<String, RailixValue> receivedValues = received;
        if (plan.step().kind() == StepDefinition.Kind.STEP && !plan.receives().isEmpty()) {
            final Map<String, RailixValue> mapped = new LinkedHashMap<>();
            for (final StepDefinition.Port port : plan.step().receives()) {
                final Path source = plan.receives().get(port.name());
                final RailixValue value = source == null
                        ? null
                        : frame.resolve(source);
                if (value == null) {
                    return execution.abort(rejected(
                            "RUN_STEP_RECEIVE_REQUIRED",
                            "Step receive path has no value: " + port.name() + ".",
                            plan.path() + ".receives." + port.name(),
                            history
                    ));
                }
                if (!port.shape().accepts(value)) {
                    return execution.abort(rejected(
                            "RUN_STEP_RECEIVE_INCOMPATIBLE",
                            "Step receive " + port.name() + " requires " + shape(port.shape())
                                    + " but receives " + shape(ValueShape.shapeOf(value)) + ".",
                            plan.path() + ".receives." + port.name(),
                            history
                    ));
                }
                final Optional<String> rejection = port.refinement().rejection(value);
                if (rejection.isPresent()) {
                    return execution.abort(rejected(
                            "RUN_STEP_RECEIVE_INCOMPATIBLE",
                            "Step receive " + port.name() + " is incompatible: " + rejection.get(),
                            plan.path() + ".receives." + port.name(),
                            history
                    ));
                }
                mapped.put(port.name(), value);
            }
            receivedValues = mapped;
        }
        final Resolution resolution = resolve(
                plan.inputs(),
                frame,
                receivedValues,
                plan.step().primaryOutcome(),
                history,
                capture
        );
        if (resolution.failure() != null) {
            resolution.closePrograms();
            return execution.abort(resolution.failure());
        }
        if (capture != null) {
            capture.inputs(resolution.values());
        }
        if (plan.step().use().isEmpty()) {
            resolution.closePrograms();
            return execution.abort(failed(
                    "STEP_HANDLER_REQUIRED",
                    "Executable Step has no handler.",
                    plan.id(),
                    history
            ));
        }
        final StepResult result;
        try {
            result = implementation.run(resolution.input());
        } catch (final ProgramAbort abort) {
            return execution.abort(abort.result());
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            return execution.abort(new RunResult.Cancelled(history));
        } catch (final RuntimeException exception) {
            return execution.abort(failed(
                    "STEP_IMPLEMENTATION_FAULT",
                    "Step implementation threw an unexpected exception.",
                    plan.id(),
                    history
            ));
        } finally {
            resolution.closePrograms();
        }
        if (Thread.currentThread().isInterrupted()) {
            return execution.abort(new RunResult.Cancelled(history));
        }
        if (result == null) {
            return execution.abort(failed(
                    "STEP_RESULT_REQUIRED",
                    "Step implementation returned Java null.",
                    plan.id(),
                    history
            ));
        }
        final int outcome = plan.step().outcomes().indexOf(result.outcome());
        if (outcome < 0) {
            return execution.abort(failed(
                    "STEP_OUTCOME_INVALID",
                    "Step returned undeclared outcome: " + result.outcome() + ".",
                    plan.id(),
                    history
            ));
        }
        if (!plan.step().primaryOutcome().equals(result.outcome()) && !result.outputs().isEmpty()) {
            return execution.abort(failed(
                    "STEP_OUTPUT_UNEXPECTED",
                    "Step returned output for outcome " + result.outcome() + ".",
                    plan.id(),
                    history
            ));
        }
        final boolean primary = plan.step().primaryOutcome().equals(result.outcome());
        if (primary) {
            boolean complete = result.outputs().size() == plan.step().returns().size();
            for (final StepDefinition.Port port : plan.step().returns()) {
                complete &= result.outputs().containsKey(port.name());
            }
            if (!complete) {
                return execution.abort(failed(
                        "STEP_OUTPUT_INVALID",
                        "Step did not return every declared output exactly once.",
                        plan.id(),
                        history
                ));
            }
            for (final StepDefinition.Port port : plan.step().returns()) {
                final RailixValue output = result.outputs().get(port.name());
                if (!port.shape().accepts(output)) {
                    return execution.abort(failed(
                            "STEP_OUTPUT_INVALID",
                            "Step output " + port.name() + " requires " + shape(port.shape()) + ".",
                            plan.id(),
                            history
                    ));
                }
                final Optional<String> rejection = port.refinement().rejection(output);
                if (rejection.isPresent()
                        || output instanceof RailixValue.NumberValue number
                        && !RailixData.fitsCanonicalNumber(number.value())) {
                    return execution.abort(failed(
                            "STEP_OUTPUT_INVALID",
                            "Step output " + port.name() + " is incompatible."
                                    + rejection.map(value -> " " + value).orElse(""),
                            plan.id(),
                            history
                    ));
                }
                final Path target = plan.returns().get(port.name());
                if (target == null) {
                    return execution.abort(failed(
                            "STEP_RETURN_TARGET_REQUIRED",
                            "Compiled Step return target is missing: " + port.name() + ".",
                            plan.id(),
                            history
                    ));
                }
            }
        }
        for (final Map.Entry<String, RailixValue> write : result.writes().entrySet()) {
            final PathBinding target = resolution.paths().get(write.getKey());
            if (target == null || !target.access().writable()) {
                return execution.abort(failed(
                        "STEP_WRITE_UNDECLARED",
                        "Step wrote through an undeclared writable PATH input: " + write.getKey() + ".",
                        plan.id(),
                        history
                ));
            }
        }
        final int mutations = (primary ? plan.step().returns().size() : 0) + result.writes().size();
        final EventFrame next = mutations > 1 ? frame.fork() : frame;
        if (primary) {
            for (final StepDefinition.Port port : plan.step().returns()) {
                final int status = next.write(plan.returns().get(port.name()), result.outputs().get(port.name()));
                if (status != WRITE_OK) {
                    return execution.abort(writeFailure(
                            status,
                            plan.path() + ".returns." + port.name(),
                            history
                    ));
                }
            }
        }
        for (final Map.Entry<String, PathBinding> path : resolution.paths().entrySet()) {
            final RailixValue write = result.writes().get(path.getKey());
            if (write == null) {
                continue;
            }
            final int status = next.write(path.getValue().path(), write);
            if (status != WRITE_OK) {
                return execution.abort(writeFailure(
                        status,
                        plan.path() + ".inputs." + path.getKey(),
                        history
                ));
            }
        }
        execution.frame = next;
        execution.frozenContext = null;
        return outcome;
    }

    private static Resolution resolve(
            final Map<String, Binding> bindings,
            final EventFrame frame,
            final Map<String, RailixValue> received,
            final String primaryOutcome,
            final List<RunResult.StepExecution> history,
            final Capture capture
    ) {
        final Map<String, RailixValue> values = new LinkedHashMap<>(received);
        final Map<String, String> options = new LinkedHashMap<>();
        final Map<String, StepInput.Program> programs = new LinkedHashMap<>();
        final Map<String, StepInput> selected = new LinkedHashMap<>();
        final Map<String, PathBinding> paths = new LinkedHashMap<>();
        List<ProgramScope> programScopes = null;
        for (final Map.Entry<String, Binding> entry : bindings.entrySet()) {
            final String name = entry.getKey();
            final Binding binding = entry.getValue();
            if (binding instanceof JsonBinding json) {
                if (!json.value().isEmpty()) {
                    values.put(name, json.value().getFirst());
                }
            } else if (binding instanceof PathBinding path) {
                paths.put(name, path);
                if (path.access().readable()) {
                    final RailixValue value = frame.resolve(path.path());
                    if (value != null) {
                        values.put(name, value);
                    }
                }
            } else if (binding instanceof ChoiceBinding choice) {
                final Resolution children = resolve(
                        choice.inputs(),
                        frame,
                        Map.of(),
                        primaryOutcome,
                        history,
                        capture
                );
                if (children.failure() != null) {
                    closePrograms(programScopes);
                    return children;
                }
                programScopes = merge(programScopes, children.programScopes());
                options.put(name, choice.option());
                selected.put(name, children.input());
                final RailixValue selectedValue = choice.valueSource()
                        .map(source -> source.scope() == StepDefinition.ReferenceScope.OWNED
                                ? children.values().get(source.input())
                                : values.get(source.input()))
                        .orElse(null);
                if (selectedValue != null) {
                    values.put(name, selectedValue);
                }
            } else if (binding instanceof CandidatesBinding candidates) {
                for (int index = 0; index < candidates.candidates().size(); index++) {
                    final CandidatePlan candidate = candidates.candidates().get(index);
                    final ChoiceBinding source = candidate.source();
                    final MatcherEvaluation evaluation = evaluate(
                            candidate,
                            values,
                            frame,
                            primaryOutcome,
                            history,
                            capture,
                            name + "[" + index + "].when"
                    );
                    if (evaluation.failure() != null) {
                        closePrograms(programScopes);
                        evaluation.children().closePrograms();
                        return failedResolution(primaryOutcome, evaluation.failure());
                    }
                    if (evaluation.matched()) {
                        programScopes = merge(programScopes, evaluation.children().programScopes());
                        values.put(name, evaluation.value());
                        options.put(name, source.option());
                        selected.put(name, evaluation.children().input());
                        if (capture != null) {
                            capture.selectedCandidate(candidates.path(), index);
                        }
                        break;
                    }
                    evaluation.children().closePrograms();
                }
            } else if (binding instanceof MatcherGroupsBinding matcherGroups) {
                boolean matched = false;
                groupLoop:
                for (int groupIndex = 0; groupIndex < matcherGroups.groups().size(); groupIndex++) {
                    final List<CandidatePlan> group = matcherGroups.groups().get(groupIndex);
                    for (int matcherIndex = 0; matcherIndex < group.size(); matcherIndex++) {
                        final MatcherEvaluation evaluation = evaluate(
                                group.get(matcherIndex),
                                values,
                                frame,
                                primaryOutcome,
                                history,
                                capture,
                                name + "[" + groupIndex + "][" + matcherIndex + "].when"
                        );
                        if (evaluation.failure() != null) {
                            closePrograms(programScopes);
                            evaluation.children().closePrograms();
                            return failedResolution(primaryOutcome, evaluation.failure());
                        }
                        evaluation.children().closePrograms();
                        if (!evaluation.matched()) {
                            continue groupLoop;
                        }
                    }
                    matched = true;
                    break;
                }
                final RailixValue booleanValue = RailixValue.bool(matched);
                values.put(name, booleanValue);
            } else if (binding instanceof StepsBinding stepsBinding) {
                final StepDefinition.ValueSource source = stepsBinding.valueSource();
                final ProgramScope program = new ProgramScope(
                        stepsBinding.steps(),
                        values.get(source.input()),
                        source.missingOutcome().orElse(primaryOutcome),
                        frame,
                        primaryOutcome,
                        history,
                        capture,
                        name
                );
                programs.put(name, program::run);
                if (programScopes == null) {
                    programScopes = new ArrayList<>();
                }
                programScopes.add(program);
            }
        }
        return new Resolution(
                new StepInput(values, options, programs, selected, primaryOutcome),
                values,
                paths,
                null,
                programScopes == null ? List.of() : List.copyOf(programScopes)
        );
    }

    private static List<ProgramScope> merge(
            List<ProgramScope> current,
            final List<ProgramScope> added
    ) {
        if (added.isEmpty()) {
            return current;
        }
        if (current == null) {
            current = new ArrayList<>();
        }
        current.addAll(added);
        return current;
    }

    private static void closePrograms(final List<ProgramScope> programs) {
        if (programs != null) {
            for (final ProgramScope program : programs) {
                program.close();
            }
        }
    }

    private static MatcherEvaluation evaluate(
            final CandidatePlan candidate,
            final Map<String, RailixValue> values,
            final EventFrame frame,
            final String primaryOutcome,
            final List<RunResult.StepExecution> history,
            final Capture capture,
            final String predicateInput
    ) {
        final ChoiceBinding source = candidate.source();
        final Resolution children = resolve(
                source.inputs(),
                frame,
                Map.of(),
                primaryOutcome,
                history,
                capture
        );
        if (children.failure() != null) {
            return new MatcherEvaluation(false, null, children, children.failure());
        }
        final RailixValue candidateValue = source.valueSource()
                .map(reference -> reference.scope() == StepDefinition.ReferenceScope.OWNED
                        ? children.values().get(reference.input())
                        : values.get(reference.input()))
                .orElse(null);
        if (candidateValue == null) {
            return new MatcherEvaluation(false, null, children, null);
        }
        if (candidate.predicates().isEmpty()) {
            return new MatcherEvaluation(true, candidateValue, children, null);
        }
        try {
            final RailixValue prepared = candidate.transforms().isEmpty()
                    ? candidateValue
                    : program(
                            candidate.transforms(),
                            candidateValue,
                            frame,
                            primaryOutcome,
                            history,
                            capture,
                            predicateInput + ".transforms"
                    ).values().getFirst();
            for (int index = 0; index < candidate.predicates().size(); index++) {
                final List<NestedStepPlan> condition = candidate.predicates().get(index);
                final StepInput.ProgramResult predicate = program(
                        condition,
                        prepared,
                        frame,
                        primaryOutcome,
                        history,
                        capture,
                        predicateInput + ".all[" + index + "]"
                );
                if (!((RailixValue.BooleanValue) predicate.values().getFirst()).value()) {
                    return new MatcherEvaluation(false, candidateValue, children, null);
                }
            }
            return new MatcherEvaluation(true, candidateValue, children, null);
        } catch (final ProgramAbort abort) {
            return new MatcherEvaluation(false, null, children, abort.result());
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new MatcherEvaluation(false, null, children, new RunResult.Cancelled(history));
        }
    }

    private static StepInput.ProgramResult program(
            final List<NestedStepPlan> program,
            RailixValue value,
            final EventFrame frame,
            final String enclosingOutcome,
            final List<RunResult.StepExecution> history,
            final Capture capture,
            final String inputName
    ) throws InterruptedException {
        for (final NestedStepPlan step : program) {
            final ExecutableStep executable = step.step();
            final StepDefinition.Port receive = executable.receives().getFirst();
            if (!receive.shape().accepts(value)) {
                capture(capture, inputName, step, "rejected", List.of());
                throw new ProgramAbort(rejected(
                        "RUN_NESTED_INPUT_INCOMPATIBLE",
                        "Nested Step " + executable.use() + " requires " + shape(receive.shape())
                                + " but receives " + shape(ValueShape.shapeOf(value)) + ".",
                        step.path(),
                        history
                ));
            }
            final Optional<String> inputRejection = receive.refinement().rejection(value);
            if (inputRejection.isPresent()) {
                capture(capture, inputName, step, "rejected", List.of());
                throw new ProgramAbort(rejected(
                        "RUN_NESTED_INPUT_INCOMPATIBLE",
                        "Nested Step " + executable.use() + " rejects value: " + inputRejection.get(),
                        step.path(),
                        history
                ));
            }
            if (Thread.currentThread().isInterrupted()) {
                capture(capture, inputName, step, "cancelled", List.of());
                throw new InterruptedException();
            }
            final Resolution resolved = resolve(
                    step.inputs(),
                    frame,
                    Map.of(receive.name(), value),
                    executable.primaryOutcome(),
                    history,
                    capture
            );
            if (resolved.failure() != null) {
                resolved.closePrograms();
                throw new ProgramAbort(resolved.failure());
            }
            if (executable.use().isEmpty()) {
                resolved.closePrograms();
                throw new ProgramAbort(failed(
                        "STEP_HANDLER_REQUIRED",
                        "Nested Step has no handler.",
                        executable.use(),
                        step.path(),
                        history
                ));
            }
            final StepResult result;
            try {
                result = step.call().run(resolved.input());
            } catch (final InterruptedException exception) {
                capture(capture, inputName, step, "cancelled", List.of());
                throw exception;
            } catch (final ProgramAbort abort) {
                throw abort;
            } catch (final RuntimeException exception) {
                capture(capture, inputName, step, "failed", List.of());
                throw new ProgramAbort(failed(
                        "STEP_IMPLEMENTATION_FAULT",
                        "Step implementation threw an unexpected exception.",
                        executable.use(),
                        step.path(),
                        history
                ));
            } finally {
                resolved.closePrograms();
            }
            if (Thread.currentThread().isInterrupted()) {
                capture(capture, inputName, step, "cancelled", List.of());
                throw new InterruptedException();
            }
            if (result == null) {
                capture(capture, inputName, step, "failed", List.of());
                throw new ProgramAbort(failed(
                        "STEP_RESULT_REQUIRED",
                        "Step implementation returned Java null.",
                        executable.use(),
                        step.path(),
                        history
                ));
            }
            if (!executable.outcomes().contains(result.outcome())) {
                capture(capture, inputName, step, "failed", List.of());
                throw new ProgramAbort(failed(
                        "STEP_OUTCOME_INVALID",
                        "Nested Step returned undeclared outcome: " + result.outcome() + ".",
                        executable.use(),
                        step.path(),
                        history
                ));
            }
            if (!result.writes().isEmpty()) {
                capture(capture, inputName, step, "failed", List.of());
                throw new ProgramAbort(failed(
                        "STEP_WRITE_UNEXPECTED",
                        "Nested Step attempted to write workflow context.",
                        executable.use(),
                        step.path(),
                        history
                ));
            }
            if (!executable.primaryOutcome().equals(result.outcome())) {
                if (!result.outputs().isEmpty()) {
                    capture(capture, inputName, step, "failed", List.copyOf(result.outputs().values()));
                    throw new ProgramAbort(failed(
                            "STEP_OUTPUT_UNEXPECTED",
                            "Nested Step returned output for outcome " + result.outcome() + ".",
                            executable.use(),
                            step.path(),
                            history
                    ));
                }
                capture(capture, inputName, step, result.outcome(), List.of());
                record(history, executable.use(), result.outcome());
                return new StepInput.ProgramResult(enclosingOutcome, result.outcome(), List.of());
            }
            final StepDefinition.Port output = executable.returns().getFirst();
            final RailixValue next = result.outputs().get(output.name());
            if (next == null || result.outputs().size() != 1 || !output.shape().accepts(next)) {
                capture(capture, inputName, step, "failed", next == null ? List.of() : List.of(next));
                throw new ProgramAbort(failed(
                        "STEP_OUTPUT_INVALID",
                        "Nested Step did not return its one declared compatible value.",
                        executable.use(),
                        step.path(),
                        history
                ));
            }
            final Optional<String> outputRejection = output.refinement().rejection(next);
            if (outputRejection.isPresent()) {
                capture(capture, inputName, step, "failed", List.of(next));
                throw new ProgramAbort(failed(
                        "STEP_OUTPUT_INVALID",
                        "Nested Step returned an incompatible value: " + outputRejection.get(),
                        executable.use(),
                        step.path(),
                        history
                ));
            }
            if (next instanceof RailixValue.NumberValue number
                    && !RailixData.fitsCanonicalNumber(number.value())) {
                capture(capture, inputName, step, "failed", List.of(next));
                throw new ProgramAbort(failed(
                        "STEP_OUTPUT_INVALID",
                        "Nested Step returned a number outside the canonical domain.",
                        executable.use(),
                        step.path(),
                        history
                ));
            }
            value = next;
            capture(capture, inputName, step, "succeeded", List.of(value));
            record(history, executable.use(), result.outcome());
        }
        return new StepInput.ProgramResult(enclosingOutcome, enclosingOutcome, List.of(value));
    }

    private static Resolution failedResolution(final String primaryOutcome, final RunResult failure) {
        return new Resolution(
                new StepInput(Map.of(), Map.of(), Map.of(), Map.of(), primaryOutcome),
                Map.of(),
                Map.of(),
                failure,
                List.of()
        );
    }

    private static Optional<RunResult> validateReceives(
            final ExecutableStep step,
            final Map<String, RailixValue> values,
            final String path,
        final List<RunResult.StepExecution> history
    ) {
        for (final String name : values.keySet()) {
            boolean declared = false;
            for (final StepDefinition.Port port : step.receives()) {
                declared |= port.name().equals(name);
            }
            if (!declared) {
                return Optional.of(rejected(
                        "RUN_SOURCE_VALUE_UNKNOWN",
                        "Trigger source value is not declared: " + name + ".",
                        path + "." + name,
                        history
                ));
            }
        }
        for (final StepDefinition.Port port : step.receives()) {
            final RailixValue value = values.get(port.name());
            if (value == null) {
                return Optional.of(rejected(
                        "RUN_SOURCE_VALUE_REQUIRED",
                        "Trigger source value is required: " + port.name() + ".",
                        path + "." + port.name(),
                        history
                ));
            }
            if (!port.shape().accepts(value)) {
                return Optional.of(rejected(
                        "RUN_SOURCE_VALUE_INCOMPATIBLE",
                        "Trigger source value " + port.name() + " requires " + shape(port.shape()) + ".",
                        path + "." + port.name(),
                        history
                ));
            }
            final Optional<String> refinementRejection = port.refinement().rejection(value);
            if (refinementRejection.isPresent()) {
                return Optional.of(rejected(
                        "RUN_SOURCE_VALUE_INCOMPATIBLE",
                        "Trigger source value " + port.name() + " is incompatible: "
                                + refinementRejection.get(),
                        path + "." + port.name(),
                        history
                ));
            }
        }
        return Optional.empty();
    }

    private static void capture(
            final Capture capture,
            final String input,
            final NestedStepPlan step,
            final String status,
            final List<RailixValue> value
    ) {
        if (capture != null) {
            capture.stage(input, step.path(), step.step().use(), status, value);
        }
    }

    private static RunResult writeFailure(
            final int status,
            final String path,
            final List<RunResult.StepExecution> history
    ) {
        final Diagnostic diagnostic = status == WRITE_SPARSE
                ? Diagnostic.atPath(
                        "RUN_ARRAY_TARGET_SPARSE",
                        "Array writes may create at most " + MAX_ARRAY_GAP + " missing items.",
                        path
                )
                : Diagnostic.atPath(
                        "RUN_FIELD_TARGET_CONFLICT",
                        "PATH write crosses an existing primitive value.",
                        path
                );
        return new RunResult.Rejected(List.of(diagnostic), history);
    }

    private static RunResult rejected(
            final String code,
            final String message,
            final String path,
            final List<RunResult.StepExecution> history
    ) {
        return new RunResult.Rejected(List.of(Diagnostic.atPath(code, message, path)), history);
    }

    private static RunResult failed(
            final String code,
            final String message,
            final String step,
            final List<RunResult.StepExecution> history
    ) {
        return new RunResult.Failed(new RunFailure(code, message, step), history);
    }

    private static RunResult failed(
            final String code,
            final String message,
            final String step,
            final String path,
            final List<RunResult.StepExecution> history
    ) {
        return new RunResult.Failed(new RunFailure(code, message, step, path), history);
    }

    private static void record(
            final List<RunResult.StepExecution> history,
            final String step,
            final String outcome
    ) {
        if (history != NO_HISTORY) {
            history.add(new RunResult.StepExecution(step, outcome));
        }
    }

    private static final class EventFrame {
        private final Object owner = new Object();
        private final FrameObject context;

        private EventFrame(final Map<String, RailixValue> values) {
            context = new FrameObject(owner, values);
        }

        private EventFrame(final EventFrame source) {
            context = new FrameObject(owner, source.context.values);
        }

        private EventFrame fork() {
            return new EventFrame(this);
        }

        private RailixValue resolve(final Path path) {
            final List<PathElement> elements = path.elements();
            if (!(elements.getFirst() instanceof Field root) || !"context".equals(root.name())) {
                return null;
            }
            Object value = context;
            for (int depth = 1; depth < elements.size(); depth++) {
                final PathElement element = elements.get(depth);
                if (element instanceof Field field) {
                    value = field(value, field.name());
                } else {
                    value = index(value, ((Index) element).value());
                }
                if (value == null) {
                    return null;
                }
            }
            return freeze(value);
        }

        private int write(final Path path, final RailixValue replacement) {
            final int status = validate(path);
            if (status == WRITE_OK) {
                writeValid(path, replacement);
            }
            return status;
        }

        private int validate(final Path path) {
            final List<PathElement> elements = path.elements();
            if (!(elements.getFirst() instanceof Field root) || !"context".equals(root.name())) {
                return WRITE_CONFLICT;
            }
            Object value = context;
            for (int depth = 1; depth < elements.size(); depth++) {
                final PathElement element = elements.get(depth);
                final boolean leaf = depth == elements.size() - 1;
                if (element instanceof Field field) {
                    if (!object(value)) {
                        return WRITE_CONFLICT;
                    }
                    if (leaf) {
                        return WRITE_OK;
                    }
                    final Object child = field(value, field.name());
                    if (child == null) {
                        return validateFresh(elements, depth + 1);
                    }
                    value = child;
                } else {
                    if (!array(value)) {
                        return WRITE_CONFLICT;
                    }
                    final int index = ((Index) element).value();
                    final int size = size(value);
                    if (index < 0 || (long) index - size > MAX_ARRAY_GAP) {
                        return WRITE_SPARSE;
                    }
                    if (leaf) {
                        return WRITE_OK;
                    }
                    if (index >= size) {
                        return validateFresh(elements, depth + 1);
                    }
                    value = index(value, index);
                }
            }
            return WRITE_CONFLICT;
        }

        private static int validateFresh(final List<PathElement> elements, final int start) {
            for (int depth = start; depth < elements.size(); depth++) {
                if (elements.get(depth) instanceof Index index
                        && (index.value() < 0 || index.value() > MAX_ARRAY_GAP)) {
                    return WRITE_SPARSE;
                }
            }
            return WRITE_OK;
        }

        private void writeValid(final Path path, final RailixValue replacement) {
            final List<PathElement> elements = path.elements();
            Object value = context;
            for (int depth = 1; depth < elements.size(); depth++) {
                invalidate(value);
                final PathElement element = elements.get(depth);
                final boolean leaf = depth == elements.size() - 1;
                if (element instanceof Field field) {
                    final FrameObject object = (FrameObject) value;
                    if (leaf) {
                        object.values.put(field.name(), replacement);
                        return;
                    }
                    Object child = object.values.get(field.name());
                    child = child == null ? container(elements.get(depth + 1)) : owned(child);
                    object.values.put(field.name(), child);
                    value = child;
                } else {
                    final FrameArray array = (FrameArray) value;
                    final int index = ((Index) element).value();
                    while (array.values.size() < index) {
                        array.values.add(RailixValue.nullValue());
                    }
                    if (leaf) {
                        if (index == array.values.size()) {
                            array.values.add(replacement);
                        } else {
                            array.values.set(index, replacement);
                        }
                        return;
                    }
                    final Object child;
                    if (index >= array.values.size()) {
                        child = container(elements.get(depth + 1));
                        array.values.add(child);
                    } else {
                        child = owned(array.values.get(index));
                        array.values.set(index, child);
                    }
                    value = child;
                }
            }
        }

        private Object container(final PathElement element) {
            return element instanceof Field
                    ? new FrameObject(owner, Map.of())
                    : new FrameArray(owner, List.of());
        }

        private Object owned(final Object value) {
            if (value instanceof FrameObject object) {
                return object.owner == owner ? object : new FrameObject(owner, object.values);
            }
            if (value instanceof FrameArray array) {
                return array.owner == owner ? array : new FrameArray(owner, array.values);
            }
            if (value instanceof RailixValue.ObjectValue object) {
                return new FrameObject(owner, object.values());
            }
            if (value instanceof RailixValue.ArrayValue array) {
                return new FrameArray(owner, array.values());
            }
            return value;
        }

        private RailixValue.ObjectValue snapshot() {
            return (RailixValue.ObjectValue) freeze(context);
        }

        private static boolean object(final Object value) {
            return value instanceof FrameObject || value instanceof RailixValue.ObjectValue;
        }

        private static boolean array(final Object value) {
            return value instanceof FrameArray || value instanceof RailixValue.ArrayValue;
        }

        private static Object field(final Object value, final String name) {
            if (value instanceof FrameObject object) {
                return object.values.get(name);
            }
            if (value instanceof RailixValue.ObjectValue object) {
                return object.values().get(name);
            }
            return null;
        }

        private static Object index(final Object value, final int index) {
            if (index < 0) {
                return null;
            }
            if (value instanceof FrameArray array) {
                return index < array.values.size() ? array.values.get(index) : null;
            }
            if (value instanceof RailixValue.ArrayValue array) {
                return index < array.values().size() ? array.values().get(index) : null;
            }
            return null;
        }

        private static int size(final Object value) {
            return value instanceof FrameArray array
                    ? array.values.size()
                    : ((RailixValue.ArrayValue) value).values().size();
        }

        private static RailixValue freeze(final Object value) {
            if (value instanceof RailixValue railix) {
                return railix;
            }
            if (value instanceof FrameObject object) {
                if (object.frozen != null) {
                    return object.frozen;
                }
                final Map<String, RailixValue> values = new LinkedHashMap<>(object.values.size());
                for (final Map.Entry<String, Object> entry : object.values.entrySet()) {
                    values.put(entry.getKey(), freeze(entry.getValue()));
                }
                object.frozen = RailixValue.object(values);
                return object.frozen;
            }
            final FrameArray array = (FrameArray) value;
            if (array.frozen != null) {
                return array.frozen;
            }
            final List<RailixValue> values = new ArrayList<>(array.values.size());
            for (final Object child : array.values) {
                values.add(freeze(child));
            }
            array.frozen = RailixValue.array(values);
            return array.frozen;
        }

        private static void invalidate(final Object value) {
            if (value instanceof FrameObject object) {
                object.frozen = null;
            } else if (value instanceof FrameArray array) {
                array.frozen = null;
            }
        }
    }

    private static final class FrameObject {
        private final Object owner;
        private final Map<String, Object> values;
        private RailixValue.ObjectValue frozen;

        private FrameObject(final Object owner, final Map<String, ?> source) {
            this.owner = owner;
            values = new LinkedHashMap<>(source.size());
            values.putAll(source);
        }
    }

    private static final class FrameArray {
        private final Object owner;
        private final List<Object> values;
        private RailixValue.ArrayValue frozen;

        private FrameArray(final Object owner, final List<?> source) {
            this.owner = owner;
            values = new ArrayList<>(source);
        }
    }

    private static String shape(final ValueShape shape) {
        return shape.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static Optional<String> canonicalResultRejection(final RailixValue value) {
        if (value instanceof RailixValue.NumberValue number) {
            return RailixData.fitsCanonicalNumber(number.value())
                    ? Optional.empty()
                    : Optional.of("Value contains a number outside the canonical 1024-character domain.");
        }
        if (value instanceof RailixValue.NullValue || value instanceof RailixValue.BooleanValue) {
            return Optional.empty();
        }
        return CANONICAL_VALUE.rejection(value);
    }

    /** Source execution plus source-specific response slots mapped by the Trigger definition. */
    public record SourceResult(RunResult result, Map<String, RailixValue> responses) {
        public SourceResult {
            if (result == null || responses == null) {
                throw new IllegalArgumentException("Source result values cannot be Java null.");
            }
            responses = Map.copyOf(responses);
        }
    }

    /** Optional development sink; production execution never creates or invokes one. */
    interface Capture {
        void inputContext(RailixValue.ObjectValue context);

        void inputs(Map<String, RailixValue> inputs);

        void stage(String input, String invocation, String use, String status, List<RailixValue> values);

        void selectedCandidate(String path, int index);
    }

    @FunctionalInterface
    interface StepCall {
        StepResult run(StepInput input) throws InterruptedException;
    }

    record ResultPlan(String name, ValueShape shape, List<RailixValue> defaultValue) {
        ResultPlan {
            defaultValue = List.copyOf(defaultValue);
        }
    }

    record ExecutableStep(
            String use,
            StepDefinition.Kind kind,
            List<StepDefinition.Port> receives,
            List<StepDefinition.Port> returns,
            List<String> outcomes,
            String source,
            Map<String, String> responses
    ) {
        ExecutableStep {
            if (use == null || use.isBlank() || kind == null || source == null) {
                throw new IllegalArgumentException("Executable Step contract must be supplied.");
            }
            receives = List.copyOf(receives);
            returns = List.copyOf(returns);
            outcomes = List.copyOf(outcomes);
            responses = Collections.unmodifiableMap(new LinkedHashMap<>(responses));
            if (outcomes.isEmpty()) {
                throw new IllegalArgumentException("Executable Step must declare an outcome.");
            }
        }

        static ExecutableStep from(final StepDefinition definition) {
            return new ExecutableStep(
                    definition.id(),
                    definition.kind(),
                    definition.receives(),
                    definition.returns(),
                    definition.outcomes(),
                    definition.source().map(StepDefinition.Source::name).orElse(""),
                    definition.source().map(StepDefinition.Source::responses).orElse(Map.of())
            );
        }

        String primaryOutcome() {
            return outcomes.getFirst();
        }
    }

    record CallPlan(
            String id,
            ExecutableStep step,
            Map<String, Binding> inputs,
            Map<String, Path> receives,
            Map<String, Path> returns,
            String path
    ) {
        CallPlan {
            inputs = Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
            receives = Collections.unmodifiableMap(new LinkedHashMap<>(receives));
            returns = Collections.unmodifiableMap(new LinkedHashMap<>(returns));
        }
    }

    sealed interface Binding permits JsonBinding, PathBinding, ChoiceBinding, CandidatesBinding, MatcherGroupsBinding,
            StepsBinding {
    }

    record JsonBinding(List<RailixValue> value) implements Binding {
        JsonBinding {
            value = List.copyOf(value);
        }
    }

    record PathBinding(Path path, StepDefinition.PathAccess access) implements Binding {
    }

    record ChoiceBinding(
            String option,
            Map<String, Binding> inputs,
            List<StepDefinition.InputReference> valueSources
    ) implements Binding {
        ChoiceBinding {
            inputs = Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
            valueSources = List.copyOf(valueSources);
        }

        Optional<StepDefinition.InputReference> valueSource() {
            return valueSources.isEmpty() ? Optional.empty() : Optional.of(valueSources.getFirst());
        }
    }

    record CandidatesBinding(List<CandidatePlan> candidates, String path) implements Binding {
        CandidatesBinding {
            candidates = List.copyOf(candidates);
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("Candidate input path must be supplied.");
            }
        }
    }

    record MatcherGroupsBinding(List<List<CandidatePlan>> groups) implements Binding {
        MatcherGroupsBinding {
            groups = groups.stream().map(List::copyOf).toList();
        }
    }

    record CandidatePlan(
            ChoiceBinding source,
            List<NestedStepPlan> transforms,
            List<List<NestedStepPlan>> predicates
    ) {
        CandidatePlan {
            if (source == null) {
                throw new IllegalArgumentException("Candidate plan source must be supplied.");
            }
            transforms = List.copyOf(transforms);
            predicates = predicates.stream().map(List::copyOf).toList();
        }
    }

    record StepsBinding(
            List<NestedStepPlan> steps,
            StepDefinition.ValueSource valueSource,
            boolean propagatesOutcomes
    ) implements Binding {
        StepsBinding {
            steps = List.copyOf(steps);
            if (valueSource == null) {
                throw new IllegalArgumentException("Nested Step value source cannot be Java null.");
            }
        }
    }

    record NestedStepPlan(
            ExecutableStep step,
            Map<String, Binding> inputs,
            String path,
            StepCall call
    ) {
        NestedStepPlan {
            inputs = Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
            if (call == null) {
                throw new IllegalArgumentException("Nested Step call cannot be Java null.");
            }
        }
    }

    record Path(List<PathElement> elements) {
        Path {
            if (elements == null || elements.size() < 2 || elements.size() > RailixData.DEFAULT_MAX_DEPTH) {
                throw new IllegalArgumentException(
                        "Runtime path must contain 2 through " + RailixData.DEFAULT_MAX_DEPTH + " elements."
                );
            }
            elements = List.copyOf(elements);
        }
    }

    sealed interface PathElement permits Field, Index {
    }

    record Field(String name) implements PathElement {
    }

    record Index(int value) implements PathElement {
    }

    private record MatcherEvaluation(
            boolean matched,
            RailixValue value,
            Resolution children,
            RunResult failure
    ) {
    }

    private static final class ProgramScope {
        private Thread owner;
        private List<NestedStepPlan> steps;
        private RailixValue value;
        private String missingOutcome;
        private EventFrame frame;
        private String primaryOutcome;
        private List<RunResult.StepExecution> history;
        private Capture capture;
        private String inputName;

        private ProgramScope(
                final List<NestedStepPlan> steps,
                final RailixValue value,
                final String missingOutcome,
                final EventFrame frame,
                final String primaryOutcome,
                final List<RunResult.StepExecution> history,
                final Capture capture,
                final String inputName
        ) {
            owner = Thread.currentThread();
            this.steps = steps;
            this.value = value;
            this.missingOutcome = missingOutcome;
            this.frame = frame;
            this.primaryOutcome = primaryOutcome;
            this.history = history;
            this.capture = capture;
            this.inputName = inputName;
        }

        private StepInput.ProgramResult run() throws InterruptedException {
            if (frame == null) {
                throw new IllegalStateException("Nested Step program is no longer active.");
            }
            if (Thread.currentThread() != owner) {
                throw new IllegalStateException("Nested Step program belongs to its invocation thread.");
            }
            return value == null
                    ? new StepInput.ProgramResult(primaryOutcome, missingOutcome, List.of())
                    : program(steps, value, frame, primaryOutcome, history, capture, inputName);
        }

        private void close() {
            owner = null;
            steps = List.of();
            value = null;
            missingOutcome = null;
            frame = null;
            primaryOutcome = null;
            history = null;
            capture = null;
            inputName = null;
        }
    }

    private record Resolution(
            StepInput input,
            Map<String, RailixValue> values,
            Map<String, PathBinding> paths,
            RunResult failure,
            List<ProgramScope> programScopes
    ) {
        private void closePrograms() {
            WorkflowRuntime.closePrograms(programScopes);
        }
    }

    private static final class ProgramAbort extends RuntimeException {
        private final RunResult result;

        private ProgramAbort(final RunResult result) {
            super(null, null, false, false);
            this.result = result;
        }

        private RunResult result() {
            return result;
        }
    }
}
