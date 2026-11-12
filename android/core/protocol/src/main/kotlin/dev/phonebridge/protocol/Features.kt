package dev.phonebridge.protocol

/** Feature ids (PROTOCOL.md §6–§10) and which feature owns each message type. Mirrors mac SessionHandler.featureId. */
object Features {
    const val STATUS = "status"
    const val FINDPHONE = "findphone"
    const val CLIPBOARD = "clipboard"
    const val FILES = "files"
    const val NOTIFICATIONS = "notifications"

    fun idFor(type: String): String? = when {
        type.startsWith("status.") -> STATUS
        type.startsWith("phone.ring") -> FINDPHONE
        type.startsWith("clipboard.") -> CLIPBOARD
        type.startsWith("file.") -> FILES
        type.startsWith("notif.") -> NOTIFICATIONS
        else -> null
    }
}
