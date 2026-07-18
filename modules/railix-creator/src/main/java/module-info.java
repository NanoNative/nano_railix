module railix.creator {
    requires jdk.httpserver;
    requires java.net.http;
    requires railix.kernel;
    requires railix.std.data;
    requires railix.std.http;
    requires railix.std.store;
    requires railix.std.trigger;

    exports dev.nanonative.railix.creator.runtime;
}
