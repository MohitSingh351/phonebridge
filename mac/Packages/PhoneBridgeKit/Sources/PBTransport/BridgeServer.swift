import Foundation
import Network
import PBProtocol
import PBSecurity
import Security

/// The Mac's listener (PROTOCOL.md §2): Bonjour `_phonebridge._tcp`, WebSocket over mutual TLS 1.3.
/// Each connection gets a `SessionHandler`; this class only moves bytes and performs its actions.
public final class BridgeServer: @unchecked Sendable {
    public static let preferredPort: UInt16 = 47321

    public enum State: Equatable, Sendable { case stopped, starting, listening(port: UInt16), failed(String) }

    public typealias ConnectionID = UUID

    /// Called on the main queue.
    public var onEvent: (@MainActor (ConnectionID, SessionEvent) -> Void)?
    public var onStateChange: (@MainActor (State) -> Void)?
    public var onConnectionsChanged: (@MainActor ([ConnectionID: TrustedDevice]) -> Void)?

    private let identity: MacIdentity
    private var info: MacInfo
    private let devices: DeviceStore
    private let pairing: PairingManager
    private let queue = DispatchQueue(label: "dev.phonebridge.server")
    private var listener: NWListener?
    private var usedFallbackPort = false
    private var connections: [ConnectionID: (conn: NWConnection, handler: SessionHandler?)] = [:]
    private var timer: DispatchSourceTimer?
    private let pathMonitor = NWPathMonitor()
    private var lastPathSignature = ""

    public init(identity: MacIdentity, info: MacInfo, devices: DeviceStore, pairing: PairingManager) {
        self.identity = identity
        self.info = info
        self.devices = devices
        self.pairing = pairing
    }

    // MARK: Lifecycle

    public func start() {
        queue.async {
            self.startListener(port: Self.preferredPort)
            // Network changed (Wi-Fi switch, new IP): re-announce so the phone's discovery finds us.
            self.pathMonitor.pathUpdateHandler = { [weak self] path in
                guard let self else { return }
                let sig = path.availableInterfaces.map(\.name).joined(separator: ",") + "|\(path.status)"
                if sig != self.lastPathSignature, !self.lastPathSignature.isEmpty {
                    self.listener?.service = self.makeService()
                }
                self.lastPathSignature = sig
            }
            self.pathMonitor.start(queue: self.queue)
        }
    }

    public func stop() {
        queue.async {
            self.timer?.cancel(); self.timer = nil
            for (_, c) in self.connections { c.conn.cancel() }
            self.connections.removeAll()
            self.listener?.cancel(); self.listener = nil
            self.publish(.stopped)
        }
    }

    /// Quitting: tell every phone we're going away (close 1001) so they reconnect promptly to the next
    /// instance instead of waiting for a heartbeat timeout. Blocks briefly so the frames get sent.
    public func shutdown() {
        let done = DispatchSemaphore(value: 0)
        queue.async {
            for (_, c) in self.connections {
                let meta = NWProtocolWebSocket.Metadata(opcode: .close)
                meta.closeCode = .protocolCode(.goingAway)
                let ctx = NWConnection.ContentContext(identifier: "close", metadata: [meta])
                c.conn.send(content: nil, contentContext: ctx, isComplete: true, completion: .idempotent)
            }
            self.queue.asyncAfter(deadline: .now() + 0.3) {
                for (_, c) in self.connections { c.conn.cancel() }
                self.listener?.cancel()
                done.signal()
            }
        }
        _ = done.wait(timeout: .now() + 1)
    }

    /// Re-announce on Bonjour (after wake / network change).
    public func refreshAdvertisement() {
        queue.async { self.listener?.service = self.makeService() }
    }

    // MARK: Commands from the UI

    public func resolveApproval(_ id: ConnectionID, allow: Bool) {
        queue.async { self.perform(id, self.connections[id]?.handler?.resolveApproval(allow) ?? []) }
    }

    /// Remove a paired phone: deletes its key from the Keychain and kicks any live session.
    public func revoke(deviceId: PBUUID) {
        queue.async {
            try? self.devices.remove(id: deviceId)
            for (id, c) in self.connections where c.handler?.device?.id == deviceId {
                self.perform(id, c.handler?.revoke() ?? [])
            }
        }
    }

    public func send<P: MessagePayload>(_ payload: P, to id: ConnectionID) {
        queue.async { self.perform(id, self.connections[id]?.handler?.outgoing(payload) ?? []) }
    }

    // MARK: Listener

    private func startListener(port: UInt16) {
        publish(.starting)
        do {
            let params = makeParameters()
            let l = try NWListener(using: params, on: NWEndpoint.Port(rawValue: port) ?? .any)
            l.service = makeService()
            l.stateUpdateHandler = { [weak self] s in self?.listenerState(s) }
            l.newConnectionHandler = { [weak self] c in self?.accept(c) }
            l.start(queue: queue)
            listener = l
        } catch {
            fallbackOrFail("\(error)")
        }
    }

    private func listenerState(_ s: NWListener.State) {
        switch s {
        case .ready:
            let port = listener?.port?.rawValue ?? 0
            info.port = port
            startTimer()
            publish(.listening(port: port))
        case .failed(let e):
            listener?.cancel(); listener = nil
            fallbackOrFail("\(e)")
        case .waiting(let e):
            // Usually Local Network permission not granted yet, or no network.
            publish(.failed("waiting: \(e)"))
        default: break
        }
    }

    private func fallbackOrFail(_ message: String) {
        if !usedFallbackPort {
            usedFallbackPort = true
            startListener(port: 0) // any free port; Bonjour + QR carry the actual port
        } else {
            publish(.failed(message))
        }
    }

    private func makeService() -> NWListener.Service {
        var txt = NWTXTRecord()
        txt["v"] = "\(PBConstants.protocolVersion)"
        txt["id"] = info.deviceId.description
        txt["name"] = info.name
        return NWListener.Service(name: info.name, type: PBConstants.bonjourType, domain: nil, txtRecord: txt)
    }

    private func makeParameters() -> NWParameters {
        let tls = NWProtocolTLS.Options()
        let sec = tls.securityProtocolOptions
        sec_protocol_options_set_local_identity(sec, sec_identity_create(identity.identity)!)
        sec_protocol_options_set_min_tls_protocol_version(sec, .TLSv13)
        sec_protocol_options_set_peer_authentication_required(sec, true)
        // Pinning instead of CA trust: accept a client cert only if it belongs to a paired phone, or if a
        // pairing window is open (that connection is then restricted to pair.* messages).
        sec_protocol_options_set_verify_block(sec, { [weak self] _, trust, complete in
            guard let self,
                  let leaf = Self.leafCertificate(sec_trust_copy_ref(trust).takeRetainedValue()),
                  let fp = try? SPKI.fingerprint(certificate: leaf) else { complete(false); return }
            complete(self.devices.device(fingerprintHex: Hex.encode(fp)) != nil || self.pairing.isOpen)
        }, queue)

        let ws = NWProtocolWebSocket.Options()
        ws.autoReplyPing = true
        ws.maximumMessageSize = PBConstants.maxTextFrameBytes + 64
        let params = NWParameters(tls: tls)
        params.defaultProtocolStack.applicationProtocols.insert(ws, at: 0)
        params.includePeerToPeer = false
        return params
    }

    private static func leafCertificate(_ trust: SecTrust) -> SecCertificate? {
        (SecTrustCopyCertificateChain(trust) as? [SecCertificate])?.first
    }

    // MARK: Connections

    private func accept(_ conn: NWConnection) {
        let id = ConnectionID()
        connections[id] = (conn, nil)
        conn.stateUpdateHandler = { [weak self] s in self?.connectionState(id, s) }
        conn.start(queue: queue)
    }

    private func connectionState(_ id: ConnectionID, _ s: NWConnection.State) {
        guard let entry = connections[id] else { return }
        switch s {
        case .ready:
            guard let spki = peerSPKI(entry.conn) else { entry.conn.cancel(); return }
            let handler = SessionHandler(mac: info, peerSPKI: spki, devices: devices, pairing: pairing)
            connections[id] = (entry.conn, handler)
            perform(id, handler.start())
            receive(id)
        case .failed, .cancelled:
            // Remember when an authenticated phone was last here (drives "asleep" vs "disconnected" in the UI).
            if let h = entry.handler, h.isReady, var d = h.device {
                d.lastSeen = Date()
                try? devices.save(d)
            }
            connections[id] = nil
            publishConnections()
        default: break
        }
    }

    private func peerSPKI(_ conn: NWConnection) -> Data? {
        guard let meta = conn.metadata(definition: NWProtocolTLS.definition) as? NWProtocolTLS.Metadata else { return nil }
        var leaf: SecCertificate?
        sec_protocol_metadata_access_peer_certificate_chain(meta.securityProtocolMetadata) { cert in
            if leaf == nil { leaf = sec_certificate_copy_ref(cert).takeRetainedValue() }
        }
        return leaf.flatMap { try? SPKI.der(certificate: $0) }
    }

    private func receive(_ id: ConnectionID) {
        guard let conn = connections[id]?.conn else { return }
        conn.receiveMessage { [weak self] data, context, _, error in
            guard let self else { return }
            if error != nil { conn.cancel(); return }
            let meta = context?.protocolMetadata(definition: NWProtocolWebSocket.definition) as? NWProtocolWebSocket.Metadata
            switch meta?.opcode {
            case .text:
                if let data, let h = self.connections[id]?.handler { self.perform(id, h.receive(data)) }
            case .binary:
                break // file chunks arrive in Phase 5
            case .close:
                conn.cancel(); return
            default: break
            }
            if self.connections[id] != nil { self.receive(id) }
        }
    }

    private func perform(_ id: ConnectionID, _ actions: [SessionAction]) {
        guard let conn = connections[id]?.conn else { return }
        for action in actions {
            switch action {
            case .send(let env):
                guard let data = try? EnvelopeCodec.encode(env) else { continue }
                let meta = NWProtocolWebSocket.Metadata(opcode: .text)
                let ctx = NWConnection.ContentContext(identifier: "text", metadata: [meta])
                conn.send(content: data, contentContext: ctx, isComplete: true, completion: .contentProcessed { _ in })
            case .close(let code, _):
                let meta = NWProtocolWebSocket.Metadata(opcode: .close)
                meta.closeCode = code == 1000 ? .protocolCode(.normalClosure) : .privateCode(code)
                let ctx = NWConnection.ContentContext(identifier: "close", metadata: [meta])
                conn.send(content: nil, contentContext: ctx, isComplete: true, completion: .contentProcessed { _ in
                    // Give the close frame a moment to flush before tearing down.
                    self.queue.asyncAfter(deadline: .now() + 0.5) { conn.cancel() }
                })
            case .event(let e):
                DispatchQueue.main.async { self.onEvent?(id, e) }
                if case .authenticated = e { publishConnections() }
            }
        }
    }

    private func startTimer() {
        guard timer == nil else { return }
        let t = DispatchSource.makeTimerSource(queue: queue)
        t.schedule(deadline: .now() + 1, repeating: 1)
        t.setEventHandler { [weak self] in
            guard let self else { return }
            for (id, c) in self.connections { if let h = c.handler { self.perform(id, h.tick()) } }
        }
        t.resume()
        timer = t
    }

    private func publish(_ s: State) {
        DispatchQueue.main.async { self.onStateChange?(s) }
    }

    private func publishConnections() {
        var live: [ConnectionID: TrustedDevice] = [:]
        for (id, c) in connections { if let h = c.handler, h.isReady, let d = h.device { live[id] = d } }
        DispatchQueue.main.async { self.onConnectionsChanged?(live) }
    }

    /// Current listening port (0 until ready).
    public var port: UInt16 { queue.sync { info.port } }
}

/// The Mac's LAN address for the pairing QR code.
public enum NetworkInfo {
    public static func primaryIPv4() -> String? {
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return nil }
        defer { freeifaddrs(ifaddr) }
        var candidates: [(name: String, ip: String)] = []
        for p in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let ifa = p.pointee
            guard let addr = ifa.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET),
                  (ifa.ifa_flags & UInt32(IFF_UP)) != 0, (ifa.ifa_flags & UInt32(IFF_LOOPBACK)) == 0 else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            getnameinfo(addr, socklen_t(addr.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST)
            let name = String(cString: ifa.ifa_name)
            candidates.append((name, String(decoding: host.prefix { $0 != 0 }.map(UInt8.init), as: UTF8.self)))
        }
        // Prefer Wi-Fi/Ethernet (en*) over VPN/bridge interfaces.
        return (candidates.first { $0.name.hasPrefix("en") } ?? candidates.first)?.ip
    }
}
