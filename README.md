# ViewCast

Diffusez l’écran de votre smartphone vers un ordinateur portable en réseau local, même hors connexion:
- Android: Wi‑Fi Direct (ou point d’accès), capture d’écran (MediaProjection), encodage H.264, diffusion RTSP.
- iOS: ReplayKit + H.264 + serveur RTSP (utilise Hotspot/AP local, iOS ne propose pas Wi‑Fi Direct).
- Desktop (Windows/macOS/Linux): application "Viewer" simple basée sur OpenCV/FFmpeg.

⚠️ ViewCast inclut un serveur RTSP minimal pour démonstration. Pour un usage en production, utilisez une pile RTSP/RTP complète (live555, GStreamer) ou WebRTC.

## Sommaire
- Fonctionnement
- Téléchargements
- Démarrage rapide (non initiés)
- Plateformes
- Réseau hors connexion
- Sécurité
- Builds automatiques (CI)
- Développeurs
- Licence

---

## Fonctionnement

1. Le téléphone encode l’écran en vidéo H.264 (matériel).
2. Il expose un serveur RTSP sur le port 8554.
3. Le viewer (ordinateur) se connecte à l’URL `rtsp://<IP_DU_TÉLÉPHONE>:8554/stream` et affiche la vidéo.

## Téléchargements

Rendez-vous dans l’onglet "Releases" de GitHub:
- Android: app-debug.apk (installation directe), app-release.aab (pour Play Store).
- Windows: ViewCastViewer.exe.
- macOS: binaire ViewCastViewer.
- iOS: archive de build; pour installer sur iPhone, passez par TestFlight (voir plus bas).

## Démarrage rapide (non initiés)

1) Mettre les appareils sur le même réseau local:
- Android: laissez ViewCast créer/joindre un groupe Wi‑Fi Direct, ou activez le "Point d’accès" du téléphone et connectez l’ordinateur dessus.
- iOS: activez le "Partage de connexion" (Hotspot) sur l’iPhone et connectez l’ordinateur.

2) Sur le téléphone:
- Android:
  - Ouvrez ViewCast.
  - Appuyez sur "Découvrir appareils" (Wi‑Fi Direct), puis connectez-vous au PC si compatible; sinon, utilisez le point d’accès.
  - Appuyez sur "Démarrer diffusion" et acceptez la permission de capture d’écran.
  - Notez l’IP indiquée (souvent 192.168.49.1 en Wi‑Fi Direct).
- iOS:
  - Ouvrez ViewCast, appuyez "Démarrer diffusion".
  - Notez l’IP de l’iPhone dans les réglages Wi‑Fi (icône "i").

3) Sur l’ordinateur:
- Windows/macOS:
  - Lancez ViewCastViewer (depuis les Releases).
  - Entrez l’adresse IP du téléphone quand on vous la demande.
  - Vous devriez voir l’écran du téléphone.

Astuce: si ça ne marche pas, essayez avec FFmpeg/ffplay:
