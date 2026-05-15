import Foundation
import Combine

class CastViewModel: ObservableObject {
    @Published var state: CastState = .idle
    @Published var ipAddress: String = getIPAddress()
    @Published var resolution: String = "1280x720"
    @Published var bitrate: Float = 6.0
    @Published var fps: Float = 30.0
    @Published var showSettings: Bool = false
    @Published var showError: Bool = false
    @Published var errorMessage: String = ""

    let p2p = P2PManager()
    let capture = ScreenCapture()
    let rtsp = try! RtspServer()

    var isReady: Bool { state == .connected || state == .idle }
    var isCasting: Bool { state == .casting }
    var isDiscovering: Bool { state == .discovering }

    var rtspUrl: String { "rtsp://\(ipAddress):8554/stream" }

    var statusTitle: String {
        switch state {
        case .idle: return "Prêt"
        case .discovering: return "Recherche..."
        case .connected: return "Connecté"
        case .casting: return "En diffusion"
        case .error: return "Erreur"
        }
    }

    var statusSubtitle: String {
        switch state {
        case .idle: return "Appuyez pour démarrer"
        case .discovering: return "Scan des appareils..."
        case .connected: return "Wi-Fi actif • \(ipAddress)"
        case .casting: return "RTSP en cours • \(ipAddress)"
        case .error: return errorMessage
        }
    }

    func discover() {
        state = .discovering
        p2p.start()
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) { [weak self] in
            self?.state = .connected
        }
    }

    func startCasting() {
        let width = Int(resolution.split(separator: "x")[0])!
        let height = Int(resolution.split(separator: "x")[1])!
        capture.start(
            width: Int32(width),
            height: Int32(height),
            bitrate: Int(bitrate * 1_000_000),
            fps: Int32(fps)
        )
        capture.onSpsPps = { [weak self] sps, pps in
            self?.rtsp.updateSpsPps(sps: sps, pps: pps)
        }
        capture.onSample = { [weak self] data, _ in
            self?.rtsp.sendNal(data)
        }
        state = .casting
    }

    func stopCasting() {
        capture.stop()
        state = .connected
    }

    enum CastState {
        case idle, discovering, connected, casting, error
    }
}
