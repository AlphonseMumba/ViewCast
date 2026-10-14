"""ViewCast Viewer : reçoit en direct l'écran (et le son) d'un téléphone Android.

Usage :
    ViewCastViewer.exe                       (la fenêtre demande l'adresse)
    ViewCastViewer.exe 192.168.43.1          (IP seule, port 8554 par défaut)
    ViewCastViewer.exe rtsp://192.168.43.1:8554/stream

Raccourcis : F plein écran · M muet · Espace pause · Échap quitter/arrêter.
© 2026 Alphonse MUMBA — mumbalpha@gmail.com
"""
import sys
import traceback

__version__ = "1.4.0"


def _show_error(message):
    print(message, file=sys.stderr)
    try:
        import tkinter as tk
        from tkinter import messagebox

        root = tk.Tk()
        root.withdraw()
        messagebox.showerror("ViewCast Viewer", message, parent=root)
        root.destroy()
    except Exception:
        pass


def main():
    try:
        import tkinter as tk

        from vc_settings import Settings
        from vc_ui import ViewCastApp

        root = tk.Tk()
        ViewCastApp(root, Settings(), __version__, sys.argv[1] if len(sys.argv) > 1 else "")
        root.mainloop()
    except Exception:
        _show_error("Erreur inattendue / Unexpected error:\n\n" + traceback.format_exc())


if __name__ == "__main__":
    main()
