// swift-tools-version: 6.2
import PackageDescription

// PhoneBridgeKit — the Mac side's modules. One target per module (see protocol/PROTOCOL.md and the plan).
// Core modules are prefixed `PB` to avoid clashing with Swift keywords / system frameworks.
// Built with the Command Line Tools only (no Xcode): `swift build`, `swift test`, scripts/build-mac-app.sh.
let package = Package(
    name: "PhoneBridgeKit",
    platforms: [.macOS(.v15)],
    products: [
        .library(name: "PBProtocol", targets: ["PBProtocol"]),
        .library(name: "PBSecurity", targets: ["PBSecurity"]),
        .library(name: "PBTransport", targets: ["PBTransport"]),
        .library(name: "FeatureClipboard", targets: ["FeatureClipboard"]),
        .library(name: "FeatureFiles", targets: ["FeatureFiles"]),
        .executable(name: "PhoneBridge", targets: ["PhoneBridgeApp"]),
    ],
    dependencies: [
        // Versions chosen 2026-11-12: latest releases at least two weeks old.
        .package(url: "https://github.com/apple/swift-certificates.git", exact: "1.21.1"),
        .package(url: "https://github.com/apple/swift-asn1.git", from: "1.7.0"),
    ],
    targets: [
        .target(name: "PBProtocol"),
        .target(
            name: "PBSecurity",
            dependencies: [
                "PBProtocol",
                .product(name: "X509", package: "swift-certificates"),
                .product(name: "SwiftASN1", package: "swift-asn1"),
            ]
        ),
        .target(name: "PBTransport", dependencies: ["PBProtocol", "PBSecurity"]),
        // Features: each depends only on core modules, never on another feature.
        .target(name: "FeatureClipboard", dependencies: ["PBProtocol", "PBTransport"]),
        .target(name: "FeatureFiles", dependencies: ["PBProtocol", "PBTransport"]),
        .executableTarget(
            name: "PhoneBridgeApp",
            dependencies: ["PBProtocol", "PBSecurity", "PBTransport", "FeatureClipboard", "FeatureFiles"]
        ),
        .testTarget(name: "PBProtocolTests", dependencies: ["PBProtocol"]),
        .testTarget(name: "PBSecurityTests", dependencies: ["PBSecurity"]),
        .testTarget(name: "PBTransportTests", dependencies: ["PBTransport", "PBSecurity"]),
        .testTarget(name: "FeatureClipboardTests", dependencies: ["FeatureClipboard"]),
        .testTarget(name: "FeatureFilesTests", dependencies: ["FeatureFiles"]),
    ]
)
