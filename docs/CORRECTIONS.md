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
`python tools/fake_phone_server.py` simule le téléphone, image + son (mêmes règles de protocole que le Kotlin), puis :
`python desktop/python/viewcast_viewer.py 127.0.0.1:8554`

## Son (ajouté)
- **Android** : `capture/AudioCapture.kt` capture le son INTERNE du téléphone (AudioPlaybackCapture, Android 10+) et l'encode en AAC-LC 48 kHz stéréo 128 kbit/s. Interrupteur « Diffuser le son » dans l'app ; la permission micro n'est demandée qu'au démarrage, et un refus fait simplement diffuser sans son.
- **Flux** : deuxième piste RTSP (`streamid=1`, RTP MPEG4-GENERIC / AAC-hbr, RFC 3640) + RTCP Sender Reports sur les deux pistes. Les instants image et son sont sur la même horloge, et les Sender Reports permettent au lecteur de les resynchroniser (lip-sync).
- **Windows** : le viewer décode maintenant avec PyAV (FFmpeg embarqué) et joue le son avec sounddevice. Tampon audio borné (400 ms) pour garder le son collé à l'image, touche **M** pour couper/remettre le son. Sans carte son ou sans piste audio, l'image s'affiche quand même.
- **Dépendances Windows** : `pip install -r desktop/python/requirements.txt` (opencv-python, numpy, av, sounddevice).
- **Limite Android** : les applications qui interdisent la capture de leur son (appels, certaines apps de streaming/DRM) restent muettes.

## Refonte du design, langues, légal (v1.2.0)
- **Android** : interface Jetpack Compose en 4 onglets d'après les maquettes (Connexion, Découverte, Diffusion, Paramètres), thème sombre/corail, mesures réelles (débit, définition, récepteurs), **langue FR/EN** réglable (français par défaut), réglages enregistrés, état de diffusion retrouvé à la réouverture de l'app, permissions demandées au moment du besoin.
- **Windows** : nouvelle interface Tkinter (Réception, Diffusion avec HUD et commandes, Paramètres, plein écran immersif), langue FR/EN, réglages dans `%APPDATA%\ViewCast`.
- **Légal** : politique de confidentialité, conditions d'utilisation, notices tiers, licence MIT complète (propriété : Alphonse MUMBA), consultables dans les deux applications.
- **Tests** : `tools/test_viewer_logic.py` (52 tests), `tools/fake_phone_server.py`.

## Sécurité (v1.3.0)
- **Code PIN + chiffrement de bout en bout** : protocole SRP-6a (PAKE) + AES-256-GCM, activé par défaut, code à 6 chiffres renouvelé à chaque diffusion et après 5 échecs ; mode ouvert facultatif pour VLC/ffplay. Voir `docs/SECURITY.md`.
- **Android** : `security/*.java` (cœur testé contre Python), carte « Code PIN » dans *Connexion*, rappel dans *Diffusion*, interrupteur dans *Paramètres ▸ Sécurité*.
- **Windows** : champ PIN, pont local chiffré `SecureProxy` (FFmpeg parle en clair à 127.0.0.1), messages d'erreur dédiés, pas de nouvel essai automatique après un refus, badge « Chiffré » dans le HUD.
- **Tests** : `test_secure_protocol.py` (17), `test_e2e_secure.py` (14), `test_viewer_logic.py` (63), JUnit `SrpVectorsTest`.

## Téléphone ↔ téléphone (v1.4.0)
- **Réception sur Android** : nouvel onglet *Recevoir* (adresse + code PIN), image décodée par MediaCodec sur une SurfaceView, son AAC joué par AudioTrack, commandes (son, plein écran, déconnexion), mesures en direct, messages d'erreur dédiés.
- **Client RTSP en Java pur** (`receive/RtspPlayer.java`) : canal chiffré ou mode ouvert, SDP, H.264 (NAL seule + FU-A), AAC (AAC-hbr), keep-alive ; testé contre le serveur de test (`tools/test_receiver_java.py`, 14 tests : image 720x1280 décodée, son 440 Hz intact, bon/mauvais PIN, mode ouvert, appareil introuvable).
- La rotation de l'écran ne coupe plus la réception ; un téléphone ne peut pas diffuser et recevoir en même temps.

## Non traité
CI GitHub (`github/` n'est toujours pas `.github/`), iOS, macOS, sécurité (authentification/TLS).
