import SwiftUI

struct ContentView: View {
    @State private var isCasting = false
    @State private var ipAddress = getIPAddress()
    @State private var resolution = "1280x720"
    @State private var bitrate: Float = 6.0
    @State private var fps: Float = 30.0

    let p2p = P2PManager()
    let capture = ScreenCapture()
    let rtsp = try! RtspServer()

    var body: some View {
        VStack(spacing: 20) {
            Text("ViewCast iOS")
                .font(.largeTitle)
                .padding()

            Text("IP: \(ipAddress)")
                .font(.headline)

            // Configuration
            VStack(alignment: .leading) {
                Text("Configuration")
                    .font(.title2)

                Picker("Résolution", selection: $resolution) {
                    Text("1280x720").tag("1280x720")
                    Text("1920x1080").tag("1920x1080")
                    Text("720x480").tag("720x480")
                }
                .pickerStyle(SegmentedPickerStyle())

                HStack {
                    Text("Bitrate: \(Int(bitrate)) Mbps")
                    Slider(value: $bitrate, in: 2...10)
                }

                HStack {
                    Text("FPS: \(Int(fps))")
                    Slider(value: $fps, in: 15...60)
                }
            }
            .padding()

            Button(action: {
                if !isCasting {
                    startCasting()
                } else {
                    stopCasting()
                }
            }) {
                Text(isCasting ? "Arrêter diffusion" : "Démarrer diffusion")
                    .font(.title)
                    .padding()
                    .background(isCasting ? Color.red : Color.blue)
                    .foregroundColor(.white)
                    .cornerRadius(10)
            }

            Spacer()
        }
        .padding()
        .onAppear {
            p2p.start()
            rtsp.start()
        }
    }

    private func startCasting() {
        let width = Int(resolution.split(separator: "x")[0])!
        let height = Int(resolution.split(separator: "x")[1])!
        capture.start(width: Int32(width), height: Int32(height), bitrate: Int(Int(bitrate * 1_000_000)), fps: Int32(fps))
        capture.onSpsPps = { [weak rtsp] sps, pps in rtsp?.updateSpsPps(sps: sps, pps: pps) }
        capture.onSample = { [weak rtsp] data, _ in rtsp?.sendNal(data) }
        isCasting = true
    }

    private func stopCasting() {
        capture.stop()
        isCasting = false
    }
}

func getIPAddress() -> String {
    var address: String?
    var ifaddr: UnsafeMutablePointer<ifaddrs>?
    if getifaddrs(&ifaddr) == 0 {
        var ptr = ifaddr
        while ptr != nil {
            defer { ptr = ptr?.pointee.ifa_next }
            let interface = ptr?.pointee
            let addrFamily = interface?.ifa_addr.pointee.sa_family
            if addrFamily == UInt8(AF_INET) {
                if let name = String(cString: (interface?.ifa_name)!), name == "en0" {
                    var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                    getnameinfo(interface?.ifa_addr, socklen_t((interface?.ifa_addr.pointee.sa_len)!), &hostname, socklen_t(hostname.count), nil, socklen_t(0), NI_NUMERICHOST)
                    address = String(cString: hostname)
                }
            }
        }
        freeifaddrs(ifaddr)
    }
    return address ?? "192.168.1.1"
}

@main
struct ViewCastApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
