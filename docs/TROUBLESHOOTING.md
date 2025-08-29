# Dépannage

- L’ordinateur ne voit pas le téléphone:
  - Sur Android, ouvrez les paramètres Wi‑Fi Direct et reformez le groupe. Essayez le mode point d'accès.
- Le flux ne s’ouvre pas:
  - Vérifiez que l’application ViewCast a démarré la "diffusion" et que la permission de capture d'écran est accordée.
  - Testez avec `ffplay -rtsp_transport tcp rtsp://<IP>:8554/stream` pour isoler OpenCV.
- Qualité mauvaise / saccades:
  - Réduisez la résolution (720p), baissez le bitrate (4–6 Mbps), I‑frame toutes 1–2s.
- iOS:
  - Le viewer desktop doit être sur le même réseau que l’iPhone (Hotspot ou AP local).
  - Distribution: passe par TestFlight, pas de .ipa installable directement pour le public.
