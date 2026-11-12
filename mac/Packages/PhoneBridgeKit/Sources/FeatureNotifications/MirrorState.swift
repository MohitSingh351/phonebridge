import Foundation
import PBProtocol

/// One phone notification shown on the Mac.
public struct MirroredNotification: Identifiable, Hashable, Sendable {
    public let deviceId: PBUUID
    public var deviceName: String
    public var item: NotificationItem

    /// Also the `UNNotificationRequest` identifier, so the same phone notification replaces itself.
    public var id: String { MirrorState.identifier(deviceId, item.key) }
}

/// What the Mac shows, as pure state (PROTOCOL.md §10). The feature turns its `Delivery` results into
/// system notifications; tests check them directly.
struct MirrorState {
    static let idPrefix = "pbn|"
    static func identifier(_ device: PBUUID, _ key: String) -> String { "\(idPrefix)\(device)|\(key)" }

    enum Delivery: Equatable {
        /// Show (or replace) a notification. `alert: false` → straight to Notification Center, no banner/sound.
        case post(MirroredNotification, alert: Bool)
        case remove([String])
    }

    private(set) var byId: [String: MirroredNotification] = [:]

    /// Newest first.
    var items: [MirroredNotification] { byId.values.sorted { $0.item.postedAt > $1.item.postedAt } }

    mutating func posted(_ item: NotificationItem, device: PBUUID, name: String) -> [Delivery] {
        let n = MirroredNotification(deviceId: device, deviceName: name, item: item)
        let unchanged = byId[n.id].map { Self.sameContent($0.item, item) } ?? false
        byId[n.id] = n
        return unchanged ? [] : [.post(n, alert: true)]
    }

    mutating func removed(key: String, device: PBUUID) -> [Delivery] {
        let id = Self.identifier(device, key)
        return byId.removeValue(forKey: id) == nil ? [] : [.remove([id])]
    }

    /// Replace everything from this phone. Items that appeared while disconnected are added quietly — the
    /// user didn't miss a live event, so no banner storm on reconnect.
    mutating func sync(_ items: [NotificationItem], device: PBUUID, name: String) -> [Delivery] {
        var out: [Delivery] = []
        let incoming = Dictionary(items.map { (Self.identifier(device, $0.key), $0) }, uniquingKeysWith: { a, _ in a })
        let gone = byId.values.filter { $0.deviceId == device && incoming[$0.id] == nil }.map(\.id).sorted()
        for id in gone { byId[id] = nil }
        if !gone.isEmpty { out.append(.remove(gone)) }
        for item in items.sorted(by: { $0.postedAt < $1.postedAt }) {
            let n = MirroredNotification(deviceId: device, deviceName: name, item: item)
            if let old = byId[n.id], Self.sameContent(old.item, item) { continue }
            byId[n.id] = n
            out.append(.post(n, alert: false))
        }
        return out
    }

    /// The user dismissed it on the Mac. Returns the phone-side key to dismiss there too.
    mutating func dismiss(id: String) -> (device: PBUUID, key: String)? {
        guard let n = byId.removeValue(forKey: id) else { return nil }
        return (n.deviceId, n.item.key)
    }

    static func sameContent(_ a: NotificationItem, _ b: NotificationItem) -> Bool {
        var a = a, b = b
        a.postedAt = 0; b.postedAt = 0
        return a == b
    }
}
