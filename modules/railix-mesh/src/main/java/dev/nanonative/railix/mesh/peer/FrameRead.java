package dev.nanonative.railix.mesh.peer;

/** The result of reading one peer frame, before or after decoding its body. */
sealed interface FrameRead permits FrameBody, FrameReadStatus, PeerMessage {
}
