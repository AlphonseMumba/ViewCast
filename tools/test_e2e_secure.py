#!/usr/bin/env python3
"""Test de bout en bout du mode sécurisé, sans téléphone :

  faux téléphone (--pin, chiffré)  <--[espion réseau]--  SecureProxy (viewer)  <--  ffmpeg (RTSP en clair sur 127.0.0.1)

Vérifie : lecture image + son à travers le canal chiffré, absence de données lisibles sur le réseau,
refus d'un mauvais PIN, refus d'un lecteur RTSP direct, renouvellement du PIN après 5 échecs.
Prérequis : ffmpeg/ffprobe dans le PATH.   Usage : python tools/test_e2e_secure.py
"""
import os
import socket
import subprocess
import sys
import tempfile
import threading
import time
import wave
import zlib

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "desktop", "python"))
import vc_secure as sec  # noqa: E402

ok = []


def check(cond, label):
    ok.append(bool(cond))
    print(("  OK   " if cond else "  ÉCHEC ") + label)


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


def wait_for(cond, timeout=20.0):
    t0 = time.time()
    while time.time() - t0 < timeout:
        if cond():
            return True
        time.sleep(0.05)
    return False


class Sniffer:
    """Relais TCP transparent qui enregistre tout ce qui circule (comme un voisin sur le Wi‑Fi)."""

    def __init__(self, target_port):
        self.target = target_port
        self.data = bytearray()
        self.lock = threading.Lock()
        self.ls = socket.socket()
        self.ls.bind(("127.0.0.1", 0))
        self.ls.listen(8)
        self.port = self.ls.getsockname()[1]
        threading.Thread(target=self._accept, daemon=True).start()

    def _accept(self):
        while True:
            try:
                a, _ = self.ls.accept()
            except OSError:
                return
            b = socket.create_connection(("127.0.0.1", self.target))
            for src, dst in ((a, b), (b, a)):
                threading.Thread(target=self._pipe, args=(src, dst), daemon=True).start()

    def _pipe(self, src, dst):
        try:
            while True:
                d = src.recv(65536)
                if not d:
                    break
                with self.lock:
                    self.data += d
                dst.sendall(d)
        except OSError:
            pass
        finally:
            for s in (src, dst):
                try:
                    s.close()
                except OSError:
                    pass


def probe(url, timeout=25):
    r = subprocess.run(["ffprobe", "-v", "error", "-rtsp_transport", "tcp", "-show_entries", "stream=codec_type,codec_name",
                        "-of", "csv=p=0", url], capture_output=True, text=True, timeout=timeout)
    return r.returncode, r.stdout


PIN = "482913"
port = free_port()
srv = subprocess.Popen([sys.executable, "-u", os.path.join(HERE, "fake_phone_server.py"), "--port", str(port), "--pin", PIN],
                       stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
lines = []
threading.Thread(target=lambda: [lines.append(l.strip()) for l in srv.stdout], daemon=True).start()
try:
    check(wait_for(lambda: any("chiffre" in l for l in lines), 60), "faux téléphone chiffré démarré")
    time.sleep(0.5)
    sniff = Sniffer(port)

    print("1. Lecture image + son à travers le canal chiffré")
    proxy = sec.SecureProxy("127.0.0.1", sniff.port, PIN)
    rc, out = probe(proxy.local_url)
    check(rc == 0 and "h264,video" in out and "aac,audio" in out, "ffprobe voit h264 + aac via le pont sécurisé")
    check(proxy.status == "ok", "pont : état « ok »")
    tmp = tempfile.mkdtemp()
    mkv = os.path.join(tmp, "t.mkv")
    wav = os.path.join(tmp, "a.wav")
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-rtsp_transport", "tcp", "-i", proxy.local_url,
                    "-t", "6", "-c", "copy", "-y", mkv], capture_output=True, timeout=60)
    st = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "stream=codec_type,start_time", "-of", "csv=p=0", mkv],
                        capture_output=True, text=True).stdout.split()
    starts = {l.split(",")[0]: float(l.split(",")[1]) for l in st if "," in l}
    check(len(starts) == 2 and abs(starts["audio"] - starts["video"]) < 0.2,
          "image et son synchronisés (écart %.3f s)" % abs(starts.get("audio", 9) - starts.get("video", 0)))
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-i", mkv, "-vn", "-ac", "1", "-ar", "48000", "-y", wav],
                   capture_output=True)
    w = wave.open(wav)
    x = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(float)
    seg = x[24000:72000]
    freq = np.argmax(np.abs(np.fft.rfft(seg * np.hanning(len(seg))))) * 48000 / len(seg)
    check(abs(freq - 440) < 2 and np.sqrt((seg ** 2).mean()) > 500, "son intact : %.1f Hz (attendu 440)" % freq)
    err = subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-i", mkv, "-an", "-f", "null", "-"],
                         capture_output=True, text=True).stderr.strip()
    check(err == "", "image décodée sans aucune erreur")

    print("2. Ce qu'un espion voit sur le réseau")
    with sniff.lock:
        wire = bytes(sniff.data)
    check(len(wire) > 100000, "%d Ko ont circulé" % (len(wire) // 1000))
    check(b"RTSP" not in wire and b"H264" not in wire and b"sprop" not in wire and b"ViewCast" not in wire,
          "aucun texte RTSP/SDP lisible sur le réseau")
    ratio = len(zlib.compress(wire[4096:200000], 9)) / len(wire[4096:200000])
    check(ratio > 0.99, "données incompressibles (rapport %.3f) : indiscernables du hasard" % ratio)

    print("3. Mauvais PIN, lecteur direct, verrouillage")
    bad = sec.SecureProxy("127.0.0.1", sniff.port, "000000")
    rc, _ = probe(bad.local_url, 15)
    check(rc != 0 and bad.status == "auth_failed", "mauvais PIN : refusé (état « auth_failed »)")
    rc, _ = probe("rtsp://127.0.0.1:%d/stream" % port, 15)
    check(rc != 0, "lecteur RTSP direct (sans PIN) : refusé par le téléphone")
    for _ in range(8):                      # des essais incorrects en série, jusqu'au renouvellement du code
        if any(l.startswith("NEW_PIN") for l in lines):
            break
        probe(sec.SecureProxy("127.0.0.1", sniff.port, "222222").local_url, 15)
    wait_for(lambda: any(l.startswith("NEW_PIN") for l in lines), 15)
    new = next((l.split()[1] for l in lines if l.startswith("NEW_PIN")), None)
    check(new is not None and new != PIN, "après 5 échecs : le téléphone génère un NOUVEAU code")
    old = sec.SecureProxy("127.0.0.1", sniff.port, PIN)
    rc, _ = probe(old.local_url, 15)
    check(rc != 0, "l'ancien code ne fonctionne plus")
    fresh = sec.SecureProxy("127.0.0.1", sniff.port, new or "000000")
    rc, out = probe(fresh.local_url)
    check(rc == 0 and "h264,video" in out, "le nouveau code fonctionne")
finally:
    srv.terminate()

print("\nRésultat : %d/%d tests réussis" % (sum(ok), len(ok)))
sys.exit(0 if all(ok) else 1)
