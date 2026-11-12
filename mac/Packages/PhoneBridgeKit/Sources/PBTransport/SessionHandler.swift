import Foundation
import PBProtocol
import PBSecurity

/// Static facts about this Mac that every session needs.
public struct MacInfo: Sendable {
    public var deviceId: PBUUID
    public var name: String
    public var appVersion: String
    public var fingerprint: Data
    public var port: UInt16
    /// Features this Mac implements (and their state). Grows phase by phase.
    public var capabilities: [Capability]

    public init(deviceId: PBUUID, name: String, appVersion: String, fingerprint: Data, port: UInt16,
                capabilities: [Capability]) {
        self.deviceId = deviceId; self.name = name; self.appVersion = appVersion
        self.fingerprint = fingerprint; self.port = port; self.capabilities = capabilities
    }
}

public struct PairingRequestInfo: Equatable, Sendable {
    public let deviceId: PBUUID
    public let deviceName: String
    public let model: String
    public let code: String
}

public enum SessionEvent: Equatable, Sendable {
    case authenticated(TrustedDevice, sessionId: PBUUID, enabled: [String])
    case pairingRequested(PairingRequestInfo)
    case paired(TrustedDevice)
    case pairingFailed(String)
    case unpaired(PBUUID)
    /// A message for an enabled feature (routed by the server to the owning feature module).
    case message(Envelope)
    /// The phone's capabilities changed mid-session (e.g. notification access granted); new enabled set.
    case enabledChanged([String])
}

public enum SessionAction: Equatable, Sendable {
    case send(Envelope)
    case close(code: UInt16, reason: String)
    case event(SessionEvent)
}

/// The Mac side of one connection, as a pure state machine (PROTOCOL.md §4, §5).
/// No I/O: the network layer feeds it frames and performs the returned actions. This keeps every
/// security decision unit-testable (auth failures, replay, revocation, token rules, timeouts).
public final class SessionHandler: @unchecked Sendable {
    public static let handshakeTimeout: TimeInterval = 15
    public static let approvalTimeout: TimeInterval = 60

    enum State {
        case idle
        case awaitingAuth(TrustedDevice)
        case awaitingPairRequest
        case awaitingApproval(PairRequest, requestId: PBUUID, deadline: Date)
        case ready(TrustedDevice, sessionId: PBUUID, enabled: Set<String>, heartbeatSec: Int)
        case closed
    }

    private let mac: MacInfo
    private let peerSPKI: Data
    public let peerFingerprint: Data
    private let devices: DeviceStore
    private let pairing: PairingManager
    private let now: @Sendable () -> Date
    private let nonce: Data
    private var inbound = InboundSequence()
    private var outbound = OutboundSequence()
    private var state: State = .idle
    /// The phone's latest capability states (from `auth`, updated by `capabilities.changed`).
    private var peerCaps: [String: CapabilityState] = [:]
    private var startedAt = Date.distantPast
    private var lastInbound = Date.distantPast

    public init(mac: MacInfo, peerSPKI: Data, devices: DeviceStore, pairing: PairingManager,
                now: @escaping @Sendable () -> Date = { Date() }, nonce: Data = ProtocolCrypto.randomBytes(32)) {
        self.mac = mac
        self.peerSPKI = peerSPKI
        self.peerFingerprint = ProtocolCrypto.spkiFingerprint(spkiDER: peerSPKI)
        self.devices = devices
        self.pairing = pairing
        self.now = now
        self.nonce = nonce
    }

    public var isReady: Bool { if case .ready = state { true } else { false } }
    public var isClosed: Bool { if case .closed = state { true } else { false } }
    public var device: TrustedDevice? {
        switch state {
        case .awaitingAuth(let d), .ready(let d, _, _, _): d
        default: nil
        }
    }

    // MARK: Inputs

    /// Call once when the TLS + WebSocket connection is up. Sends `hello` or rejects the peer.
    public func start() -> [SessionAction] {
        guard case .idle = state else { return [] }
        startedAt = now(); lastInbound = startedAt
        let mode: HelloMode
        if let d = devices.device(fingerprintHex: Hex.encode(peerFingerprint)) {
            state = .awaitingAuth(d)
            mode = .session
        } else if pairing.isOpen {
            state = .awaitingPairRequest
            mode = .pairing
        } else {
            return fail(.revoked, "unknown device", re: nil)
        }
        let caps = mode == .session ? mac.capabilities : []
        return [send(Hello(mode: mode, deviceId: mac.deviceId, deviceName: mac.name, platform: "macos",
                           appVersion: mac.appVersion, nonce: B64(nonce), capabilities: caps))]
    }

    /// Call for every inbound WebSocket text frame.
    public func receive(_ data: Data) -> [SessionAction] {
        if case .closed = state { return [] }
        lastInbound = now()
        let env: Envelope
        do {
            env = try EnvelopeCodec.decode(data)
            try inbound.accept(env.seq)
        } catch {
            return fail(error.code, error.message, re: nil)
        }
        switch state {
        case .idle, .closed:
            return []
        case .awaitingAuth(let device):
            return handleAuth(env, device: device)
        case .awaitingPairRequest:
            return handlePairRequest(env)
        case .awaitingApproval:
            return fail(.notAllowedInMode, "unexpected \(env.type) while awaiting approval", re: env.id)
        case .ready(let device, _, let enabled, _):
            return handleReady(env, device: device, enabled: enabled)
        }
    }

    /// The user tapped Allow / Deny on the Mac.
    public func resolveApproval(_ allow: Bool) -> [SessionAction] {
        guard case .awaitingApproval(let req, let requestId, _) = state else { return [] }
        guard allow else { return deny(.userDenied, re: requestId) }
        let device = TrustedDevice(id: req.deviceId, name: req.deviceName, model: req.model,
                                   fingerprintHex: Hex.encode(peerFingerprint), pairedAt: now())
        do {
            // Same deviceId re-pairing (e.g. app reinstalled) replaces the old key.
            try devices.save(device)
        } catch {
            return fail(.authFailed, "could not store device", re: requestId)
        }
        state = .closed
        return [
            send(PairOk(macDeviceId: mac.deviceId, macName: mac.name, port: Int(mac.port)), re: requestId),
            .close(code: ErrorCode.paired.closeCode!, reason: "paired"),
            .event(.paired(device)),
        ]
    }

    /// Call periodically (about once a second): enforces handshake, approval and heartbeat timeouts.
    public func tick() -> [SessionAction] {
        let t = now()
        switch state {
        case .awaitingAuth, .awaitingPairRequest:
            if t.timeIntervalSince(startedAt) > Self.handshakeTimeout {
                return fail(.heartbeatTimeout, "handshake timed out", re: nil)
            }
        case .awaitingApproval(_, let requestId, let deadline):
            if t >= deadline { return deny(.timeout, re: requestId) }
        case .ready(_, _, _, let hb):
            if t.timeIntervalSince(lastInbound) > TimeInterval(3 * hb) {
                return fail(.heartbeatTimeout, "no frames for \(3 * hb)s", re: nil)
            }
        case .idle, .closed:
            break
        }
        return []
    }

    /// The user removed this device on the Mac while it is connected.
    public func revoke() -> [SessionAction] {
        guard case .ready = state else {
            if case .closed = state { return [] }
            return fail(.revoked, "removed", re: nil)
        }
        state = .closed
        return [send(PairRevoked(reason: "removed_by_user")),
                .close(code: ErrorCode.revoked.closeCode!, reason: "revoked")]
    }

    /// Send a feature message on a ready session.
    public func outgoing<P: MessagePayload>(_ payload: P, re: PBUUID? = nil) -> [SessionAction] {
        guard isReady else { return [] }
        return [send(payload, re: re)]
    }

    // MARK: Handlers

    private func handleAuth(_ env: Envelope, device: TrustedDevice) -> [SessionAction] {
        guard env.type == Auth.type else {
            return fail(.notAllowedInMode, "expected auth, got \(env.type)", re: env.id)
        }
        let auth: Auth
        do { auth = try env.decode(Auth.self) } catch let e as ProtocolError {
            return fail(e.code, e.message, re: env.id)
        } catch { return fail(.invalidPayload, "\(error)", re: env.id) }

        guard (PBConstants.minProtocolVersion...PBConstants.protocolVersion).contains(auth.protocolVersion) else {
            return fail(.versionMismatch, "protocol \(auth.protocolVersion) unsupported", re: env.id)
        }
        guard auth.deviceId == device.id else {
            return fail(.revoked, "device id does not match certificate", re: env.id)
        }
        let message = ProtocolCrypto.authSigningBytes(macNonce: nonce, clientNonce: auth.clientNonce.data,
                                                      macDeviceId: mac.deviceId, phoneDeviceId: auth.deviceId)
        guard SPKI.verify(signatureDER: auth.sig.data, message: message, spkiDER: peerSPKI) else {
            return fail(.authFailed, "signature verification failed", re: env.id)
        }

        for c in auth.capabilities { peerCaps[c.id] = c.state }
        let enabled = enabledSet()
        let sessionId = PBUUID.random()
        var seen = device
        seen.lastSeen = now()
        try? devices.save(seen)
        state = .ready(seen, sessionId: sessionId, enabled: enabled, heartbeatSec: PBConstants.defaultHeartbeatSec)
        return [
            send(Ready(sessionId: sessionId, enabled: enabled.sorted()), re: env.id),
            .event(.authenticated(seen, sessionId: sessionId, enabled: enabled.sorted())),
        ]
    }

    private func handlePairRequest(_ env: Envelope) -> [SessionAction] {
        guard env.type == PairRequest.type else {
            return fail(.notAllowedInMode, "only pair.request allowed while pairing", re: env.id)
        }
        let req: PairRequest
        do { req = try env.decode(PairRequest.self) } catch let e as ProtocolError {
            return fail(e.code, e.message, re: env.id)
        } catch { return fail(.invalidPayload, "\(error)", re: env.id) }

        switch pairing.redeem(req.token.data) {
        case .ok: break
        case .invalid: return fail(.tokenInvalid, "wrong pairing token", re: env.id)
        case .expired, .notOpen: return fail(.tokenExpired, "pairing token expired or used", re: env.id)
        case .rateLimited: return fail(.rateLimited, "too many attempts", re: env.id)
        }

        let phoneFp = peerFingerprint
        let code = ProtocolCrypto.sasCode(macSpkiFingerprint: mac.fingerprint, phoneSpkiFingerprint: phoneFp,
                                          macNonce: nonce)
        state = .awaitingApproval(req, requestId: env.id, deadline: now().addingTimeInterval(Self.approvalTimeout))
        return [
            send(PairConfirmCode(code: code, timeoutSec: Int(Self.approvalTimeout)), re: env.id),
            .event(.pairingRequested(PairingRequestInfo(deviceId: req.deviceId, deviceName: req.deviceName,
                                                        model: req.model, code: code))),
        ]
    }

    private func handleReady(_ env: Envelope, device: TrustedDevice, enabled: Set<String>) -> [SessionAction] {
        switch env.type {
        case Ping.type:
            return [send(Pong(), re: env.id)]
        case Pong.type:
            return []
        case PairUnpair.type:
            try? devices.remove(id: device.id)
            state = .closed
            return [.close(code: ErrorCode.revoked.closeCode!, reason: "unpaired"), .event(.unpaired(device.id))]
        case ErrorPayload.type:
            return []
        case CapabilitiesChanged.type:
            guard let msg = try? env.decode(CapabilitiesChanged.self) else {
                return [send(ErrorPayload(code: .invalidPayload, message: env.type, fatal: false), re: env.id)]
            }
            for c in msg.capabilities { peerCaps[c.id] = c.state }
            let now = enabledSet()
            if case .ready(let d, let sid, _, let hb) = state { state = .ready(d, sessionId: sid, enabled: now, heartbeatSec: hb) }
            var out = [send(CapabilitiesEnabled(enabled: now.sorted()), re: env.id)]
            if now != enabled { out.append(.event(.enabledChanged(now.sorted()))) }
            return out
        default:
            guard MessageRegistry.byType[env.type] != nil else {
                return [send(ErrorPayload(code: .unsupportedType, message: env.type, fatal: false), re: env.id)]
            }
            guard let feature = Self.featureId(for: env.type), enabled.contains(feature) else {
                return [send(ErrorPayload(code: .featureDisabled, message: env.type, fatal: false), re: env.id)]
            }
            return [.event(.message(env))]
        }
    }

    /// Which feature owns a message type (PROTOCOL.md §6–§10).
    public static func featureId(for type: String) -> String? {
        if type.hasPrefix("status.") { return "status" }
        if type.hasPrefix("phone.ring") { return "findphone" }
        if type.hasPrefix("clipboard.") { return "clipboard" }
        if type.hasPrefix("file.") { return "files" }
        if type.hasPrefix("notif.") { return "notifications" }
        if type.hasPrefix("capabilities.") { return "session" }
        return nil
    }

    // MARK: Helpers

    /// Features both sides currently have available (PROTOCOL.md §4.3, §4.5).
    private func enabledSet() -> Set<String> {
        let mine = Set(mac.capabilities.filter { $0.state == .available }.map(\.id))
        let theirs = Set(peerCaps.filter { $0.value == .available }.map(\.key))
        return mine.intersection(theirs)
    }

    private func send<P: MessagePayload>(_ p: P, re: PBUUID? = nil) -> SessionAction {
        // Encoding our own well-formed payloads cannot fail.
        .send(try! Envelope.make(p, seq: outbound.take(), re: re))
    }

    private func fail(_ code: ErrorCode, _ message: String, re: PBUUID?) -> [SessionAction] {
        let handshake: Bool = { if case .ready = state { false } else { true } }()
        guard code.isFatal || handshake else {
            return [send(ErrorPayload(code: code, message: message, fatal: false), re: re)]
        }
        state = .closed
        let close = code.closeCode ?? ErrorCode.authFailed.closeCode!
        return [send(ErrorPayload(code: code, message: message, fatal: true), re: re),
                .close(code: close, reason: message)]
    }

    private func deny(_ reason: PairDeniedReason, re: PBUUID) -> [SessionAction] {
        state = .closed
        return [send(PairDenied(reason: reason), re: re), .close(code: 1000, reason: "pairing \(reason.rawValue)"),
                .event(.pairingFailed(reason.rawValue))]
    }
}
