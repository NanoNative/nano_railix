package dev.nanonative.railix.kernel.model;

import java.util.Map;

public record Reply(
        Mode mode,
        RailixValue status,
        RailixValue.ObjectValue metadata,
        RailixValue payload,
        RailixValue file,
        RailixValue stream,
        RailixValue session,
        RailixValue deferred
) {
    public Reply {
        mode = java.util.Objects.requireNonNull(mode, "mode");
        status = java.util.Objects.requireNonNull(status, "status");
        metadata = java.util.Objects.requireNonNull(metadata, "metadata");
        payload = java.util.Objects.requireNonNull(payload, "payload");
        file = requireReplyRef("file", file, RailixValue.FileRef.class);
        stream = requireReplyRef("stream", stream, RailixValue.StreamRef.class);
        session = requireReplyRef("session", session, RailixValue.SessionRef.class);
        deferred = requireReplyRef("deferred", deferred, RailixValue.DeferredRef.class);
        validateModeShape(mode, status, payload, file, stream, session, deferred);
    }

    public enum Mode {
        NONE,
        IMMEDIATE,
        FILE,
        STREAM,
        DEFERRED,
        SESSION,
        BROADCAST
    }

    public static Reply none() {
        return new Reply(
                Mode.NONE,
                RailixValue.NULL,
                new RailixValue.ObjectValue(Map.of()),
                RailixValue.NULL,
                RailixValue.NULL,
                RailixValue.NULL,
                RailixValue.NULL,
                RailixValue.NULL
        );
    }

    private static RailixValue requireReplyRef(
            final String fieldName,
            final RailixValue value,
            final Class<? extends RailixValue> expectedType
    ) {
        final RailixValue normalized = java.util.Objects.requireNonNullElse(value, RailixValue.NULL);
        if (normalized == RailixValue.NULL || expectedType.isInstance(normalized)) {
            return normalized;
        }
        throw new IllegalArgumentException(fieldName + " must be " + expectedType.getSimpleName() + " or RailixValue.NULL");
    }

    private static void validateModeShape(
            final Mode mode,
            final RailixValue status,
            final RailixValue payload,
            final RailixValue file,
            final RailixValue stream,
            final RailixValue session,
            final RailixValue deferred
    ) {
        final int populatedRefs = countPopulatedRefs(file, stream, session, deferred);
        switch (mode) {
            case NONE -> {
                if (status != RailixValue.NULL || payload != RailixValue.NULL || populatedRefs > 0) {
                    throw new IllegalArgumentException("Mode.NONE requires null status, null payload, and no transport refs");
                }
            }
            case FILE -> requireExclusiveTransport("Mode.FILE", file, populatedRefs, "file");
            case STREAM -> requireExclusiveTransport("Mode.STREAM", stream, populatedRefs, "stream");
            case DEFERRED -> requireExclusiveTransport("Mode.DEFERRED", deferred, populatedRefs, "deferred");
            case SESSION -> requireExclusiveTransport("Mode.SESSION", session, populatedRefs, "session");
            case IMMEDIATE, BROADCAST -> {
                if (populatedRefs > 0) {
                    throw new IllegalArgumentException("Mode." + mode.name() + " must not populate transport refs");
                }
            }
        }
    }

    private static int countPopulatedRefs(
            final RailixValue file,
            final RailixValue stream,
            final RailixValue session,
            final RailixValue deferred
    ) {
        int count = 0;
        if (file != RailixValue.NULL) {
            count++;
        }
        if (stream != RailixValue.NULL) {
            count++;
        }
        if (session != RailixValue.NULL) {
            count++;
        }
        if (deferred != RailixValue.NULL) {
            count++;
        }
        return count;
    }

    private static void requireExclusiveTransport(
            final String mode,
            final RailixValue expectedValue,
            final int populatedRefs,
            final String expectedField
    ) {
        if (expectedValue == RailixValue.NULL) {
            throw new IllegalArgumentException(mode + " requires a " + expectedField + " ref");
        }
        if (populatedRefs > 1) {
            throw new IllegalArgumentException(mode + " must only populate " + expectedField);
        }
    }
}
