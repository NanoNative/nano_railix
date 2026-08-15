package dev.nanonative.railix.core.step;

/**
 * Executable behavior attached explicitly to a Step definition.
 * Implementations are shared by concurrent flow runs and must be stateless or thread-safe.
 */
@FunctionalInterface
public interface StepHandler {
    /**
     * Runs one invocation using only the supplied immutable input and configuration.
     *
     * @param input invocation-local values
     * @return the declared outcome and outputs; never {@code null}
     * @throws InterruptedException when the trigger-owned invocation is cancelled
     */
    StepResult run(StepInput input) throws InterruptedException;
}
