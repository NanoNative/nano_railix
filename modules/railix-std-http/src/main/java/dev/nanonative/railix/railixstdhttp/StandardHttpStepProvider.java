package dev.nanonative.railix.railixstdhttp;

import dev.nanonative.railix.kernel.model.Envelope;
import dev.nanonative.railix.kernel.model.Patch;
import dev.nanonative.railix.kernel.model.PermissionSet;
import dev.nanonative.railix.kernel.model.RailixPath;
import dev.nanonative.railix.kernel.model.RailixValue;
import dev.nanonative.railix.kernel.model.StepContract;
import dev.nanonative.railix.kernel.runtime.Step;
import dev.nanonative.railix.kernel.runtime.StepProvider;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class StandardHttpStepProvider implements StepProvider {

    static final String HTTP_TRIGGER_USE = "railix.std.http.HttpTrigger";

    private static final RailixPath HTTP_HOST_PATH = RailixPath.parse("settings.http.host");
    private static final RailixPath HTTP_PORT_PATH = RailixPath.parse("settings.http.port");
    private static final RailixPath HTTP_TLS_ENABLED_PATH = RailixPath.parse("settings.http.tls.enabled");
    private static final RailixPath CONTEXT_PAYLOAD_PATH = RailixPath.parse("ctx.payload");
    private static final RailixPath CONTEXT_METADATA_PATH = RailixPath.parse("ctx.metadata");
    private static final RailixPath TRIGGER_KIND_PATH = RailixPath.parse("ctx.trigger.kind");
    private static final RailixPath TRIGGER_SOURCE_PATH = RailixPath.parse("ctx.trigger.source");
    private static final RailixPath TRIGGER_PROTOCOL_PATH = RailixPath.parse("ctx.trigger.protocol");
    private static final RailixPath TRIGGER_REPLY_SUPPORTED_PATH = RailixPath.parse("ctx.trigger.reply.supported");
    private static final RailixPath TRIGGER_REPLY_MODES_PATH = RailixPath.parse("ctx.trigger.reply.modes");
    private static final RailixPath HTTP_REQUEST_PATH = RailixPath.parse("ctx.http.request");

    @Override
    public Optional<Step> resolve(final String use) {
        return HTTP_TRIGGER_USE.equals(use) ? Optional.of(httpTriggerStep()) : Optional.empty();
    }

    @Override
    public List<String> supportedUses() {
        return List.of(HTTP_TRIGGER_USE);
    }

    private static Step httpTriggerStep() {
        return new Step() {
            @Override
            public StepContract contract() {
                return new StepContract(
                        HTTP_TRIGGER_USE,
                        "0.1.0",
                        "HTTP Trigger",
                        "Normalizes a prebuilt HTTP envelope into trigger-owned and request-owned context fields.",
                        StepContract.Kind.TRIGGER,
                        List.of(new StepContract.Port("envelope", "envelope", "envelope", true, List.of())),
                        List.of(new StepContract.Port("httpRequest", "document", HTTP_REQUEST_PATH.toString(), false, List.of())),
                        Map.of("request", new StepContract.Outcome("HTTP request normalized into context")),
                        new StepContract.Settings(List.of(
                                HTTP_HOST_PATH.toString(),
                                HTTP_PORT_PATH.toString(),
                                HTTP_TLS_ENABLED_PATH.toString()
                        )),
                        PermissionSet.none(),
                        new StepContract.Timeout(Duration.ofSeconds(30)),
                        new StepContract.RetryPolicy(1, Duration.ZERO),
                        new StepContract.CachePolicy(StepContract.CachePolicy.Mode.NONE, "", Duration.ZERO),
                        new StepContract.Resources(new StepContract.Limits(RailixValue.NULL, RailixValue.NULL)),
                        new StepContract.Metrics(List.of()),
                        Map.of("editor", new RailixValue.StringValue("trigger.http"))
                );
            }

            @Override
            public Result execute(final ExecutionInput input) {
                final Envelope envelope = input.envelope();
                if (!"http".equalsIgnoreCase(envelope.protocol())) {
                    throw new IllegalStateException("HTTP trigger requires envelope.protocol=http");
                }
                final RailixValue.ObjectValue metadata = envelope.metadata();
                final RailixValue.ObjectValue httpRequest = new RailixValue.ObjectValue(Map.of(
                        "method", requiredMetadataString(metadata, "method"),
                        "path", requiredMetadataString(metadata, "path"),
                        "headers", requiredMetadataObject(metadata, "headers"),
                        "query", requiredMetadataObject(metadata, "query"),
                        "remoteAddress", requiredMetadataString(metadata, "remoteAddress")
                ));
                return new Result(
                        "request",
                        List.of(
                                new Patch.Set(CONTEXT_PAYLOAD_PATH, new Patch.LiteralSource(envelope.payload())),
                                new Patch.Set(CONTEXT_METADATA_PATH, new Patch.LiteralSource(metadata)),
                                new Patch.Set(TRIGGER_KIND_PATH, new Patch.LiteralSource(new RailixValue.StringValue("http"))),
                                new Patch.Set(TRIGGER_SOURCE_PATH, new Patch.LiteralSource(new RailixValue.StringValue(envelope.source()))),
                                new Patch.Set(TRIGGER_PROTOCOL_PATH, new Patch.LiteralSource(new RailixValue.StringValue(envelope.protocol().toLowerCase(Locale.ROOT)))),
                                new Patch.Set(
                                        TRIGGER_REPLY_SUPPORTED_PATH,
                                        new Patch.LiteralSource(new RailixValue.BoolValue(envelope.replyChannel().supported()))
                                ),
                                new Patch.Set(
                                        TRIGGER_REPLY_MODES_PATH,
                                        new Patch.LiteralSource(new RailixValue.ListValue(
                                                envelope.replyChannel().modes().stream()
                                                        .map(mode -> (RailixValue) new RailixValue.StringValue(mode.name().toLowerCase(Locale.ROOT)))
                                                        .toList()
                                        ))
                                ),
                                new Patch.Set(HTTP_REQUEST_PATH, new Patch.LiteralSource(httpRequest))
                        )
                );
            }
        };
    }

    private static RailixValue.StringValue requiredMetadataString(final RailixValue.ObjectValue metadata, final String key) {
        final RailixValue value = metadata.values().get(key);
        if (!(value instanceof RailixValue.StringValue stringValue) || stringValue.value().isBlank()) {
            throw new IllegalStateException("HTTP envelope metadata " + key + " must be a non-blank string");
        }
        return stringValue;
    }

    private static RailixValue.ObjectValue requiredMetadataObject(final RailixValue.ObjectValue metadata, final String key) {
        final RailixValue value = metadata.values().get(key);
        if (!(value instanceof RailixValue.ObjectValue objectValue)) {
            throw new IllegalStateException("HTTP envelope metadata " + key + " must be an object");
        }
        return objectValue;
    }
}
