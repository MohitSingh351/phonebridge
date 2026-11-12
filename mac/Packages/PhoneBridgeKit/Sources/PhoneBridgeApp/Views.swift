import AppKit
import CoreImage.CIFilterBuiltins
import PBSecurity
import PBTransport
import FeatureClipboard
import FeatureFiles
import SwiftUI

// NOTE: no @State / @Entry / @Animatable anywhere in this target. In the macOS 27 SDK these are compiler
// macros whose plugin ships only with Xcode, and we build with the Command Line Tools. Keep view state in
// the @Observable AppModel instead (an App struct is created once, so a stored `let` is safe here).
@main
struct PhoneBridgeApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    private let model: AppModel

    init() {
        let m = AppModel()
        model = m
        AppDelegate.onTerminate = { m.shutdown() }
    }

    var body: some Scene {
        MenuBarExtra {
            MenuContent(model: model)
        } label: {
            Image(systemName: model.connected.isEmpty ? "iphone.gen3" : "iphone.gen3.radiowaves.left.and.right")
        }
        .menuBarExtraStyle(.window)

        Window("Pair a Phone", id: "pairing") {
            PairingView(model: model)
        }
        .windowResizability(.contentSize)
        .defaultPosition(.center)
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    /// Set by the App so termination can reach the model.
    @MainActor static var onTerminate: (() -> Void)?

    func applicationDidFinishLaunching(_ n: Notification) {
        // Menu-bar only (also set via LSUIElement in the bundled app).
        NSApp.setActivationPolicy(.accessory)
    }

    func applicationWillTerminate(_ n: Notification) {
        MainActor.assumeIsolated { Self.onTerminate?() }
    }
}

// MARK: - Menu bar popover

struct MenuContent: View {
    @Bindable var model: AppModel
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("PhoneBridge").font(.headline)
                Spacer()
                StatusPill(model: model)
            }

            if let err = model.fatalError {
                Label(err, systemImage: "exclamationmark.triangle.fill")
                    .foregroundStyle(.orange).font(.callout).fixedSize(horizontal: false, vertical: true)
            }

            Divider()

            if model.devices.isEmpty {
                Text("No phones paired yet.").foregroundStyle(.secondary)
            } else {
                Text("Paired phones").font(.subheadline).foregroundStyle(.secondary)
                ForEach(model.devices) { d in
                    DeviceRow(device: d, connected: model.isConnected(d), asleep: model.isProbablyAsleep(d)) { model.remove(d) }
                }
            }

            if let files = model.files, !model.devices.isEmpty {
                Divider()
                FilesSection(files: files)
            }

            if let clip = model.clipboard, !model.devices.isEmpty {
                Divider()
                ClipboardSection(clipboard: clip)
            }

            Divider()

            Button {
                model.beginPairing()
                openWindow(id: "pairing")
                NSApp.activate()
            } label: {
                Label("Pair a New Phone…", systemImage: "qrcode")
            }
            .disabled({ if case .listening = model.status { false } else { true } }())

            Toggle("Open at Login", isOn: Binding(get: { model.launchAtLogin }, set: { model.launchAtLogin = $0 }))
                .toggleStyle(.checkbox)
            if model.loginItemNeedsApproval {
                Button("Approve in System Settings…") { model.openLoginItemsSettings() }
                    .font(.caption)
            }

            Button("Quit PhoneBridge") { NSApp.terminate(nil) }
                .keyboardShortcut("q")
        }
        .padding(14)
        .frame(width: 320)
        // Drop files anywhere on the menu to send them to the phone.
        .dropDestination(for: URL.self) { urls, _ in
            guard let files = model.files, !urls.isEmpty else { return false }
            files.send(urls: urls)
            return true
        }
    }
}

struct StatusPill: View {
    let model: AppModel
    var body: some View {
        let (text, color): (String, Color) = switch model.status {
        case .starting: ("Starting…", .secondary)
        case .failed: ("Offline", .red)
        case .listening: model.connected.isEmpty ? ("Waiting for phone", .secondary) : ("Connected", .green)
        }
        HStack(spacing: 5) {
            Circle().fill(color).frame(width: 7, height: 7)
            Text(text).font(.caption).foregroundStyle(.secondary)
        }
        .help({ if case .failed(let m) = model.status { m } else { "" } }())
    }
}

struct DeviceRow: View {
    let device: TrustedDevice
    let connected: Bool
    let asleep: Bool
    let onRemove: () -> Void

    var body: some View {
        HStack {
            Image(systemName: "candybarphone")
            VStack(alignment: .leading, spacing: 1) {
                Text(device.name)
                Text(connected ? "Connected" : asleep ? "Asleep or away · reconnects automatically" : lastSeenText)
                    .font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            Button(role: .destructive) { confirmRemove() } label: { Image(systemName: "xmark.circle") }
                .buttonStyle(.borderless)
                .help("Remove this phone")
        }
    }

    private func confirmRemove() {
        let alert = NSAlert()
        alert.messageText = "Remove \(device.name)?"
        alert.informativeText = "It will be disconnected and must be paired again to reconnect."
        alert.addButton(withTitle: "Remove").hasDestructiveAction = true
        alert.addButton(withTitle: "Cancel")
        NSApp.activate()
        if alert.runModal() == .alertFirstButtonReturn { onRemove() }
    }

    private var lastSeenText: String {
        guard let d = device.lastSeen else { return "Never connected" }
        return "Last seen " + d.formatted(.relative(presentation: .named))
    }
}

// MARK: - Pairing window

struct PairingView: View {
    @Bindable var model: AppModel
    @Environment(\.dismissWindow) private var dismissWindow

    var body: some View {
        VStack(spacing: 18) {
            if let w = model.pairingWindow {
                if let outcome = w.outcome {
                    outcomeView(outcome)
                } else if let req = w.request {
                    approvalView(req)
                } else {
                    qrView(w)
                }
            } else {
                Text("Choose “Pair a New Phone…” from the menu bar.").foregroundStyle(.secondary)
            }
        }
        .padding(28)
        .frame(width: 380)
        .onDisappear { if model.pairingWindow?.outcome == nil { model.cancelPairing() } }
    }

    private func qrView(_ w: AppModel.PairingWindow) -> some View {
        VStack(spacing: 14) {
            Text("Scan with PhoneBridge on your phone").font(.title3.weight(.semibold))
            QRCodeImage(payload: w.qrPayload)
                .frame(width: 240, height: 240)
                .padding(10)
                .background(.white, in: RoundedRectangle(cornerRadius: 12))
            TimelineView(.periodic(from: .now, by: 1)) { ctx in
                let left = max(0, Int(w.expiresAt.timeIntervalSince(ctx.date)))
                if left > 0 {
                    Text("Code expires in \(left)s · works once").font(.callout).foregroundStyle(.secondary)
                } else {
                    Button("Code expired — make a new one") { model.beginPairing() }
                }
            }
            Text("Phone and Mac must be on the same Wi-Fi.").font(.caption).foregroundStyle(.tertiary)
        }
    }

    private func approvalView(_ req: PairingRequestInfo) -> some View {
        VStack(spacing: 14) {
            Image(systemName: "candybarphone").font(.system(size: 40))
            Text("Allow “\(req.deviceName)” to connect?").font(.title3.weight(.semibold))
                .multilineTextAlignment(.center)
            Text("Check that your phone shows the same code:").foregroundStyle(.secondary)
            Text(req.code.prefix(3) + " " + req.code.suffix(3))
                .font(.system(size: 34, weight: .semibold, design: .monospaced))
                .accessibilityLabel("Code \(req.code.map(String.init).joined(separator: " "))")
            HStack {
                Button("Deny", role: .cancel) { model.approve(false) }
                    .keyboardShortcut(.cancelAction)
                Button("Allow") { model.approve(true) }
                    .keyboardShortcut(.defaultAction)
            }
            .controlSize(.large)
        }
    }

    private func outcomeView(_ o: AppModel.PairingWindow.Outcome) -> some View {
        VStack(spacing: 14) {
            switch o {
            case .paired(let name):
                Image(systemName: "checkmark.circle.fill").font(.system(size: 44)).foregroundStyle(.green)
                Text("\(name) is paired").font(.title3.weight(.semibold))
            case .failed(let reason):
                Image(systemName: "xmark.octagon.fill").font(.system(size: 44)).foregroundStyle(.red)
                Text("Pairing didn’t complete").font(.title3.weight(.semibold))
                Text(reason.replacingOccurrences(of: "_", with: " ")).foregroundStyle(.secondary)
            }
            Button("Done") { model.pairingWindow = nil; dismissWindow(id: "pairing") }
                .keyboardShortcut(.defaultAction)
        }
    }
}

struct QRCodeImage: View {
    let payload: String

    var body: some View {
        if let img = Self.render(payload) {
            Image(nsImage: img).interpolation(.none).resizable().scaledToFit()
                .accessibilityLabel("Pairing QR code")
        } else {
            Color.gray.opacity(0.2)
        }
    }

    static func render(_ s: String) -> NSImage? {
        let f = CIFilter.qrCodeGenerator()
        f.message = Data(s.utf8)
        f.correctionLevel = "M"
        guard let out = f.outputImage?.transformed(by: CGAffineTransform(scaleX: 10, y: 10)) else { return nil }
        let rep = NSCIImageRep(ciImage: out)
        let img = NSImage(size: rep.size)
        img.addRepresentation(rep)
        return img
    }
}

// MARK: - Clipboard

struct ClipboardSection: View {
    @Bindable var clipboard: ClipboardFeature

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Toggle("Send Mac clipboard to phone", isOn: $clipboard.autoSend)
                .toggleStyle(.checkbox)
            if clipboard.accessDenied {
                Label("Clipboard access is set to Deny. Allow PhoneBridge in System Settings → Privacy & Security → Paste from Other Apps.",
                      systemImage: "hand.raised.fill")
                    .font(.caption).foregroundStyle(.orange).fixedSize(horizontal: false, vertical: true)
            } else if clipboard.autoSend {
                Text("If macOS asks to allow pasting, choose “Always Allow” for automatic sync. Password-manager items are never sent.")
                    .font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            }
            if let e = clipboard.lastEvent {
                Text(e).font(.caption).foregroundStyle(.secondary)
            }
        }
    }
}

// MARK: - Files

struct FilesSection: View {
    let files: FilesFeature

    var body: some View {
        let active = files.transfers.filter { !$0.state.isFinal }
        let recent = files.transfers.filter(\.state.isFinal).prefix(5)
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("Files").font(.subheadline).foregroundStyle(.secondary)
                Spacer()
                Button {
                    files.pickAndSend()
                } label: {
                    Label("Send File…", systemImage: "arrow.up.doc")
                }
                .controlSize(.small)
            }
            if files.transfers.isEmpty {
                Text("Drop files here to send them to your phone. Files from your phone go to Downloads/PhoneBridge.")
                    .font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            }
            ForEach(active) { TransferRow(r: $0, files: files) }
            if !recent.isEmpty {
                HStack {
                    Text("Recent").font(.caption).foregroundStyle(.tertiary)
                    Spacer()
                    Button("Clear") { files.clearHistory() }.buttonStyle(.borderless).font(.caption)
                }
                ForEach(Array(recent)) { TransferRow(r: $0, files: files) }
            }
        }
    }
}

struct TransferRow: View {
    let r: TransferRecord
    let files: FilesFeature

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack(spacing: 6) {
                Image(systemName: r.direction == .OUT ? "arrow.up.circle" : "arrow.down.circle")
                    .foregroundStyle(.secondary)
                    .accessibilityLabel(r.direction == .OUT ? "Sent" : "Received")
                Text(r.savedName ?? r.name).lineLimit(1).truncationMode(.middle)
                Spacer()
                controls
            }
            if !r.state.isFinal && r.size > 0 {
                ProgressView(value: Double(r.transferred), total: Double(r.size))
            }
            Text(status).font(.caption).foregroundStyle(r.state == .FAILED ? .red : .secondary)
        }
        .contentShape(Rectangle())
        .onTapGesture(count: 2) { files.reveal(r) }
    }

    @ViewBuilder private var controls: some View {
        HStack(spacing: 2) {
            switch r.state {
            case .ACTIVE, .WAITING:
                iconButton("pause.fill", "Pause") { files.pause(r.id) }
            case .PAUSED:
                iconButton("play.fill", "Resume") { files.resume(r.id) }
            case .FAILED where r.direction == .OUT:
                iconButton("arrow.clockwise", "Retry") { files.retry(r.id) }
            case .DONE where r.direction == .IN:
                iconButton("magnifyingglass", "Show in Finder") { files.reveal(r) }
            default:
                EmptyView()
            }
            if !r.state.isFinal { iconButton("xmark", "Cancel") { files.cancel(r.id) } }
        }
    }

    private func iconButton(_ symbol: String, _ help: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) { Image(systemName: symbol) }
            .buttonStyle(.borderless).help(help).accessibilityLabel(help)
    }

    private var status: String {
        switch r.state {
        case .WAITING: return "Waiting for phone · \(formatBytes(r.size))"
        case .ACTIVE:
            var s = "\(formatBytes(r.transferred)) of \(formatBytes(r.size))"
            if r.bytesPerSec > 0 { s += " · \(formatBytes(r.bytesPerSec))/s · \(formatDuration((r.size - r.transferred) / r.bytesPerSec)) left" }
            return s
        case .PAUSED: return "Paused\(r.pausedBy == .REMOTE ? " on phone" : "") · \(formatBytes(r.transferred)) of \(formatBytes(r.size))"
        case .VERIFYING: return "Checking…"
        case .DONE: return r.direction == .OUT ? "Sent · \(formatBytes(r.size))" : "In Downloads/PhoneBridge · \(formatBytes(r.size))"
        case .FAILED:
            switch r.error {
            case "insufficient_space": return "Failed: not enough space on the phone"
            case "hash_mismatch": return "Failed: file changed or corrupted — retry"
            case "transfer_failed": return "Failed: connection too unreliable — retry"
            case "source_unavailable": return "Failed: file no longer accessible"
            default: return "Failed: \(r.error ?? "unknown")"
            }
        case .CANCELLED: return "Cancelled"
        }
    }
}
