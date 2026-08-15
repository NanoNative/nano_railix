package dev.nanonative.railix.core.project;

import dev.nanonative.railix.core.runtime.RunFailure;
import dev.nanonative.railix.core.runtime.RunResult;
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
            final StepPlan step,
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

    static Inputs inputs(final Map<String, RailixValue> received, final String primaryOutcome) {
        return new Inputs(received, primaryOutcome);
    }

    static final class Execution {
        private EventFrame frame;
        private RailixValue.ObjectValue frozenContext;
        private RunResult aborted;
        private Capture capture;
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
            final LinkedHashMap<String, Object> context = new LinkedHashMap<>(streamContext.values());
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
                final StepPlan plan,
                final StepCall implementation,
                final Map<String, RailixValue> received,
                final InputResolver resolver
        ) {
            return invoke(plan, implementation, received, resolver, null);
        }

        int observe(
                final StepPlan plan,
                final StepCall implementation,
                final Map<String, RailixValue> received,
                final InputResolver resolver,
                final Capture capture
        ) {
            if (capture == null) {
                throw new IllegalArgumentException("Workflow observation capture cannot be Java null.");
            }
            capture.inputContext(frame.snapshot());
            return invoke(plan, implementation, received, resolver, capture);
        }

        private int invoke(
                final StepPlan plan,
                final StepCall implementation,
                final Map<String, RailixValue> received,
                final InputResolver resolver,
                final Capture observed
        ) {
            if (plan == null || implementation == null || received == null || resolver == null) {
                return abort(failed(
                        "RUN_PLAN_MISSING",
                        "Compiled Step invocation is incomplete.",
                        plan == null ? "" : plan.id(),
                        history
                ));
            }
            if (Thread.currentThread().isInterrupted()) {
                return abort(new RunResult.Cancelled(history));
            }
            Map<String, RailixValue> resolvedReceives = received;
            if (plan.mappedReceives() && !plan.receives().isEmpty()) {
                final Map<String, RailixValue> mapped = LinkedHashMap.newLinkedHashMap(plan.receives().size());
                for (final Port port : plan.receives()) {
                    final Path source = plan.receivePaths().get(port.name());
                    final RailixValue value = source == null ? null : frame.resolve(source);
                    if (value == null) {
                        return abort(rejected(
                                "RUN_STEP_RECEIVE_REQUIRED",
                                "Step receive path has no value: " + port.name() + ".",
                                plan.path() + ".receives." + port.name(),
                                history
                        ));
                    }
                    if (!port.shape().accepts(value)) {
                        return abort(rejected(
                                "RUN_STEP_RECEIVE_INCOMPATIBLE",
                                "Step receive " + port.name() + " requires " + shape(port.shape())
                                        + " but receives " + shape(ValueShape.shapeOf(value)) + ".",
                                plan.path() + ".receives." + port.name(),
                                history
                        ));
                    }
                    final Optional<String> rejection = port.refinement().rejection(value);
                    if (rejection.isPresent()) {
                        return abort(rejected(
                                "RUN_STEP_RECEIVE_INCOMPATIBLE",
                                "Step receive " + port.name() + " is incompatible: " + rejection.get(),
                                plan.path() + ".receives." + port.name(),
                                history
                        ));
                    }
                    mapped.put(port.name(), value);
                }
                resolvedReceives = mapped;
            }
            final Capture previous = capture;
            capture = observed;
            try {
                final Inputs resolved = resolver.resolve(this, resolvedReceives, plan.primaryOutcome());
                if (resolved == null) {
                    return abort(failed(
                            "RUN_PLAN_MISSING",
                            "Compiled Step input resolver returned Java null.",
                            plan.id(),
                            history
                    ));
                }
                return WorkflowRuntime.call(plan, implementation, this, resolved);
            } finally {
                capture = previous;
            }
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

        RailixValue resolve(final Path path) {
            return frame.resolve(path);
        }

        private RailixValue.ObjectValue context() {
            if (frozenContext == null) {
                frozenContext = frame.snapshot();
            }
            return frozenContext;
        }
    }

    private static int call(
            final StepPlan plan,
            final StepCall implementation,
            final Execution execution,
            final Inputs resolution
    ) {
        final List<RunResult.StepExecution> history = execution.history;
        final Capture capture = execution.capture;
        final EventFrame frame = execution.frame;
        if (resolution.failure != null) {
            resolution.closePrograms();
            return execution.abort(resolution.failure);
        }
        if (capture != null) {
            capture.inputs(resolution.values);
        }
        if (plan.use().isEmpty()) {
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
        final int outcome = plan.outcomes().indexOf(result.outcome());
        if (outcome < 0) {
            return execution.abort(failed(
                    "STEP_OUTCOME_INVALID",
                    "Step returned undeclared outcome: " + result.outcome() + ".",
                    plan.id(),
                    history
            ));
        }
        if (!plan.primaryOutcome().equals(result.outcome()) && !result.outputs().isEmpty()) {
            return execution.abort(failed(
                    "STEP_OUTPUT_UNEXPECTED",
                    "Step returned output for outcome " + result.outcome() + ".",
                    plan.id(),
                    history
            ));
        }
        final boolean primary = plan.primaryOutcome().equals(result.outcome());
        if (primary) {
            boolean complete = result.outputs().size() == plan.returns().size();
            for (final Port port : plan.returns()) {
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
            for (final Port port : plan.returns()) {
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
                final Path target = plan.returnPaths().get(port.name());
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
            final PathBinding target = resolution.paths.get(write.getKey());
            if (target == null || !target.writable()) {
                return execution.abort(failed(
                        "STEP_WRITE_UNDECLARED",
                        "Step wrote through an undeclared writable PATH input: " + write.getKey() + ".",
                        plan.id(),
                        history
                ));
            }
        }
        final int mutations = (primary ? plan.returns().size() : 0) + result.writes().size();
        final EventFrame next = mutations > 1 ? frame.fork() : frame;
        if (primary) {
            for (final Port port : plan.returns()) {
                final int status = next.write(plan.returnPaths().get(port.name()), result.outputs().get(port.name()));
                if (status != WRITE_OK) {
                    return execution.abort(writeFailure(
                            status,
                            plan.path() + ".returns." + port.name(),
                            history
                    ));
                }
            }
        }
        for (final Map.Entry<String, PathBinding> path : resolution.paths.entrySet()) {
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

    static final class Inputs {
        private final Map<String, RailixValue> values;
        private Map<String, String> options = Map.of();
        private Map<String, StepInput.Program> programs = Map.of();
        private Map<String, StepInput> selected = Map.of();
        private Map<String, PathBinding> paths = Map.of();
        private final String primaryOutcome;
        private List<ProgramScope> programScopes;
        private RunResult failure;

        private Inputs(final Map<String, RailixValue> received, final String primaryOutcome) {
            values = new LinkedHashMap<>(received);
            this.primaryOutcome = primaryOutcome;
        }

        void value(final String name, final RailixValue value) {
            if (failure == null && value != null) {
                values.put(name, value);
            }
        }

        void path(
                final String name,
                final PathBinding binding,
                final Execution execution
        ) {
            if (failure != null) {
                return;
            }
            paths = put(paths, name, binding);
            if (binding.readable()) {
                final RailixValue value = execution.resolve(binding.path());
                if (value != null) {
                    values.put(name, value);
                }
            }
        }

        void choice(
                final String name,
                final String option,
                final InputResolver resolver,
                final List<InputReference> valueSources,
                final Execution execution
        ) {
            if (failure != null) {
                return;
            }
            final Inputs children = resolver.resolve(execution, Map.of(), primaryOutcome);
            merge(children);
            if (failure != null) {
                return;
            }
            options = put(options, name, option);
            selected = put(selected, name, children.input());
            final RailixValue selectedValue = valueSources.isEmpty()
                    ? null
                    : valueSources.getFirst().owned()
                    ? children.values.get(valueSources.getFirst().input())
                    : values.get(valueSources.getFirst().input());
            if (selectedValue != null) {
                values.put(name, selectedValue);
            }
        }

        void candidates(
                final String name,
                final List<CandidatePlan> candidates,
                final String path,
                final Execution execution
        ) {
            if (failure != null) {
                return;
            }
            for (int index = 0; index < candidates.size(); index++) {
                final CandidatePlan candidate = candidates.get(index);
                final MatcherEvaluation evaluation = evaluate(
                        candidate,
                        values,
                        execution,
                        primaryOutcome,
                        name + "[" + index + "].when"
                );
                if (evaluation.failure() != null) {
                    evaluation.children().closePrograms();
                    fail(evaluation.failure());
                    return;
                }
                if (evaluation.matched()) {
                    merge(evaluation.children());
                    values.put(name, evaluation.value());
                    options = put(options, name, candidate.option());
                    selected = put(selected, name, evaluation.children().input());
                    if (execution.capture != null) {
                        execution.capture.selectedCandidate(path, index);
                    }
                    return;
                }
                evaluation.children().closePrograms();
            }
        }

        void matcherGroups(
                final String name,
                final List<List<CandidatePlan>> groups,
                final Execution execution
        ) {
            if (failure != null) {
                return;
            }
            boolean matched = false;
            groupLoop:
            for (int groupIndex = 0; groupIndex < groups.size(); groupIndex++) {
                final List<CandidatePlan> group = groups.get(groupIndex);
                for (int matcherIndex = 0; matcherIndex < group.size(); matcherIndex++) {
                    final MatcherEvaluation evaluation = evaluate(
                            group.get(matcherIndex),
                            values,
                            execution,
                            primaryOutcome,
                            name + "[" + groupIndex + "][" + matcherIndex + "].when"
                    );
                    if (evaluation.failure() != null) {
                        evaluation.children().closePrograms();
                        fail(evaluation.failure());
                        return;
                    }
                    evaluation.children().closePrograms();
                    if (!evaluation.matched()) {
                        continue groupLoop;
                    }
                }
                matched = true;
                break;
            }
            values.put(name, RailixValue.bool(matched));
        }

        void program(
                final String name,
                final NestedProgram steps,
                final String sourceInput,
                final String missingOutcome,
                final Execution execution
        ) {
            if (failure != null) {
                return;
            }
            final ProgramScope program = new ProgramScope(
                    steps,
                    values.get(sourceInput),
                    missingOutcome.isEmpty() ? primaryOutcome : missingOutcome,
                    execution,
                    primaryOutcome,
                    name
            );
            programs = put(programs, name, program::run);
            if (programScopes == null) {
                programScopes = new ArrayList<>();
            }
            programScopes.add(program);
        }

        private void merge(final Inputs children) {
            if (children == null) {
                fail(failed(
                        "RUN_PLAN_MISSING",
                        "Compiled child input resolver returned Java null.",
                        "",
                        List.of()
                ));
                return;
            }
            if (children.programScopes != null && !children.programScopes.isEmpty()) {
                if (programScopes == null) {
                    programScopes = new ArrayList<>();
                }
                programScopes.addAll(children.programScopes);
                children.programScopes = null;
            }
            if (children.failure != null) {
                fail(children.failure);
            }
        }

        private void fail(final RunResult result) {
            if (failure == null) {
                failure = result;
            }
        }

        private static <K, V> Map<K, V> put(final Map<K, V> current, final K key, final V value) {
            final Map<K, V> mutable;
            if (current.isEmpty()) {
                mutable = new LinkedHashMap<>();
            } else {
                mutable = current;
            }
            mutable.put(key, value);
            return mutable;
        }

        private StepInput input() {
            return new StepInput(values, options, programs, selected, primaryOutcome);
        }

        private void closePrograms() {
            if (programScopes != null) {
                for (final ProgramScope program : programScopes) {
                    program.close();
                }
                programScopes = null;
            }
        }
    }

    private static MatcherEvaluation evaluate(
            final CandidatePlan candidate,
            final Map<String, RailixValue> values,
            final Execution execution,
            final String primaryOutcome,
            final String predicateInput
    ) {
        final Inputs children = candidate.inputs().resolve(execution, Map.of(), primaryOutcome);
        if (children == null) {
            final Inputs failed = inputs(Map.of(), primaryOutcome);
            failed.fail(failed(
                    "RUN_PLAN_MISSING",
                    "Compiled candidate input resolver returned Java null.",
                    "",
                    execution.history
            ));
            return new MatcherEvaluation(false, null, failed, failed.failure);
        }
        if (children.failure != null) {
            return new MatcherEvaluation(false, null, children, children.failure);
        }
        final RailixValue candidateValue = candidate.valueSources().isEmpty()
                ? null
                : candidate.valueSources().getFirst().owned()
                ? children.values.get(candidate.valueSources().getFirst().input())
                : values.get(candidate.valueSources().getFirst().input());
        if (candidateValue == null) {
            return new MatcherEvaluation(false, null, children, null);
        }
        if (candidate.predicates().isEmpty()) {
            return new MatcherEvaluation(true, candidateValue, children, null);
        }
        try {
            final RailixValue prepared = candidate.transforms().steps().isEmpty()
                    ? candidateValue
                    : program(
                            candidate.transforms(),
                            candidateValue,
                            execution,
                            primaryOutcome,
                            predicateInput + ".transforms"
                    ).values().getFirst();
            for (int index = 0; index < candidate.predicates().size(); index++) {
                final NestedProgram condition = candidate.predicates().get(index);
                final StepInput.ProgramResult predicate = program(
                        condition,
                        prepared,
                        execution,
                        primaryOutcome,
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
            return new MatcherEvaluation(false, null, children, new RunResult.Cancelled(execution.history));
        }
    }

    private static StepInput.ProgramResult program(
            final NestedProgram program,
            RailixValue value,
            final Execution execution,
            final String enclosingOutcome,
            final String inputName
    ) throws InterruptedException {
        final List<RunResult.StepExecution> history = execution.history;
        final Capture capture = execution.capture;
        for (final NestedStep step : program.steps()) {
            final StepPlan executable = step.step();
            final Port receive = executable.receives().getFirst();
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
            final Inputs resolved = step.inputs().resolve(
                    execution,
                    Map.of(receive.name(), value),
                    executable.primaryOutcome()
            );
            if (resolved == null) {
                throw new ProgramAbort(failed(
                        "RUN_PLAN_MISSING",
                        "Compiled nested input resolver returned Java null.",
                        executable.use(),
                        step.path(),
                        history
                ));
            }
            if (resolved.failure != null) {
                resolved.closePrograms();
                throw new ProgramAbort(resolved.failure);
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
            final Port output = executable.returns().getFirst();
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

    private static Optional<RunResult> validateReceives(
            final StepPlan step,
            final Map<String, RailixValue> values,
            final String path,
            final List<RunResult.StepExecution> history
    ) {
        for (final String name : values.keySet()) {
            boolean declared = false;
            for (final Port port : step.receives()) {
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
        for (final Port port : step.receives()) {
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
            final NestedStep step,
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

        private EventFrame(final LinkedHashMap<String, Object> values) {
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

        private FrameObject(final Object owner, final LinkedHashMap<String, Object> values) {
            this.owner = owner;
            this.values = values;
        }

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
    }

    record Port(String name, ValueShape shape, ValueRefinement refinement) {
    }

    record StepPlan(
            String id,
            String use,
            boolean mappedReceives,
            List<Port> receives,
            List<Port> returns,
            List<String> outcomes,
            Map<String, Path> receivePaths,
            Map<String, Path> returnPaths,
            String path
    ) {
        String primaryOutcome() {
            return outcomes.getFirst();
        }
    }

    @FunctionalInterface
    interface InputResolver {
        Inputs resolve(Execution execution, Map<String, RailixValue> received, String primaryOutcome);
    }

    record PathBinding(Path path, boolean readable, boolean writable) {
    }

    record InputReference(boolean owned, String input) {
    }

    record CandidatePlan(
            String option,
            InputResolver inputs,
            List<InputReference> valueSources,
            NestedProgram transforms,
            List<NestedProgram> predicates
    ) {
    }

    record NestedProgram(List<NestedStep> steps) {
    }

    record NestedStep(
            StepPlan step,
            InputResolver inputs,
            String path,
            StepCall call
    ) {
    }

    record Path(List<PathElement> elements) {
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
            Inputs children,
            RunResult failure
    ) {
    }

    private static final class ProgramScope {
        private Thread owner;
        private NestedProgram steps;
        private RailixValue value;
        private String missingOutcome;
        private Execution execution;
        private String primaryOutcome;
        private String inputName;

        private ProgramScope(
                final NestedProgram steps,
                final RailixValue value,
                final String missingOutcome,
                final Execution execution,
                final String primaryOutcome,
                final String inputName
        ) {
            owner = Thread.currentThread();
            this.steps = steps;
            this.value = value;
            this.missingOutcome = missingOutcome;
            this.execution = execution;
            this.primaryOutcome = primaryOutcome;
            this.inputName = inputName;
        }

        private StepInput.ProgramResult run() throws InterruptedException {
            if (execution == null) {
                throw new IllegalStateException("Nested Step program is no longer active.");
            }
            if (Thread.currentThread() != owner) {
                throw new IllegalStateException("Nested Step program belongs to its invocation thread.");
            }
            return value == null
                    ? new StepInput.ProgramResult(primaryOutcome, missingOutcome, List.of())
                    : program(steps, value, execution, primaryOutcome, inputName);
        }

        private void close() {
            owner = null;
            steps = null;
            value = null;
            missingOutcome = null;
            execution = null;
            primaryOutcome = null;
            inputName = null;
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
