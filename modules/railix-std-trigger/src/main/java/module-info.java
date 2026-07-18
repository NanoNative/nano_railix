module railix.std.trigger {
    requires railix.kernel;
    provides dev.nanonative.railix.kernel.runtime.StepProvider
            with dev.nanonative.railix.railixstdtrigger.StandardTriggerStepProvider;
}
