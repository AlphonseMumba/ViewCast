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

```bash
# Remplacez <IP_DU_TÉLÉPHONE> par l'adresse IP de votre téléphone
ffplay -rtsp_transport tcp rtsp://<IP_DU_TÉLÉPHONE>:8554/stream
```

## Plateformes

- Android: 8.0 (API 26) ou supérieur, ARM64.
- iOS: iOS 11.0 ou supérieur, ARM64.
- Windows: 10 ou supérieur, x64.
- macOS: 10.13 (High Sierra) ou supérieur, x64.

## Réseau hors connexion

### Android

1. Activez le Wi‑Fi Direct sur le téléphone.
2. Connectez l’ordinateur au réseau Wi‑Fi Direct du téléphone.
3. Ouvrez ViewCast sur le téléphone et démarrez la diffusion.
4. Sur l’ordinateur, utilisez l’URL `rtsp://<IP_DU_TÉLÉPHONE>:8554/stream` dans le viewer.

### iOS

1. Activez le "Partage de connexion" sur l’iPhone.
2. Connectez l’ordinateur au réseau Hotspot de l’iPhone.
3. Ouvrez ViewCast sur l’iPhone et démarrez la diffusion.
4. Sur l’ordinateur, utilisez l’URL `rtsp://<IP_DU_TÉLÉPHONE>:8554/stream` dans le viewer.

## Sécurité

- Changez le mot de passe par défaut du Wi‑Fi Direct sur Android.
- Utilisez un mot de passe fort pour le Hotspot sur iOS.
- Ne partagez pas l’URL RTSP publiquement.

## Builds automatiques (CI)

[![Android CI](https://github.com/<user>/<repo>/actions/workflows/android.yml/badge.svg)](github/workflows/android.yml)
[![iOS CI](https://github.com/<user>/<repo>/actions/workflows/ios.yml/badge.svg)](github/workflows/ios.yml)
[![Desktop CI](https://github.com/<user>/<repo>/actions/workflows/desktop.yml/badge.svg)](github/workflows/desktop.yml)

## Développeurs

Pour contribuer:
1. Fork ce dépôt.
2. Créez une branche pour votre fonctionnalité (`git checkout -b ma-fonctionnalité`).
3. Commitez vos changements (`git commit -m 'Ajoute une fonctionnalité'`).
4. Poussez vers votre fork (`git push origin ma-fonctionnalité`).
5. Ouvrez une Pull Request.

## Licence

Voir [security/LICENCE](security/LICENCE).
