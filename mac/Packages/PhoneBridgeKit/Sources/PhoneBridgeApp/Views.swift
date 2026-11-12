import AppKit
import CoreImage.CIFilterBuiltins
import PBSecurity
import PBTransport
import SwiftUI

// NOTE: no @State / @Entry / @Animatable anywhere in this target. In the macOS 27 SDK these are compiler
// macros whose plugin ships only with Xcode, and we build with the Command Line Tools. Keep view state in
// the @Observable AppModel instead (an App struct is created once, so a stored `let` is safe here).
@main
struct PhoneBridgeApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    private let model = AppModel()

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
    func applicationDidFinishLaunching(_ n: Notification) {
        // Menu-bar only (also set via LSUIElement in the bundled app).
        NSApp.setActivationPolicy(.accessory)
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
                    DeviceRow(device: d, connected: model.isConnected(d)) { model.remove(d) }
                }
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

            Button("Quit PhoneBridge") { NSApp.terminate(nil) }
                .keyboardShortcut("q")
        }
        .padding(14)
        .frame(width: 300)
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
    let onRemove: () -> Void

    var body: some View {
        HStack {
            Image(systemName: "candybarphone")
            VStack(alignment: .leading, spacing: 1) {
                Text(device.name)
                Text(connected ? "Connected" : lastSeenText).font(.caption).foregroundStyle(.secondary)
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
