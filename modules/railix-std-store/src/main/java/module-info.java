module railix.std.store {
    requires railix.kernel;
    provides dev.nanonative.railix.kernel.runtime.StepProvider
            with dev.nanonative.railix.railixstdstore.StandardStoreStepProvider;
}
