import Foundation
import Testing
@testable import PBSecurity

final class Clock: @unchecked Sendable {
    var t = Date(timeIntervalSince1970: 1_800_000_000)
    func advance(_ s: TimeInterval) { t = t.addingTimeInterval(s) }
}

@Suite("Pairing tokens (§5.1)")
struct PairingManagerTests {
    let clock = Clock()
    var pm: PairingManager { PairingManager(now: { [clock] in clock.t }) }

    @Test func singleUse() {
        let pm = self.pm
        #expect(!pm.isOpen)
        let ticket = pm.open()
        #expect(pm.isOpen)
        #expect(pm.redeem(ticket.token) == .ok)
        #expect(pm.redeem(ticket.token) == .expired) // replay of a used token
        #expect(!pm.isOpen)
    }

    @Test func expiresAfter120s() {
        let pm = self.pm
        let ticket = pm.open()
        clock.advance(119)
        #expect(pm.isOpen)
        clock.advance(1)
        #expect(!pm.isOpen)
        #expect(pm.redeem(ticket.token) == .expired)
    }

    @Test func newQrInvalidatesOldToken() {
        let pm = self.pm
        let first = pm.open()
        let second = pm.open()
        #expect(pm.redeem(first.token) == .invalid)
        #expect(pm.redeem(second.token) == .ok)
    }

    @Test func fiveWrongAttemptsLockOut() {
        let pm = self.pm
        let ticket = pm.open()
        for _ in 0..<4 { #expect(pm.redeem(Data(repeating: 1, count: 32)) == .invalid) }
        #expect(pm.isOpen)
        #expect(pm.redeem(Data(repeating: 2, count: 32)) == .invalid) // 5th
        #expect(pm.redeem(ticket.token) == .rateLimited)                // even the right token now fails
        #expect(!pm.isOpen)
        clock.advance(60)
        #expect(pm.redeem(ticket.token) == .notOpen)                    // token was invalidated
        #expect(pm.redeem(pm.open().token) == .ok)                      // fresh QR works
    }

    @Test func storesOnlyAHash() {
        // The raw token must not be recoverable from the manager's state.
        let pm = self.pm
        let ticket = pm.open()
        let dump = String(describing: Mirror(reflecting: pm).children.map(\.value))
        #expect(!dump.contains(ticket.token.base64EncodedString()))
    }
}

@Suite("SPKI")
struct SPKITests {
    @Test func matchesCryptoKitDER() throws {
        let key = P256Key()
        let fromPoint = try SPKI.der(x963: key.publicKey.x963Representation)
        #expect(fromPoint == key.publicKey.derRepresentation)
    }

    @Test func rejectsNonP256() {
        #expect(throws: SPKI.Error.self) { try SPKI.der(x963: Data(repeating: 4, count: 33)) }
    }

    @Test func verifiesSignatures() throws {
        let key = P256Key()
        let msg = Data("hello".utf8)
        let sig = try key.signature(for: msg).derRepresentation
        #expect(SPKI.verify(signatureDER: sig, message: msg, spkiDER: key.publicKey.derRepresentation))
        #expect(!SPKI.verify(signatureDER: sig, message: Data("hellO".utf8), spkiDER: key.publicKey.derRepresentation))
        #expect(!SPKI.verify(signatureDER: Data([1, 2, 3]), message: msg, spkiDER: key.publicKey.derRepresentation))
    }
}

import CryptoKit
typealias P256Key = P256.Signing.PrivateKey
