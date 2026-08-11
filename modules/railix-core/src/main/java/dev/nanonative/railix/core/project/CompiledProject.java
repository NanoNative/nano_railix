package dev.nanonative.railix.core.project;

import dev.nanonative.railix.core.runtime.RunFailure;
import dev.nanonative.railix.core.runtime.RunResult;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.step.StepHandler;
import dev.nanonative.railix.core.step.StepInput;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** One immutable executable project. Every stream item owns one isolated workflow context. */
public final class CompiledProject {
    private final Map<String, TriggerPlan> triggers;
    private final Map<String, TriggerPlan> sources;

    CompiledProject(final List<TriggerPlan> triggers) {
        final Map<String, TriggerPlan> indexed = new LinkedHashMap<>();
        final Map<String, TriggerPlan> ingress = new LinkedHashMap<>();
        triggers.forEach(trigger -> {
            indexed.put(trigger.id(), trigger);
            trigger.definition().source().ifPresent(source -> ingress.put(source.name(), trigger));
        });
        this.triggers = Map.copyOf(indexed);
        sources = Map.copyOf(ingress);
    }

    /** Runs one example or explicit workflow context after the selected Trigger boundary. */
    public RunResult run(final String triggerId, final StreamItem item) {
        return execute(
                triggerId,
                item,
                "",
                new LinkedHashMap<>(),
                new ArrayList<>(),
                new LinkedHashMap<>(),
                new ArrayList<>()
        );
    }

    /** Runs the Trigger owning one opaque external source and returns its mapped response slots. */
    public SourceResult runSource(final String source, final Map<String, RailixValue> values) {
        if (source == null || source.isBlank()) {
            return new SourceResult(rejected(
                    "RUN_SOURCE_REQUIRED",
                    "Trigger source must be a non-blank string.",
                    "source",
                    List.of()
            ), Map.of());
        }
        if (values == null) {
            return new SourceResult(rejected(
                    "RUN_SOURCE_VALUES_REQUIRED",
                    "Trigger source values must be supplied.",
                    "values",
                    List.of()
            ), Map.of());
        }
        final TriggerPlan trigger = sources.get(source);
        if (trigger == null) {
            return new SourceResult(rejected(
                    "RUN_SOURCE_UNKNOWN",
                    "Project has no Trigger for source: " + source + ".",
                    "source",
                    List.of()
            ), Map.of());
        }
        final RunResult ingress = validateReceives(trigger.definition(), values, trigger.path(), List.of());
        if (ingress != null) {
            return new SourceResult(ingress, Map.of());
        }
        RailixValue.ObjectValue frame = frame(
                trigger,
                new StreamItem(false, RailixValue.object(Map.of()))
        );
        final List<RunResult.StepExecution> history = new ArrayList<>();
        final CallResult call = call(
                trigger.node(),
                frame,
                values,
                history,
                false,
                new LinkedHashMap<>(),
                new ArrayList<>(),
                new LinkedHashMap<>()
        );
        if (call instanceof Aborted aborted) {
            return new SourceResult(aborted.result(), Map.of());
        }
        final Called started = (Called) call;
        frame = started.frame();
        history.add(new RunResult.StepExecution(trigger.id(), started.outcome()));
        final String destination = trigger.node().destinations().get(started.outcome());
        if (destination == null) {
            return new SourceResult(failed(
                    "STEP_OUTCOME_UNROUTED",
                    "Trigger returned an outcome without a connection: " + started.outcome() + ".",
                    trigger.id(),
                    history
            ), Map.of());
        }
        final RunResult result = execute(
                trigger,
                frame,
                destination,
                "",
                new LinkedHashMap<>(),
                new ArrayList<>(),
                new LinkedHashMap<>(),
                new ArrayList<>(),
                history
        );
        if (!(result instanceof RunResult.Succeeded succeeded)) {
            return new SourceResult(result, Map.of());
        }
        final Map<String, RailixValue> responses = new LinkedHashMap<>();
        trigger.definition().source().orElseThrow().responses().forEach((slot, resultName) ->
                responses.put(slot, succeeded.context().values().get(resultName))
        );
        return new SourceResult(result, responses);
    }

    /**
     * Executes a whole example flow and captures one selected Step boundary.
     *
     * @param triggerId Trigger node that admits the example
     * @param stepId selected Step whose resolved inputs and nested stages are captured
     * @param item explicit example item; production traffic is never sampled
     * @return the normal flow result plus the context immediately before the selected Step executes
     */
    public PreviewResult preview(
            final String triggerId,
            final String stepId,
            final StreamItem item
    ) {
        if (stepId == null || stepId.isBlank()) {
            return new PreviewResult(
                    rejected("PREVIEW_STEP_REQUIRED", "Step id must be a non-blank string.", "step", List.of()),
                    Map.of(),
                    RailixValue.object(Map.of()),
                    List.of(),
                    Map.of()
            );
        }
        final Map<String, RailixValue> inputs = new LinkedHashMap<>();
        final List<NestedStepStage> stages = new ArrayList<>();
        final Map<String, Integer> selectedCandidates = new LinkedHashMap<>();
        final List<RailixValue.ObjectValue> inputContexts = new ArrayList<>(1);
        final RunResult result = execute(
                triggerId,
                item,
                stepId,
                inputs,
                stages,
                selectedCandidates,
                inputContexts
        );
        return new PreviewResult(
                result,
                inputs,
                inputContexts.isEmpty() ? RailixValue.object(Map.of()) : inputContexts.getFirst(),
                stages,
                selectedCandidates
        );
    }

    private RunResult execute(
            final String triggerId,
            final StreamItem item,
            final String previewTarget,
            final Map<String, RailixValue> previewInputs,
            final List<NestedStepStage> previewStages,
            final Map<String, Integer> selectedCandidates,
            final List<RailixValue.ObjectValue> previewInputContexts
    ) {
        if (triggerId == null || triggerId.isBlank()) {
            return rejected("RUN_TRIGGER_REQUIRED", "Trigger id must be a non-blank string.", "trigger", List.of());
        }
        if (item == null) {
            return rejected("RUN_INPUT_REQUIRED", "Stream item must be supplied.", "input", List.of());
        }
        final TriggerPlan trigger = triggers.get(triggerId);
        if (trigger == null) {
            return rejected(
                    "RUN_TRIGGER_UNKNOWN",
                    "Trigger is not part of this project: " + triggerId + ".",
                    "trigger",
                    List.of()
            );
        }
        if (item.context().values().containsKey("runtime")) {
            return rejected(
                    "RUN_RUNTIME_RESERVED",
                    "context.runtime is supplied by Railix.",
                    "context.runtime",
                    List.of()
            );
        }
        if (!previewTarget.isEmpty() && !trigger.nodes().containsKey(previewTarget)) {
            return rejected(
                    "PREVIEW_STEP_UNKNOWN",
                    "Step is not part of the selected Trigger branch: " + previewTarget + ".",
                    "step",
                    List.of()
            );
        }
        if (Thread.currentThread().isInterrupted()) {
            return new RunResult.Cancelled(List.of());
        }
        return execute(
                trigger,
                frame(trigger, item),
                trigger.start(),
                previewTarget,
                previewInputs,
                previewStages,
                selectedCandidates,
                previewInputContexts,
                new ArrayList<>()
        );
    }

    private RunResult execute(
            final TriggerPlan trigger,
            RailixValue.ObjectValue frame,
            String current,
            final String previewTarget,
            final Map<String, RailixValue> previewInputs,
            final List<NestedStepStage> previewStages,
            final Map<String, Integer> selectedCandidates,
            final List<RailixValue.ObjectValue> previewInputContexts,
            final List<RunResult.StepExecution> history
    ) {
        final boolean preview = !previewTarget.isEmpty();
        while (!"end".equals(current)) {
            final NodePlan plan = trigger.nodes().get(current);
            if (plan == null) {
                return failed("RUN_PLAN_MISSING", "Compiled Step plan is missing.", current, history);
            }
            final boolean capture = preview && current.equals(previewTarget);
            if (capture) {
                previewInputContexts.add(context(frame));
            }
            final CallResult call = call(
                    plan,
                    frame,
                    Map.of(),
                    history,
                    capture,
                    previewInputs,
                    previewStages,
                    selectedCandidates
            );
            if (call instanceof Aborted aborted) {
                return aborted.result();
            }
            final Called completed = (Called) call;
            frame = completed.frame();
            history.add(new RunResult.StepExecution(plan.id(), completed.outcome()));
            if (capture) {
                return new RunResult.Succeeded(context(frame), history);
            }
            final String destination = plan.destinations().get(completed.outcome());
            if (destination == null) {
                return failed(
                        "STEP_OUTCOME_UNROUTED",
                        "Step returned an outcome without a connection: " + completed.outcome() + ".",
                        plan.id(),
                        history
                );
            }
            current = destination;
        }
        if (preview) {
            return rejected(
                    "PREVIEW_STEP_NOT_REACHED",
                    "Step was not reached by this stream item: " + previewTarget + ".",
                    "step",
                    history
            );
        }
        if (Thread.currentThread().isInterrupted()) {
            return new RunResult.Cancelled(history);
        }
        final RailixValue.ObjectValue context = context(frame);
        for (final ResultPlan result : trigger.results()) {
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
        }
        return new RunResult.Succeeded(context, history);
    }

    private CallResult call(
            final NodePlan plan,
            final RailixValue.ObjectValue frame,
            final Map<String, RailixValue> received,
            final List<RunResult.StepExecution> history,
            final boolean capture,
            final Map<String, RailixValue> previewInputs,
            final List<NestedStepStage> previewStages,
            final Map<String, Integer> selectedCandidates
    ) {
        if (Thread.currentThread().isInterrupted()) {
            return new Aborted(new RunResult.Cancelled(history));
        }
        Map<String, RailixValue> receivedValues = received;
        if (plan.definition().kind() == StepDefinition.Kind.STEP && !plan.receives().isEmpty()) {
            final Map<String, RailixValue> mapped = new LinkedHashMap<>();
            for (final StepDefinition.Port port : plan.definition().receives()) {
                final Path source = plan.receives().get(port.name());
                final RailixValue value = source == null
                        ? null
                        : resolve(frame, source).stream().findFirst().orElse(null);
                if (value == null) {
                    return new Aborted(rejected(
                            "RUN_STEP_RECEIVE_REQUIRED",
                            "Step receive path has no value: " + port.name() + ".",
                            plan.path() + ".receives." + port.name(),
                            history
                    ));
                }
                if (!port.shape().accepts(value)) {
                    return new Aborted(rejected(
                            "RUN_STEP_RECEIVE_INCOMPATIBLE",
                            "Step receive " + port.name() + " requires " + shape(port.shape())
                                    + " but receives " + shape(ValueShape.shapeOf(value)) + ".",
                            plan.path() + ".receives." + port.name(),
                            history
                    ));
                }
                final Optional<String> rejection = port.refinement().rejection(value);
                if (rejection.isPresent()) {
                    return new Aborted(rejected(
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
                plan.definition().primaryOutcome(),
                history,
                capture,
                previewStages,
                selectedCandidates
        );
        if (resolution.failure() != null) {
            return new Aborted(resolution.failure());
        }
        if (capture) {
            previewInputs.putAll(resolution.previewValues());
        }
        final StepHandler handler = plan.definition().handler().orElse(null);
        if (handler == null) {
            return new Aborted(failed(
                    "STEP_HANDLER_REQUIRED",
                    "Executable Step has no handler.",
                    plan.id(),
                    history
            ));
        }
        final StepResult result;
        try {
            result = handler.run(resolution.input());
        } catch (final ProgramAbort abort) {
            return new Aborted(abort.result());
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new Aborted(new RunResult.Cancelled(history));
        } catch (final RuntimeException exception) {
            return new Aborted(failed(
                    "STEP_IMPLEMENTATION_FAULT",
                    "Step implementation threw an unexpected exception.",
                    plan.id(),
                    history
            ));
        }
        if (Thread.currentThread().isInterrupted()) {
            return new Aborted(new RunResult.Cancelled(history));
        }
        if (result == null) {
            return new Aborted(failed(
                    "STEP_RESULT_REQUIRED",
                    "Step implementation returned Java null.",
                    plan.id(),
                    history
            ));
        }
        if (!plan.outcomes().contains(result.outcome())) {
            return new Aborted(failed(
                    "STEP_OUTCOME_INVALID",
                    "Step returned undeclared outcome: " + result.outcome() + ".",
                    plan.id(),
                    history
            ));
        }
        if (!plan.definition().primaryOutcome().equals(result.outcome()) && !result.outputs().isEmpty()) {
            return new Aborted(failed(
                    "STEP_OUTPUT_UNEXPECTED",
                    "Step returned output for outcome " + result.outcome() + ".",
                    plan.id(),
                    history
            ));
        }
        RailixValue.ObjectValue next = frame;
        if (plan.definition().primaryOutcome().equals(result.outcome())) {
            if (result.outputs().size() != plan.definition().returns().size()
                    || plan.definition().returns().stream()
                    .anyMatch(port -> !result.outputs().containsKey(port.name()))) {
                return new Aborted(failed(
                        "STEP_OUTPUT_INVALID",
                        "Step did not return every declared output exactly once.",
                        plan.id(),
                        history
                ));
            }
            for (final StepDefinition.Port port : plan.definition().returns()) {
                final RailixValue output = result.outputs().get(port.name());
                if (!port.shape().accepts(output)) {
                    return new Aborted(failed(
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
                    return new Aborted(failed(
                            "STEP_OUTPUT_INVALID",
                            "Step output " + port.name() + " is incompatible."
                                    + rejection.map(value -> " " + value).orElse(""),
                            plan.id(),
                            history
                    ));
                }
                final Path target = plan.returns().get(port.name());
                if (target == null) {
                    return new Aborted(failed(
                            "STEP_RETURN_TARGET_REQUIRED",
                            "Compiled Step return target is missing: " + port.name() + ".",
                            plan.id(),
                            history
                    ));
                }
                final WriteResult written = write(
                        next,
                        target,
                        output,
                        plan.path() + ".returns." + port.name()
                );
                if (!written.diagnostics().isEmpty()) {
                    return new Aborted(new RunResult.Rejected(written.diagnostics(), history));
                }
                next = (RailixValue.ObjectValue) written.value();
            }
        }
        for (final Map.Entry<String, RailixValue> write : result.writes().entrySet()) {
            final PathBinding target = resolution.paths().get(write.getKey());
            if (target == null || !target.access().writable()) {
                return new Aborted(failed(
                        "STEP_WRITE_UNDECLARED",
                        "Step wrote through an undeclared writable PATH input: " + write.getKey() + ".",
                        plan.id(),
                        history
                ));
            }
            final WriteResult written = write(
                    next,
                    target.path(),
                    write.getValue(),
                    plan.path() + ".inputs." + write.getKey()
            );
            if (!written.diagnostics().isEmpty()) {
                return new Aborted(new RunResult.Rejected(written.diagnostics(), history));
            }
            next = (RailixValue.ObjectValue) written.value();
        }
        return new Called(next, result.outcome());
    }

    private Resolution resolve(
            final Map<String, Binding> bindings,
            final RailixValue.ObjectValue frame,
            final Map<String, RailixValue> received,
            final String primaryOutcome,
            final List<RunResult.StepExecution> history,
            final boolean capture,
            final List<NestedStepStage> stages,
            final Map<String, Integer> selectedCandidates
    ) {
        final Map<String, RailixValue> values = new LinkedHashMap<>(received);
        final Map<String, String> options = new LinkedHashMap<>();
        final Map<String, StepInput.Program> programs = new LinkedHashMap<>();
        final Map<String, StepInput> selected = new LinkedHashMap<>();
        final Map<String, PathBinding> paths = new LinkedHashMap<>();
        final Map<String, RailixValue> preview = new LinkedHashMap<>(received);
        for (final Map.Entry<String, Binding> entry : bindings.entrySet()) {
            final String name = entry.getKey();
            final Binding binding = entry.getValue();
            if (binding instanceof JsonBinding json) {
                json.value().stream().findFirst().ifPresent(value -> {
                    values.put(name, value);
                    preview.put(name, value);
                });
            } else if (binding instanceof PathBinding path) {
                paths.put(name, path);
                if (path.access().readable()) {
                    resolve(frame, path.path()).stream().findFirst().ifPresent(value -> {
                        values.put(name, value);
                        preview.put(name, value);
                    });
                }
            } else if (binding instanceof ChoiceBinding choice) {
                final Resolution children = resolve(
                        choice.inputs(),
                        frame,
                        Map.of(),
                        primaryOutcome,
                        history,
                        capture,
                        stages,
                        selectedCandidates
                );
                if (children.failure() != null) {
                    return children;
                }
                options.put(name, choice.option());
                selected.put(name, children.input());
                final RailixValue selectedValue = choice.valueSource()
                        .map(source -> source.scope() == StepDefinition.ReferenceScope.OWNED
                                ? children.values().get(source.input())
                                : values.get(source.input()))
                        .orElse(null);
                if (selectedValue != null) {
                    values.put(name, selectedValue);
                    preview.put(name, selectedValue);
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
                            stages,
                            selectedCandidates,
                            name + "[" + index + "].when"
                    );
                    if (evaluation.failure() != null) {
                        return failedResolution(primaryOutcome, evaluation.failure());
                    }
                    if (evaluation.matched()) {
                        values.put(name, evaluation.value());
                        preview.put(name, evaluation.value());
                        options.put(name, source.option());
                        selected.put(name, evaluation.children().input());
                        if (capture) {
                            selectedCandidates.put(candidates.path(), index);
                        }
                        break;
                    }
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
                                stages,
                                selectedCandidates,
                                name + "[" + groupIndex + "][" + matcherIndex + "].when"
                        );
                        if (evaluation.failure() != null) {
                            return failedResolution(primaryOutcome, evaluation.failure());
                        }
                        if (!evaluation.matched()) {
                            continue groupLoop;
                        }
                    }
                    matched = true;
                    break;
                }
                final RailixValue booleanValue = RailixValue.bool(matched);
                values.put(name, booleanValue);
                preview.put(name, booleanValue);
            } else if (binding instanceof StepsBinding stepsBinding) {
                final StepDefinition.ValueSource source = stepsBinding.valueSource();
                final RailixValue programValue = values.get(source.input());
                programs.put(name, () -> {
                    if (programValue != null) {
                        return program(
                                    stepsBinding.steps(),
                                    programValue,
                                    frame,
                                    primaryOutcome,
                                    history,
                                    capture,
                                    stages,
                                    selectedCandidates,
                                    name
                            );
                    }
                    return new StepInput.ProgramResult(
                            primaryOutcome,
                            source.missingOutcome().orElse(primaryOutcome),
                            List.of()
                    );
                });
            }
        }
        return new Resolution(
                new StepInput(values, options, programs, selected, primaryOutcome),
                values,
                paths,
                preview,
                null
        );
    }

    private MatcherEvaluation evaluate(
            final CandidatePlan candidate,
            final Map<String, RailixValue> values,
            final RailixValue.ObjectValue frame,
            final String primaryOutcome,
            final List<RunResult.StepExecution> history,
            final boolean capture,
            final List<NestedStepStage> stages,
            final Map<String, Integer> selectedCandidates,
            final String predicateInput
    ) {
        final ChoiceBinding source = candidate.source();
        final Resolution children = resolve(
                source.inputs(),
                frame,
                Map.of(),
                primaryOutcome,
                history,
                capture,
                stages,
                selectedCandidates
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
                            stages,
                            selectedCandidates,
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
                        stages,
                        selectedCandidates,
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

    private StepInput.ProgramResult program(
            final List<NestedStepPlan> program,
            RailixValue value,
            final RailixValue.ObjectValue frame,
            final String enclosingOutcome,
            final List<RunResult.StepExecution> history,
            final boolean capture,
            final List<NestedStepStage> stages,
            final Map<String, Integer> selectedCandidates,
            final String inputName
    ) throws InterruptedException {
        for (final NestedStepPlan step : program) {
            final StepDefinition definition = step.definition();
            final StepDefinition.Port receive = definition.receives().getFirst();
            if (!receive.shape().accepts(value)) {
                capture(stages, capture, inputName, step, "rejected", List.of());
                throw new ProgramAbort(rejected(
                        "RUN_NESTED_INPUT_INCOMPATIBLE",
                        "Nested Step " + definition.id() + " requires " + shape(receive.shape())
                                + " but receives " + shape(ValueShape.shapeOf(value)) + ".",
                        step.path(),
                        history
                ));
            }
            final Optional<String> inputRejection = receive.refinement().rejection(value);
            if (inputRejection.isPresent()) {
                capture(stages, capture, inputName, step, "rejected", List.of());
                throw new ProgramAbort(rejected(
                        "RUN_NESTED_INPUT_INCOMPATIBLE",
                        "Nested Step " + definition.id() + " rejects value: " + inputRejection.get(),
                        step.path(),
                        history
                ));
            }
            if (Thread.currentThread().isInterrupted()) {
                capture(stages, capture, inputName, step, "cancelled", List.of());
                throw new InterruptedException();
            }
            final Resolution resolved = resolve(
                    step.inputs(),
                    frame,
                    Map.of(receive.name(), value),
                    definition.primaryOutcome(),
                    history,
                    capture,
                    stages,
                    selectedCandidates
            );
            if (resolved.failure() != null) {
                throw new ProgramAbort(resolved.failure());
            }
            final StepHandler handler = definition.handler().orElse(null);
            if (handler == null) {
                throw new ProgramAbort(failed(
                        "STEP_HANDLER_REQUIRED",
                        "Nested Step has no handler.",
                        definition.id(),
                        step.path(),
                        history
                ));
            }
            final StepResult result;
            try {
                result = handler.run(resolved.input());
            } catch (final InterruptedException exception) {
                throw exception;
            } catch (final ProgramAbort abort) {
                throw abort;
            } catch (final RuntimeException exception) {
                capture(stages, capture, inputName, step, "failed", List.of());
                throw new ProgramAbort(failed(
                        "STEP_IMPLEMENTATION_FAULT",
                        "Step implementation threw an unexpected exception.",
                        definition.id(),
                        step.path(),
                        history
                ));
            }
            if (Thread.currentThread().isInterrupted()) {
                capture(stages, capture, inputName, step, "cancelled", List.of());
                throw new InterruptedException();
            }
            if (result == null) {
                throw new ProgramAbort(failed(
                        "STEP_RESULT_REQUIRED",
                        "Step implementation returned Java null.",
                        definition.id(),
                        step.path(),
                        history
                ));
            }
            if (!definition.outcomes().contains(result.outcome())) {
                throw new ProgramAbort(failed(
                        "STEP_OUTCOME_INVALID",
                        "Nested Step returned undeclared outcome: " + result.outcome() + ".",
                        definition.id(),
                        step.path(),
                        history
                ));
            }
            if (!result.writes().isEmpty()) {
                throw new ProgramAbort(failed(
                        "STEP_WRITE_UNEXPECTED",
                        "Nested Step attempted to write workflow context.",
                        definition.id(),
                        step.path(),
                        history
                ));
            }
            if (!definition.primaryOutcome().equals(result.outcome())) {
                if (!result.outputs().isEmpty()) {
                    throw new ProgramAbort(failed(
                            "STEP_OUTPUT_UNEXPECTED",
                            "Nested Step returned output for outcome " + result.outcome() + ".",
                            definition.id(),
                            step.path(),
                            history
                    ));
                }
                capture(stages, capture, inputName, step, result.outcome(), List.of());
                history.add(new RunResult.StepExecution(definition.id(), result.outcome()));
                return new StepInput.ProgramResult(enclosingOutcome, result.outcome(), List.of());
            }
            final StepDefinition.Port output = definition.returns().getFirst();
            final RailixValue next = result.outputs().get(output.name());
            if (next == null || result.outputs().size() != 1 || !output.shape().accepts(next)) {
                throw new ProgramAbort(failed(
                        "STEP_OUTPUT_INVALID",
                        "Nested Step did not return its one declared compatible value.",
                        definition.id(),
                        step.path(),
                        history
                ));
            }
            final Optional<String> outputRejection = output.refinement().rejection(next);
            if (outputRejection.isPresent()) {
                throw new ProgramAbort(failed(
                        "STEP_OUTPUT_INVALID",
                        "Nested Step returned an incompatible value: " + outputRejection.get(),
                        definition.id(),
                        step.path(),
                        history
                ));
            }
            if (next instanceof RailixValue.NumberValue number
                    && !RailixData.fitsCanonicalNumber(number.value())) {
                throw new ProgramAbort(failed(
                        "STEP_OUTPUT_INVALID",
                        "Nested Step returned a number outside the canonical domain.",
                        definition.id(),
                        step.path(),
                        history
                ));
            }
            value = next;
            capture(stages, capture, inputName, step, "succeeded", List.of(value));
            history.add(new RunResult.StepExecution(definition.id(), result.outcome()));
        }
        return new StepInput.ProgramResult(enclosingOutcome, enclosingOutcome, List.of(value));
    }

    private static Resolution failedResolution(final String primaryOutcome, final RunResult failure) {
        return new Resolution(
                new StepInput(Map.of(), Map.of(), Map.of(), Map.of(), primaryOutcome),
                Map.of(),
                Map.of(),
                Map.of(),
                failure
        );
    }

    private static RunResult validateReceives(
            final StepDefinition definition,
            final Map<String, RailixValue> values,
            final String path,
            final List<RunResult.StepExecution> history
    ) {
        for (final String name : values.keySet()) {
            if (definition.receives().stream().noneMatch(port -> port.name().equals(name))) {
                return rejected(
                        "RUN_SOURCE_VALUE_UNKNOWN",
                        "Trigger source value is not declared: " + name + ".",
                        path + "." + name,
                        history
                );
            }
        }
        for (final StepDefinition.Port port : definition.receives()) {
            final RailixValue value = values.get(port.name());
            if (value == null) {
                return rejected(
                        "RUN_SOURCE_VALUE_REQUIRED",
                        "Trigger source value is required: " + port.name() + ".",
                        path + "." + port.name(),
                        history
                );
            }
            if (!port.shape().accepts(value)) {
                return rejected(
                        "RUN_SOURCE_VALUE_INCOMPATIBLE",
                        "Trigger source value " + port.name() + " requires " + shape(port.shape()) + ".",
                        path + "." + port.name(),
                        history
                );
            }
            final Optional<String> refinementRejection = port.refinement().rejection(value);
            if (refinementRejection.isPresent()) {
                return rejected(
                        "RUN_SOURCE_VALUE_INCOMPATIBLE",
                        "Trigger source value " + port.name() + " is incompatible: "
                                + refinementRejection.get(),
                        path + "." + port.name(),
                        history
                );
            }
        }
        return null;
    }

    private static void capture(
            final List<NestedStepStage> stages,
            final boolean capture,
            final String input,
            final NestedStepPlan step,
            final String status,
            final List<RailixValue> value
    ) {
        if (capture) {
            stages.add(new NestedStepStage(input, step.path(), step.definition().id(), status, value));
        }
    }

    private static RailixValue.ObjectValue frame(final TriggerPlan trigger, final StreamItem item) {
        final Map<String, RailixValue> context = new LinkedHashMap<>(item.context().values());
        trigger.results().forEach(result -> {
            context.remove(result.name());
            result.defaultValue().stream().findFirst().ifPresent(value -> context.put(result.name(), value));
        });
        context.put("runtime", RailixValue.object(Map.of(
                "test", RailixValue.bool(item.test()),
                "trigger", RailixValue.string(trigger.id())
        )));
        return RailixValue.object(Map.of("context", RailixValue.object(context)));
    }

    private static RailixValue.ObjectValue context(final RailixValue.ObjectValue frame) {
        return (RailixValue.ObjectValue) frame.values().get("context");
    }

    private static List<RailixValue> resolve(final RailixValue source, final Path path) {
        RailixValue current = source;
        for (final PathElement element : path.elements()) {
            if (element instanceof Field field) {
                if (!(current instanceof RailixValue.ObjectValue object)) {
                    return List.of();
                }
                final RailixValue next = object.values().get(field.name());
                if (next == null) {
                    return List.of();
                }
                current = next;
            } else {
                final int index = ((Index) element).value();
                if (!(current instanceof RailixValue.ArrayValue array) || index >= array.values().size()) {
                    return List.of();
                }
                current = array.values().get(index);
            }
        }
        return List.of(current);
    }

    private static WriteResult write(
            final RailixValue source,
            final Path path,
            final RailixValue replacement,
            final String diagnosticPath
    ) {
        return write(source, path.elements(), 0, replacement, diagnosticPath);
    }

    private static WriteResult write(
            final RailixValue source,
            final List<PathElement> path,
            final int depth,
            final RailixValue replacement,
            final String diagnosticPath
    ) {
        final PathElement element = path.get(depth);
        final boolean leaf = depth == path.size() - 1;
        if (element instanceof Field field) {
            if (!(source instanceof RailixValue.ObjectValue object)) {
                return conflict(diagnosticPath);
            }
            final Map<String, RailixValue> values = new LinkedHashMap<>(object.values());
            if (leaf) {
                values.put(field.name(), replacement);
                return WriteResult.success(RailixValue.object(values));
            }
            RailixValue child = values.get(field.name());
            if (child == null) {
                child = container(path.get(depth + 1));
            }
            final WriteResult nested = write(child, path, depth + 1, replacement, diagnosticPath);
            if (!nested.diagnostics().isEmpty()) {
                return nested;
            }
            values.put(field.name(), nested.value());
            return WriteResult.success(RailixValue.object(values));
        }
        if (!(source instanceof RailixValue.ArrayValue array)) {
            return conflict(diagnosticPath);
        }
        final int index = ((Index) element).value();
        final List<RailixValue> values = new ArrayList<>(array.values());
        final boolean missing = index >= values.size();
        while (values.size() <= index) {
            values.add(RailixValue.nullValue());
        }
        if (leaf) {
            values.set(index, replacement);
            return WriteResult.success(RailixValue.array(values));
        }
        RailixValue child = values.get(index);
        if (missing) {
            child = container(path.get(depth + 1));
        }
        final WriteResult nested = write(child, path, depth + 1, replacement, diagnosticPath);
        if (!nested.diagnostics().isEmpty()) {
            return nested;
        }
        values.set(index, nested.value());
        return WriteResult.success(RailixValue.array(values));
    }

    private static RailixValue container(final PathElement next) {
        return next instanceof Field ? RailixValue.object(Map.of()) : RailixValue.array(List.of());
    }

    private static WriteResult conflict(final String path) {
        return new WriteResult(
                RailixValue.nullValue(),
                List.of(Diagnostic.atPath(
                        "RUN_FIELD_TARGET_CONFLICT",
                        "PATH write crosses an existing primitive value.",
                        path
                ))
        );
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

    private static String shape(final ValueShape shape) {
        return shape.name().toLowerCase(java.util.Locale.ROOT);
    }

    /** One transient stream item. No event entity or retained execution state exists. */
    public record StreamItem(boolean test, RailixValue.ObjectValue context) {
        public StreamItem {
            if (context == null) {
                throw new IllegalArgumentException("Stream item context cannot be Java null.");
            }
        }
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

    /**
     * Target-scoped development preview. {@code inputContext} is captured before the selected Step,
     * while {@code result} contains the normal post-execution flow result. Candidate winners are
     * keyed by canonical project input path.
     */
    public record PreviewResult(
            RunResult result,
            Map<String, RailixValue> inputs,
            RailixValue.ObjectValue inputContext,
            List<NestedStepStage> stages,
            Map<String, Integer> selectedCandidates
    ) {
        public PreviewResult {
            if (result == null || inputs == null || inputContext == null
                    || stages == null || selectedCandidates == null) {
                throw new IllegalArgumentException("Preview result values cannot be Java null.");
            }
            inputs = Map.copyOf(inputs);
            stages = List.copyOf(stages);
            selectedCandidates = Map.copyOf(selectedCandidates);
        }
    }

    /** Actual output or explicit terminal state of one nested Step invocation. */
    public record NestedStepStage(
            String input,
            String invocation,
            String use,
            String status,
            List<RailixValue> value
    ) {
        public NestedStepStage {
            value = List.copyOf(value);
        }
    }

    record TriggerPlan(
            String id,
            StepDefinition definition,
            NodePlan node,
            List<ResultPlan> results,
            String start,
            Map<String, NodePlan> nodes,
            String path
    ) {
        TriggerPlan {
            results = List.copyOf(results);
            nodes = Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
        }
    }

    record ResultPlan(String name, ValueShape shape, List<RailixValue> defaultValue) {
        ResultPlan {
            defaultValue = List.copyOf(defaultValue);
        }
    }

    record NodePlan(
            String id,
            StepDefinition definition,
            Map<String, Binding> inputs,
            Map<String, Path> receives,
            Map<String, Path> returns,
            Map<String, String> destinations,
            List<String> outcomes,
            String path
    ) {
        NodePlan {
            inputs = Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
            receives = Collections.unmodifiableMap(new LinkedHashMap<>(receives));
            returns = Collections.unmodifiableMap(new LinkedHashMap<>(returns));
            destinations = Collections.unmodifiableMap(new LinkedHashMap<>(destinations));
            outcomes = List.copyOf(outcomes);
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
            return valueSources.stream().findFirst();
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
            StepDefinition definition,
            Map<String, Binding> inputs,
            String path
    ) {
        NestedStepPlan {
            inputs = Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
        }
    }

    record Path(List<PathElement> elements) {
        Path {
            elements = List.copyOf(elements);
        }
    }

    sealed interface PathElement permits Field, Index {
    }

    record Field(String name) implements PathElement {
    }

    record Index(int value) implements PathElement {
    }

    private sealed interface CallResult permits Called, Aborted {
    }

    private record Called(RailixValue.ObjectValue frame, String outcome) implements CallResult {
    }

    private record MatcherEvaluation(
            boolean matched,
            RailixValue value,
            Resolution children,
            RunResult failure
    ) {
    }

    private record Aborted(RunResult result) implements CallResult {
    }

    private record Resolution(
            StepInput input,
            Map<String, RailixValue> values,
            Map<String, PathBinding> paths,
            Map<String, RailixValue> previewValues,
            RunResult failure
    ) {
    }

    private record WriteResult(RailixValue value, List<Diagnostic> diagnostics) {
        static WriteResult success(final RailixValue value) {
            return new WriteResult(value, List.of());
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
