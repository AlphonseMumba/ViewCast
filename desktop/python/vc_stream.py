"""Lecture du flux RTSP (image + son) : PyAV pour le décodage, sounddevice pour la sortie audio."""
import threading
import time

try:
    import av  # FFmpeg embarqué dans la roue PyAV
except ImportError:
    av = None

try:
    import numpy as np
except ImportError:  # numpy est requis ; message clair ailleurs
    np = None

try:
    import sounddevice as sd  # PortAudio embarqué dans la roue Windows
except Exception:  # ImportError, ou OSError si PortAudio est introuvable
    sd = None

DEFAULT_PORT = 8554
AUDIO_RATE = 48000
AUDIO_CHANNELS = 2
BYTES_PER_FRAME = AUDIO_CHANNELS * 2  # int16

# Mode de latence -> options FFmpeg + tailles du tampon audio (secondes).
LATENCY_MODES = {
    "low":      {"opts": {"fflags": "nobuffer", "flags": "low_delay", "max_delay": "100000"},  "pre": 0.06, "max": 0.30},
    "balanced": {"opts": {"fflags": "nobuffer", "flags": "low_delay", "max_delay": "500000"},  "pre": 0.12, "max": 0.50},
    "stable":   {"opts": {"max_delay": "2000000"},                                              "pre": 0.25, "max": 1.00},
}


def parse_target(text):
    """'192.168.1.5', '192.168.1.5:8554' ou 'rtsp://hôte:port/...' -> (hôte, port). ValueError si invalide."""
    text = (text or "").strip()
    if not text:
        raise ValueError("empty")
    if text.lower().startswith("rtsp://"):
        rest = text[7:].split("/", 1)[0]
    else:
        if any(c.isspace() or c == "/" for c in text):
            raise ValueError(text)
        rest = text
    host, sep, port = rest.rpartition(":")
    if not sep:
        host, port = rest, str(DEFAULT_PORT)
    if not host or not port.isdigit() or not (0 < int(port) < 65536):
        raise ValueError(text)
    return host, int(port)


def build_url(text):
    """'192.168.1.5', '192.168.1.5:8554' ou 'rtsp://...' -> URL RTSP complète (ValueError si invalide)."""
    text = (text or "").strip()
    if not text:
        raise ValueError("empty")
    if text.lower().startswith("rtsp://"):
        return text
    if any(c.isspace() or c == "/" for c in text):
        raise ValueError(text)
    host, sep, port = text.rpartition(":")
    if not sep:
        host, port = text, str(DEFAULT_PORT)
    if not host or not port.isdigit() or not (0 < int(port) < 65536):
        raise ValueError(text)
    return "rtsp://%s:%s/stream" % (host, port)


class AudioBuffer:
    """Tampon PCM int16 borné (on jette le plus ancien si le son prend du retard), avec pré‑tampon."""

    def __init__(self, rate=AUDIO_RATE, max_seconds=0.4, prebuffer_seconds=0.08):
        self._buf = bytearray()
        self._lock = threading.Lock()
        self._max = int(rate * max_seconds) * BYTES_PER_FRAME
        self._pre = int(rate * prebuffer_seconds) * BYTES_PER_FRAME
        self._started = False
        self.dropped_bytes = 0

    def push(self, data):
        with self._lock:
            self._buf += data
            excess = len(self._buf) - self._max
            if excess > 0:
                excess -= excess % BYTES_PER_FRAME
                del self._buf[:excess]
                self.dropped_bytes += excess

    def pull(self, nbytes):
        """Exactement nbytes octets (complétés par du silence si le tampon est vide)."""
        with self._lock:
            if not self._started and len(self._buf) >= self._pre:
                self._started = True
            if not self._started:
                return bytes(nbytes)
            chunk = bytes(self._buf[:nbytes])
            del self._buf[:nbytes]
        if len(chunk) < nbytes:
            chunk += bytes(nbytes - len(chunk))
        return chunk

    def clear(self):
        with self._lock:
            self._buf.clear()
            self._started = False


def apply_volume(chunk, volume):
    """Volume 0..1 sur du PCM int16 ; 1.0 = inchangé, 0 = silence."""
    if volume >= 0.999:
        return chunk
    if volume <= 0.001:
        return bytes(len(chunk))
    samples = np.frombuffer(chunk, dtype=np.int16).astype(np.float32) * volume
    return np.clip(samples, -32768, 32767).astype(np.int16).tobytes()


class AudioPlayer:
    """Sortie audio Windows. Inactive (sans erreur) si sounddevice/PortAudio manque."""

    def __init__(self, volume=0.8, muted=False, mode="low"):
        cfg = LATENCY_MODES.get(mode, LATENCY_MODES["low"])
        self.buffer = AudioBuffer(max_seconds=cfg["max"], prebuffer_seconds=cfg["pre"])
        self.volume = volume
        self.muted = muted
        self.available = False
        self.error = ""
        self._stream = None
        if sd is None:
            self.error = "sounddevice/PortAudio"
            return
        try:
            self._stream = sd.RawOutputStream(
                samplerate=AUDIO_RATE, channels=AUDIO_CHANNELS, dtype="int16",
                latency="low", blocksize=0, callback=self._callback)
            self._stream.start()
            self.available = True
        except Exception as exc:  # pas de carte son, périphérique occupé...
            self.error = str(exc)

    def _callback(self, outdata, frames, time_info, status):
        n = frames * BYTES_PER_FRAME
        chunk = self.buffer.pull(n)
        outdata[:n] = bytes(n) if self.muted else apply_volume(chunk, self.volume)

    def push(self, data):
        if self.available:
            self.buffer.push(data)

    def close(self):
        if self._stream is not None:
            try:
                self._stream.stop()
                self._stream.close()
            except Exception:
                pass


class StreamReader(threading.Thread):
    """Lit image + son du flux RTSP ; ne garde que la dernière image (latence minimale).

    Se reconnecte tout seul si le flux est coupé ou si le téléphone n'est pas encore prêt.
    Les compteurs (images, octets) servent aux mesures affichées dans le HUD.
    """

    def __init__(self, url, mode="low", audio_sink=None, on_message=None, proxy=None):
        super().__init__(daemon=True)
        self.url = url
        self.proxy = proxy                    # SecureProxy (mode PIN) ou None (mode ouvert)
        self.mode = mode if mode in LATENCY_MODES else "low"
        self.audio_sink = audio_sink
        self.on_message = on_message          # fonction(texte) : état de connexion
        self.stop_event = threading.Event()
        self._lock = threading.Lock()
        self._frame = None
        self._seq = 0
        self.frames_decoded = 0
        self.bytes_received = 0
        self.size = (0, 0)                    # (largeur, hauteur) de l'image
        self.video_codec = ""
        self.audio_codec = ""
        self.has_audio = None                 # None = inconnu, True/False une fois connecté
        self.connected = False
        self.failures = 0

    def get(self):
        with self._lock:
            return self._seq, self._frame

    def _say(self, kind, *args):
        if self.on_message:
            self.on_message(kind, *args)

    def run(self):
        if av is None:
            self._say("no_av")
            return
        opts = {"rtsp_transport": "tcp"}
        opts.update(LATENCY_MODES[self.mode]["opts"])
        while not self.stop_event.is_set():
            self._say("connecting", self.url)
            try:
                container = av.open(self.url, options=opts, timeout=(5.0, 5.0))
            except Exception:
                status = getattr(self.proxy, "status", "")
                if status in ("auth_failed", "locked", "protocol"):
                    self._say(status)     # inutile de réessayer : chaque essai compte pour la limite du téléphone
                    return
                self.failures += 1
                self._say("unreachable", self.failures)
                self.stop_event.wait(1.0)
                continue
            self.failures = 0
            try:
                self._play(container)
            except Exception:
                pass
            finally:
                try:
                    container.close()
                except Exception:
                    pass
                with self._lock:
                    self._frame = None
                self.connected = False
                self.has_audio = None
            if not self.stop_event.is_set():
                self._say("interrupted")
                self.stop_event.wait(0.5)

    def _play(self, container):
        video = next((s for s in container.streams if s.type == "video"), None)
        audio = next((s for s in container.streams if s.type == "audio"), None)
        if video is None:
            raise RuntimeError("no video track")
        try:
            video.thread_type = "SLICE"   # pas de file d'attente d'images : latence minimale
        except Exception:
            pass
        resampler = av.AudioResampler(format="s16", layout="stereo", rate=AUDIO_RATE) if audio else None
        self.video_codec = getattr(video.codec_context, "name", "") or ""
        self.audio_codec = (getattr(audio.codec_context, "name", "") or "") if audio else ""
        self.has_audio = audio is not None
        self.connected = True
        self._say("connected")

        streams = [s for s in (video, audio) if s is not None]
        for packet in container.demux(*streams):
            if self.stop_event.is_set():
                return
            self.bytes_received += int(getattr(packet, "size", 0) or 0)
            for frame in packet.decode():
                if packet.stream.type == "video":
                    img = frame.to_ndarray(format="bgr24")
                    with self._lock:
                        self._frame = img
                        self._seq += 1
                        self.size = (img.shape[1], img.shape[0])
                    self.frames_decoded += 1
                elif resampler is not None and self.audio_sink is not None:
                    out = resampler.resample(frame)
                    if out is None:
                        continue
                    for f in (out if isinstance(out, list) else [out]):
                        self.audio_sink(f.to_ndarray().tobytes())

    def stop(self):
        self.stop_event.set()


class RateMeter:
    """Calcule images/s et Mb/s à partir des compteurs du lecteur (appelé périodiquement)."""

    def __init__(self):
        self._t = time.monotonic()
        self._frames = 0
        self._bytes = 0
        self.fps = 0.0
        self.mbps = 0.0

    def update(self, frames, nbytes):
        now = time.monotonic()
        dt = now - self._t
        if dt < 0.5:
            return
        self.fps = max(0.0, (frames - self._frames) / dt)
        self.mbps = max(0.0, (nbytes - self._bytes) * 8 / 1_000_000 / dt)
        self._t, self._frames, self._bytes = now, frames, nbytes
