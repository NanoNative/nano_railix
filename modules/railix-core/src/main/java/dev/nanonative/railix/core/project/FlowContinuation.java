package dev.nanonative.railix.core.project;

import dev.nanonative.railix.core.value.RailixValue;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * Canonical input for a Trigger continuation selected by a compatible application.
 * @param trigger compiled Trigger ID
 * @param outcome selected compiled Trigger outcome
 * @param input context produced by the Trigger
 */
public record FlowContinuation(String trigger, int outcome, RailixValue.ObjectValue input) {
    public FlowContinuation {
        if (trigger == null || trigger.isBlank() || outcome < 0 || input == null
                || input.values().containsKey("runtime")) {
            throw new IllegalArgumentException("Invalid compiled Flow continuation.");
        }
    }

    WorkflowRuntime.Execution resume(final List<WorkflowRuntime.ResultPlan> results, final RailixValue.ObjectValue runtime, final WorkflowRuntime.Lifecycle lifecycle) {
        final LinkedHashMap<String, Object> context = new LinkedHashMap<>(input.values());
        context.put("runtime", runtime);
        return new WorkflowRuntime.Execution(results, context, lifecycle);
    }
}
