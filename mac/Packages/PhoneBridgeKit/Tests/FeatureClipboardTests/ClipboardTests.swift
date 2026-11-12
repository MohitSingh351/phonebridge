import Foundation
import PBProtocol
import Testing
@testable import FeatureClipboard

/// Clipboard loop tests (plan §7), mirroring Android's ClipboardSyncCoreTest.
@Suite("Clipboard sync core")
struct ClipboardSyncCoreTests {
    final class Device {
        let core: ClipboardSyncCore
        var clipboard = ""
        var peer: Device!
        var sent = 0
        init(_ id: PBUUID) { core = ClipboardSyncCore(myDeviceId: id) }

        func localChange(_ text: String) {
            clipboard = text
            if case .send(let m) = core.onLocalCopy(text) { sent += 1; peer.receive(m) }
        }
        func receive(_ m: ClipboardSet) {
            guard let t = core.onRemote(m) else { return }
            localChange(t) // writing fires the watcher: the classic loop trigger
        }
    }

    func pair() -> (Device, Device) {
        let a = Device(.random()), b = Device(.random())
        a.peer = b; b.peer = a
        return (a, b)
    }

    @Test func thousandRandomCopiesCrossOnceAndNeverBounce() {
        let (phone, mac) = pair()
        var rng = SystemRandomNumberGenerator()
        var expected = 0
        for _ in 0..<1000 {
            let by = Bool.random(using: &rng) ? phone : mac
            let other = by === phone ? mac : phone
            let text = "item-\(Int.random(in: 0..<12, using: &rng))"
            if by.clipboard != text { expected += 1 }
            by.localChange(text)
            #expect(by.clipboard == other.clipboard)
        }
        #expect(phone.sent + mac.sent == expected)
    }

    @Test func receivedItemIsNotEchoed() {
        let (phone, mac) = pair()
        mac.localChange("hello")
        #expect(phone.clipboard == "hello")
        #expect(phone.sent == 0)
    }

    @Test func deliberateReCopySyncsAgain() {
        let (phone, mac) = pair()
        mac.localChange("A"); phone.localChange("B"); phone.localChange("A")
        #expect(mac.clipboard == "A")
        #expect(phone.sent == 2)
    }

    @Test func sameTextTwiceSendsOnce() {
        let (phone, _) = pair()
        phone.localChange("same"); phone.localChange("same")
        #expect(phone.sent == 1)
    }
}

/// Fake pasteboard for the watcher tests.
@MainActor
final class FakePasteboard: Pasteboard {
    var changeCount = 0
    var typeIdentifiers: [String] = ["public.utf8-plain-text"]
    var text: String?
    var reads = 0
    var accessDenied = false
    func readString() -> String? { reads += 1; return text }
    func write(_ t: String) -> Int { text = t; changeCount += 1; return changeCount }
    func userCopies(_ t: String, types: [String] = ["public.utf8-plain-text"]) {
        text = t; typeIdentifiers = types; changeCount += 1
    }
}

@MainActor
@Suite("Mac pasteboard watcher")
struct WatcherTests {
    let macId = PBUUID.random()

    @Test func passwordManagerItemsAreNeverRead() {
        let pb = FakePasteboard()
        let f = ClipboardFeature(macDeviceId: macId, pasteboard: pb, startTimer: false)
        pb.userCopies("hunter2", types: ["public.utf8-plain-text", "org.nspasteboard.ConcealedType"])
        f.poll()
        #expect(pb.reads == 0, "concealed items must not even be read")
    }

    @Test func unchangedPasteboardIsNotRead() {
        let pb = FakePasteboard()
        let f = ClipboardFeature(macDeviceId: macId, pasteboard: pb, startTimer: false)
        f.poll(); f.poll()
        #expect(pb.reads == 0, "polling must only read after a change (avoids privacy prompts)")
    }

    @Test func whateverWasCopiedBeforeLaunchIsNotSent() {
        let pb = FakePasteboard()
        pb.userCopies("old secret")
        let f = ClipboardFeature(macDeviceId: macId, pasteboard: pb, startTimer: false)
        f.poll()
        #expect(pb.reads == 0)
    }

    @Test func deniedAccessOrSettingOffMeansNoReads() {
        let pb = FakePasteboard()
        let f = ClipboardFeature(macDeviceId: macId, pasteboard: pb, startTimer: false)
        pb.accessDenied = true
        pb.userCopies("x"); f.poll()
        #expect(pb.reads == 0)
        #expect(f.accessDenied)
        pb.accessDenied = false
        f.autoSend = false
        pb.userCopies("y"); f.poll()
        #expect(pb.reads == 0)
        f.autoSend = true
    }

    @Test func normalCopyIsRead() {
        let pb = FakePasteboard()
        let f = ClipboardFeature(macDeviceId: macId, pasteboard: pb, startTimer: false)
        pb.userCopies("hello"); f.poll()
        #expect(pb.reads == 1)
    }
}
