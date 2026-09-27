package dev.nanonative.railix.core.project;

import dev.nanonative.railix.core.runtime.RunResult;
import dev.nanonative.railix.core.value.RailixValue;

import java.time.Duration;
import java.util.Map;
import java.util.List;

/**
 * Runtime boundary implemented by every generated Railix application.
 * Implementations execute one complete production flow and retain no request state after completion.
 */
public interface RuntimeApplication {
    /** Default application-wide grace period, also honored by the owning command launcher. */
    Duration DEFAULT_DRAIN_BUDGET = Duration.ofSeconds(30);

    /** Returns the stable project identifier compiled into this application. */
    String projectId();

    /** Initializes configured Steps once; returns startup success or an explicit failure after cleanup. */
    RunResult start();

    /**
     * Initializes Steps and activates their registered external sources exactly once.
     * Initialization through {@link #start()} or {@link #runSource(String, Map)} alone does not
     * launch external sources. A development host calls this with no business arguments.
     * Completion of one source does not stop others. This method does not wait for source completion.
     *
     * @param arguments immutable-copied business launch arguments, with no Java null list or entries
     * @return startup success or failure after attempted cleanup; never terminates the host process
     */
    RunResult launch(List<String> arguments);

    /** Stops admission, drains for up to 30 seconds and then releases resources; does not terminate the host. */
    default RunResult stop() {
        return stop(DEFAULT_DRAIN_BUDGET);
    }

    /**
     * Stops admission and waits up to the given application-wide budget for admitted work.
     * Expiry requests cancellation and reports incomplete shutdown without releasing resources still in use.
     * The first stop call sets the shared deadline; concurrent or repeated calls cannot extend it.
     * A subsequent call may finish cleanup after that work exits. Completed cleanup is not repeated.
     *
     * @param drainBudget nonnegative maximum drain duration, representable in nanoseconds
     * @return explicit shutdown success, cancellation or failure; never terminates an embedded host
     * @throws IllegalArgumentException for a missing or negative budget
     * @throws ArithmeticException when the duration cannot be represented in nanoseconds
     */
    RunResult stop(Duration drainBudget);

    /**
     * Executes one real Trigger source with its native input values.
     * Generated implementations initialize an unstarted application on its first call and
     * reject admission while startup has failed or shutdown is in progress/completed.
     *
     * @param source source name declared by exactly one compiled Trigger
     * @param values native input values keyed by the Trigger receive ports
     * @return complete flow result and mapped source response slots
     */
    WorkflowRuntime.SourceResult runSource(String source, Map<String, RailixValue> values);
}
