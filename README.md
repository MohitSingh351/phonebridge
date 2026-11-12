# PhoneBridge

Local-first link between an Android phone and a Mac: clipboard sync, file transfer, notification mirroring,
phone status and find-my-phone. The devices talk directly over Wi-Fi using mutual TLS. There is no cloud backend.

| Part | Tech | Path |
|---|---|---|
| Protocol spec (**single source of truth**) | Markdown + generated fixtures | [`protocol/PROTOCOL.md`](protocol/PROTOCOL.md) |
| Android app (client) | Kotlin, Compose · minSdk 29, targetSdk 36, compileSdk 37 | `android/` |
| Mac app (server) | Swift, SwiftUI menu-bar app · macOS 15+ | `mac/` |

## Status

| Phase | State |
|---|---|
| 0 Foundations: spec, fixtures, protocol codecs on both platforms | ✅ |
| 1 Secure transport and pairing | ✅ |
| 2 Background reliability | ✅ |
| 3 Status/find phone | ⏸ skipped for now |
| 4 Clipboard | ✅ |
| 5 Files · 6 Notifications · 7 Polish · 8 Hardening | – |

## Changing the protocol

1. Edit `protocol/PROTOCOL.md`. Example blocks tagged ```` ```json fixture=… ```` become test fixtures.
2. Run `python3 protocol/tools/extract_fixtures.py` to regenerate `protocol/fixtures/`.
3. Update `mac/Packages/PhoneBridgeKit/Sources/PBProtocol` and `android/core/protocol` until both test suites pass.

## Running the checks

```sh
scripts/check.sh          # fixture sync + mac tests + android tests
```

Or run each part separately:

```sh
cd mac/Packages/PhoneBridgeKit && swift test
cd android && ./gradlew test
```

## Toolchain

- **JDK 17:** `brew install openjdk@17`. Set `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`.
- **Android SDK:** installed at `~/Library/Android/sdk` (shared with Android Studio).
- **Xcode: not required.** The Mac app builds with the Command Line Tools (`xcode-select --install`). Edit it in VS Code with the official Swift extension.
