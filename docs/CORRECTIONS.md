# Corrections apportées (Android + viewer Windows)

## Android
- **Build** : `gradlew` régénéré (il faisait 0 octet), Gradle 8.7 / AGP 8.5.2 / compileSdk 34, icône de notification 0 octet remplacée par un vecteur, `strings.xml` en double et layout inutilisé supprimés, dépendance coroutines explicite, `@OptIn` Material3.
- **Manifest** : suppression de `package=` (namespace Gradle), de `RECORD_AUDIO`/`microphone` (aucun audio capturé), de `usesCleartextTraffic`, ajout de `POST_NOTIFICATIONS`.
- **Serveur RTSP/RTP** (`rtsp/RtspServer.kt`, `rtsp/RtpPacketizer.kt`) : réécrit. Vrai RTP (en-tête, séquence, horloge 90 kHz, marqueur), FU-A pour les grosses images, NAL sans start code, SDP valide (SPS/PPS en base64 sans start code, `profile-level-id`), envoi seulement après `PLAY`, démarrage sur image clé (IDR), file d'envoi par client (un client lent ne bloque plus l'encodeur), UDP refusé (461) pour forcer le TCP.
- **Capture** (`capture/ScreenCapture.kt`) : callback `MediaProjection` (obligatoire Android 14), extraction fiable de SPS/PPS, demande d'image clé à la connexion d'un client, répétition d'image sur écran statique, rapport d'aspect de l'écran respecté (plus d'image étirée).
- **Service** : `startForeground` de type `mediaProjection` appelé en premier, plus de `!!`, `START_NOT_STICKY`, bouton « Arrêter » dans la notification.
- **IP** : détection de l'interface Wi-Fi / hotspot (plus la « première IPv4 »).
- **UI** : bouton Démarrer/Arrêter, la liste Wi-Fi Direct est cliquable (la connexion n'était jamais lancée).

## Windows (viewer)
- Transport TCP forcé avant l'import d'OpenCV, faible latence, reconnexion automatique, erreurs affichées dans une boîte de dialogue (le `.exe` est sans console), mémorisation de la dernière adresse, plein écran (F).
- `build_windows.bat` / `desktop/windows/build.bat` corrigés, `icon.ico` régénéré (c'était un PNG renommé), fichiers vides `rtsp_view_*.py` supprimés.

## Test sans téléphone
`python tools/fake_phone_server.py` simule le téléphone (mêmes règles de protocole que le Kotlin), puis :
`python desktop/python/viewcast_viewer.py 127.0.0.1:8554`

## Non traité
CI GitHub (`github/` n'est toujours pas `.github/`), iOS, macOS, sécurité (authentification/TLS).
