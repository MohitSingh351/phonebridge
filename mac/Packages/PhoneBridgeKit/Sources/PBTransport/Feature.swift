import Foundation
import PBProtocol
import PBSecurity

/// One authenticated phone session as seen by a feature. Sending hops to the server's queue.
public final class FeatureLink: @unchecked Sendable, Hashable {
    public let connection: BridgeServer.ConnectionID
    public let device: TrustedDevice
    weak var server: BridgeServer?

    init(connection: BridgeServer.ConnectionID, device: TrustedDevice, server: BridgeServer) {
        self.connection = connection
        self.device = device
        self.server = server
    }

    public func send<P: MessagePayload>(_ payload: P) {
        server?.send(payload, to: connection)
    }

    /// Binary frame (file chunks, PROTOCOL.md §9.2).
    public func sendBinary(_ frame: Data) {
        server?.sendBinary(frame, to: connection)
    }

    public static func == (a: FeatureLink, b: FeatureLink) -> Bool { a.connection == b.connection }
    public func hash(into h: inout Hasher) { h.combine(connection) }
}

/// A pluggable feature (clipboard, files, …). Features depend only on PBProtocol/PBTransport, never on
/// each other; the app registers them in one list. All callbacks arrive on the main actor.
@MainActor
public protocol Feature: AnyObject {
    var id: String { get }
    var version: Int { get }
    /// Reported to phones in `hello`; only `.available` features get enabled.
    var state: CapabilityState { get }

    func sessionStarted(_ link: FeatureLink)
    func received(_ envelope: Envelope, from link: FeatureLink)
    /// Binary frames (only the files feature uses them).
    func receivedBinary(_ frame: Data, from link: FeatureLink)
    func sessionEnded(_ link: FeatureLink)
}

public extension Feature {
    var version: Int { 1 }
    func receivedBinary(_ frame: Data, from link: FeatureLink) {}
    var capability: Capability { Capability(id: id, v: version, state: state) }
}
