import { useState, useEffect, useCallback } from "react";
import { invoke } from "@tauri-apps/api/core";
import { Cast, Wifi, ScanLine, Monitor, Play, Square } from "lucide-react";
import "./styles.css";

type ViewMode = "connect" | "scan" | "player";

export default function App() {
  const [mode, setMode] = useState<ViewMode>("connect");
  const [ip, setIp] = useState("");
  const [url, setUrl] = useState("");
  const [status, setStatus] = useState("Prêt");
  const [isStreaming, setIsStreaming] = useState(false);

  useEffect(() => {
    invoke<string>("get_local_ip").then(setIp).catch(console.error);
  }, []);

  const handleConnect = useCallback(async () => {
    if (!url) return;
    setStatus("Connexion en cours...");
    try {
      await invoke("start_stream", { url });
      setIsStreaming(true);
      setMode("player");
      setStatus("Diffusion active");
    } catch (e) {
      setStatus(`Erreur: ${e}`);
    }
  }, [url]);

  const handleStop = useCallback(async () => {
    await invoke("stop_stream");
    setIsStreaming(false);
    setStatus("Diffusion arrêtée");
    setMode("connect");
  }, []);

  const handleQrScan = useCallback((result: string) => {
    if (result.startsWith("rtsp://")) {
      setUrl(result);
      setMode("connect");
    }
  }, []);

  return (
    <div className="app">
      <header className="header">
        <div className="logo">
          <Cast size={28} />
          <h1>ViewCast Viewer</h1>
        </div>
        <div className="status-badge" data-status={isStreaming ? "active" : "idle"}>
          <span className="status-dot" />
          {status}
        </div>
      </header>

      <nav className="nav">
        <button
          className={mode === "connect" ? "active" : ""}
          onClick={() => setMode("connect")}
        >
          <Wifi size={18} />
          Connexion
        </button>
        <button
          className={mode === "scan" ? "active" : ""}
          onClick={() => setMode("scan")}
        >
          <ScanLine size={18} />
          Scan QR
        </button>
        <button
          className={mode === "player" ? "active" : ""}
          onClick={() => setMode("player")}
          disabled={!isStreaming}
        >
          <Monitor size={18} />
          Visionneuse
        </button>
      </nav>

      <main className="main">
        {mode === "connect" && (
          <div className="panel connect-panel">
            <div className="card">
              <h2>Se connecter à un appareil</h2>
              <p className="subtitle">Entrez l'URL RTSP ou scannez le QR code</p>

              <div className="input-group">
                <label>Adresse IP du téléphone</label>
                <input
                  type="text"
                  placeholder="192.168.49.1"
                  value={ip}
                  onChange={(e) => setIp(e.target.value)}
                />
              </div>

              <div className="input-group">
                <label>URL RTSP</label>
                <input
                  type="text"
                  value={url}
                  onChange={(e) => setUrl(e.target.value)}
                  placeholder="rtsp://192.168.49.1:8554/stream"
                />
              </div>

              <div className="actions">
                <button className="btn-secondary" onClick={() => setMode("scan")}>
                  <ScanLine size={16} />
                  Scanner QR
                </button>
                <button
                  className="btn-primary"
                  onClick={handleConnect}
                  disabled={!url || isStreaming}
                >
                  <Play size={16} />
                  {isStreaming ? "Connecté" : "Connecter"}
                </button>
              </div>
            </div>

            <div className="card tips">
              <h3>💡 Astuce rapide</h3>
              <p>
                Assurez-vous que votre téléphone et cet ordinateur sont sur le même réseau Wi-Fi.
                L'IP affichée sur le téléphone est généralement <code>192.168.49.1</code> en Wi-Fi Direct.
              </p>
            </div>
          </div>
        )}

        {mode === "scan" && (
          <div className="panel scan-panel">
            <div className="card">
              <h2>Scannez le QR code</h2>
              <p className="subtitle">Placez le QR code affiché sur votre téléphone devant la caméra</p>
              <QRScanner onScan={handleQrScan} />
              <button className="btn-text" onClick={() => setMode("connect")}>
                Retour à la saisie manuelle
              </button>
            </div>
          </div>
        )}

        {mode === "player" && (
          <div className="panel player-panel">
            <div className="video-container">
              <div className="video-placeholder">
                <Cast size={64} opacity={0.3} />
                <p>Flux RTSP en cours</p>
                <code>{url}</code>
              </div>
            </div>
            <div className="player-controls">
              <button className="btn-danger" onClick={handleStop}>
                <Square size={16} />
                Arrêter la diffusion
              </button>
            </div>
          </div>
        )}
      </main>

      <footer className="footer">
        <span>ViewCast Desktop v1.0</span>
        <span>IP locale: {ip || "..."}</span>
      </footer>
    </div>
  );
}

function QRScanner({ onScan }: { onScan: (result: string) => void }) {
  return (
    <div className="qr-scanner">
      <div className="scanner-frame">
        <ScanLine size={48} />
        <p>Caméra active...</p>
      </div>
      <input
        type="text"
        placeholder="Ou collez l'URL ici"
        className="qr-fallback"
        onChange={(e) => e.target.value.startsWith("rtsp://") && onScan(e.target.value)}
      />
    </div>
  );
}
