import Foundation
import PBProtocol
import Testing
@testable import FeatureNotifications

private func item(_ key: String, _ text: String = "Lunch at 1?", at: Int64 = 1000, pkg: String = "com.whatsapp") -> NotificationItem {
    NotificationItem(key: key, pkg: pkg, app: "WhatsApp", title: "Ravi", text: text, postedAt: at)
}

@Suite("Mirror state (§10)")
struct MirrorStateTests {
    let phone = PBUUID.random()

    @Test func liveNotificationAlertsAndIdenticalRepostDoesNot() {
        var m = MirrorState()
        let first = m.posted(item("k", at: 1), device: phone, name: "Pixel")
        guard case .post(let n, let alert) = first.first else { Issue.record("no post"); return }
        #expect(alert)
        #expect(n.id == MirrorState.identifier(phone, "k"))
        #expect(m.posted(item("k", at: 2), device: phone, name: "Pixel").isEmpty) // silent refresh
        guard case .post(_, true) = m.posted(item("k", "Lunch at 2?"), device: phone, name: "Pixel").first else {
            Issue.record("changed content should alert"); return
        }
        #expect(m.items.count == 1)
    }

    @Test func removalOnlyForKnownItems() {
        var m = MirrorState()
        #expect(m.removed(key: "nope", device: phone).isEmpty)
        _ = m.posted(item("k"), device: phone, name: "Pixel")
        #expect(m.removed(key: "k", device: phone) == [.remove([MirrorState.identifier(phone, "k")])])
        #expect(m.items.isEmpty)
    }

    @Test func syncReplacesThisPhoneQuietlyAndLeavesOthers() {
        var m = MirrorState()
        let other = PBUUID.random()
        _ = m.posted(item("old"), device: phone, name: "Pixel")
        _ = m.posted(item("keep"), device: phone, name: "Pixel")
        _ = m.posted(item("x"), device: other, name: "Tablet")
        let d = m.sync([item("keep"), item("new", at: 5000)], device: phone, name: "Pixel")
        #expect(d.first == .remove([MirrorState.identifier(phone, "old")]))
        let posts = d.compactMap { if case .post(let n, let alert) = $0 { (n.item.key, alert) } else { nil } }
        #expect(posts.map(\.0) == ["new"]) // "keep" unchanged → not re-posted
        #expect(posts.allSatisfy { !$0.1 }) // no banners for catch-up items
        #expect(Set(m.items.map(\.item.key)) == ["keep", "new", "x"])
        #expect(m.items.first?.item.key == "new") // newest first
    }

    @Test func emptySyncClearsThePhone() {
        var m = MirrorState()
        _ = m.posted(item("a"), device: phone, name: "Pixel")
        _ = m.sync([], device: phone, name: "Pixel")
        #expect(m.items.isEmpty)
    }

    @Test func dismissReturnsThePhoneKeyOnce() {
        var m = MirrorState()
        _ = m.posted(item("k"), device: phone, name: "Pixel")
        let id = MirrorState.identifier(phone, "k")
        #expect(m.dismiss(id: id)?.key == "k")
        #expect(m.dismiss(id: id) == nil)
    }
}

@MainActor
final class FakePoster: NotificationPoster {
    var posted: [(id: String, alert: Bool, icon: Data?)] = []
    var removed: [String] = []
    func post(_ n: MirroredNotification, alert: Bool, iconPNG: Data?) { posted.append((n.id, alert, iconPNG)) }
    func remove(ids: [String]) { removed += ids }
}

@MainActor
@Suite("Notifications feature")
struct NotificationsFeatureTests {
    let phone = PBUUID.random()

    func env<P: MessagePayload>(_ p: P) -> Envelope { try! Envelope.make(p, seq: 1) }

    @Test func iconIsAttachedAndRemovalFlowsThrough() {
        let poster = FakePoster()
        let f = NotificationsFeature(poster: poster)
        f.handle(env(NotifIcon(pkg: "com.whatsapp", png: B64(Data([1, 2, 3])))), device: phone, name: "Pixel")
        f.handle(env(NotifPosted(item: item("k"))), device: phone, name: "Pixel")
        #expect(poster.posted.count == 1)
        #expect(poster.posted[0].alert)
        #expect(poster.posted[0].icon == Data([1, 2, 3]))
        #expect(f.items.map(\.item.key) == ["k"])

        f.handle(env(NotifRemoved(key: "k")), device: phone, name: "Pixel")
        #expect(poster.removed == [MirrorState.identifier(phone, "k")])
        #expect(f.items.isEmpty)
    }

    @Test func dismissOnMacWorksOfflineAndOnlyOnce() {
        let poster = FakePoster()
        let f = NotificationsFeature(poster: poster)
        f.handle(env(NotifSync(items: [item("a", at: 1), item("b", at: 2)])), device: phone, name: "Pixel")
        #expect(poster.posted.map(\.alert) == [false, false])
        f.dismiss(f.items[0].id)
        f.dismiss(MirrorState.identifier(phone, "b")) // already gone
        #expect(poster.removed == [MirrorState.identifier(phone, "b")])
        f.dismissAll()
        #expect(f.items.isEmpty)
    }

    @Test func malformedMessageIsIgnored() {
        let poster = FakePoster()
        let f = NotificationsFeature(poster: poster)
        let bad = Envelope(type: NotifPosted.type, seq: 1, payload: .object(["key": .string("k")]))
        f.handle(bad, device: phone, name: "Pixel")
        #expect(poster.posted.isEmpty)
    }
}
