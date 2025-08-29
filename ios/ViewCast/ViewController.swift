import UIKit

class ViewController: UIViewController {
    let p2p = P2PManager()
    let capture = ScreenCapture()
    let rtsp = try! RtspServer()

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        let btn = UIButton(type: .system)
        btn.setTitle("Démarrer diffusion", for: .normal)
        btn.addTarget(self, action: #selector(start), for: .touchUpInside)
        btn.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(btn)
        NSLayoutConstraint.activate([
            btn.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            btn.centerYAnchor.constraint(equalTo: view.centerYAnchor)
        ])
        p2p.start()
        rtsp.start()
    }

    @objc func start() {
        capture.onSpsPps = { [weak self] sps, pps in self?.rtsp.updateSpsPps(sps: sps, pps: pps) }
        capture.onSample = { [weak self] data, _ in self?.rtsp.sendNal(data) }
        capture.start()
    }
}
