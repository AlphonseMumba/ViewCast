# Mise en réseau hors connexion

## Android Wi‑Fi Direct
- Le téléphone peut créer un groupe Wi‑Fi Direct (P2P).
- En mode Group Owner, l’IP du téléphone est souvent 192.168.49.1.
- Connectez l’ordinateur portable au réseau P2P si possible (certains laptops ne supportent pas Wi‑Fi Direct).

Alternative: créez un "Point d'accès" sur le téléphone et connectez l'ordinateur au SSID affiché.

## iOS
- iOS n’expose pas Wi‑Fi Direct. Utilisez "Partage de connexion" (Hotspot personnel) ou un routeur local sans Internet.
- Relevez l’IP de l’iPhone (icône "i" dans le Wi‑Fi).

## Lecture du flux
- L’URL RTSP est: `rtsp://<IP_DU_TÉLÉPHONE>:8554/stream`
- Exemple: `rtsp://192.168.49.1:8554/stream`

## Dépannage rapide
- Pare-feu: vérifiez que le port 8554 n’est pas bloqué.
- Latence: utilisez RTSP sur TCP côté lecteur (ffplay `-rtsp_transport tcp`), résol. 720p.
- Si écran noir: assurez-vous que la diffusion est démarrée et que l’IP est correcte.
