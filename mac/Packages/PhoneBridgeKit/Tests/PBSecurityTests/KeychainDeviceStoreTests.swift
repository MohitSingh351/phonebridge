import Foundation
import PBProtocol
import Testing
@testable import PBSecurity

/// Runs against the REAL login keychain (a unique throwaway service name, cleaned up afterwards).
/// The in-memory store hid a macOS-keychain quirk that broke reconnects after pairing.
@Suite("KeychainDeviceStore (real keychain)", .serialized)
struct KeychainDeviceStoreTests {
    let service = "dev.phonebridge.test.\(UUID().uuidString)"

    private func device(_ name: String) -> TrustedDevice {
        TrustedDevice(id: .random(), name: name, model: "Test", fingerprintHex: Hex.encode(ProtocolCrypto.randomBytes(32)))
    }

    @Test func savedDevicesAreListedAndFoundByFingerprint() throws {
        let store = KeychainDeviceStore(service: service)
        defer { for d in store.all() { try? store.remove(id: d.id) } }
        let a = device("A"), b = device("B")
        try store.save(a)
        try store.save(b)

        // A fresh store instance must read them back from the keychain (no cache).
        let fresh = KeychainDeviceStore(service: service)
        #expect(Set(fresh.all().map(\.id)) == [a.id, b.id])
        #expect(fresh.device(fingerprintHex: a.fingerprintHex)?.id == a.id)
        #expect(fresh.device(fingerprintHex: b.fingerprintHex)?.name == "B")
    }

    @Test func updateReplacesAndRemoveDeletes() throws {
        let store = KeychainDeviceStore(service: service)
        defer { for d in store.all() { try? store.remove(id: d.id) } }
        var a = device("A")
        try store.save(a)
        a.fingerprintHex = Hex.encode(ProtocolCrypto.randomBytes(32)) // re-pair with a new key
        try store.save(a)
        let fresh = KeychainDeviceStore(service: service)
        #expect(fresh.all().count == 1)
        #expect(fresh.all().first?.fingerprintHex == a.fingerprintHex)

        try store.remove(id: a.id)
        #expect(KeychainDeviceStore(service: service).all().isEmpty)
    }
}
