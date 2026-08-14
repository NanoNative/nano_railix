package dev.nanonative.railix.core.project;

import dev.nanonative.railix.core.value.RailixValue;

import java.util.Map;

/**
 * Runtime boundary implemented by every generated Railix application.
 * Implementations execute one complete production flow and retain no request state.
 */
public interface RuntimeApplication {
    /** Returns the stable project identifier compiled into this application. */
    String projectId();

    /**
     * Executes one real Trigger source with its native input values.
     *
     * @param source source name declared by exactly one compiled Trigger
     * @param values native input values keyed by the Trigger receive ports
     * @return complete flow result and mapped source response slots
     */
    WorkflowRuntime.SourceResult runSource(String source, Map<String, RailixValue> values);
}
