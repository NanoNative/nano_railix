package dev.nanonative.railix.kernel.runtime;

/**
 * Resolves trusted, installed Java Step dependencies by their stable contract identifier without reflection.
 */
@FunctionalInterface
public interface StepResolver {
    /**
     * Resolves one Step implementation. The returned Step contract identifier must equal {@code use}.
     *
     * @param use stable Step dependency identifier declared by an app plan
     * @return resolved Step implementation; never {@code null}
     * @throws RuntimeException when the dependency is unknown or cannot be loaded
     */
    Step resolve(String use);
}
