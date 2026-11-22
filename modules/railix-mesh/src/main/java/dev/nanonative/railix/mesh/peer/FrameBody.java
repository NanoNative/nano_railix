package dev.nanonative.railix.mesh.peer;

/** A complete frame body in the caller's reserved buffer. */
record FrameBody(byte[] bytes) implements FrameRead {
}
