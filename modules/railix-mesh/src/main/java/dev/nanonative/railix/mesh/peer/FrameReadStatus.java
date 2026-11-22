package dev.nanonative.railix.mesh.peer;

/** Stream outcomes that did not produce a complete frame. */
enum FrameReadStatus implements FrameRead {
    END_OF_STREAM,
    TRUNCATED_HEADER,
    INVALID_LENGTH,
    BODY_UNAVAILABLE,
    TRUNCATED_BODY
}
