package dev.nanonative.railix.railixstdhttp;

import dev.nanonative.railix.kernel.model.RailixPath;
import dev.nanonative.railix.kernel.model.RailixValue;
import dev.nanonative.railix.kernel.model.SettingsTree;

record HttpBoundarySettings(String host, int port, boolean tlsEnabled) {

    private static final RailixPath HTTP_HOST_PATH = RailixPath.parse("settings.http.host");
    private static final RailixPath HTTP_PORT_PATH = RailixPath.parse("settings.http.port");
    private static final RailixPath HTTP_TLS_ENABLED_PATH = RailixPath.parse("settings.http.tls.enabled");
    static final int MAX_REQUEST_BYTES = 1_048_576;

    HttpBoundarySettings {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 0 and 65535");
        }
    }

    static HttpBoundarySettings from(final SettingsTree settingsTree) {
        final SettingsTree.Entry portEntry = settingsTree.entries().get(HTTP_PORT_PATH);
        if (portEntry == null) {
            throw new IllegalStateException("settings.http.port must be configured");
        }
        final String host = stringSetting(settingsTree.entries().get(HTTP_HOST_PATH)).orElse("127.0.0.1");
        final int port = intSetting(portEntry).orElseThrow();
        final boolean tlsEnabled = boolSetting(settingsTree.entries().get(HTTP_TLS_ENABLED_PATH)).orElse(false);
        if (tlsEnabled) {
            throw new IllegalStateException("settings.http.tls.enabled=true is not supported by the first std.http slice");
        }
        return new HttpBoundarySettings(host, port, false);
    }

    private static java.util.Optional<String> stringSetting(final SettingsTree.Entry entry) {
        if (entry == null) {
            return java.util.Optional.empty();
        }
        if (!(entry.value() instanceof SettingsTree.PlainValue plainValue)
                || !(plainValue.value() instanceof RailixValue.StringValue stringValue)) {
            throw new IllegalStateException(entry.path() + " must be a string");
        }
        if (stringValue.value().isBlank()) {
            throw new IllegalStateException(entry.path() + " must not be blank");
        }
        return java.util.Optional.of(stringValue.value());
    }

    private static java.util.Optional<Boolean> boolSetting(final SettingsTree.Entry entry) {
        if (entry == null) {
            return java.util.Optional.empty();
        }
        if (!(entry.value() instanceof SettingsTree.PlainValue plainValue)
                || !(plainValue.value() instanceof RailixValue.BoolValue boolValue)) {
            throw new IllegalStateException(entry.path() + " must be a bool");
        }
        return java.util.Optional.of(boolValue.value());
    }

    private static java.util.Optional<Integer> intSetting(final SettingsTree.Entry entry) {
        if (entry == null) {
            return java.util.Optional.empty();
        }
        if (!(entry.value() instanceof SettingsTree.PlainValue plainValue)
                || !(plainValue.value() instanceof RailixValue.NumberValue numberValue)) {
            throw new IllegalStateException(entry.path() + " must be a number");
        }
        try {
            return java.util.Optional.of(numberValue.value().intValueExact());
        } catch (final ArithmeticException exception) {
            throw new IllegalStateException(entry.path() + " must be an integer", exception);
        }
    }
}
