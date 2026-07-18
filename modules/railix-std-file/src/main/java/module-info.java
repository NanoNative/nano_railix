module railix.std.file {
    requires railix.kernel;
    provides dev.nanonative.railix.kernel.runtime.StepProvider
            with dev.nanonative.railix.railixstdfile.StandardFileStepProvider;
}
