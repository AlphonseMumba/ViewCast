"""Réglages enregistrés localement : %APPDATA%\\ViewCast\\settings.json (aucune donnée n'est envoyée ailleurs)."""
import json
import os

CONFIG_DIR = os.path.join(os.environ.get("APPDATA") or os.path.expanduser("~"), "ViewCast")
CONFIG_FILE = os.path.join(CONFIG_DIR, "settings.json")

DEFAULTS = {
    "language": "fr",        # fr (principale) | en
    "auto_fullscreen": False,
    "always_on_top": False,
    "aspect": "fit",         # fit | stretch | center
    "latency": "low",        # low | balanced | stable
    "volume": 80,            # 0..100
    "muted": False,
    "last_address": "",
}


class Settings:
    def __init__(self, path=CONFIG_FILE):
        self.path = path
        self.data = dict(DEFAULTS)
        self.load()

    def load(self):
        try:
            with open(self.path, encoding="utf-8") as f:
                stored = json.load(f)
            for k, default in DEFAULTS.items():
                v = stored.get(k, default)
                if isinstance(v, type(default)):  # ignore les valeurs de mauvais type
                    self.data[k] = v
        except (OSError, ValueError):
            pass
        if self.data["aspect"] not in ("fit", "stretch", "center"):
            self.data["aspect"] = "fit"
        if self.data["latency"] not in ("low", "balanced", "stable"):
            self.data["latency"] = "low"
        if self.data["language"] not in ("fr", "en"):
            self.data["language"] = "fr"
        self.data["volume"] = max(0, min(100, int(self.data["volume"])))

    def save(self):
        try:
            os.makedirs(os.path.dirname(self.path), exist_ok=True)
            with open(self.path, "w", encoding="utf-8") as f:
                json.dump(self.data, f, ensure_ascii=False, indent=2)
        except OSError:
            pass

    def __getitem__(self, key):
        return self.data[key]

    def set(self, key, value):
        self.data[key] = value
        self.save()
