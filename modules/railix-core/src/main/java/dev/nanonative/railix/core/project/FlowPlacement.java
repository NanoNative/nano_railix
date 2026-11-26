package dev.nanonative.railix.core.project;

import dev.nanonative.railix.core.runtime.RunResult;
import dev.nanonative.railix.core.value.RailixValue;

import java.util.function.Consumer;
import java.util.function.Supplier;

/** Selects execution for a compiled continuation after its Trigger has finished. */
@FunctionalInterface
public interface FlowPlacement {
    /**
     * @param trigger compiled Trigger ID
     * @param outcome selected compiled Trigger outcome
     * @param input snapshots canonical continuation values only if transfer is needed
     * @param local executes the continuation in its original frame
     * @param recordOffload receives true for a peer outcome or false for uncertain completion after possible delivery
     * @return the terminal result for the source that owns the request
     */
    RunResult place(String trigger, int outcome, Supplier<RailixValue.ObjectValue> input, Supplier<RunResult> local,
                    Consumer<Boolean> recordOffload);
}
