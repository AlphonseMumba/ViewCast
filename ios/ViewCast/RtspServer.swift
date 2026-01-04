import Foundation
import Network

class RtspServer {
    private let listener: NWListener
    private var connections: [NWConnection] = []
    private var sps: Data?
    private var pps: Data?

    init(port: UInt16 = 8554) throws {
        listener = try NWListener(using: .tcp, on: NWEndpoint.Port(rawValue: port)!)
        listener.newConnectionHandler = { [weak self] conn in
            self?.setup(conn)
        }
    }

    func start() { listener.start(queue: .global()) }
    func stop() { listener.cancel(); connections.forEach { $0.cancel() } }

    func updateSpsPps(sps: Data, pps: Data) { self.sps = sps; self.pps = pps }

    private func setup(_ conn: NWConnection) {
        conn.start(queue: .global())
        connections.append(conn)
        receiveRequest(on: conn)
    }

    private func receiveRequest(on conn: NWConnection) {
        conn.receive(minimumIncompleteLength: 1, maximumLength: 8192) { [weak self] data, _, isComplete, err in
            guard let self = self, let d = data, !d.isEmpty else { return }
            let req = String(data: d, encoding: .utf8) ?? ""
            let cseq = req.components(separatedBy: "\r\n").first(where: { $0.hasPrefix("CSeq:") })?.split(separator: ":").last?.trimmingCharacters(in: .whitespaces) ?? "1"
            if req.starts(with: "DESCRIBE") {
                let spsB64 = (self.sps ?? Data()).base64EncodedString()
                let ppsB64 = (self.pps ?? Data()).base64EncodedString()
                let sdp = """
                v=0
                o=- 0 0 IN IP4 127.0.0.1
                s=iOSScreen
                t=0 0
                m=video 0 RTP/AVP 96
                a=rtpmap:96 H264/90000
                a=fmtp:96 packetization-mode=1;sprop-parameter-sets=\(spsB64),\(ppsB64)
                a=control:trackID=1

                """
                let resp = "RTSP/1.0 200 OK\r\nCSeq: \(cseq)\r\nContent-Type: application/sdp\r\nContent-Length: \(sdp.utf8.count)\r\n\r\n\(sdp)"
                conn.send(content: resp.data(using: .utf8), completion: .contentProcessed({ _ in }))
            } else if req.starts(with: "OPTIONS") {
                let resp = "RTSP/1.0 200 OK\r\nCSeq: \(cseq)\r\nPublic: OPTIONS, DESCRIBE, SETUP, PLAY\r\n\r\n"
                conn.send(content: resp.data(using: .utf8), completion: .contentProcessed({ _ in }))
            } else if req.starts(with: "SETUP") {
                let resp = "RTSP/1.0 200 OK\r\nCSeq: \(cseq)\r\nTransport: RTP/AVP/TCP;unicast;interleaved=0-1\r\nSession: 1\r\n\r\n"
                conn.send(content: resp.data(using: .utf8), completion: .contentProcessed({ _ in }))
            } else if req.starts(with: "PLAY") {
                let resp = "RTSP/1.0 200 OK\r\nCSeq: \(cseq)\r\nRTP-Info: url=rtsp://localhost/trackID=1;seq=0\r\n\r\n"
                conn.send(content: resp.data(using: .utf8), completion: .contentProcessed({ _ in }))
            } else if req.starts(with: "PAUSE") {
                let resp = "RTSP/1.0 200 OK\r\nCSeq: \(cseq)\r\n\r\n"
                conn.send(content: resp.data(using: .utf8), completion: .contentProcessed({ _ in }))
            } else if req.starts(with: "TEARDOWN") {
                let resp = "RTSP/1.0 200 OK\r\nCSeq: \(cseq)\r\n\r\n"
                conn.send(content: resp.data(using: .utf8), completion: .contentProcessed({ _ in }))
                // Close connection
                conn.cancel()
            }
            if !isComplete { self.receiveRequest(on: conn) }
        }
    }

    func sendNal(_ nal: Data) {
        let header: [UInt8] = [UInt8(ascii: "$"), 0, UInt8((nal.count >> 8) & 0xFF), UInt8(nal.count & 0xFF)]
        let pkt = Data(header) + nal
        for c in connections {
            c.send(content: pkt, completion: .contentProcessed({ _ in }))
        }
    }
}
