#!/usr/bin/env python3
"""Tests de la logique du viewer Windows SANS écran, SANS carte son et SANS téléphone.

Tkinter, PyAV et sounddevice sont remplacés par des doublures : on vérifie la logique de ViewCast
(lecture du flux, réglages, langues, affichage, raccourcis), pas les bibliothèques tierces.
Usage : python tools/test_viewer_logic.py
"""
import json
import os
import re
import sys
import tempfile
import threading
import time
import types

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "desktop", "python"))

# ───────────── doublures Tkinter / ImageTk ─────────────
class FakeW:
    _next = 1

    def __init__(self, *a, **k):
        self.kw = dict(k)
        self.w, self.h = 800, 450
        self.items = {}
        self.attrs = {}
        self.cmd = k.get("command")

    def __getattr__(self, name):          # pack, grid, bind, coords, after_cancel...
        return lambda *a, **k: None

    def configure(self, **k):
        self.kw.update(k)
    config = configure

    def winfo_width(self): return self.w
    def winfo_height(self): return self.h
    def winfo_children(self): return []
    def focus_get(self): return None
    def attributes(self, name, value=None):
        if value is not None:
            self.attrs[name] = value
    def after(self, ms, fn): return None
    def get(self): return self.kw.get("value", "")
    def set(self, v): self.kw["value"] = v
    def _new(self):
        FakeW._next += 1
        return FakeW._next
    def create_image(self, *a, **k): return self._new()
    def create_text(self, *a, **k): i = self._new(); self.items[i] = dict(k); return i
    def create_rectangle(self, *a, **k): i = self._new(); self.items[i] = dict(k); return i
    def create_window(self, *a, **k): i = self._new(); self.items[i] = dict(k); return i
    def itemconfigure(self, item, **k): self.items.setdefault(item, {}).update(k)


class Var:
    def __init__(self, value=None): self.v = value
    def get(self): return self.v
    def set(self, v): self.v = v


tk = types.ModuleType("tkinter")
for n in ("Tk", "Frame", "Label", "Button", "Canvas", "Entry", "Scale", "Scrollbar", "Toplevel", "Text", "Checkbutton"):
    setattr(tk, n, type(n, (FakeW,), {}))
tk.StringVar, tk.BooleanVar = Var, Var
sys.modules["tkinter"] = tk

shown = []                                # tailles des images affichées
class FakePhoto:
    def __init__(self, img): shown.append(img.size)
imagetk = types.ModuleType("PIL.ImageTk")
imagetk.PhotoImage = FakePhoto
sys.modules["PIL.ImageTk"] = imagetk
import PIL
PIL.ImageTk = imagetk

# ───────────── doublure PyAV ─────────────
class FFrame:
    def __init__(self, kind): self.kind = kind
    def to_ndarray(self, format=None):
        if self.kind == "video":
            img = np.zeros((720, 1280, 3), np.uint8); img[:, :, 2] = 200; return img
        return np.zeros((1, 2048), np.int16)           # 512 trames stéréo

class FStream:
    def __init__(self, kind, name):
        self.type = kind; self.codec_context = types.SimpleNamespace(name=name)

class FPacket:
    def __init__(self, stream, size): self.stream = stream; self.size = size
    def decode(self): return [FFrame(self.stream.type)]

class FContainer:
    def __init__(self, with_audio):
        self.v = FStream("video", "h264"); self.a = FStream("audio", "aac")
        self.streams = [self.v] + ([self.a] if with_audio else [])
        self.closed = False
    def demux(self, *streams):
        for i in range(40):
            yield FPacket(self.v, 5000)
            if self.a in streams:
                yield FPacket(self.a, 300)
        time.sleep(0.4)                                   # le flux reste ouvert un moment
    def close(self): self.closed = True

class FakeAV:
    def __init__(self, with_audio=True, fail=False):
        self.opens = []; self.with_audio = with_audio; self.fail = fail
    def open(self, url, options=None, timeout=None):
        self.opens.append((url, dict(options or {})))
        if self.fail or len(self.opens) > 1:
            raise OSError("unreachable")
        return FContainer(self.with_audio)
    class AudioResampler:
        def __init__(self, **k): pass
        def resample(self, frame): return [frame]

import vc_i18n, vc_settings, vc_stream, vc_legal            # noqa: E402

ok = []
def check(cond, label):
    ok.append(bool(cond))
    print(("  OK   " if cond else "  ÉCHEC ") + label)

def wait_for(cond, timeout=3.0):
    t0 = time.time()
    while time.time() - t0 < timeout:
        if cond(): return True
        time.sleep(0.02)
    return False

# ───────────── 1. langues ─────────────
print("1. Langues FR/EN")
fmt = lambda s: sorted(re.findall(r"%[sd]", s))
check(all(fmt(fr) == fmt(en) for fr, en in vc_i18n.S.values()), "mêmes marqueurs %s/%d dans FR et EN")
check(all(fr.strip() and en.strip() for fr, en in vc_i18n.S.values()), "aucun texte vide")
vc_i18n.set_language("en"); check(vc_i18n.t("btn_connect") == "Connect", "anglais : « Connect »")
vc_i18n.set_language("fr"); check(vc_i18n.t("btn_connect") == "Se connecter", "français : « Se connecter »")
check(vc_i18n.t("clé inconnue") == "clé inconnue", "clé inconnue → affichée telle quelle")
check("mumbalpha@gmail.com" in vc_legal.PRIVACY["fr"] and "Alphonse MUMBA" in vc_legal.TERMS["en"], "textes légaux : éditeur + contact")

# ───────────── 2. réglages ─────────────
print("2. Réglages")
tmp = tempfile.mkdtemp(); path = os.path.join(tmp, "s.json")
json.dump({"language": "xx", "volume": 500, "aspect": "bizarre", "muted": "oui", "latency": "stable"}, open(path, "w"))
st = vc_settings.Settings(path)
check(st["language"] == "fr" and st["aspect"] == "fit" and st["volume"] == 100 and st["muted"] is False, "valeurs invalides corrigées")
check(st["latency"] == "stable", "valeur valide conservée")
st.set("language", "en"); check(json.load(open(path))["language"] == "en", "écriture sur disque")
open(path, "w").write("{pas du json"); check(vc_settings.Settings(path)["language"] == "fr", "fichier corrompu → défauts")

# ───────────── 3. adresses ─────────────
print("3. Adresses")
check(vc_stream.build_url("192.168.43.1") == "rtsp://192.168.43.1:8554/stream", "IP seule")
check(vc_stream.build_url("10.0.0.2:9000") == "rtsp://10.0.0.2:9000/stream", "IP:port")
bad = 0
for b in ("", "a b", "1.2.3.4:abc", "1.2.3.4:99999"):
    try: vc_stream.build_url(b)
    except ValueError: bad += 1
check(bad == 4, "adresses invalides refusées")

# ───────────── 4. tampon et volume audio ─────────────
print("4. Audio")
B = vc_stream.BYTES_PER_FRAME
b = vc_stream.AudioBuffer()
check(b.pull(400) == bytes(400), "silence avant le pré-tampon")
b.push(b"\x01" * (B * 6000)); check(b.pull(8)[:4] == b"\x01" * 4, "lecture dans l'ordre")
b.clear(); b.push(b"\x03" * (B * 40000))
check(len(b._buf) == int(48000 * 0.4) * B and b.dropped_bytes > 0, "tampon borné (retard rattrapé)")
pcm = (np.full(100, 10000, np.int16)).tobytes()
check(vc_stream.apply_volume(pcm, 1.0) == pcm and vc_stream.apply_volume(pcm, 0.0) == bytes(len(pcm)), "volume 100 % / 0 %")
half = np.frombuffer(vc_stream.apply_volume(pcm, 0.5), np.int16)
check(abs(int(half[0]) - 5000) <= 1, "volume 50 % = moitié de l'amplitude")

# ───────────── 5. lecture du flux ─────────────
print("5. Lecture du flux (PyAV simulé)")
av = FakeAV(); vc_stream.av = av
audio = []; msgs = []
r = vc_stream.StreamReader("rtsp://x/stream", "low", audio.append, lambda k, *a: msgs.append(k))
r.start()
check(wait_for(lambda: r.frames_decoded >= 40), "40 images décodées")
check(wait_for(lambda: len(audio) >= 40), "paquets audio reçus")
seq, frame = r.get()
check(frame is not None and frame.shape == (720, 1280, 3) and r.size == (1280, 720), "dernière image + taille")
check(r.has_audio is True and r.video_codec == "h264" and r.audio_codec == "aac", "pistes détectées (h264 + aac)")
check(len(audio[0]) == 2048 * 2, "audio transmis en octets PCM")
url, opts = av.opens[0]
check(opts.get("rtsp_transport") == "tcp" and opts.get("fflags") == "nobuffer", "options : TCP + faible latence")
check(wait_for(lambda: "unreachable" in msgs), "reconnexion tentée quand le flux tombe")
r.stop()
check("connecting" in msgs and "connected" in msgs and "interrupted" in msgs, "messages d'état : connexion → connecté → interrompu")
m = vc_stream.RateMeter(); m.update(0, 0); time.sleep(0.6); m.update(30, 600_000)
check(m.fps > 30 and m.mbps > 6, "mesure images/s et Mb/s")

# ───────────── 6. interface ─────────────
print("6. Interface (Tkinter simulé)")
import vc_ui                                               # noqa: E402
root = tk.Tk()
st = vc_settings.Settings(os.path.join(tmp, "ui.json"))
vc_stream.av = FakeAV(); vc_stream.sd = None
app = vc_ui.ViewCastApp(root, st, "1.2.0")
check(app.page == "wait", "démarre sur l'écran d'attente")
app.addr_var.set("pas une ip !"); app.connect()
check("invalide" in app.wait_status.kw.get("text", "") and app.reader is None, "adresse invalide → message, pas de connexion")
app.addr_var.set("192.168.1.9"); app.connect()
check(st["last_address"] == "192.168.1.9", "dernière adresse mémorisée")
check(wait_for(lambda: app.reader.get()[1] is not None), "image reçue")
app._update()
check(app.page == "live" and app.shown_live, "bascule sur l'écran de diffusion")
check(shown and shown[-1] == (800, 450), "mode Adapter : 1280×720 dans 800×450 sans déformation")
app.canvas.w, app.canvas.h = 400, 800; app.dirty = True; app._update()
check(shown[-1] == (400, 225), "mode Adapter : barres noires (400×225 dans 400×800)")
app.cycle_aspect(); app._update(); check(st["aspect"] == "stretch" and shown[-1] == (400, 800), "mode Étirer remplit la fenêtre")
app.cycle_aspect(); app._update(); check(st["aspect"] == "center" and shown[-1] == (400, 720), "mode Centrer : recadrage 1:1 (400×720 dans 400×800)")
app.cycle_aspect(); check(st["aspect"] == "fit", "retour au mode Adapter")
app.canvas.w, app.canvas.h = 800, 450; app.rotate(); app._update()
check(shown[-1][1] == 450 and shown[-1][0] < 300, "rotation 90° : image portrait adaptée")
app.rotation = 0
app._on_key(types.SimpleNamespace(keysym="m")); check(st["muted"] is True, "touche M : son coupé")
app._on_key(types.SimpleNamespace(keysym="m")); check(st["muted"] is False, "touche M : son remis")
app._on_key(types.SimpleNamespace(keysym="space")); check(app.paused is True, "Espace : pause")
app._on_key(types.SimpleNamespace(keysym="space")); check(app.paused is False, "Espace : lecture")
app._on_key(types.SimpleNamespace(keysym="f")); check(app.fullscreen and root.attrs.get("-fullscreen") is True, "touche F : plein écran")
app.last_motion -= 5; app._update()
check(app.chrome_hidden is True, "plein écran : commandes masquées après 2,5 s")
app._on_motion(None); app._update(); check(app.chrome_hidden is False, "mouvement de souris : commandes réaffichées")
app._on_key(types.SimpleNamespace(keysym="escape")); check(not app.fullscreen, "Échap : quitte le plein écran")
app._on_key(types.SimpleNamespace(keysym="escape")); check(app.reader is None and app.page == "wait", "Échap (fenêtré) : arrête et revient à l'attente")
app.change_language("en"); check(vc_i18n.get_language() == "en" and st["language"] == "en", "changement de langue → anglais, enregistré")
app.show("settings"); app.change_language("fr"); check(app.page == "settings" and vc_i18n.t("btn_connect") == "Se connecter", "retour en français sans quitter la page")
st.set("auto_fullscreen", True); vc_stream.av = FakeAV()
app.addr_var.set("192.168.1.9"); app.connect(); wait_for(lambda: app.reader.get()[1] is not None); app._update()
check(app.fullscreen, "plein écran automatique à l'arrivée de l'image")
app._set_latency("stable"); check(app.reader is not None and app.reader.mode == "stable", "changement de tampon → reconnexion appliquée")
app.quit()
check(app.reader is None, "fermeture : flux arrêté")

# ───────────── 7. sécurité (code PIN) ─────────────
print("7. Sécurité : code PIN et pont chiffré")
import vc_secure                                            # noqa: E402
check(vc_secure.valid_pin("482913") and not vc_secure.valid_pin("12345") and not vc_secure.valid_pin("12a456"), "format du PIN : 6 chiffres exactement")
check(vc_stream.parse_target("192.168.43.1") == ("192.168.43.1", 8554) and vc_stream.parse_target("10.0.0.2:9000") == ("10.0.0.2", 9000)
      and vc_stream.parse_target("rtsp://1.2.3.4:8554/stream") == ("1.2.3.4", 8554), "adresse : IP, IP:port ou URL rtsp://")
bad = 0
for b in ("", "a b", "1.2.3.4:abc", "1.2.3.4:99999"):
    try: vc_stream.parse_target(b)
    except ValueError: bad += 1
check(bad == 4, "adresses invalides refusées")
vc_stream.av = FakeAV()
app2 = vc_ui.ViewCastApp(tk.Tk(), st, "1.3.0")
app2.addr_var.set("192.168.1.9"); app2.pin_var.set("12ab"); app2.connect()
check(app2.reader is None and "6 chiffres" in app2.wait_status.kw.get("text", ""), "PIN mal formé : message, pas de connexion")
app2.pin_var.set("482913"); app2.connect()
check(app2.proxy is not None and app2.reader.url == app2.proxy.local_url and app2.reader.url.startswith("rtsp://127.0.0.1:"),
      "avec PIN : le flux passe par le pont local chiffré (127.0.0.1)")
check(app2.secure_mode is True and app2.reader.proxy is app2.proxy, "mode sécurisé actif")
app2.disconnect()
check(app2.pin_var.get() == "" and app2.proxy is None, "déconnexion : PIN effacé de la mémoire de l'interface, pont fermé")
app2.addr_var.set("192.168.1.9"); app2.connect()
check(app2.proxy is None and app2.secure_mode is False, "sans PIN : mode ouvert (RTSP direct)")
app2.quit()
class StubProxy: status = "auth_failed"
msgs2 = []; fav = FakeAV(fail=True); vc_stream.av = fav
rd2 = vc_stream.StreamReader("rtsp://x", "low", None, lambda k, *a: msgs2.append(k), StubProxy())
rd2.start(); rd2.join(3)
check(not rd2.is_alive() and msgs2.count("auth_failed") == 1 and len(fav.opens) == 1,
      "PIN refusé : on ne réessaie PAS (chaque essai compte pour la limite du téléphone)")
vc_i18n.set_language("fr")
check("incorrect" in vc_i18n.t("msg_auth_failed") and "nouveau code" in vc_i18n.t("msg_locked"), "messages d'erreur PIN en français")
vc_i18n.set_language("en"); check("Wrong PIN" in vc_i18n.t("msg_auth_failed"), "messages d'erreur PIN en anglais"); vc_i18n.set_language("fr")

print("\nRésultat : %d/%d tests réussis" % (sum(ok), len(ok)))
sys.exit(0 if all(ok) else 1)
