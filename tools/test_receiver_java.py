#!/usr/bin/env python3
"""Test du client de RÉCEPTION Android (RtspPlayer.java, le vrai code) contre le faux téléphone :

  faux téléphone (Python, --pin ou ouvert)  -->  RtspPlayer (Java)  -->  video.h264 + audio.aac  -->  ffmpeg vérifie

Vérifie : image décodable (720x1280, bon nombre d'images), son intact (440 Hz), mode PIN et mode ouvert,
refus d'un mauvais PIN, d'un client sans PIN sur un téléphone protégé, d'un PIN sur un téléphone en mode ouvert.
Prérequis : Java 8+, ffmpeg/ffprobe.   Usage : python tools/test_receiver_java.py
"""
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import wave

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "..", "android", "app", "src", "main", "java", "com", "example", "viewcast")
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


def wait_for(cond, timeout=60.0):
    t0 = time.time()
    while time.time() - t0 < timeout:
        if cond():
            return True
        time.sleep(0.05)
    return False


jdir = tempfile.mkdtemp()
files = [os.path.join(SRC, "security", f) for f in ("HandshakeException.java", "Kdf.java", "Srp.java", "SecureChannel.java", "PinGuard.java")]
files += [os.path.join(SRC, "receive", "RtspPlayer.java"), os.path.join(HERE, "java", "ReceiverProbe.java")]
javac = shutil.which("javac")
cmd = ([javac] if javac else ["java", "-m", "jdk.compiler/com.sun.tools.javac.Main"]) + ["-source", "8", "-target", "8", "-Xlint:-options", "-d", jdir] + files
comp = subprocess.run(cmd, capture_output=True, text=True)
check(comp.returncode == 0, "RtspPlayer.java compile (cible Java 8, comme Android)" + ("" if comp.returncode == 0 else "\n" + comp.stderr[:800]))


def start_phone(pin=None):
    port = free_port()
    args = [sys.executable, "-u", os.path.join(HERE, "fake_phone_server.py"), "--port", str(port)] + (["--pin", pin] if pin else [])
    p = subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    lines = []
    threading.Thread(target=lambda: [lines.append(l) for l in p.stdout], daemon=True).start()
    wait_for(lambda: any("Faux telephone" in l for l in lines))
    time.sleep(0.4)
    return p, port


def probe(port, pin, seconds=5):
    out = tempfile.mkdtemp()
    r = subprocess.run(["java", "-cp", jdir, "com.example.viewcast.receive.ReceiverProbe", "127.0.0.1", str(port), pin or "-", str(seconds), out],
                       capture_output=True, text=True, timeout=seconds + 40)
    stats = {}
    for line in r.stdout.splitlines():
        if line.startswith("STATS"):
            stats = dict(kv.split("=") for kv in line.split()[1:])
    return out, stats, r.stdout


def verify_media(out, stats, label):
    frames = int(stats.get("frames", 0))
    check(frames >= 100 and int(stats.get("keys", 0)) >= 3, "%s : %s images reçues dont %s clés (5 s à 30 i/s)" % (label, frames, stats.get("keys")))
    pr = subprocess.run(["ffprobe", "-v", "error", "-count_frames", "-select_streams", "v:0", "-show_entries",
                         "stream=width,height,nb_read_frames", "-of", "csv=p=0", os.path.join(out, "video.h264")],
                        capture_output=True, text=True).stdout.strip()
    w, h, n = (pr.split(",") + ["0", "0", "0"])[:3]
    check((w, h) == ("720", "1280") and int(n) >= 100, "%s : l'image reconstruite se décode (%sx%s, %s images)" % (label, w, h, n))
    err = subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-i", os.path.join(out, "video.h264"), "-f", "null", "-"],
                         capture_output=True, text=True).stderr.strip()
    check(err == "", "%s : aucune erreur de décodage vidéo" % label)
    wav = os.path.join(out, "a.wav")
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-i", os.path.join(out, "audio.aac"), "-ac", "1", "-ar", "48000", "-y", wav], capture_output=True)
    w = wave.open(wav)
    x = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(float)
    seg = x[24000:72000] if len(x) > 72000 else x
    freq = np.argmax(np.abs(np.fft.rfft(seg * np.hanning(len(seg))))) * 48000 / len(seg)
    check(abs(freq - 440) < 2 and np.sqrt((seg ** 2).mean()) > 500, "%s : son intact (%.1f Hz, attendu 440)" % (label, freq))


if comp.returncode == 0:
    print("1. Réception chiffrée (mode PIN)")
    phone, port = start_phone("482913")
    out, stats, log = probe(port, "482913")
    check("CONFIG" in log and "aac=48000Hz/2ch" in log, "SDP lu : SPS/PPS + audio AAC 48 kHz stéréo")
    verify_media(out, stats, "PIN")
    out, stats, log = probe(port, "000000", 3)
    check("ERR 2" in log and stats.get("frames") == "0", "mauvais PIN : refusé (ERR_AUTH)")
    out, stats, log = probe(port, None, 3)
    check(stats.get("frames") == "0" and "ERR" in log, "sans PIN sur un téléphone protégé : refusé")
    phone.terminate()

    print("2. Réception en mode ouvert")
    phone, port = start_phone(None)
    out, stats, log = probe(port, None)
    verify_media(out, stats, "ouvert")
    out, stats, log = probe(port, "482913", 3)
    check(stats.get("frames") == "0" and "ERR 4" in log, "PIN fourni mais téléphone en mode ouvert : erreur de protocole (ERR_PROTOCOL)")
    phone.terminate()

    print("3. Téléphone introuvable")
    out, stats, log = probe(free_port(), None, 3)
    check("ERR 1" in log, "aucun téléphone à cette adresse : ERR_UNREACHABLE")

print("\nRésultat : %d/%d tests réussis" % (sum(ok), len(ok)))
sys.exit(0 if all(ok) else 1)
