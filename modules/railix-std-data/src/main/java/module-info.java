module railix.std.data {
    requires railix.kernel;
    provides dev.nanonative.railix.kernel.runtime.StepProvider
            with dev.nanonative.railix.railixstddata.StandardDataStepProvider;
}
