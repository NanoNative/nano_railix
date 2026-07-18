package dev.nanonative.railix.kernel.model;

import java.util.Map;
import java.util.Objects;

public record Envelope(
        String source,
        String protocol,
        RailixValue.ObjectValue payload,
        RailixValue.ObjectValue metadata,
        Map<String, RailixValue> refs,
        ReplyChannel replyChannel
) {
    public Envelope {
        source = requireNonBlank(source, "source");
        protocol = requireNonBlank(protocol, "protocol");
        payload = Objects.requireNonNull(payload, "payload");
        metadata = Objects.requireNonNull(metadata, "metadata");
        refs = Map.copyOf(refs);
        replyChannel = Objects.requireNonNull(replyChannel, "replyChannel");
    }

    public record ReplyChannel(boolean supported, java.util.List<Reply.Mode> modes) {
        public ReplyChannel {
            modes = java.util.List.copyOf(modes);
            if (supported && modes.isEmpty()) {
                throw new IllegalArgumentException("replyChannel supported=true requires at least one mode");
            }
            if (!supported && !modes.isEmpty()) {
                throw new IllegalArgumentException("replyChannel supported=false requires an empty mode list");
            }
        }
    }

    private static String requireNonBlank(final String value, final String fieldName) {
        Objects.requireNonNull(value, fieldName);
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
