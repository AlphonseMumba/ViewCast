"""ViewCast Viewer : affiche en direct le flux RTSP de l'écran d'un téléphone Android.

Usage :
    ViewCastViewer.exe                       (demande l'adresse)
    ViewCastViewer.exe 192.168.43.1          (IP seule, port 8554 par défaut)
    ViewCastViewer.exe 192.168.43.1:8554
    ViewCastViewer.exe rtsp://192.168.43.1:8554/stream

Touches : Échap / Q = quitter, F = plein écran.
"""
import os
import sys
import threading
import traceback

# À définir AVANT l'import de cv2 : le serveur du téléphone ne fait que du RTP sur TCP,
# et ces options réduisent la latence (pas de tampon de lecture).
os.environ.setdefault(
    "OPENCV_FFMPEG_CAPTURE_OPTIONS",
    "rtsp_transport;tcp|fflags;nobuffer|flags;low_delay|max_delay;500000",
)

import cv2  # noqa: E402
import numpy as np  # noqa: E402

DEFAULT_PORT = 8554
WINDOW = "ViewCast"
CONFIG_DIR = os.path.join(os.environ.get("APPDATA") or os.path.expanduser("~"), "ViewCast")
CONFIG_FILE = os.path.join(CONFIG_DIR, "last_address.txt")


def log(msg):
    try:
        print(msg, flush=True)
    except Exception:
        pass


def build_url(text):
    """'192.168.1.5', '192.168.1.5:8554' ou 'rtsp://...' -> URL RTSP complète."""
    text = (text or "").strip()
    if not text:
        raise ValueError("Adresse vide.")
    if text.lower().startswith("rtsp://"):
        return text
    if any(c.isspace() or c == "/" for c in text):
        raise ValueError("Adresse invalide : %r" % text)
    host, sep, port = text.rpartition(":")
    if not sep:
        host, port = text, str(DEFAULT_PORT)
    if not host or not port.isdigit() or not (0 < int(port) < 65536):
        raise ValueError("Adresse invalide : %r (attendu : IP ou IP:port)" % text)
    return "rtsp://%s:%s/stream" % (host, port)


def load_last():
    try:
        with open(CONFIG_FILE, encoding="utf-8") as f:
            return f.read().strip()
    except OSError:
        return ""


def save_last(text):
    try:
        os.makedirs(CONFIG_DIR, exist_ok=True)
        with open(CONFIG_FILE, "w", encoding="utf-8") as f:
            f.write(text.strip())
    except OSError:
        pass


def ask_address():
    import tkinter as tk
    from tkinter import simpledialog

    root = tk.Tk()
    root.withdraw()
    try:
        return simpledialog.askstring(
            "ViewCast Viewer",
            "Adresse IP du téléphone (affichée dans l'app ViewCast) :\nex. 192.168.43.1",
            initialvalue=load_last(),
            parent=root,
        )
    finally:
        root.destroy()


def show_error(message):
    log(message)
    try:
        import tkinter as tk
        from tkinter import messagebox

        root = tk.Tk()
        root.withdraw()
        messagebox.showerror("ViewCast Viewer", message, parent=root)
        root.destroy()
    except Exception:
        pass


class StreamReader(threading.Thread):
    """Lit le flux en continu et ne garde que la dernière image (latence minimale).

    Se reconnecte automatiquement si le flux est coupé ou si le téléphone n'est pas encore prêt.
    """

    def __init__(self, url):
        super().__init__(daemon=True)
        self.url = url
        self.stop_event = threading.Event()
        self._lock = threading.Lock()
        self._frame = None
        self._seq = 0
        self.message = "Connexion a %s ..." % url
        self._failures = 0

    def get(self):
        with self._lock:
            return self._seq, self._frame

    def _open(self):
        params = [cv2.CAP_PROP_OPEN_TIMEOUT_MSEC, 5000, cv2.CAP_PROP_READ_TIMEOUT_MSEC, 5000]
        try:
            return cv2.VideoCapture(self.url, cv2.CAP_FFMPEG, params)
        except TypeError:  # OpenCV trop ancien : pas de paramètres
            return cv2.VideoCapture(self.url, cv2.CAP_FFMPEG)

    def run(self):
        while not self.stop_event.is_set():
            cap = self._open()
            if not cap.isOpened():
                cap.release()
                self._failures += 1
                self.message = (
                    "Impossible de joindre le telephone (essai %d)\n"
                    "Verifiez l'IP, le Wi-Fi commun et que la diffusion est demarree." % self._failures
                )
                self.stop_event.wait(1.0)
                continue
            self._failures = 0
            log("Flux ouvert : %s" % self.url)
            while not self.stop_event.is_set():
                ok, frame = cap.read()
                if not ok:
                    break
                with self._lock:
                    self._frame = frame
                    self._seq += 1
            cap.release()
            with self._lock:
                self._frame = None
            if not self.stop_event.is_set():
                self.message = "Flux interrompu, reconnexion ..."
                self.stop_event.wait(0.5)

    def stop(self):
        self.stop_event.set()


def placeholder(text, size=(640, 360)):
    img = np.zeros((size[1], size[0], 3), dtype=np.uint8)
    y = 60
    for line in text.split("\n"):
        cv2.putText(img, line, (20, y), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (255, 255, 255), 1, cv2.LINE_AA)
        y += 30
    return img


def run_viewer(url):
    reader = StreamReader(url)
    reader.start()
    cv2.namedWindow(WINDOW, cv2.WINDOW_NORMAL)
    cv2.resizeWindow(WINDOW, 640, 360)
    log("Echap / Q : quitter, F : plein ecran")

    last_seq = -1
    sized = False
    fullscreen = False
    try:
        while True:
            seq, frame = reader.get()
            if frame is not None:
                if seq != last_seq:
                    if not sized:  # 1re image : fenêtre à la taille de l'écran du téléphone (max 900 px de haut)
                        h, w = frame.shape[:2]
                        k = min(1.0, 900.0 / h)
                        cv2.resizeWindow(WINDOW, int(w * k), int(h * k))
                        sized = True
                    cv2.imshow(WINDOW, frame)
                    last_seq = seq
            else:
                last_seq = -1
                cv2.imshow(WINDOW, placeholder(reader.message))

            key = cv2.waitKey(10) & 0xFF
            if key in (27, ord("q"), ord("Q")):
                break
            if key in (ord("f"), ord("F")):
                fullscreen = not fullscreen
                cv2.setWindowProperty(
                    WINDOW, cv2.WND_PROP_FULLSCREEN,
                    cv2.WINDOW_FULLSCREEN if fullscreen else cv2.WINDOW_NORMAL,
                )
            if cv2.getWindowProperty(WINDOW, cv2.WND_PROP_VISIBLE) < 1:
                break
    finally:
        reader.stop()
        cv2.destroyAllWindows()


def main():
    try:
        target = sys.argv[1] if len(sys.argv) > 1 else ask_address()
        if not target:
            return
        url = build_url(target)
        save_last(target)
        log("Connexion au flux : %s" % url)
        run_viewer(url)
    except ValueError as exc:
        show_error(str(exc))
    except Exception:
        show_error("Erreur inattendue :\n\n" + traceback.format_exc())


if __name__ == "__main__":
    main()
