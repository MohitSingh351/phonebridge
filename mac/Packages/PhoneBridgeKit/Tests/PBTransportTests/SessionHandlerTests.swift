import CryptoKit
import Foundation
import PBProtocol
import PBSecurity
import Testing
@testable import PBTransport

final class Clock: @unchecked Sendable {
    var t = Date(timeIntervalSince1970: 1_800_000_000)
    func advance(_ s: TimeInterval) { t = t.addingTimeInterval(s) }
}

/// A simulated phone: its own P-256 key (stands in for the Android Keystore key) and seq counter.
struct FakePhone {
    var key = P256.Signing.PrivateKey()
    var id = PBUUID.random()
    var seq = OutboundSequence()
    var spki: Data { key.publicKey.derRepresentation }
    var fingerprintHex: String { Hex.encode(ProtocolCrypto.spkiFingerprint(spkiDER: spki)) }

    mutating func frame<P: MessagePayload>(_ p: P, re: PBUUID? = nil) -> Data {
        try! EnvelopeCodec.encode(Envelope.make(p, seq: seq.take(), re: re))
    }

    func sign(macNonce: Data, clientNonce: Data, macId: PBUUID, as phoneId: PBUUID? = nil, with k: P256.Signing.PrivateKey? = nil) -> B64 {
        let msg = ProtocolCrypto.authSigningBytes(macNonce: macNonce, clientNonce: clientNonce, macDeviceId: macId,
                                                  phoneDeviceId: phoneId ?? id)
        return B64(try! (k ?? key).signature(for: msg).derRepresentation)
    }

    mutating func auth(hello: Hello, deviceId: PBUUID? = nil, version: Int = 1, sigNonce: Data? = nil,
                       signer: P256.Signing.PrivateKey? = nil, caps: [Capability] = []) -> Data {
        let cn = ProtocolCrypto.randomBytes(32)
        let sig = sign(macNonce: sigNonce ?? hello.nonce.data, clientNonce: cn, macId: hello.deviceId,
                       as: deviceId, with: signer)
        return frame(Auth(deviceId: deviceId ?? id, protocolVersion: version, appVersion: "1.0.0",
                          clientNonce: B64(cn), sig: sig, capabilities: caps))
    }

    mutating func pairRequest(token: Data) -> Data {
        frame(PairRequest(token: B64(token), deviceId: id, deviceName: "Pixel 9", manufacturer: "Google",
                          model: "Pixel 9", platform: "android", osVersion: "17", sdkInt: 37, appVersion: "1.0.0"))
    }
}

extension [SessionAction] {
    var sent: [Envelope] { compactMap { if case .send(let e) = $0 { e } else { nil } } }
    var closeCode: UInt16? { compactMap { if case .close(let c, _) = $0 { c } else { nil } }.first }
    var events: [SessionEvent] { compactMap { if case .event(let e) = $0 { e } else { nil } } }
    var errorCode: ErrorCode? { sent.first { $0.type == ErrorPayload.type }.flatMap { try? $0.decode(ErrorPayload.self).code } }
}

struct Harness {
    let clock = Clock()
    let macKey = P256.Signing.PrivateKey()
    let devices = InMemoryDeviceStore()
    let pairing: PairingManager
    let mac: MacInfo

    init() {
        let clock = self.clock
        pairing = PairingManager(now: { clock.t })
        mac = MacInfo(deviceId: .random(), name: "Test Mac", appVersion: "1.0.0",
                      fingerprint: ProtocolCrypto.spkiFingerprint(spkiDER: macKey.publicKey.derRepresentation),
                      port: 47321, capabilities: [Capability(id: "clipboard", v: 1, state: .available),
                                                  Capability(id: "files", v: 1, state: .available)])
    }

    func trust(_ phone: FakePhone) {
        try! devices.save(TrustedDevice(id: phone.id, name: "Pixel 9", model: "Pixel 9", fingerprintHex: phone.fingerprintHex))
    }

    func session(for phone: FakePhone) -> (SessionHandler, Hello?, [SessionAction]) {
        let clock = self.clock
        let h = SessionHandler(mac: mac, peerSPKI: phone.spki, devices: devices, pairing: pairing, now: { clock.t })
        let actions = h.start()
        return (h, try? actions.sent.first?.decode(Hello.self), actions)
    }

    /// Fully authenticated session.
    func ready(_ phone: inout FakePhone, caps: [Capability] = []) -> SessionHandler {
        trust(phone)
        let (h, hello, _) = session(for: phone)
        let a = h.receive(phone.auth(hello: hello!, caps: caps))
        precondition(a.sent.first?.type == Ready.type)
        return h
    }
}

@Suite("Session handshake (§4)")
struct HandshakeTests {
    @Test func trustedPhoneAuthenticates() throws {
        let hx = Harness()
        var phone = FakePhone()
        hx.trust(phone)
        let (h, hello, start) = hx.session(for: phone)
        #expect(start.sent.count == 1)
        #expect(hello?.mode == .session)
        #expect(hello?.nonce.data.count == 32)

        let caps = [Capability(id: "clipboard", v: 1, state: .available),
                    Capability(id: "files", v: 1, state: .permissionMissing)]
        let a = h.receive(phone.auth(hello: hello!, caps: caps))
        let ready = try #require(try a.sent.first?.decode(Ready.self))
        #expect(ready.enabled == ["clipboard"]) // files: phone lacks permission
        #expect(a.sent.first?.re != nil)
        #expect(h.isReady)
        #expect(hx.devices.device(id: phone.id)?.lastSeen != nil)
        guard case .authenticated(let d, _, let enabled) = a.events.first else { Issue.record("no event"); return }
        #expect(d.id == phone.id)
        #expect(enabled == ["clipboard"])
    }

    @Test func unknownDeviceWithoutPairingWindowIsRejected() {
        let hx = Harness()
        let (_, hello, start) = hx.session(for: FakePhone())
        #expect(hello == nil)
        #expect(start.errorCode == .revoked)
        #expect(start.closeCode == 4003)
    }

    @Test func forgedSignatureFails() {
        let hx = Harness()
        var phone = FakePhone()
        hx.trust(phone)
        let (h, hello, _) = hx.session(for: phone)
        let a = h.receive(phone.auth(hello: hello!, signer: P256.Signing.PrivateKey()))
        #expect(a.errorCode == .authFailed)
        #expect(a.closeCode == 4001)
        #expect(h.isClosed)
        #expect(a.events.isEmpty) // nothing leaks to features
    }

    @Test func replayedAuthFromOldSessionFails() {
        let hx = Harness()
        var phone = FakePhone()
        hx.trust(phone)
        // Record a valid auth from session 1…
        let (_, hello1, _) = hx.session(for: phone)
        let recorded = phone.auth(hello: hello1!)
        // …and replay it in session 2 (fresh nonce). seq 1 is valid on a new connection, so only the
        // nonce binding stops it.
        var replayer = phone
        replayer.seq = OutboundSequence()
        let (h2, _, _) = hx.session(for: phone)
        let a = h2.receive(recorded)
        #expect(a.closeCode == 4001)
    }

    @Test func signatureOverWrongNonceFails() {
        let hx = Harness()
        var phone = FakePhone()
        hx.trust(phone)
        let (h, hello, _) = hx.session(for: phone)
        let a = h.receive(phone.auth(hello: hello!, sigNonce: ProtocolCrypto.randomBytes(32)))
        #expect(a.closeCode == 4001)
    }

    @Test func deviceIdMustMatchCertificate() {
        let hx = Harness()
        var phone = FakePhone()
        hx.trust(phone)
        let other = PBUUID.random()
        let (h, hello, _) = hx.session(for: phone)
        let a = h.receive(phone.auth(hello: hello!, deviceId: other))
        #expect(a.errorCode == .revoked)
        #expect(a.closeCode == 4003)
    }

    @Test func revokedDeviceCannotReconnect() throws {
        let hx = Harness()
        let phone = FakePhone()
        hx.trust(phone)
        try hx.devices.remove(id: phone.id)
        let (_, _, start) = hx.session(for: phone)
        #expect(start.closeCode == 4003)
    }

    @Test func versionMismatch() {
        let hx = Harness()
        var phone = FakePhone()
        hx.trust(phone)
        let (h, hello, _) = hx.session(for: phone)
        let a = h.receive(phone.auth(hello: hello!, version: 2))
        #expect(a.errorCode == .versionMismatch)
        #expect(a.closeCode == 4002)
    }

    @Test func sequenceViolationIsFatal() {
        let hx = Harness()
        var phone = FakePhone()
        hx.trust(phone)
        let (h, hello, _) = hx.session(for: phone)
        _ = phone.seq.take() // skip seq 1 → auth arrives with seq 2
        let a = h.receive(phone.auth(hello: hello!))
        #expect(a.errorCode == .sequenceViolation)
        #expect(a.closeCode == 4004)
    }

    @Test func duplicateSeqAfterReadyIsFatal() {
        let hx = Harness()
        var phone = FakePhone()
        let h = hx.ready(&phone)
        let ping = phone.frame(Ping())
        #expect(h.receive(ping).sent.first?.type == Pong.type)
        #expect(h.receive(ping).closeCode == 4004) // replayed frame
    }

    @Test func wrongFirstMessage() {
        let hx = Harness()
        var phone = FakePhone()
        hx.trust(phone)
        let (h, _, _) = hx.session(for: phone)
        let a = h.receive(phone.frame(Ping()))
        #expect(a.closeCode == 4009)
    }

    @Test func malformedAuthIsFatal() {
        let hx = Harness()
        var phone = FakePhone()
        hx.trust(phone)
        let (h, _, _) = hx.session(for: phone)
        let a = h.receive(Data(#"{"v":1,"type":"auth","id":"3f8e1c2a-0b4d-4e6f-8a9b-1c2d3e4f5a6b","seq":1,"ts":1,"payload":{}}"#.utf8))
        #expect(a.errorCode == .invalidPayload)
        #expect(a.closeCode != nil)
    }

    @Test func handshakeTimeout() {
        let hx = Harness()
        let phone = FakePhone()
        hx.trust(phone)
        let (h, _, _) = hx.session(for: phone)
        hx.clock.advance(14)
        #expect(h.tick().isEmpty)
        hx.clock.advance(2)
        #expect(h.tick().closeCode == 4010)
    }
}

@Suite("Ready session")
struct ReadyTests {
    @Test func heartbeatTimeout() {
        let hx = Harness()
        var phone = FakePhone()
        let h = hx.ready(&phone)
        hx.clock.advance(44)
        #expect(h.tick().isEmpty)
        _ = h.receive(phone.frame(Ping()))
        hx.clock.advance(44)
        #expect(h.tick().isEmpty)
        hx.clock.advance(2)
        #expect(h.tick().closeCode == 4010)
    }

    @Test func routesEnabledFeaturesOnly() {
        let hx = Harness()
        var phone = FakePhone()
        let h = hx.ready(&phone, caps: [Capability(id: "clipboard", v: 1, state: .available)])
        let clip = h.receive(phone.frame(ClipboardSet(originDeviceId: phone.id, text: "hi")))
        guard case .message(let env) = clip.events.first else { Issue.record("not routed"); return }
        #expect(env.type == ClipboardSet.type)

        let file = h.receive(phone.frame(FilePause(transferId: .random())))
        #expect(file.errorCode == .featureDisabled)
        #expect(file.closeCode == nil) // non-fatal after ready
        #expect(h.isReady)
    }

    @Test func unknownTypeIsNonFatal() {
        let hx = Harness()
        var phone = FakePhone()
        let h = hx.ready(&phone)
        let env = Envelope(type: "future.thing", seq: phone.seq.take(), payload: .object([:]))
        let a = h.receive(try! EnvelopeCodec.encode(env))
        #expect(a.errorCode == .unsupportedType)
        #expect(h.isReady)
    }

    @Test func phoneUnpairRemovesDevice() {
        let hx = Harness()
        var phone = FakePhone()
        let h = hx.ready(&phone)
        let a = h.receive(phone.frame(PairUnpair()))
        #expect(a.closeCode == 4003)
        #expect(hx.devices.device(id: phone.id) == nil)
        #expect(a.events == [.unpaired(phone.id)])
    }

    @Test func macRevokeSendsRevokedThenCloses() throws {
        let hx = Harness()
        var phone = FakePhone()
        let h = hx.ready(&phone)
        let a = h.revoke()
        #expect(a.sent.first?.type == PairRevoked.type)
        #expect(a.closeCode == 4003)
        #expect(h.isClosed)
    }
}

@Suite("Pairing flow (§5)")
struct PairingFlowTests {
    @Test func approvedPairingStoresDevice() throws {
        let hx = Harness()
        var phone = FakePhone()
        let ticket = hx.pairing.open()
        let (h, hello, _) = hx.session(for: phone)
        #expect(hello?.mode == .pairing)
        #expect(hello?.capabilities.isEmpty == true)

        let a = h.receive(phone.pairRequest(token: ticket.token))
        let confirm = try #require(try a.sent.first?.decode(PairConfirmCode.self))
        // The phone computes the same code independently.
        let phoneSide = ProtocolCrypto.sasCode(macSpkiFingerprint: hx.mac.fingerprint,
                                               phoneSpkiFingerprint: ProtocolCrypto.spkiFingerprint(spkiDER: phone.spki),
                                               macNonce: hello!.nonce.data)
        #expect(confirm.code == phoneSide)
        guard case .pairingRequested(let info) = a.events.first else { Issue.record("no request"); return }
        #expect(info.code == confirm.code)
        #expect(hx.devices.all().isEmpty) // nothing stored before approval

        let done = h.resolveApproval(true)
        let ok = try #require(try done.sent.first?.decode(PairOk.self))
        #expect(ok.macDeviceId == hx.mac.deviceId)
        #expect(done.closeCode == 4000)
        #expect(hx.devices.device(fingerprintHex: phone.fingerprintHex)?.id == phone.id)
        #expect(!hx.pairing.isOpen) // token consumed
    }

    @Test func deniedPairingStoresNothing() throws {
        let hx = Harness()
        var phone = FakePhone()
        let ticket = hx.pairing.open()
        let (h, _, _) = hx.session(for: phone)
        _ = h.receive(phone.pairRequest(token: ticket.token))
        let a = h.resolveApproval(false)
        #expect(try a.sent.first?.decode(PairDenied.self).reason == .userDenied)
        #expect(hx.devices.all().isEmpty)
    }

    @Test func approvalTimesOut() throws {
        let hx = Harness()
        var phone = FakePhone()
        let ticket = hx.pairing.open()
        let (h, _, _) = hx.session(for: phone)
        _ = h.receive(phone.pairRequest(token: ticket.token))
        hx.clock.advance(60)
        let a = h.tick()
        #expect(try a.sent.first?.decode(PairDenied.self).reason == .timeout)
        #expect(h.resolveApproval(true).isEmpty) // too late
        #expect(hx.devices.all().isEmpty)
    }

    @Test func wrongToken() {
        let hx = Harness()
        var phone = FakePhone()
        _ = hx.pairing.open()
        let (h, _, _) = hx.session(for: phone)
        let a = h.receive(phone.pairRequest(token: ProtocolCrypto.randomBytes(32)))
        #expect(a.errorCode == .tokenInvalid)
        #expect(a.closeCode == 4005)
    }

    @Test func reusedTokenOnSecondConnection() {
        let hx = Harness()
        var p1 = FakePhone(), p2 = FakePhone()
        let ticket = hx.pairing.open()
        let (h1, _, _) = hx.session(for: p1)
        _ = h1.receive(p1.pairRequest(token: ticket.token))
        // Pairing window is now closed: an attacker with a new key and the same token is turned away at start.
        let (_, _, start2) = hx.session(for: p2)
        #expect(start2.closeCode == 4003)
        _ = p2
    }

    @Test func expiredToken() {
        let hx = Harness()
        var phone = FakePhone()
        let ticket = hx.pairing.open()
        let (h, _, _) = hx.session(for: phone)
        hx.clock.advance(121)
        let a = h.receive(phone.pairRequest(token: ticket.token))
        #expect(a.errorCode == .tokenExpired)
        #expect(a.closeCode == 4006)
    }

    @Test func fiveBadTokensRateLimit() {
        let hx = Harness()
        _ = hx.pairing.open()
        var last: [SessionAction] = []
        for _ in 0..<6 {
            var phone = FakePhone()
            let (h, hello, start) = hx.session(for: phone)
            if hello == nil { last = start; break }
            last = h.receive(phone.pairRequest(token: ProtocolCrypto.randomBytes(32)))
        }
        // 6th attempt is refused before it can even present a token.
        #expect(last.closeCode == 4003 || last.closeCode == 4007)
        #expect(!hx.pairing.isOpen)
    }

    @Test func onlyPairMessagesAllowedInPairingMode() {
        let hx = Harness()
        var phone = FakePhone()
        _ = hx.pairing.open()
        let (h, hello, _) = hx.session(for: phone)
        let a = h.receive(phone.auth(hello: hello!))
        #expect(a.closeCode == 4009)
    }

    @Test func rePairingSameDeviceReplacesKey() throws {
        let hx = Harness()
        var old = FakePhone()
        hx.trust(old)
        var reinstalled = FakePhone(key: P256.Signing.PrivateKey(), id: old.id)
        let ticket = hx.pairing.open()
        let (h, _, _) = hx.session(for: reinstalled)
        _ = h.receive(reinstalled.pairRequest(token: ticket.token))
        _ = h.resolveApproval(true)
        #expect(hx.devices.all().count == 1)
        #expect(hx.devices.device(id: old.id)?.fingerprintHex == reinstalled.fingerprintHex)
        // The old key no longer works.
        let (_, _, start) = hx.session(for: old)
        #expect(start.closeCode == 4003)
        _ = old.seq.take()
    }
}
