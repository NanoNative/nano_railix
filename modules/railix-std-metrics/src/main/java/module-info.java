module railix.std.metrics {
    requires railix.kernel;
    provides dev.nanonative.railix.kernel.runtime.StepProvider
            with dev.nanonative.railix.railixstdmetrics.StandardMetricsStepProvider;
}
