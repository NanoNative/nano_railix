package dev.nanonative.railix.mesh.peer;

/** A complete frame body and the time its length prefix was accepted. */
record FrameBody(byte[] bytes, long headerReceivedNanos) implements FrameRead {
}
