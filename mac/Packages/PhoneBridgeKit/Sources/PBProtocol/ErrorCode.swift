import Foundation

/// Error codes from PROTOCOL.md §11. `closeCode` is the WebSocket close code used when the error is fatal.
public enum ErrorCode: String, Codable, Sendable, CaseIterable {
    case paired
    case authFailed = "auth_failed"
    case versionMismatch = "version_mismatch"
    case revoked
    case sequenceViolation = "sequence_violation"
    case tokenInvalid = "token_invalid"
    case tokenExpired = "token_expired"
    case rateLimited = "rate_limited"
    case frameTooLarge = "frame_too_large"
    case notAllowedInMode = "not_allowed_in_mode"
    case heartbeatTimeout = "heartbeat_timeout"
    case invalidPayload = "invalid_payload"
    case unsupportedType = "unsupported_type"
    case featureDisabled = "feature_disabled"
    case permissionMissing = "permission_missing"
    case transferFailed = "transfer_failed"
    /// Any code this build doesn't know (forward compatibility).
    case unknown

    public init(from decoder: Decoder) throws {
        let raw = try decoder.singleValueContainer().decode(String.self)
        self = ErrorCode(rawValue: raw) ?? .unknown
    }

    public var closeCode: UInt16? {
        switch self {
        case .paired: 4000
        case .authFailed: 4001
        case .versionMismatch: 4002
        case .revoked: 4003
        case .sequenceViolation: 4004
        case .tokenInvalid: 4005
        case .tokenExpired: 4006
        case .rateLimited: 4007
        case .frameTooLarge: 4008
        case .notAllowedInMode: 4009
        case .heartbeatTimeout: 4010
        default: nil
        }
    }

    /// Fatal errors close the connection (paired is a clean close, not an error).
    public var isFatal: Bool { closeCode != nil && self != .paired }
}

/// A protocol violation detected while decoding or validating a message.
public struct ProtocolError: Error, Equatable, Sendable, CustomStringConvertible {
    public let code: ErrorCode
    public let message: String

    public init(_ code: ErrorCode, _ message: String) {
        self.code = code
        self.message = message
    }

    public var description: String { "\(code.rawValue): \(message)" }

    static func invalid(_ message: String) -> ProtocolError { .init(.invalidPayload, message) }
}
