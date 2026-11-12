import CryptoKit
import Foundation

// Typed payloads for every message in PROTOCOL.md. Field names match the spec exactly.

// MARK: - Session (§4)

public enum CapabilityState: String, LenientStringEnum {
    case available
    case permissionMissing = "permission_missing"
    case disabled
    case unknown
    public static let unknownValue = CapabilityState.unknown
}

public struct Capability: Codable, Sendable, Hashable {
    public var id: String
    public var v: Int
    public var state: CapabilityState
    public init(id: String, v: Int, state: CapabilityState) { self.id = id; self.v = v; self.state = state }
}

public enum HelloMode: String, LenientStringEnum {
    case session, pairing, unknown
    public static let unknownValue = HelloMode.unknown
}

public struct Hello: MessagePayload {
    public static let type = "hello"
    public var mode: HelloMode
    public var protocolVersion: Int
    public var minProtocolVersion: Int
    public var deviceId: PBUUID
    public var deviceName: String
    public var platform: String
    public var appVersion: String
    public var nonce: B64
    public var capabilities: [Capability]

    public init(mode: HelloMode, protocolVersion: Int = PBConstants.protocolVersion,
                minProtocolVersion: Int = PBConstants.minProtocolVersion, deviceId: PBUUID, deviceName: String,
                platform: String, appVersion: String, nonce: B64, capabilities: [Capability]) {
        self.mode = mode; self.protocolVersion = protocolVersion; self.minProtocolVersion = minProtocolVersion
        self.deviceId = deviceId; self.deviceName = deviceName; self.platform = platform
        self.appVersion = appVersion; self.nonce = nonce; self.capabilities = capabilities
    }

    public func validate() throws {
        guard nonce.data.count == 32 else { throw ProtocolError.invalid("nonce must be 32 bytes") }
        guard mode != .unknown else { throw ProtocolError.invalid("unknown hello mode") }
    }
}

public struct Auth: MessagePayload {
    public static let type = "auth"
    public var deviceId: PBUUID
    public var protocolVersion: Int
    public var appVersion: String
    public var clientNonce: B64
    public var sig: B64
    public var capabilities: [Capability]

    public init(deviceId: PBUUID, protocolVersion: Int = PBConstants.protocolVersion, appVersion: String,
                clientNonce: B64, sig: B64, capabilities: [Capability]) {
        self.deviceId = deviceId; self.protocolVersion = protocolVersion; self.appVersion = appVersion
        self.clientNonce = clientNonce; self.sig = sig; self.capabilities = capabilities
    }

    public func validate() throws {
        guard clientNonce.data.count == 32 else { throw ProtocolError.invalid("clientNonce must be 32 bytes") }
        guard !sig.data.isEmpty, sig.data.count <= 80 else { throw ProtocolError.invalid("bad signature length") }
    }
}

public struct Ready: MessagePayload {
    public static let type = "ready"
    public var sessionId: PBUUID
    public var enabled: [String]
    public var heartbeatSec: Int
    public init(sessionId: PBUUID, enabled: [String], heartbeatSec: Int = PBConstants.defaultHeartbeatSec) {
        self.sessionId = sessionId; self.enabled = enabled; self.heartbeatSec = heartbeatSec
    }
    public func validate() throws {
        guard (5...300).contains(heartbeatSec) else { throw ProtocolError.invalid("heartbeatSec out of range") }
    }
}

public struct Ping: MessagePayload { public static let type = "ping"; public init() {} }
public struct Pong: MessagePayload { public static let type = "pong"; public init() {} }

public struct CapabilitiesChanged: MessagePayload {
    public static let type = "capabilities.changed"
    public var capabilities: [Capability]
    public init(capabilities: [Capability]) { self.capabilities = capabilities }
}

public struct CapabilitiesEnabled: MessagePayload {
    public static let type = "capabilities.enabled"
    public var enabled: [String]
    public init(enabled: [String]) { self.enabled = enabled }
}

public struct ErrorPayload: MessagePayload {
    public static let type = "error"
    public var code: ErrorCode
    public var message: String?
    public var fatal: Bool
    public init(code: ErrorCode, message: String? = nil, fatal: Bool? = nil) {
        self.code = code; self.message = message; self.fatal = fatal ?? code.isFatal
    }
}

// MARK: - Pairing (§5)

public struct PairRequest: MessagePayload {
    public static let type = "pair.request"
    public var token: B64
    public var deviceId: PBUUID
    public var deviceName: String
    public var manufacturer: String
    public var model: String
    public var platform: String
    public var osVersion: String
    public var sdkInt: Int
    public var appVersion: String

    public func validate() throws {
        guard token.data.count == 32 else { throw ProtocolError(.tokenInvalid, "token must be 32 bytes") }
        guard !deviceName.isEmpty, deviceName.count <= 64 else { throw ProtocolError.invalid("bad deviceName") }
    }
}

public struct PairConfirmCode: MessagePayload {
    public static let type = "pair.confirm_code"
    public var code: String
    public var timeoutSec: Int
    public init(code: String, timeoutSec: Int) { self.code = code; self.timeoutSec = timeoutSec }
    public func validate() throws {
        guard code.count == 6, code.allSatisfy(\.isASCII), code.allSatisfy(\.isNumber) else {
            throw ProtocolError.invalid("code must be 6 digits")
        }
    }
}

public struct PairOk: MessagePayload {
    public static let type = "pair.ok"
    public var macDeviceId: PBUUID
    public var macName: String
    public var port: Int
    public init(macDeviceId: PBUUID, macName: String, port: Int) {
        self.macDeviceId = macDeviceId; self.macName = macName; self.port = port
    }
}

public enum PairDeniedReason: String, LenientStringEnum {
    case userDenied = "user_denied", timeout, tokenInvalid = "token_invalid", tokenExpired = "token_expired"
    case rateLimited = "rate_limited", deviceExists = "device_exists", unknown
    public static let unknownValue = PairDeniedReason.unknown
}

public struct PairDenied: MessagePayload {
    public static let type = "pair.denied"
    public var reason: PairDeniedReason
    public init(reason: PairDeniedReason) { self.reason = reason }
}

public struct PairRevoked: MessagePayload {
    public static let type = "pair.revoked"
    public var reason: String
    public init(reason: String) { self.reason = reason }
}

public struct PairUnpair: MessagePayload { public static let type = "pair.unpair"; public init() {} }

// MARK: - Status / find phone (§6, §7)

public struct StatusDevice: MessagePayload {
    public static let type = "status.device"
    public var deviceName: String
    public var manufacturer: String
    public var model: String
    public var osVersion: String
    public var sdkInt: Int
}

public enum Plugged: String, LenientStringEnum {
    case ac, usb, wireless, dock, none, unknown
    public static let unknownValue = Plugged.unknown
}

public struct StatusBattery: MessagePayload {
    public static let type = "status.battery"
    public var level: Int
    public var charging: Bool
    public var plugged: Plugged
    public func validate() throws {
        guard (0...100).contains(level) else { throw ProtocolError.invalid("battery level out of range") }
    }
}

public struct PhoneRing: MessagePayload {
    public static let type = "phone.ring"
    public static let allowedDurations: Set<Int> = [15, 30, 60, 120]
    public var durationSec: Int
    public init(durationSec: Int) { self.durationSec = durationSec }
    public func validate() throws {
        guard Self.allowedDurations.contains(durationSec) else {
            throw ProtocolError.invalid("durationSec must be one of 15, 30, 60, 120")
        }
    }
}

public struct PhoneRingStop: MessagePayload { public static let type = "phone.ring.stop"; public init() {} }

public enum RingStopReason: String, LenientStringEnum {
    case stoppedOnPhone = "stopped_on_phone", stoppedRemotely = "stopped_remotely", timeout
    case audioBlocked = "audio_blocked", unknown
    public static let unknownValue = RingStopReason.unknown
}

public struct PhoneRingState: MessagePayload {
    public static let type = "phone.ring.state"
    public var ringing: Bool
    public var remainingSec: Int
    public var reason: RingStopReason?
}

// MARK: - Clipboard (§8)

public struct ClipboardSet: MessagePayload {
    public static let type = "clipboard.set"
    public static let maxTextBytes = 1024 * 1024
    public var originDeviceId: PBUUID
    public var hash: String
    public var mime: String
    public var text: String

    public init(originDeviceId: PBUUID, text: String) {
        self.originDeviceId = originDeviceId
        self.mime = "text/plain"
        self.text = text
        self.hash = ClipboardSet.hash(of: text)
    }

    public static func hash(of text: String) -> String {
        Hex.encode(SHA256.hash(data: Data(text.utf8)))
    }

    public func validate() throws {
        guard mime == "text/plain" else { throw ProtocolError.invalid("unsupported mime \(mime)") }
        guard text.utf8.count <= Self.maxTextBytes else { throw ProtocolError.invalid("clipboard text too large") }
        guard hash == Self.hash(of: text) else { throw ProtocolError.invalid("clipboard hash mismatch") }
    }
}

// MARK: - Files (§9)

public struct FileOffer: MessagePayload {
    public static let type = "file.offer"
    public var transferId: PBUUID
    public var name: String
    public var size: Int64
    public var mime: String
    public var sha256: String
    public var chunkSize: Int
    public var modifiedAt: Int64?

    public init(transferId: PBUUID, name: String, size: Int64, mime: String, sha256: String,
                chunkSize: Int = PBConstants.maxChunkPayload, modifiedAt: Int64? = nil) {
        self.transferId = transferId; self.name = name; self.size = size; self.mime = mime
        self.sha256 = sha256; self.chunkSize = chunkSize; self.modifiedAt = modifiedAt
    }

    public func validate() throws {
        guard size >= 0 else { throw ProtocolError.invalid("negative size") }
        guard Hex.isSHA256(sha256) else { throw ProtocolError.invalid("bad sha256") }
        guard (1...PBConstants.maxChunkPayload).contains(chunkSize) else { throw ProtocolError.invalid("bad chunkSize") }
        guard !name.isEmpty, name.utf8.count <= 255, !name.contains("/"), !name.contains("\0"),
              name != ".", name != ".." else {
            throw ProtocolError.invalid("unsafe file name")
        }
    }
}

public struct FileAccept: MessagePayload {
    public static let type = "file.accept"
    public var transferId: PBUUID
    public var offset: Int64
    public init(transferId: PBUUID, offset: Int64) { self.transferId = transferId; self.offset = offset }
    public func validate() throws { guard offset >= 0 else { throw ProtocolError.invalid("negative offset") } }
}

public enum FileRejectReason: String, LenientStringEnum {
    case userDeclined = "user_declined", insufficientSpace = "insufficient_space", tooLarge = "too_large", busy
    case unknown
    public static let unknownValue = FileRejectReason.unknown
}

public struct FileReject: MessagePayload {
    public static let type = "file.reject"
    public var transferId: PBUUID
    public var reason: FileRejectReason
    public init(transferId: PBUUID, reason: FileRejectReason) { self.transferId = transferId; self.reason = reason }
}

public struct FileAck: MessagePayload {
    public static let type = "file.ack"
    public var transferId: PBUUID
    public var offset: Int64
    public init(transferId: PBUUID, offset: Int64) { self.transferId = transferId; self.offset = offset }
}

public struct FileNack: MessagePayload {
    public static let type = "file.nack"
    public var transferId: PBUUID
    public var offset: Int64
    public init(transferId: PBUUID, offset: Int64) { self.transferId = transferId; self.offset = offset }
}

public struct FilePause: MessagePayload {
    public static let type = "file.pause"
    public var transferId: PBUUID
    public init(transferId: PBUUID) { self.transferId = transferId }
}

public struct FileResume: MessagePayload {
    public static let type = "file.resume"
    public var transferId: PBUUID
    public init(transferId: PBUUID) { self.transferId = transferId }
}

public struct FileCancel: MessagePayload {
    public static let type = "file.cancel"
    public var transferId: PBUUID
    public var reason: String
    public init(transferId: PBUUID, reason: String) { self.transferId = transferId; self.reason = reason }
}

public struct FileDone: MessagePayload {
    public static let type = "file.done"
    public var transferId: PBUUID
    public init(transferId: PBUUID) { self.transferId = transferId }
}

public enum FileResultError: String, LenientStringEnum {
    case hashMismatch = "hash_mismatch", sizeMismatch = "size_mismatch", ioError = "io_error", unknown
    public static let unknownValue = FileResultError.unknown
}

public struct FileResult: MessagePayload {
    public static let type = "file.result"
    public var transferId: PBUUID
    public var ok: Bool
    public var savedName: String?
    public var error: FileResultError?
    public init(transferId: PBUUID, ok: Bool, savedName: String? = nil, error: FileResultError? = nil) {
        self.transferId = transferId; self.ok = ok; self.savedName = savedName; self.error = error
    }
}

// MARK: - Notifications (§10)

public struct NotificationItem: Codable, Sendable, Hashable {
    public var key: String
    public var pkg: String
    public var app: String
    public var title: String?
    public var text: String?
    public var subText: String?
    public var postedAt: Int64
    public var redacted: Bool
}

public struct NotifPosted: MessagePayload {
    public static let type = "notif.posted"
    public var item: NotificationItem
    public init(item: NotificationItem) { self.item = item }
    // The item's fields are flattened into the payload.
    public init(from decoder: Decoder) throws { item = try NotificationItem(from: decoder) }
    public func encode(to encoder: Encoder) throws { try item.encode(to: encoder) }
}

public struct NotifRemoved: MessagePayload {
    public static let type = "notif.removed"
    public var key: String
    public init(key: String) { self.key = key }
}

public struct NotifDismiss: MessagePayload {
    public static let type = "notif.dismiss"
    public var key: String
    public init(key: String) { self.key = key }
}

public struct NotifSync: MessagePayload {
    public static let type = "notif.sync"
    public var items: [NotificationItem]
    public init(items: [NotificationItem]) { self.items = items }
}

public struct NotifIcon: MessagePayload {
    public static let type = "notif.icon"
    public static let maxBytes = 16 * 1024
    public var pkg: String
    public var png: B64
    public func validate() throws {
        guard png.data.count <= Self.maxBytes else { throw ProtocolError.invalid("icon too large") }
    }
}

// MARK: - Registry

/// Every known message type → its payload model. Used by routers and by conformance tests.
public enum MessageRegistry {
    public static let all: [any MessagePayload.Type] = [
        Hello.self, Auth.self, Ready.self, Ping.self, Pong.self, CapabilitiesChanged.self, CapabilitiesEnabled.self,
        ErrorPayload.self,
        PairRequest.self, PairConfirmCode.self, PairOk.self, PairDenied.self, PairRevoked.self, PairUnpair.self,
        StatusDevice.self, StatusBattery.self, PhoneRing.self, PhoneRingStop.self, PhoneRingState.self,
        ClipboardSet.self,
        FileOffer.self, FileAccept.self, FileReject.self, FileAck.self, FileNack.self, FilePause.self,
        FileResume.self, FileCancel.self, FileDone.self, FileResult.self,
        NotifPosted.self, NotifRemoved.self, NotifDismiss.self, NotifSync.self, NotifIcon.self,
    ]

    public static let byType: [String: any MessagePayload.Type] =
        Dictionary(uniqueKeysWithValues: all.map { ($0.type, $0) })

    /// Message types allowed on a pairing-mode connection (§2, §5).
    public static let pairingModeTypes: Set<String> = [
        Hello.type, ErrorPayload.type, PairRequest.type, PairConfirmCode.type, PairOk.type, PairDenied.type,
    ]
}
