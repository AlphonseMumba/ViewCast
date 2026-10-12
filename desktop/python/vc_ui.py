"""Interface Windows de ViewCast (Tkinter) : écran d'attente, diffusion avec HUD, paramètres, plein écran."""
import os
import sys
import time
import traceback
import tkinter as tk
import webbrowser

import cv2
import numpy as np
from PIL import Image, ImageTk

import vc_stream
from vc_i18n import LANGS, get_language, set_language, t
from vc_legal import PRIVACY, TERMS
from vc_secure import SecureProxy, valid_pin
from vc_stream import AudioPlayer, RateMeter, StreamReader, build_url, parse_target

# Palette des maquettes (thème sombre « obsidienne » + corail)
C = {
    "bg": "#0f131d", "low": "#171b26", "card": "#1c1f2a", "high": "#262a35", "highest": "#313540",
    "text": "#dfe2f1", "muted": "#e1bfb5", "primary": "#ffb59d", "coral": "#ff6b35", "on_coral": "#390c00",
    "cyan": "#4cd7f6", "amber": "#ffb875", "error": "#ffb4ab", "error_bg": "#93000a", "outline": "#a98a80",
    "video_bg": "#05070c",
}
FONT = "Segoe UI"
MONO = "Consolas"
MAIL = "mumbalpha@gmail.com"
ASPECTS = ("fit", "stretch", "center")


def resource_path(name):
    base = getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__)))
    for p in (os.path.join(base, name), os.path.join(base, "..", "windows", name)):
        if os.path.exists(p):
            return p
    return None


def flat_button(parent, text, command, kind="soft", font_size=10, padx=14, pady=7):
    colors = {
        "primary": (C["coral"], C["on_coral"], "#ff8a5c"),
        "soft": (C["highest"], C["text"], "#3d4250"),
        "danger": (C["error_bg"], "#ffffff", "#b3261e"),
        "ghost": (C["card"], C["text"], C["high"]),
    }
    bg, fg, hover = colors[kind]
    weight = "bold" if kind in ("primary", "danger") else "normal"
    b = tk.Button(parent, text=text, command=command, bg=bg, fg=fg, activebackground=hover, activeforeground=fg,
                  relief="flat", bd=0, padx=padx, pady=pady, cursor="hand2", font=(FONT, font_size, weight))
    b.bind("<Enter>", lambda e: b.configure(bg=hover))
    b.bind("<Leave>", lambda e: b.configure(bg=bg))
    return b


class ViewCastApp:
    def __init__(self, root, settings, version, initial_address=""):
        self.root = root
        self.s = settings
        self.version = version
        set_language(settings["language"])

        self.page = "wait"
        self.reader = None
        self.player = None
        self.fullscreen = False
        self.paused = False
        self.rotation = 0
        self.meter = RateMeter()
        self.msg = None                     # (type, args) envoyé par le thread de lecture
        self.status_text = ""
        self.last_seq = -1
        self.dirty = True
        self.photo = None
        self.shown_live = False
        self.last_motion = time.monotonic()
        self.chrome_hidden = False
        self.hud_updated = 0.0
        self.addr_var = tk.StringVar(value=settings["last_address"])
        self.pin_var = tk.StringVar(value="")      # jamais enregistré : le code change à chaque diffusion
        self.proxy = None
        self.secure_mode = False

        root.title("ViewCast Viewer")
        root.geometry("1100x700")
        root.minsize(900, 600)
        root.configure(bg=C["bg"])
        icon = resource_path("icon.ico")
        if icon:
            try:
                root.iconbitmap(icon)
            except Exception:
                pass
        root.attributes("-topmost", bool(settings["always_on_top"]))
        root.protocol("WM_DELETE_WINDOW", self.quit)
        root.bind("<Key>", self._on_key)
        root.bind("<Motion>", self._on_motion)

        self._build()
        self._tick()
        if initial_address:
            self.addr_var.set(initial_address)
            self.connect()

    # ───────────────────────── construction ─────────────────────────

    def _build(self):
        for w in self.root.winfo_children():
            w.destroy()
        self.root.grid_columnconfigure(0, weight=1)
        self.root.grid_rowconfigure(1, weight=1)

        # Barre du haut
        self.topbar = tk.Frame(self.root, bg=C["low"])
        self.topbar.grid(row=0, column=0, sticky="ew")
        left = tk.Frame(self.topbar, bg=C["low"])
        left.pack(side="left", padx=18, pady=10)
        tk.Label(left, text="ViewCast", bg=C["low"], fg=C["text"], font=(FONT, 16, "bold")).pack(side="left")
        tk.Label(left, text="  " + t("app_receiver"), bg=C["low"], fg=C["primary"], font=(FONT, 11)).pack(side="left")
        nav = tk.Frame(self.topbar, bg=C["low"])
        nav.pack(side="left", padx=24)
        self.nav_labels = {}
        for key, label in (("wait", "tab_wait"), ("live", "tab_live"), ("settings", "tab_settings")):
            lb = tk.Label(nav, text=t(label), bg=C["low"], fg=C["muted"], font=(FONT, 11), padx=12, pady=6, cursor="hand2")
            lb.pack(side="left", padx=2)
            lb.bind("<Button-1>", lambda e, k=key: self.show(k))
            self.nav_labels[key] = lb
        right = tk.Frame(self.topbar, bg=C["low"])
        right.pack(side="right", padx=18)
        self.addr_chip = tk.Label(right, text="", bg=C["low"], fg=C["muted"], font=(MONO, 10))
        self.addr_chip.pack(side="left", padx=12)
        self.status_chip = tk.Label(right, text="", bg=C["low"], fg=C["amber"], font=(FONT, 10, "bold"))
        self.status_chip.pack(side="left")

        # Corps
        self.body = tk.Frame(self.root, bg=C["bg"])
        self.body.grid(row=1, column=0, sticky="nsew")
        self.pages = {
            "wait": self._build_wait(self.body),
            "live": self._build_live(self.body),
            "settings": self._build_settings(self.body),
        }

        # Pied de page
        self.footer = tk.Frame(self.root, bg=C["low"])
        self.footer.grid(row=2, column=0, sticky="ew")
        tk.Label(self.footer, text="%s v%s · PyAV / FFmpeg" % (t("engine"), self.version), bg=C["low"],
                 fg=C["outline"], font=(MONO, 9)).pack(side="left", padx=18, pady=6)
        self.stats_label = tk.Label(self.footer, text="", bg=C["low"], fg=C["outline"], font=(MONO, 9))
        self.stats_label.pack(side="right", padx=18)

        self.show(self.page)
        self.dirty = True

    def _build_wait(self, parent):
        f = tk.Frame(parent, bg=C["bg"])
        card = tk.Frame(f, bg=C["card"], padx=36, pady=30)
        card.place(relx=0.5, rely=0.5, anchor="center")
        tk.Label(card, text="●  " + t("wait_ready"), bg=C["card"], fg=C["cyan"], font=(FONT, 10, "bold")).pack(anchor="w")
        tk.Label(card, text=t("wait_title"), bg=C["card"], fg=C["text"], font=(FONT, 26, "bold")).pack(anchor="w", pady=(8, 2))
        tk.Label(card, text=t("wait_sub"), bg=C["card"], fg=C["muted"], font=(FONT, 11), wraplength=560,
                 justify="left").pack(anchor="w", pady=(0, 18))
        tk.Label(card, text=t("addr_label"), bg=C["card"], fg=C["muted"], font=(FONT, 10, "bold")).pack(anchor="w")
        row = tk.Frame(card, bg=C["card"])
        row.pack(fill="x", pady=(4, 2))
        self.entry = tk.Entry(row, textvariable=self.addr_var, bg=C["low"], fg=C["text"], insertbackground=C["primary"],
                              relief="flat", font=(MONO, 15), width=30, highlightthickness=1,
                              highlightbackground=C["highest"], highlightcolor=C["coral"])
        self.entry.pack(side="left", ipady=8, padx=(0, 10))
        self.entry.bind("<Return>", lambda e: self.connect())
        self.connect_btn = flat_button(row, t("btn_connect"), self.connect, "primary", 11, 20, 8)
        self.connect_btn.pack(side="left")
        flat_button(row, t("btn_cancel"), self.disconnect, "ghost", 10, 12, 8).pack(side="left", padx=(8, 0))
        tk.Label(card, text=t("addr_hint"), bg=C["card"], fg=C["outline"], font=(MONO, 9)).pack(anchor="w")
        tk.Label(card, text=t("pin_label"), bg=C["card"], fg=C["muted"], font=(FONT, 10, "bold")).pack(anchor="w", pady=(12, 0))
        self.pin_entry = tk.Entry(card, textvariable=self.pin_var, bg=C["low"], fg=C["cyan"], insertbackground=C["primary"],
                                  relief="flat", font=(MONO, 18, "bold"), width=8, highlightthickness=1,
                                  highlightbackground=C["highest"], highlightcolor=C["coral"])
        self.pin_entry.pack(anchor="w", ipady=6, pady=(4, 2))
        self.pin_entry.bind("<Return>", lambda e: self.connect())
        tk.Label(card, text=t("pin_hint"), bg=C["card"], fg=C["outline"], font=(FONT, 9), wraplength=560,
                 justify="left").pack(anchor="w")
        self.wait_status = tk.Label(card, text="", bg=C["card"], fg=C["amber"], font=(FONT, 10), justify="left", wraplength=560)
        self.wait_status.pack(anchor="w", pady=(10, 0))

        tk.Frame(card, bg=C["highest"], height=1).pack(fill="x", pady=18)
        tk.Label(card, text=t("steps_title"), bg=C["card"], fg=C["text"], font=(FONT, 12, "bold")).pack(anchor="w", pady=(0, 8))
        for i in (1, 2, 3):
            r = tk.Frame(card, bg=C["card"])
            r.pack(anchor="w", pady=4, fill="x")
            tk.Label(r, text=str(i), bg=C["highest"], fg=C["primary"], font=(FONT, 10, "bold"), width=3).pack(side="left", anchor="n")
            col = tk.Frame(r, bg=C["card"])
            col.pack(side="left", padx=12)
            tk.Label(col, text=t("step%d_t" % i), bg=C["card"], fg=C["text"], font=(FONT, 11, "bold")).pack(anchor="w")
            tk.Label(col, text=t("step%d_d" % i), bg=C["card"], fg=C["muted"], font=(FONT, 10), wraplength=500,
                     justify="left").pack(anchor="w")
        tk.Label(card, text=t("shortcuts"), bg=C["card"], fg=C["outline"], font=(MONO, 9)).pack(anchor="w", pady=(14, 0))
        return f

    def _build_live(self, parent):
        f = tk.Frame(parent, bg=C["video_bg"])
        self.canvas = tk.Canvas(f, bg=C["video_bg"], highlightthickness=0)
        self.canvas.pack(fill="both", expand=True)
        self.img_item = self.canvas.create_image(0, 0, anchor="center")
        self.msg_item = self.canvas.create_text(0, 0, text="", fill=C["text"], font=(FONT, 14), justify="center")
        self.hud_bg = self.canvas.create_rectangle(0, 0, 0, 0, fill=C["bg"], outline="", stipple="gray50")
        self.hud_left = self.canvas.create_text(16, 20, anchor="w", text="", fill=C["text"], font=(MONO, 11, "bold"))
        self.hud_right = self.canvas.create_text(0, 20, anchor="e", text="", fill=C["cyan"], font=(MONO, 10))
        self.hint_item = self.canvas.create_text(0, 0, anchor="s", text="", fill=C["muted"], font=(FONT, 10))

        self.ctl = tk.Frame(self.canvas, bg=C["high"], padx=10, pady=8)
        self.ctl_win = self.canvas.create_window(0, 0, window=self.ctl, anchor="s")
        self.pause_btn = flat_button(self.ctl, "", self.toggle_pause, "soft", 10, 12, 6)
        self.pause_btn.pack(side="left", padx=3)
        self.sound_btn = flat_button(self.ctl, "", self.toggle_mute, "soft", 10, 12, 6)
        self.sound_btn.pack(side="left", padx=3)
        self.vol = tk.Scale(self.ctl, from_=0, to=100, orient="horizontal", showvalue=0, length=110, bg=C["high"],
                            troughcolor=C["highest"], highlightthickness=0, bd=0, sliderrelief="flat",
                            activebackground=C["coral"], command=self._on_volume)
        self.vol.set(self.s["volume"])
        self.vol.pack(side="left", padx=6)
        self.aspect_btn = flat_button(self.ctl, "", self.cycle_aspect, "soft", 10, 12, 6)
        self.aspect_btn.pack(side="left", padx=3)
        flat_button(self.ctl, "⟳  " + t("ctl_rotate"), self.rotate, "soft", 10, 12, 6).pack(side="left", padx=3)
        self.fs_btn = flat_button(self.ctl, "", self.toggle_fullscreen, "soft", 10, 12, 6)
        self.fs_btn.pack(side="left", padx=3)
        flat_button(self.ctl, "⏻  " + t("ctl_stop"), self.disconnect, "danger", 10, 14, 6).pack(side="left", padx=(8, 0))
        self.canvas.bind("<Configure>", self._on_canvas_resize)
        self.canvas.bind("<Double-Button-1>", lambda e: self.toggle_fullscreen())
        self._refresh_controls()
        return f

    # ───────────────────────── paramètres ─────────────────────────

    def _card(self, parent, title):
        card = tk.Frame(parent, bg=C["card"], padx=22, pady=16)
        card.pack(fill="x", pady=8)
        tk.Label(card, text=title, bg=C["card"], fg=C["primary"], font=(FONT, 12, "bold")).pack(anchor="w", pady=(0, 8))
        return card

    def _segmented(self, parent, options, current, on_select):
        row = tk.Frame(parent, bg=C["low"], padx=3, pady=3)
        buttons = {}

        def select(value):
            for v, b in buttons.items():
                on = v == value
                b.configure(bg=C["coral"] if on else C["low"], fg=C["on_coral"] if on else C["text"],
                            font=(FONT, 10, "bold" if on else "normal"))
            on_select(value)

        for value, label in options:
            b = tk.Button(row, text=label, relief="flat", bd=0, padx=16, pady=7, cursor="hand2",
                          bg=C["low"], fg=C["text"], activebackground=C["highest"], font=(FONT, 10),
                          command=lambda v=value: select(v))
            b.pack(side="left", padx=2)
            buttons[value] = b
        for v, b in buttons.items():
            if v == current:
                b.configure(bg=C["coral"], fg=C["on_coral"], font=(FONT, 10, "bold"))
        return row

    def _check(self, parent, title, desc, key, after=None):
        var = tk.BooleanVar(value=bool(self.s[key]))

        def changed():
            self.s.set(key, bool(var.get()))
            if after:
                after()

        tk.Checkbutton(parent, text=title, variable=var, command=changed, bg=C["card"], fg=C["text"],
                       selectcolor=C["low"], activebackground=C["card"], activeforeground=C["text"],
                       font=(FONT, 11, "bold"), anchor="w", highlightthickness=0).pack(anchor="w")
        tk.Label(parent, text=desc, bg=C["card"], fg=C["muted"], font=(FONT, 10), wraplength=700, justify="left").pack(anchor="w", padx=22, pady=(0, 8))

    def _build_settings(self, parent):
        outer = tk.Frame(parent, bg=C["bg"])
        canvas = tk.Canvas(outer, bg=C["bg"], highlightthickness=0)
        bar = tk.Scrollbar(outer, orient="vertical", command=canvas.yview)
        canvas.configure(yscrollcommand=bar.set)
        bar.pack(side="right", fill="y")
        canvas.pack(side="left", fill="both", expand=True)
        inner = tk.Frame(canvas, bg=C["bg"], padx=40, pady=20)
        win = canvas.create_window(0, 0, window=inner, anchor="nw")
        inner.bind("<Configure>", lambda e: canvas.configure(scrollregion=canvas.bbox("all")))
        canvas.bind("<Configure>", lambda e: canvas.itemconfigure(win, width=min(e.width, 900)))
        canvas.bind("<Enter>", lambda e: canvas.bind_all("<MouseWheel>", lambda ev: canvas.yview_scroll(int(-ev.delta / 120), "units")))
        canvas.bind("<Leave>", lambda e: canvas.unbind_all("<MouseWheel>"))

        tk.Label(inner, text=t("set_title"), bg=C["bg"], fg=C["text"], font=(FONT, 22, "bold")).pack(anchor="w")
        tk.Label(inner, text=t("set_sub"), bg=C["bg"], fg=C["muted"], font=(FONT, 11)).pack(anchor="w", pady=(0, 6))

        c = self._card(inner, t("set_video"))
        self._check(c, t("set_autofs"), t("set_autofs_d"), "auto_fullscreen")
        self._check(c, t("set_top"), t("set_top_d"), "always_on_top",
                    lambda: self.root.attributes("-topmost", bool(self.s["always_on_top"])))
        tk.Label(c, text=t("set_aspect"), bg=C["card"], fg=C["text"], font=(FONT, 11, "bold")).pack(anchor="w", pady=(6, 4))
        self._segmented(c, [("fit", t("aspect_fit")), ("stretch", t("aspect_stretch")), ("center", t("aspect_center"))],
                        self.s["aspect"], self._set_aspect).pack(anchor="w")
        tk.Label(c, text=t("set_aspect_d"), bg=C["card"], fg=C["muted"], font=(FONT, 10), wraplength=700, justify="left").pack(anchor="w", pady=(6, 0))

        c = self._card(inner, t("set_stream"))
        tk.Label(c, text=t("set_latency"), bg=C["card"], fg=C["text"], font=(FONT, 11, "bold")).pack(anchor="w", pady=(0, 4))
        self._segmented(c, [("low", t("lat_low")), ("balanced", t("lat_balanced")), ("stable", t("lat_stable"))],
                        self.s["latency"], self._set_latency).pack(anchor="w")
        tk.Label(c, text=t("set_latency_d"), bg=C["card"], fg=C["muted"], font=(FONT, 10), wraplength=700, justify="left").pack(anchor="w", pady=(6, 0))

        c = self._card(inner, t("set_audio"))
        tk.Label(c, text=t("set_volume"), bg=C["card"], fg=C["text"], font=(FONT, 11, "bold")).pack(anchor="w")
        vol = tk.Scale(c, from_=0, to=100, orient="horizontal", length=320, showvalue=1, bg=C["card"], fg=C["text"],
                       troughcolor=C["highest"], highlightthickness=0, bd=0, activebackground=C["coral"],
                       command=self._on_volume)
        vol.set(self.s["volume"])
        vol.pack(anchor="w", pady=(0, 6))
        self._check(c, t("set_mute"), "", "muted", self._apply_audio)

        c = self._card(inner, t("set_lang"))
        self._segmented(c, [("fr", t("lang_fr")), ("en", t("lang_en"))], get_language(), self.change_language).pack(anchor="w")

        c = self._card(inner, t("set_about"))
        tk.Label(c, text=t("about_version", self.version), bg=C["card"], fg=C["text"], font=(FONT, 11, "bold")).pack(anchor="w")
        tk.Label(c, text=t("about_rights"), bg=C["card"], fg=C["muted"], font=(FONT, 10)).pack(anchor="w")
        tk.Label(c, text=t("about_contact"), bg=C["card"], fg=C["muted"], font=(FONT, 10)).pack(anchor="w", pady=(0, 8))
        row = tk.Frame(c, bg=C["card"])
        row.pack(anchor="w")
        flat_button(row, t("btn_mail"), lambda: webbrowser.open("mailto:" + MAIL + "?subject=ViewCast")).pack(side="left", padx=(0, 6))
        flat_button(row, t("btn_privacy"), lambda: self.show_text(t("btn_privacy"), PRIVACY[get_language()])).pack(side="left", padx=6)
        flat_button(row, t("btn_terms"), lambda: self.show_text(t("btn_terms"), TERMS[get_language()])).pack(side="left", padx=6)
        flat_button(row, t("btn_faq"), lambda: self.show_text(t("faq_title"), t("faq_body"))).pack(side="left", padx=6)

        c = self._card(inner, t("shortcuts_title"))
        tk.Label(c, text=t("shortcuts"), bg=C["card"], fg=C["muted"], font=(MONO, 10)).pack(anchor="w")
        return outer

    def show_text(self, title, body):
        win = tk.Toplevel(self.root)
        win.title(title)
        win.configure(bg=C["card"])
        win.geometry("640x520")
        win.transient(self.root)
        box = tk.Text(win, bg=C["card"], fg=C["muted"], wrap="word", relief="flat", font=(FONT, 10), padx=18, pady=14,
                      highlightthickness=0)
        bar = tk.Scrollbar(win, command=box.yview)
        box.configure(yscrollcommand=bar.set)
        box.insert("1.0", body)
        box.configure(state="disabled")
        bar.pack(side="right", fill="y")
        flat_button(win, t("btn_close"), win.destroy, "primary").pack(side="bottom", pady=10)
        box.pack(side="left", fill="both", expand=True)

    # ───────────────────────── actions ─────────────────────────

    def show(self, page):
        self.page = page
        for k, f in self.pages.items():
            f.pack_forget()
        self.pages[page].pack(fill="both", expand=True)
        for k, lb in self.nav_labels.items():
            lb.configure(fg=C["primary"] if k == page else C["muted"],
                         font=(FONT, 11, "bold" if k == page else "normal"))
        self.dirty = True

    def connect(self):
        text = self.addr_var.get().strip()
        pin = self.pin_var.get().strip()
        try:
            host, port = parse_target(text)
        except ValueError:
            self.msg = None
            self.status_text = t("msg_invalid_addr", text)
            self._refresh_wait()
            return
        if pin and not valid_pin(pin):
            self.msg = None
            self.status_text = t("msg_pin_format")
            self._refresh_wait()
            return
        if vc_stream.av is None:
            self.msg = None
            self.status_text = t("msg_no_av")
            self._refresh_wait()
            return
        self.s.set("last_address", text)
        self._stop_stream()
        self.msg = None
        self.status_text = ""
        if pin:     # mode sécurisé : le flux passe par un pont local chiffré vers le téléphone
            self.proxy = SecureProxy(host, port, pin)
            url = self.proxy.local_url
        else:       # mode ouvert (téléphone sans protection PIN)
            self.proxy = None
            url = build_url(text)
        self.secure_mode = bool(pin)
        self.player = AudioPlayer(self.s["volume"] / 100.0, bool(self.s["muted"]), self.s["latency"])
        self.reader = StreamReader(url, self.s["latency"], self.player.push if self.player.available else None,
                                   self._on_reader_message, self.proxy)
        self.shown_live = False
        self.paused = False
        self.rotation = 0
        self.last_seq = -1
        self.addr_chip.configure(text="%s:%d" % (host, port))
        self.reader.start()
        self.show("wait")

    def _on_reader_message(self, kind, *args):   # appelé depuis le thread de lecture
        self.msg = (kind, args)

    def _stop_stream(self):
        if self.reader:
            self.reader.stop()
            self.reader = None
        if self.player:
            self.player.close()
            self.player = None
        if self.proxy:
            self.proxy.close()
            self.proxy = None

    def disconnect(self):
        self._stop_stream()
        self.msg = None
        self.status_text = ""
        self.pin_var.set("")
        self.addr_chip.configure(text="")
        if self.fullscreen:
            self.toggle_fullscreen(False)
        self.show("wait")
        self._refresh_wait()

    def toggle_fullscreen(self, value=None):
        self.fullscreen = (not self.fullscreen) if value is None else bool(value)
        self.root.attributes("-fullscreen", self.fullscreen)
        self.last_motion = time.monotonic()
        self._set_chrome(not self.fullscreen)
        self._refresh_controls()
        self.dirty = True

    def _set_chrome(self, visible):
        if visible:
            self.topbar.grid()
            self.footer.grid()
        else:
            self.topbar.grid_remove()
            self.footer.grid_remove()

    def toggle_pause(self):
        self.paused = not self.paused
        if self.player:
            self.player.muted = self.paused or bool(self.s["muted"])
        self._refresh_controls()

    def toggle_mute(self):
        self.s.set("muted", not self.s["muted"])
        self._apply_audio()
        self._refresh_controls()

    def _apply_audio(self):
        if self.player:
            self.player.muted = bool(self.s["muted"]) or self.paused
            self.player.volume = self.s["volume"] / 100.0
        self._refresh_controls()

    def _on_volume(self, value):
        self.s.set("volume", int(float(value)))
        self._apply_audio()

    def cycle_aspect(self):
        nxt = ASPECTS[(ASPECTS.index(self.s["aspect"]) + 1) % len(ASPECTS)]
        self._set_aspect(nxt)

    def _set_aspect(self, value):
        self.s.set("aspect", value)
        self._refresh_controls()
        self.dirty = True

    def _set_latency(self, value):
        self.s.set("latency", value)
        if self.reader:   # appliqué tout de suite : on se reconnecte avec le nouveau tampon
            self.connect()

    def rotate(self):
        self.rotation = (self.rotation + 90) % 360
        self.dirty = True

    def change_language(self, code):
        if code == get_language():
            return
        set_language(code)
        self.s.set("language", code)
        page = self.page
        self._build()
        self.show(page)

    def _refresh_controls(self):
        self.pause_btn.configure(text=("▶  " + t("ctl_play")) if self.paused else ("⏸  " + t("ctl_pause")))
        self.sound_btn.configure(text="🔇  " + t("sound_off") if self.s["muted"] else "🔊  " + t("sound_on"))
        self.aspect_btn.configure(text=t("ctl_aspect") + " : " + t("aspect_" + self.s["aspect"]))
        self.fs_btn.configure(text=("⛶  " + t("ctl_exit_fullscreen")) if self.fullscreen else ("⛶  " + t("ctl_fullscreen")))

    def _refresh_wait(self):
        if hasattr(self, "wait_status"):
            self.wait_status.configure(text=self._status_message(), fg=C["error"] if self.msg and self.msg[0] in ("unreachable", "no_av", "auth_failed", "locked", "protocol") else C["amber"])

    def _status_message(self):
        if self.msg is None:
            return self.status_text
        kind, args = self.msg
        if kind == "connecting":
            return t("msg_connecting", *args)
        if kind == "unreachable":
            return t("msg_unreachable", *args)
        if kind == "interrupted":
            return t("msg_interrupted")
        if kind == "no_av":
            return t("msg_no_av")
        if kind == "auth_failed":
            return t("msg_auth_failed")
        if kind == "locked":
            return t("msg_locked")
        if kind == "protocol":
            return t("msg_protocol")
        return ""

    def quit(self):
        self._stop_stream()
        self.root.destroy()

    # ───────────────────────── clavier / souris ─────────────────────────

    def _on_key(self, event):
        if isinstance(self.root.focus_get(), tk.Entry):
            return
        key = (event.keysym or "").lower()
        if key == "escape":
            if self.fullscreen:
                self.toggle_fullscreen(False)
            elif self.reader and self.page == "live":
                self.disconnect()
        elif key == "f" and self.page == "live":
            self.toggle_fullscreen()
        elif key == "m":
            self.toggle_mute()
        elif key == "space" and self.page == "live":
            self.toggle_pause()

    def _on_motion(self, event):
        self.last_motion = time.monotonic()

    def _on_canvas_resize(self, event):
        w, h = event.width, event.height
        self.canvas.coords(self.img_item, w // 2, h // 2)
        self.canvas.coords(self.msg_item, w // 2, h // 2)
        self.canvas.coords(self.hud_bg, 0, 0, w, 40)
        self.canvas.coords(self.hud_left, 16, 20)
        self.canvas.coords(self.hud_right, w - 16, 20)
        self.canvas.coords(self.ctl_win, w // 2, h - 20)
        self.canvas.coords(self.hint_item, w // 2, h - 80)
        self.dirty = True

    # ───────────────────────── boucle d'affichage ─────────────────────────

    def _tick(self):
        try:
            self._update()
        except Exception:
            traceback.print_exc()
        self.root.after(15, self._tick)

    def _update(self):
        now = time.monotonic()
        reader = self.reader
        seq, frame = (reader.get() if reader else (0, None))

        # État de connexion (puce du haut + messages)
        if reader is None:
            chip, color = t("status_waiting"), C["amber"]
        elif reader.connected and frame is not None:
            chip, color = t("status_active"), C["cyan"]
        else:
            chip, color = t("status_connecting"), C["amber"]
        self.status_chip.configure(text="●  " + chip, fg=color)

        if frame is not None and not self.shown_live:
            self.shown_live = True
            self.show("live")
            if self.s["auto_fullscreen"] and not self.fullscreen:
                self.toggle_fullscreen(True)
        if self.page == "wait":
            self._refresh_wait()

        # Mesures (≈ 2 fois par seconde)
        if reader and now - self.hud_updated > 0.5:
            self.hud_updated = now
            self.meter.update(reader.frames_decoded, reader.bytes_received)
            w, h = reader.size
            label = "%dp" % min(w, h) if w else "—"
            codecs = " · ".join(x.upper() for x in (reader.video_codec, reader.audio_codec) if x)
            self.stats_label.configure(text="%s %.1f Mb/s  ·  %s %.0f FPS  ·  %s" % (t("hud_rate").upper(), self.meter.mbps, label, self.meter.fps, codecs))
            sound = t("hud_no_audio") if reader.has_audio is False else (t("sound_off") if self.s["muted"] else t("sound_on"))
            codecs = (t("secure_badge") if self.secure_mode else t("open_badge")) + " · " + codecs
            self.canvas.itemconfigure(self.hud_left, text="%s @ %.0f FPS" % (label, self.meter.fps))
            self.canvas.itemconfigure(self.hud_right, text="%s %.1f Mb/s  ·  %s  ·  %s" % (t("hud_rate"), self.meter.mbps, codecs, sound))

        # Message au centre quand il n'y a pas d'image
        self.canvas.itemconfigure(self.msg_item, text="" if frame is not None else self._status_message())

        # Image
        if frame is not None and not self.paused and (seq != self.last_seq or self.dirty):
            self._render(frame)
            self.last_seq = seq
            self.dirty = False
        elif self.paused and self.dirty:
            self.canvas.itemconfigure(self.msg_item, text=t("paused"))
            self.dirty = False

        # Plein écran : commandes masquées après 2,5 s sans mouvement
        hide = self.fullscreen and self.page == "live" and now - self.last_motion > 2.5
        if hide != self.chrome_hidden:
            self.chrome_hidden = hide
            state = "hidden" if hide else "normal"
            for item in (self.ctl_win, self.hud_bg, self.hud_left, self.hud_right):
                self.canvas.itemconfigure(item, state=state)
            self.canvas.itemconfigure(self.hint_item, text="" if hide or not self.fullscreen else t("fs_hint"))
            self.canvas.configure(cursor="none" if hide else "")
        elif self.fullscreen and not hide:
            self.canvas.itemconfigure(self.hint_item, text=t("fs_hint"))

    def _render(self, frame):
        if self.rotation:
            code = {90: cv2.ROTATE_90_CLOCKWISE, 180: cv2.ROTATE_180, 270: cv2.ROTATE_90_COUNTERCLOCKWISE}[self.rotation]
            frame = cv2.rotate(frame, code)
        cw = max(2, self.canvas.winfo_width())
        ch = max(2, self.canvas.winfo_height())
        fh, fw = frame.shape[:2]
        mode = self.s["aspect"]
        if mode == "stretch":
            img = cv2.resize(frame, (cw, ch), interpolation=cv2.INTER_LINEAR)
        elif mode == "center":
            img = frame
            if fw > cw or fh > ch:   # on recadre au centre (échelle 1:1, sans déformation)
                x0, y0 = max(0, (fw - cw) // 2), max(0, (fh - ch) // 2)
                img = frame[y0:y0 + min(ch, fh), x0:x0 + min(cw, fw)]
        else:  # fit : barres noires, sans déformation
            k = min(cw / fw, ch / fh)
            img = cv2.resize(frame, (max(2, int(fw * k)), max(2, int(fh * k))), interpolation=cv2.INTER_LINEAR)
        rgb = cv2.cvtColor(img, cv2.COLOR_BGR2RGB)
        self.photo = ImageTk.PhotoImage(Image.fromarray(rgb))   # on garde une référence (sinon l'image disparaît)
        self.canvas.itemconfigure(self.img_item, image=self.photo)
        self.canvas.coords(self.img_item, cw // 2, ch // 2)
