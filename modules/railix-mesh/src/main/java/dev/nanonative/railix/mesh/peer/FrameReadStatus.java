package dev.nanonative.railix.mesh.peer;

/** Read outcomes without a valid body or decoded message. */
enum FrameReadStatus implements FrameRead {
    END_OF_STREAM,
    TRUNCATED_HEADER,
    INVALID_LENGTH,
    BODY_UNAVAILABLE,
    TRUNCATED_BODY,
    INVALID_MESSAGE
}
