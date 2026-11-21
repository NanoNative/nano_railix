package dev.nanonative.railix.mesh;

/** A complete frame body in the caller's reserved buffer. */
record FrameBody(byte[] bytes) implements FrameRead {
}
