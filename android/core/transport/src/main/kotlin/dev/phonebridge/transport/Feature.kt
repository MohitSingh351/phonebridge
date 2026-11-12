package dev.phonebridge.transport

import dev.phonebridge.protocol.Capability
import dev.phonebridge.protocol.CapabilityState
import dev.phonebridge.protocol.Envelope
import dev.phonebridge.protocol.MessagePayload
import dev.phonebridge.protocol.MessageType

/** How a feature talks to the Mac during a session. */
interface FeatureLink {
    /** Sends on the live session. Returns false if the session already ended. */
    fun <P : MessagePayload> send(mt: MessageType<P>, payload: P): Boolean

    /** Sends a binary frame (file chunks, PROTOCOL.md §9.2). Returns false if the session ended. */
    fun sendBinary(frame: ByteArray): Boolean
}

/**
 * A pluggable feature (clipboard, files, …). Features depend only on core modules, never on each other;
 * the app registers them in one list (AppGraph). Callbacks run on the session's serial dispatcher.
 */
interface Feature {
    val id: String
    val version: Int get() = 1

    /** Reported to the Mac in `auth`; only `AVAILABLE` features get enabled. */
    fun state(): CapabilityState = CapabilityState.AVAILABLE

    /** The feature is enabled on a new session. */
    suspend fun onSessionStarted(link: FeatureLink) {}

    /** A message for this feature arrived. */
    suspend fun onMessage(envelope: Envelope) {}

    /** A binary frame arrived (only the files feature receives these). */
    suspend fun onBinary(frame: ByteArray) {}

    /** The session ended (any reason). */
    fun onSessionEnded() {}

    fun capability() = Capability(id, version, state())
}
