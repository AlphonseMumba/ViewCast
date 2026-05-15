import SwiftUI
import CoreImage.CIFilterBuiltins

struct ContentView: View {
    @StateObject private var viewModel = CastViewModel()

    var body: some View {
        NavigationView {
            ZStack {
                Color(.systemBackground).ignoresSafeArea()

                ScrollView {
                    VStack(spacing: 24) {
                        // Header
                        headerCard

                        // Status
                        statusCard

                        // QR Code
                        if viewModel.isReady || viewModel.isCasting {
                            qrCodeCard
                        }

                        // Controls
                        controlsSection

                        Spacer(minLength: 40)
                    }
                    .padding(.horizontal, 20)
                    .padding(.top, 20)
                }
            }
            .navigationTitle("ViewCast")
            .navigationBarTitleDisplayMode(.large)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button(action: { viewModel.showSettings.toggle() }) {
                        Image(systemName: "gear")
                            .font(.title3)
                    }
                }
            }
            .sheet(isPresented: $viewModel.showSettings) {
                SettingsSheet(viewModel: viewModel)
            }
            .alert("Erreur", isPresented: $viewModel.showError) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(viewModel.errorMessage)
            }
        }
        .onAppear {
            viewModel.p2p.start()
            viewModel.rtsp.start()
        }
    }

    private var headerCard: some View {
        VStack(spacing: 12) {
            Image(systemName: "tv.and.mediabox")
                .font(.system(size: 48))
                .foregroundStyle(.indigo)

            Text("ViewCast")
                .font(.system(size: 28, weight: .bold, design: .rounded))

            Text("Diffusez votre écran vers un ordinateur")
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 32)
        .background(
            RoundedRectangle(cornerRadius: 20)
                .fill(Color.indigo.opacity(0.1))
        )
    }

    private var statusCard: some View {
        HStack(spacing: 16) {
            Image(systemName: statusIcon)
                .font(.title2)
                .foregroundStyle(statusColor)

            VStack(alignment: .leading, spacing: 4) {
                Text(viewModel.statusTitle)
                    .font(.headline)
                Text(viewModel.statusSubtitle)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            Spacer()
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: 16)
                .fill(statusColor.opacity(0.1))
        )
        .animation(.easeInOut, value: viewModel.statusTitle)
    }

    private var qrCodeCard: some View {
        VStack(spacing: 12) {
            Text("Scannez pour connecter")
                .font(.headline)

            if let qrImage = generateQRCode(from: viewModel.rtspUrl) {
                Image(uiImage: qrImage)
                    .resizable()
                    .interpolation(.none)
                    .scaledToFit()
                    .frame(width: 180, height: 180)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
            }

            Text(viewModel.rtspUrl)
                .font(.caption)
                .foregroundStyle(.indigo)
                .textSelection(.enabled)
        }
        .padding()
        .frame(maxWidth: .infinity)
        .background(
            RoundedRectangle(cornerRadius: 16)
                .fill(Color(.secondarySystemBackground))
        )
    }

    private var controlsSection: some View {
        VStack(spacing: 12) {
            if !viewModel.isCasting {
                Button(action: { viewModel.discover() }) {
                    HStack {
                        Image(systemName: "wifi")
                        Text("Découvrir appareils")
                    }
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(.indigo)
                .disabled(viewModel.isDiscovering)

                Button(action: { viewModel.startCasting() }) {
                    HStack {
                        Image(systemName: "record.circle")
                        Text("Démarrer la diffusion")
                    }
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(.green)
            } else {
                Button(action: { viewModel.stopCasting() }) {
                    HStack {
                        Image(systemName: "stop.circle.fill")
                        Text("Arrêter la diffusion")
                    }
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(.red)
            }
        }
    }

    private var statusIcon: String {
        switch viewModel.state {
        case .idle: return "wifi.slash"
        case .discovering: return "magnifyingglass"
        case .connected: return "wifi"
        case .casting: return "video.fill"
        case .error: return "exclamationmark.triangle"
        }
    }

    private var statusColor: Color {
        switch viewModel.state {
        case .idle: return .gray
        case .discovering: return .orange
        case .connected: return .blue
        case .casting: return .green
        case .error: return .red
        }
    }

    private func generateQRCode(from string: String) -> UIImage? {
        let context = CIContext()
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(string.utf8)

        if let outputImage = filter.outputImage {
            let transform = CGAffineTransform(scaleX: 10, y: 10)
            let scaledImage = outputImage.transformed(by: transform)

            if let cgImage = context.createCGImage(scaledImage, from: scaledImage.extent) {
                return UIImage(cgImage: cgImage)
            }
        }
        return nil
    }
}

// MARK: - Settings Sheet
struct SettingsSheet: View {
    @ObservedObject var viewModel: CastViewModel
    @Environment(\.dismiss) var dismiss

    var body: some View {
        NavigationView {
            Form {
                Section("Résolution") {
                    Picker("Résolution", selection: $viewModel.resolution) {
                        Text("720x480").tag("720x480")
                        Text("1280x720").tag("1280x720")
                        Text("1920x1080").tag("1920x1080")
                    }
                    .pickerStyle(.segmented)
                }

                Section("Qualité") {
                    VStack(alignment: .leading) {
                        Text("Bitrate: \(Int(viewModel.bitrate)) Mbps")
                        Slider(value: $viewModel.bitrate, in: 2...10, step: 1)
                    }

                    VStack(alignment: .leading) {
                        Text("FPS: \(Int(viewModel.fps))")
                        Slider(value: $viewModel.fps, in: 15...60, step: 5)
                    }
                }
            }
            .navigationTitle("Paramètres")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Terminé") { dismiss() }
                }
            }
        }
    }
}
