package dev.nanonative.railix.mesh.peer;

/** The result of reading one frame from a peer stream. */
sealed interface FrameRead permits FrameBody, FrameReadStatus {
}
