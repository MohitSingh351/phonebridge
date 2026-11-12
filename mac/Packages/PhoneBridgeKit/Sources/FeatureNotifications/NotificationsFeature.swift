import AppKit
import Foundation
import Observation
import OSLog
import PBProtocol
import PBTransport
@preconcurrency import UserNotifications

/// Where mirrored notifications are shown. The system Notification Center in the app; a fake in tests
/// (UNUserNotificationCenter can't be used outside a bundled app).
@MainActor
public protocol NotificationPoster: AnyObject {
    func post(_ n: MirroredNotification, alert: Bool, iconPNG: Data?)
    func remove(ids: [String])
}

/// Notification mirroring, Mac side (PROTOCOL.md §10).
///  - Phone notifications appear in Notification Center as "<App>" with the sender/title as subtitle.
///  - "Dismiss on Phone", clearing it in Notification Center, or the ✕ in the menu clears it on the phone.
///  - Removed on the phone → removed here. `notif.sync` on every connect replaces this phone's state.
///  - If macOS notifications are off for PhoneBridge, the menu still lists them.
@MainActor @Observable
public final class NotificationsFeature: Feature {
    public let id = "notifications"
    public var state: CapabilityState { .available }

    /// Newest first (all paired phones).
    public private(set) var items: [MirroredNotification] = []
    /// App icons by Android package (sent once per session by the phone).
    public private(set) var icons: [String: Data] = [:]
    /// nil until known; false when the user turned PhoneBridge notifications off in System Settings.
    public private(set) var alertsAllowed: Bool?

    private var mirror = MirrorState()
    private var links: [PBUUID: FeatureLink] = [:]
    private let poster: NotificationPoster

    public convenience init() {
        self.init(poster: SystemNotificationPoster())
    }

    init(poster: NotificationPoster) {
        self.poster = poster
        (poster as? SystemNotificationPoster)?.onUserDismiss = Self.dismissHandler(WeakBox(self))
    }

    // MARK: Feature

    public func sessionStarted(_ link: FeatureLink) {
        links[link.device.id] = link
    }

    public func sessionEnded(_ link: FeatureLink) {
        // Keep what's shown: the phone is probably just asleep; the next sync reconciles.
        if links[link.device.id] == link { links[link.device.id] = nil }
    }

    public func received(_ envelope: Envelope, from link: FeatureLink) {
        handle(envelope, device: link.device.id, name: link.device.name)
    }

    /// Message handling without a live link (internal for tests).
    func handle(_ envelope: Envelope, device: PBUUID, name: String) {
        do {
            switch envelope.type {
            case NotifIcon.type:
                let icon = try envelope.decode(NotifIcon.self)
                icons[icon.pkg] = icon.png.data
            case NotifPosted.type:
                let item = try envelope.decode(NotifPosted.self).item
                apply(mirror.posted(item, device: device, name: name))
            case NotifRemoved.type:
                apply(mirror.removed(key: try envelope.decode(NotifRemoved.self).key, device: device))
            case NotifSync.type:
                apply(mirror.sync(try envelope.decode(NotifSync.self).items, device: device, name: name))
            default:
                break
            }
        } catch {
            Self.logger.error("bad \(envelope.type, privacy: .public): \(String(describing: error), privacy: .public)")
        }
    }

    // MARK: User actions

    /// ✕ in the menu (or dismissed in Notification Center): clear it here and on the phone.
    public func dismiss(_ id: String) {
        guard let (device, key) = mirror.dismiss(id: id) else { return }
        poster.remove(ids: [id])
        links[device]?.send(NotifDismiss(key: key)) // if offline, the next sync brings it back — that's correct
        items = mirror.items
    }

    public func dismissAll() {
        for n in items { dismiss(n.id) }
    }

    public func refreshPermission() {
        guard let p = poster as? SystemNotificationPoster else { return }
        p.authorizationAllowed { [weak self] allowed in self?.alertsAllowed = allowed }
    }

    public func openNotificationSettings() {
        let url = URL(string: "x-apple.systempreferences:com.apple.Notifications-Settings.extension")!
        NSWorkspace.shared.open(url)
    }

    // MARK: Internals

    private func apply(_ deliveries: [MirrorState.Delivery]) {
        for d in deliveries {
            switch d {
            case .post(let n, let alert): poster.post(n, alert: alert, iconPNG: icons[n.item.pkg])
            case .remove(let ids): poster.remove(ids: ids)
            }
        }
        items = mirror.items
    }

    /// Dismiss callbacks come from Notification Center on a background thread: hop to the main actor here,
    /// in a nonisolated factory (see FilesFeature for why closures must not be created inside the class).
    final class WeakBox: @unchecked Sendable { weak var feature: NotificationsFeature?; init(_ f: NotificationsFeature) { feature = f } }

    nonisolated static func dismissHandler(_ box: WeakBox) -> @Sendable (String) -> Void {
        { id in DispatchQueue.main.async { MainActor.assumeIsolated { box.feature?.dismiss(id) } } }
    }

    nonisolated static let logger = Logger(subsystem: "dev.phonebridge", category: "notifications")
}

// MARK: - System Notification Center

/// Posts mirrored notifications with UNUserNotificationCenter and reports user dismissals back.
@MainActor
final class SystemNotificationPoster: NotificationPoster {
    nonisolated static let category = "pb.mirror"
    nonisolated static let dismissAction = "pb.dismiss"

    var onUserDismiss: (@Sendable (String) -> Void)? {
        didSet { delegate.onDismiss = onUserDismiss }
    }

    private let center = UNUserNotificationCenter.current()
    private let delegate = CenterDelegate()
    private let iconDir = FileManager.default.temporaryDirectory.appendingPathComponent("pb-icons", isDirectory: true)

    init() {
        center.delegate = delegate
        let dismiss = UNNotificationAction(identifier: Self.dismissAction, title: "Dismiss on Phone")
        // customDismissAction: clearing it in Notification Center tells us, so we can clear it on the phone.
        center.setNotificationCategories([
            UNNotificationCategory(identifier: Self.category, actions: [dismiss], intentIdentifiers: [],
                                   options: [.customDismissAction]),
        ])
        center.requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
        // Mirrored notifications from a previous run are stale; the phone's sync brings back the live ones.
        center.getDeliveredNotifications { delivered in
            let stale = delivered.map(\.request.identifier).filter { $0.hasPrefix(MirrorState.idPrefix) }
            UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: stale)
        }
        try? FileManager.default.createDirectory(at: iconDir, withIntermediateDirectories: true)
    }

    func post(_ n: MirroredNotification, alert: Bool, iconPNG: Data?) {
        let c = UNMutableNotificationContent()
        let item = n.item
        c.title = item.app
        if let t = item.title, !t.isEmpty { c.subtitle = t }
        c.body = item.text ?? (item.redacted ? "Content hidden by Android" : "")
        c.threadIdentifier = "\(n.deviceId)|\(item.pkg)"
        c.categoryIdentifier = Self.category
        c.interruptionLevel = alert ? .active : .passive
        if alert { c.sound = .default }
        if let png = iconPNG {
            // The attachment API moves the file it's given, so each post gets its own copy.
            let url = iconDir.appendingPathComponent(UUID().uuidString + ".png")
            if (try? png.write(to: url)) != nil,
               let a = try? UNNotificationAttachment(identifier: "icon", url: url,
                                                     options: [UNNotificationAttachmentOptionsTypeHintKey: "public.png"]) {
                c.attachments = [a]
            }
        }
        center.add(UNNotificationRequest(identifier: n.id, content: c, trigger: nil)) { err in
            if let err { NotificationsFeature.logger.error("post failed: \(err.localizedDescription, privacy: .public)") }
        }
    }

    func remove(ids: [String]) {
        guard !ids.isEmpty else { return }
        center.removeDeliveredNotifications(withIdentifiers: ids)
        center.removePendingNotificationRequests(withIdentifiers: ids)
    }

    func authorizationAllowed(_ done: @escaping @MainActor (Bool) -> Void) {
        center.getNotificationSettings { s in
            let allowed = s.authorizationStatus == .authorized || s.authorizationStatus == .provisional
            DispatchQueue.main.async { MainActor.assumeIsolated { done(allowed) } }
        }
    }
}

/// Notification Center's delegate (one per app; it also lets other PhoneBridge notifications show as
/// banners while the menu is open). Called on arbitrary threads — keep it nonisolated.
final class CenterDelegate: NSObject, UNUserNotificationCenterDelegate, @unchecked Sendable {
    var onDismiss: (@Sendable (String) -> Void)?

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .list, .sound])
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        let id = response.notification.request.identifier
        let action = response.actionIdentifier
        if id.hasPrefix(MirrorState.idPrefix),
           action == SystemNotificationPoster.dismissAction || action == UNNotificationDismissActionIdentifier {
            onDismiss?(id)
        }
        completionHandler()
    }
}
