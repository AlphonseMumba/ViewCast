import sys
import cv2
import tkinter as tk
from tkinter import simpledialog

def main():
    print("=== ViewCast Viewer ===")
    root = tk.Tk()
    root.withdraw()  # Hide the main window

    ip = sys.argv[1] if len(sys.argv) > 1 else simpledialog.askstring("ViewCast Viewer", "Entrez l'adresse IP du téléphone (ex: 192.168.49.1):")
    if not ip:
        print("IP manquante.")
        return

    url = f"rtsp://{ip}:8554/stream"
    print(f"Connexion au flux: {url}")
    cap = cv2.VideoCapture(url, cv2.CAP_FFMPEG)
    if not cap.isOpened():
        print("Impossible d'ouvrir le flux. Vérifiez l'IP, le réseau, et que la diffusion est lancée sur le téléphone.")
        return
    print("Appuyez sur ECHAP pour quitter.")
    while True:
        ok, frame = cap.read()
        if not ok:
            print("Flux interrompu.")
            break
        cv2.imshow("ViewCast Viewer", frame)
        if cv2.waitKey(1) & 0xFF == 27:
            break
    cap.release()
    cv2.destroyAllWindows()

if __name__ == "__main__":
    main()
