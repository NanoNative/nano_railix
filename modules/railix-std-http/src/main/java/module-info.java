module railix.std.http {
    requires railix.kernel;
    requires jdk.httpserver;
    requires java.net.http;

    exports dev.nanonative.railix.railixstdhttp;

    provides dev.nanonative.railix.kernel.runtime.StepProvider
            with dev.nanonative.railix.railixstdhttp.StandardHttpStepProvider;
}
