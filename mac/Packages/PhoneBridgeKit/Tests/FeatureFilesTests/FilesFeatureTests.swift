import Foundation
import Testing
@testable import FeatureFiles

/// Regression: the engine's change callback runs on the engine's background queue. It must not be
/// main-actor isolated, or Swift's runtime isolation check kills the app (crash seen on 2026-11-12).
@Suite("FilesFeature threading")
struct FilesFeatureThreadingTests {
    @Test func changeHandlerIsSafeOffTheMainThread() async {
        let handler = FilesFeature.changeHandler(FilesFeature.WeakBox())
        let rec = TransferRecord(id: "x", direction: .IN, name: "a", size: 1, mime: "x", sha256: "", state: .ACTIVE, createdAt: 1)
        await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in
            DispatchQueue(label: "engine").async {
                handler([rec, rec]) // sorting inside used to trip the isolation check
                c.resume()
            }
        }
    }
}
