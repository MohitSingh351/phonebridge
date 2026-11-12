import Foundation
import PBProtocol

/// Loop-prevention rules for clipboard sync (PROTOCOL.md §8) as pure logic.
/// Mirrors Android `ClipboardSyncCore` — keep the two identical.
///
/// - An item received from the phone and written to the pasteboard is never sent back.
/// - Copying the same text twice in a row sends it once.
/// - After a *different* item was copied, re-copying an older text sends it again.
public final class ClipboardSyncCore: @unchecked Sendable {
    public enum Outgoing: Equatable {
        case send(ClipboardSet)
        case cameFromOtherDevice
        case alreadySent
        case empty
        case tooLarge
    }

    private let myDeviceId: PBUUID
    private var lastAppliedHash: String?
    private var lastSentHash: String?
    private let lock = NSLock()

    public init(myDeviceId: PBUUID) { self.myDeviceId = myDeviceId }

    public func onLocalCopy(_ text: String) -> Outgoing {
        lock.lock(); defer { lock.unlock() }
        if text.isEmpty { return .empty }
        if text.utf8.count > ClipboardSet.maxTextBytes { return .tooLarge }
        let hash = ClipboardSet.hash(of: text)
        if hash == lastAppliedHash { return .cameFromOtherDevice }
        if hash == lastSentHash { return .alreadySent }
        lastAppliedHash = nil
        lastSentHash = hash
        return .send(ClipboardSet(originDeviceId: myDeviceId, text: text))
    }

    /// Text to write to the pasteboard, or nil to ignore (our own echo).
    public func onRemote(_ message: ClipboardSet) -> String? {
        lock.lock(); defer { lock.unlock() }
        if message.originDeviceId == myDeviceId { return nil }
        lastAppliedHash = message.hash
        lastSentHash = nil
        return message.text
    }
}
