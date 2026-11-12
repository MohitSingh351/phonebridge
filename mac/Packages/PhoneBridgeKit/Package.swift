// swift-tools-version: 6.2
import PackageDescription

// PhoneBridgeKit — the Mac side's modules. One target per module (see protocol/PROTOCOL.md and the plan).
// Core modules are prefixed `PB` to avoid clashing with Swift keywords / system frameworks.
// Feature targets (FeatureStatus, FeatureClipboard, …) and PBTransport/PBSecurity/PBPersistence are added
// in later phases.
let package = Package(
    name: "PhoneBridgeKit",
    platforms: [.macOS(.v15)],
    products: [
        .library(name: "PBProtocol", targets: ["PBProtocol"]),
    ],
    targets: [
        .target(name: "PBProtocol"),
        .testTarget(
            name: "PBProtocolTests",
            dependencies: ["PBProtocol"]
        ),
    ]
)
