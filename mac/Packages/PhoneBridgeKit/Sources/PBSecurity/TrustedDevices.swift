import Foundation
import PBProtocol
import Security

/// A paired phone. `fingerprint` is the SHA-256 of its certificate's SPKI (its Android Keystore key).
public struct TrustedDevice: Codable, Sendable, Hashable, Identifiable {
    public var id: PBUUID
    public var name: String
    public var model: String
    public var fingerprintHex: String
    public var pairedAt: Date
    public var lastSeen: Date?

    public init(id: PBUUID, name: String, model: String, fingerprintHex: String, pairedAt: Date = Date(),
                lastSeen: Date? = nil) {
        self.id = id; self.name = name; self.model = model; self.fingerprintHex = fingerprintHex
        self.pairedAt = pairedAt; self.lastSeen = lastSeen
    }
}

/// Where paired devices live. Production: Keychain. Tests: in-memory.
public protocol DeviceStore: AnyObject, Sendable {
    func all() -> [TrustedDevice]
    func device(fingerprintHex: String) -> TrustedDevice?
    func device(id: PBUUID) -> TrustedDevice?
    /// Insert or replace (same id → re-pair after reinstall replaces the old key).
    func save(_ device: TrustedDevice) throws
    func remove(id: PBUUID) throws
}

public extension DeviceStore {
    func device(fingerprintHex: String) -> TrustedDevice? { all().first { $0.fingerprintHex == fingerprintHex } }
    func device(id: PBUUID) -> TrustedDevice? { all().first { $0.id == id } }
}

/// Stores each trusted device as a Keychain generic-password item (service + device id).
public final class KeychainDeviceStore: DeviceStore, @unchecked Sendable {
    private let service: String
    private let lock = NSLock()
    private var cache: [TrustedDevice]?

    public init(service: String = "dev.phonebridge.trusted-device") { self.service = service }

    public func all() -> [TrustedDevice] {
        lock.lock(); defer { lock.unlock() }
        if let cache { return cache }
        // The macOS file-based keychain does not support kSecReturnData together with kSecMatchLimitAll
        // (it silently returns nothing). So: list accounts first, then read each item's data individually.
        let list: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecReturnAttributes as String: true,
            kSecMatchLimit as String: kSecMatchLimitAll,
        ]
        var out: CFTypeRef?
        var devices: [TrustedDevice] = []
        if SecItemCopyMatching(list as CFDictionary, &out) == errSecSuccess, let items = out as? [[String: Any]] {
            for account in items.compactMap({ $0[kSecAttrAccount as String] as? String }) {
                if let data = readData(account: account),
                   let d = try? JSONDecoder().decode(TrustedDevice.self, from: data) {
                    devices.append(d)
                }
            }
        }
        cache = devices.sorted { $0.pairedAt < $1.pairedAt }
        return cache!
    }

    private func readData(account: String) -> Data? {
        let q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var out: CFTypeRef?
        return SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess ? out as? Data : nil
    }

    public func save(_ device: TrustedDevice) throws {
        lock.lock(); defer { lock.unlock(); }
        let data = try JSONEncoder().encode(device)
        let key: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: device.id.description,
        ]
        let update = SecItemUpdate(key as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if update == errSecItemNotFound {
            var add = key
            add[kSecValueData as String] = data
            add[kSecAttrLabel as String] = "PhoneBridge paired device: \(device.name)"
            let s = SecItemAdd(add as CFDictionary, nil)
            guard s == errSecSuccess else { throw MacIdentityStore.Error.keychain("save device", s) }
        } else if update != errSecSuccess {
            throw MacIdentityStore.Error.keychain("update device", update)
        }
        cache = nil
    }

    public func remove(id: PBUUID) throws {
        lock.lock(); defer { lock.unlock() }
        let s = SecItemDelete([
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: id.description,
        ] as CFDictionary)
        guard s == errSecSuccess || s == errSecItemNotFound else {
            throw MacIdentityStore.Error.keychain("remove device", s)
        }
        cache = nil
    }
}

public final class InMemoryDeviceStore: DeviceStore, @unchecked Sendable {
    private let lock = NSLock()
    private var devices: [PBUUID: TrustedDevice] = [:]
    public init(_ initial: [TrustedDevice] = []) { for d in initial { devices[d.id] = d } }
    public func all() -> [TrustedDevice] {
        lock.lock(); defer { lock.unlock() }
        return devices.values.sorted { $0.pairedAt < $1.pairedAt }
    }
    public func save(_ device: TrustedDevice) throws { lock.lock(); devices[device.id] = device; lock.unlock() }
    public func remove(id: PBUUID) throws { lock.lock(); devices[id] = nil; lock.unlock() }
}
