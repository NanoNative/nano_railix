package dev.nanonative.railix.mesh.peer;

import dev.nanonative.railix.core.value.RailixValue;

/** A decoded message with the metadata of the frame that carried it. */
record PeerMessage(RailixValue.ObjectValue value, int bodyBytes, long headerReceivedNanos) implements FrameRead {
}
