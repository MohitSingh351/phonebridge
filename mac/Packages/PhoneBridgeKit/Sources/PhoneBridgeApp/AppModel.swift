import AppKit
import FeatureClipboard
import Foundation
import Observation
import PBProtocol
import PBSecurity
import PBTransport
import ServiceManagement

/// App-wide state for the menu bar UI. Owns the identity, trust store, pairing manager and server.
@MainActor @Observable
final class AppModel {
    enum Status: Equatable {
        case starting, listening(port: UInt16), failed(String)
    }

    struct PairingWindow: Equatable {
        var qrPayload: String
        var expiresAt: Date
        var request: PairingRequestInfo?
        var connection: BridgeServer.ConnectionID?
        var outcome: Outcome?
        enum Outcome: Equatable { case paired(String), failed(String) }
    }

    var status: Status = .starting
    var devices: [TrustedDevice] = []
    var connected: [BridgeServer.ConnectionID: TrustedDevice] = [:]
    var pairingWindow: PairingWindow?
    var fatalError: String?

    let macName: String
    let macId: PBUUID
    private(set) var identity: MacIdentity?
    private let deviceStore: DeviceStore = KeychainDeviceStore()
    private let pairing = PairingManager()
    private var server: BridgeServer?
    /// Features — register new ones here (each independent of the others).
    private(set) var clipboard: ClipboardFeature?

    var connectedNames: [String] { connected.values.map(\.name).sorted() }

    init() {
        macName = Host.current().localizedName ?? "Mac"
        let defaults = UserDefaults.standard
        if let s = defaults.string(forKey: "macDeviceId"), let id = PBUUID(string: s) {
            macId = id
        } else {
            macId = .random()
            defaults.set(macId.description, forKey: "macDeviceId")
        }
        start()
    }

    private func start() {
        do {
            let identity = try MacIdentityStore.loadOrCreate(commonName: "PhoneBridge \(macId)")
            self.identity = identity
            let info = MacInfo(deviceId: macId, name: macName, appVersion: Self.appVersion,
                               fingerprint: identity.fingerprint, port: BridgeServer.preferredPort,
                               capabilities: []) // features register here from Phase 3 on
            let server = BridgeServer(identity: identity, info: info, devices: deviceStore, pairing: pairing)
            let clipboard = ClipboardFeature(macDeviceId: macId)
            self.clipboard = clipboard
            server.features = [clipboard]
            server.onStateChange = { [weak self] s in self?.serverState(s) }
            server.onEvent = { [weak self] id, e in self?.handle(id, e) }
            server.onConnectionsChanged = { [weak self] live in
                self?.connected = live
                self?.reloadDevices()
            }
            server.start()
            self.server = server
            reloadDevices()
            observeWake()
        } catch {
            fatalError = "Could not create the Mac's security identity: \(error)"
            status = .failed(fatalError!)
        }
    }

    /// Called when the app quits (menu, logout, restart, update).
    func shutdown() { server?.shutdown() }

    // MARK: Pairing

    func beginPairing() {
        guard let identity, case .listening(let port) = status else { return }
        guard let ip = NetworkInfo.primaryIPv4() else {
            pairingWindow = nil
            fatalError = "No Wi-Fi/Ethernet address. Connect the Mac to the same network as the phone."
            return
        }
        let ticket = pairing.open()
        var c = URLComponents()
        c.scheme = "phonebridge"
        c.host = "pair"
        c.queryItems = [
            .init(name: "v", value: "1"),
            .init(name: "h", value: ip),
            .init(name: "p", value: "\(port)"),
            .init(name: "fp", value: identity.fingerprintHex),
            .init(name: "t", value: B64.encode(ticket.token)),
            .init(name: "id", value: macId.description),
            .init(name: "n", value: macName),
        ]
        pairingWindow = PairingWindow(qrPayload: c.string ?? "", expiresAt: ticket.expiresAt)
    }

    func cancelPairing() {
        pairing.close()
        if let id = pairingWindow?.connection, pairingWindow?.request != nil, pairingWindow?.outcome == nil {
            server?.resolveApproval(id, allow: false)
        }
        pairingWindow = nil
    }

    func approve(_ allow: Bool) {
        guard let id = pairingWindow?.connection else { return }
        server?.resolveApproval(id, allow: allow)
    }

    // MARK: Devices

    func remove(_ device: TrustedDevice) {
        server?.revoke(deviceId: device.id)
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { self.reloadDevices() }
    }

    func isConnected(_ device: TrustedDevice) -> Bool { connected.values.contains { $0.id == device.id } }

    /// A phone that dropped off recently is most likely asleep (Doze) or briefly off Wi-Fi; it reconnects
    /// by itself, so don't alarm the user for 15 minutes (plan §Doze).
    static let asleepWindow: TimeInterval = 15 * 60

    func isProbablyAsleep(_ device: TrustedDevice) -> Bool {
        guard !isConnected(device), let seen = device.lastSeen else { return false }
        return Date().timeIntervalSince(seen) < Self.asleepWindow
    }

    // MARK: Launch at login

    var launchAtLogin: Bool {
        get { SMAppService.mainApp.status == .enabled }
        set {
            do {
                if newValue { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
            } catch {
                fatalError = "Couldn't change Open at Login: \(error.localizedDescription)"
            }
            loginItemNeedsApproval = SMAppService.mainApp.status == .requiresApproval
        }
    }
    var loginItemNeedsApproval = SMAppService.mainApp.status == .requiresApproval

    func openLoginItemsSettings() { SMAppService.openSystemSettingsLoginItems() }

    private func reloadDevices() { devices = deviceStore.all() }

    // MARK: Events

    private func serverState(_ s: BridgeServer.State) {
        switch s {
        case .listening(let port): status = .listening(port: port)
        case .failed(let m): status = .failed(m)
        case .starting, .stopped: status = .starting
        }
    }

    private func handle(_ id: BridgeServer.ConnectionID, _ e: SessionEvent) {
        switch e {
        case .pairingRequested(let info):
            pairingWindow?.request = info
            pairingWindow?.connection = id
            NSApp.activate()
        case .paired(let d):
            pairingWindow?.outcome = .paired(d.name)
            reloadDevices()
        case .pairingFailed(let reason):
            pairingWindow?.outcome = .failed(reason)
        case .authenticated, .unpaired:
            reloadDevices()
        case .message:
            break // feature routing arrives with the features (Phase 3+)
        }
    }

    private func observeWake() {
        NSWorkspace.shared.notificationCenter.addObserver(
            forName: NSWorkspace.didWakeNotification, object: nil, queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated { self?.server?.refreshAdvertisement() }
        }
    }

    static var appVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0.1.0"
    }
}
