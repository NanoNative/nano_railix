package dev.nanonative.railix.kernel.runtime;

import java.util.Objects;

/**
 * Minimal bootstrap surface for a built Railix app with an embedded plan.
 */
public final class BuiltRailixApp {

    private final AppPlan plan;
    private final LocalExecutionKernel kernel;
    private final ConnectionPlan connectionPlan;

    /**
     * Create a built app that resolves installed pack-backed steps through {@link PackBackedStepResolver}.
     *
     * @param plan embedded app plan
     */
    public BuiltRailixApp(final AppPlan plan) {
        this(plan, new LocalExecutionKernel(), new PackBackedStepResolver());
    }

    /**
     * Create a built app with explicit runtime collaborators.
     *
     * @param plan embedded app plan
     * @param kernel execution kernel
     * @param resolver step resolver used for plan execution
     */
    public BuiltRailixApp(final AppPlan plan, final LocalExecutionKernel kernel, final StepResolver resolver) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.kernel = Objects.requireNonNull(kernel, "kernel");
        this.connectionPlan = ConnectionPlan.compile(plan, Objects.requireNonNull(resolver, "resolver"));
    }

    /**
     * Execute the embedded app plan for the provided run request.
     *
     * @param request run request carrying envelope, settings, and run folder root
     * @return completed run record
     */
    public LocalExecutionKernel.RunRecord run(final LocalExecutionKernel.RunRequest request) {
        return kernel.run(plan, request, connectionPlan);
    }

    /**
     * @return embedded app plan
     */
    public AppPlan plan() {
        return plan;
    }
}
