# ViewCast

**Diffusez l'écran et le son de votre téléphone Android sur votre PC Windows, sans câble, sur votre réseau local.**

🇫🇷 Français (ce document) · 🇬🇧 [English](README.en.md) · [Confidentialité / Privacy](PRIVACY.md) · [Conditions / Terms](TERMS.md)

© 2026 **Alphonse MUMBA** — tous droits sur le nom, le logo et l'interface réservés ; code publié sous licence MIT (voir [LICENCE](LICENCE)). Contact : **mumbalpha@gmail.com**

---

## 1. Ce que fait ViewCast

| Côté | Application | Rôle |
|---|---|---|
| Téléphone | **ViewCast** (Android 7.0+, son : Android 10+) | Capture l'écran et le son interne, les encode (H.264 + AAC) et les diffuse |
| PC | **ViewCast Viewer** (Windows 10/11) | Se connecte au téléphone, affiche l'image et joue le son |

Le téléphone fait office de **serveur** (RTSP, port TCP 8554) : c'est le **PC qui se connecte au téléphone** en saisissant son adresse. Rien ne passe par Internet ni par un serveur de l'éditeur.

**Nouveau (1.4)** : un téléphone peut aussi **recevoir** l'écran et le son d'un autre téléphone (onglet **Recevoir**, voir §2.7).

Langues : **français** (par défaut) et **anglais**, au choix dans les *Paramètres* des deux applications.

---

## 2. Utiliser ViewCast

### 2.1 Préparer le réseau (au choix)

1. **Même Wi‑Fi** : téléphone et PC sur la même box/le même routeur (idéal en 5 GHz).
2. **Point d'accès du téléphone** *(le plus fiable)* : activez le partage de connexion Wi‑Fi sur le téléphone, puis connectez le PC à ce réseau.
3. **Wi‑Fi Direct** : onglet *Découverte* de l'app mobile (voir 2.4). Moins universel côté PC Windows.

> 🔒 Par défaut, le flux est **chiffré (AES‑256) et protégé par un code PIN** à 6 chiffres (voir §2.6 et [docs/SECURITY.md](docs/SECURITY.md)). Utilisez malgré tout un réseau de confiance.

### 2.2 Côté Android — diffuser

1. Installez `ViewCast.apk` (autorisez « Installer des applications inconnues » pour votre navigateur ou gestionnaire de fichiers).
2. Ouvrez **ViewCast**. Onglet **Connexion** : l'application affiche l'**adresse de diffusion**, par exemple `rtsp://192.168.43.1:8554/stream`. Touchez **Copier l'adresse** si besoin.
3. (Facultatif) Onglet **Paramètres** : choisissez la résolution (480p/720p/1080p), les images par seconde (30/60), le débit, le mode *Faible latence* ou *Qualité visuelle*, et le **son interne** (activé par défaut). Ces réglages sont verrouillés pendant une diffusion.
4. Touchez **Démarrer la diffusion**. Android demande :
   - la permission **micro** (nécessaire à Android pour capturer le *son interne* ; le micro physique n'est pas utilisé) ;
   - l'autorisation de **capture d'écran** → *Commencer maintenant*.
5. Le **code PIN à 6 chiffres** s'affiche dans l'onglet *Connexion* (et est rappelé dans *Diffusion*) : notez‑le, il **change à chaque diffusion**. L'application passe sur l'onglet **Diffusion** : débit réel, définition, état du son, nombre de récepteurs connectés. Vous pouvez **couper le son** à tout moment, ou **Arrêter la diffusion** (une confirmation est demandée). Une notification permanente permet aussi d'arrêter.

### 2.3 Côté Windows — recevoir

1. Lancez **`ViewCastViewer.exe`** (ou, depuis les sources : `python viewcast_viewer.py`, voir §4).
   *Au premier lancement, le pare-feu Windows peut demander une autorisation : acceptez pour les réseaux privés.*
2. Sur l'écran **Réception**, saisissez l'adresse du téléphone — l'IP suffit (`192.168.43.1`), ou l'adresse complète `rtsp://192.168.43.1:8554/stream` — et le **code PIN** affiché sur le téléphone, puis **Se connecter** (touche Entrée). Le HUD affiche « Chiffré · AES‑256 » quand le canal sécurisé est actif.
3. Dès que l'image arrive, le récepteur passe en **Diffusion** : l'écran du téléphone s'affiche avec un **HUD** (résolution, images/s, débit, codecs) et une **barre de commandes** :
   Pause · Son (+ volume) · Format (Adapter / Étirer / Centrer 1:1) · Rotation · Plein écran · Arrêter.
4. Le viewer se **reconnecte tout seul** si le flux est coupé.

**Raccourcis clavier** : `F` plein écran · `M` muet · `Espace` pause · `Échap` quitter le plein écran, puis arrêter · double‑clic sur l'image = plein écran. En plein écran, les commandes se masquent après 2,5 s ; bougez la souris pour les revoir.

**Onglet Paramètres (PC)** : plein écran automatique, toujours au premier plan, format d'affichage par défaut, tampon *Ultra‑faible / Équilibré / Stable*, volume, muet, **langue (Français / English)**, à propos, politique de confidentialité, conditions d'utilisation, dépannage. Les réglages sont enregistrés dans `%APPDATA%\ViewCast\settings.json`.

### 2.4 Wi‑Fi Direct (facultatif)

Dans l'onglet **Découverte** du téléphone, touchez **Actualiser** : les appareils Wi‑Fi Direct proches apparaissent ; touchez **Connecter** pour créer le groupe. Quand c'est fait, l'adresse affichée dans *Connexion* se met à jour (le téléphone est généralement `192.168.49.1`). Android demande alors la permission « Appareils Wi‑Fi à proximité » (ou la localisation avant Android 13) — uniquement pour cette recherche.

### 2.5 Dépannage

| Symptôme | Que faire |
|---|---|
| « Code PIN incorrect » | Relisez le code affiché sur le téléphone (6 chiffres). Le viewer ne réessaie pas tout seul : corrigez puis reconnectez‑vous. |
| « Trop d'essais : un nouveau code a été généré » | Après 5 essais incorrects, le téléphone change de code : saisissez le nouveau. |
| VLC / ffplay ne se connecte pas | Le flux chiffré n'est lisible que par le viewer ViewCast. Pour VLC/ffplay, désactivez **Protéger par code PIN** (*Paramètres ▸ Sécurité*) : *mode ouvert, sans protection*. |
| « Impossible de joindre le téléphone » | Même réseau ? Diffusion démarrée ? Essayez le point d'accès du téléphone. Vérifiez l'IP saisie. Autorisez le pare-feu Windows. |
| Écran noir puis rien | Attendez 2–3 s (première image clé). Si besoin : *Arrêter* puis reconnecter. |
| Pas de son | Android 10+, permission micro accordée, option « Son interne » activée, volume Windows OK. Certaines applications (appels, streaming protégé) interdisent la capture de leur son. Le viewer affiche « Pas de son dans le flux ». |
| Image saccadée | Baissez la résolution/le débit sur le téléphone, ou choisissez le tampon **Stable** sur le PC ; préférez le 5 GHz. |
| Le son est décalé | Choisissez le tampon **Ultra‑faible** sur le PC. |
| Le viewer ne démarre pas depuis les sources | `python -m pip install -r desktop/python/requirements.txt` (Python 3.10–3.12, 64 bits). |

### 2.6 Sécurité : code PIN et chiffrement

- **Activée par défaut** (*Paramètres ▸ Sécurité ▸ Protéger par code PIN*). Le téléphone génère un code à 6 chiffres à chaque diffusion ; le récepteur doit le saisir.
- Le code **n'est jamais envoyé** sur le réseau (preuve cryptographique SRP‑6a) ni enregistré. Tout le flux — image, son, échanges — est **chiffré en AES‑256‑GCM** ; un paquet modifié ou rejoué est rejeté.
- **5 essais incorrects ⇒ nouveau code** généré automatiquement (l'application vous le signale).
- **Mode ouvert** (interrupteur désactivé) : RTSP en clair, compatible VLC/ffplay, mais **n'importe qui sur le réseau peut regarder**. À réserver aux tests.
- Détails, protocole et limites : [docs/SECURITY.md](docs/SECURITY.md). Le protocole n'a pas été audité par un tiers.

### 2.7 Téléphone ↔ téléphone (nouveau)

Un téléphone **diffuse** (comme d'habitude), l'autre **reçoit** :

1. **Téléphone A (émetteur)** : onglet *Connexion* → **Démarrer la diffusion**. Notez l'adresse (ex. `192.168.43.1`) et le **code PIN**.
2. **Téléphone B (récepteur)** : onglet **Recevoir** → saisissez l'adresse de A et son code PIN (laissez le code vide si A est en mode ouvert) → **Se connecter et recevoir**.
3. L'écran de A s'affiche sur B, avec le son. Touchez l'écran pour afficher les commandes : **Son / Muet**, **Plein écran**, **Déconnecter** ; la définition, le débit et les images/s sont affichés.
4. Réseau : les deux téléphones sur le même Wi‑Fi, ou B connecté au point d'accès de A (le plus fiable : A active son partage de connexion, B s'y connecte).

Remarques : la réception s'arrête quand vous quittez l'application ; un téléphone **ne peut pas diffuser et recevoir en même temps** (il se verrait lui‑même) ; le même chiffrement et le même code PIN protègent la liaison (voir §2.6). Les erreurs (adresse, code incorrect, trop d'essais, connexion perdue) sont expliquées à l'écran.

---

## 3. Maquettes ↔ application (ce qui est fait, adapté ou reporté)

Le design suit vos 8 maquettes (thème sombre « obsidienne », corail `#FF6B4A`, ambre, vert/cyan d'état).

**Android (4 onglets)**

| Maquette | Écran | Réalisé | Adapté / non réalisé |
|---|---|---|---|
| Connexion / Bienvenue | **Connexion** | statut réseau, carte d'accueil, 3 étapes, adresse + copier, démarrer/arrêter | la saisie de « l'IP du PC » devient « l'adresse du téléphone à saisir sur le PC » (le PC se connecte au téléphone) ; une carte **Code PIN** s'ajoute à l'écran ; QR code et sonde mDNS **non implémentés** (prévus, voir §7) ; chiffrement réalisé par PIN + AES‑256‑GCM (voir `docs/SECURITY.md`) |
| Découverte des appareils | **Découverte** | recherche et connexion Wi‑Fi Direct réelles, statuts des appareils, saisie manuelle | mDNS, code PIN, latence par appareil non implémentés |
| Diffusion en direct | **Diffusion** | « En direct · 720p @ 30 FPS », récepteurs connectés, débit réel, définition, audio, couper le son, confirmation d'arrêt | pause, plein écran, pointeur, batterie/thermique non applicables |
| Paramètres du flux | **Paramètres** | résolution, fps, débit, mode d'optimisation, audio, **sécurité (interrupteur code PIN)**, **langue FR/EN**, à propos (version, © auteur, contact, confidentialité, conditions, FAQ) | micro de commentaires, masquer les notifications, connexion auto, test de bande passante, « licence Pro » : hors périmètre |

**Windows (4 écrans)**

| Maquette | Écran | Réalisé | Adapté / non réalisé |
|---|---|---|---|
| Écran d'attente / appairage | **Réception** | état, saisie d'adresse et de code PIN, étapes, raccourcis | QR code non implémenté (le PC saisit l'adresse et le code) |
| Diffusion active + HUD | **Diffusion** | HUD résolution/fps/débit/codecs, barre de commandes, pause, volume, format, rotation | badge « Chiffré · AES‑256 » dans le HUD ; ping, batterie/température non applicables |
| Panneau des paramètres | **Paramètres** | plein écran auto, premier plan, format, tampon, volume, langue | décodage GPU/CPU, port d'écoute, choix WebRTC : non applicables à l'architecture actuelle |
| Plein écran immersion | **Plein écran** | commandes auto‑masquées, indications `Échap`/`F` | — |

> Polices : les maquettes utilisent Roboto Flex / Inter / JetBrains Mono ; l'app utilise la police système (Roboto sur Android, Segoe UI/Consolas sur Windows) pour ne rien embarquer.

---

## 4. Construire le projet (build)

### 4.1 Android — `ViewCast.apk`

**Prérequis** : JDK 17, Android SDK (plateforme 34 + build‑tools) — le plus simple : **Android Studio** (Hedgehog ou plus récent).

*Avec Android Studio* : *File ▸ Open* → dossier `android/` → attendre la synchronisation Gradle → *Run ▸ Run 'app'* sur un téléphone en mode débogage USB.

*En ligne de commande* (dans `android/`) :

```bash
# Windows : gradlew.bat ... — Linux/macOS : ./gradlew ...
./gradlew assembleDebug      # APK de test  → app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # APK release  → app/build/outputs/apk/release/app-release.apk
```

**Signer l'APK de publication** (une seule fois) :

```bash
keytool -genkey -v -keystore viewcast-release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias viewcast
```

Puis créez `android/keystore.properties` (déjà ignoré par Git — **ne le commitez jamais**) :

```properties
storeFile=../viewcast-release.jks
storePassword=VOTRE_MOT_DE_PASSE
keyAlias=viewcast
keyPassword=VOTRE_MOT_DE_PASSE
```

Relancez `./gradlew assembleRelease`. ⚠️ **Sauvegardez le `.jks`** : sans lui, vous ne pourrez plus publier de mises à jour installables par‑dessus l'ancienne version. (Sans `keystore.properties`, la release est signée avec la clé de debug : utile pour tester seulement.)

### 4.2 Windows — `ViewCastViewer.exe`

**Prérequis** : Windows 10/11, **Python 3.10–3.12 (64 bits)** avec Tkinter (inclus par défaut ; cochez « Add Python to PATH »).

*Lancer depuis les sources* :

```bat
cd desktop\python
python -m venv .venv
.venv\Scripts\activate
python -m pip install -r requirements.txt
python viewcast_viewer.py               :: ou : python viewcast_viewer.py 192.168.43.1
```

*Fabriquer l'exécutable* (fichier unique, sans console) :

```bat
desktop\python\build_windows.bat        :: résultat : desktop\python\dist\ViewCastViewer.exe
desktop\windows\build.bat               :: idem, copié dans desktop\windows\dist\
```

L'exécutable embarque FFmpeg (PyAV), PortAudio et l'icône ; il pèse plusieurs dizaines de Mo. Windows SmartScreen peut afficher « éditeur inconnu » tant que l'exécutable n'est pas signé numériquement : *Informations complémentaires ▸ Exécuter quand même*.

### 4.3 Tester sans téléphone

```bash
python tools/test_viewer_logic.py          # 63 tests de la logique du viewer (aucun écran requis)
python tools/test_secure_protocol.py       # 17 tests du canal chiffré, dont Python <-> Java (vrai code Android)
python tools/test_e2e_secure.py            # 14 tests de bout en bout : chiffrement, mauvais PIN, espion réseau (ffmpeg requis)
python tools/test_receiver_java.py         # 14 tests du client de RÉCEPTION Android (vrai code Java) : image, son, PIN, ouvert (ffmpeg requis)
(cd android && ./gradlew test)             # mêmes vecteurs cryptographiques côté Android (JUnit)

python tools/fake_phone_server.py --pin 482913     # faux téléphone chiffré : mire + son 440 Hz (ffmpeg requis)
python desktop/python/viewcast_viewer.py 127.0.0.1:8554    # puis saisir le code PIN 482913 dans la fenêtre
```

### 4.4 Publier une version (GitHub Releases)

1. Fusionnez la branche dans `main`, puis `git tag v1.4.0 && git push origin v1.4.0`.
2. Sur GitHub : *Releases ▸ Draft a new release* → choisissez le tag → joignez `ViewCast.apk` et `ViewCastViewer.exe` → *Publish release*.
3. Ajoutez le lien `https://github.com/AlphonseMumba/ViewCast/releases/latest` à vos pages de présentation.

---

## 5. Organisation du dépôt

```
android/            application Android (Kotlin, Jetpack Compose)
  .../ui/           thème, composants, 4 écrans, état
  .../rtsp/         serveur RTSP/RTP, service de diffusion
  .../capture/      capture d'écran + encodage H.264, capture du son + AAC
  .../security/     canal chiffré : SRP-6a (code PIN), AES-256-GCM, limite d'essais (Java)
  .../receive/      réception : client RTSP + dépaquetage (Java), décodage image/son (Kotlin)
  .../res/values*/  textes FR (défaut) et EN, thème ; res/raw : textes légaux
desktop/python/     viewer Windows : vc_ui (interface), vc_stream (flux, audio), vc_secure (canal chiffré + pont local), vc_i18n (langues), vc_settings, vc_legal
desktop/windows/    icône et script de build
tools/              faux téléphone, tests, générateur des textes légaux (gen_legal.py)
docs/SECURITY.md                            protocole de sécurité, modèle de menace, limites
PRIVACY.md TERMS.md NOTICE.md LICENCE      documents légaux et licences
```

Les textes légaux (confidentialité, conditions) ont **une seule source** : `tools/gen_legal.py` ; modifiez‑le puis lancez `python tools/gen_legal.py` pour régénérer `PRIVACY.md`, `TERMS.md`, les textes de l'app Android et du viewer.

---

## 6. Confidentialité, légal et propriété intellectuelle

- **Aucune donnée collectée** par l'éditeur : pas de compte, de serveur, de publicité ni d'outil d'analyse. L'écran et le son vont **directement** du téléphone au PC sur votre réseau local ; rien n'est enregistré.
- Le flux est **chiffré (AES‑256) et protégé par code PIN** par défaut ; le *mode ouvert* (désactivable) ne protège rien. Voir [docs/SECURITY.md](docs/SECURITY.md).
- Détails : [Politique de confidentialité](PRIVACY.md) · [Conditions d'utilisation](TERMS.md) · [Notices tiers](NOTICE.md) (aussi consultables dans les deux applications, onglet *Paramètres ▸ À propos*).
- **Propriété intellectuelle** : ViewCast (nom, logo, interface, code, documentation) © 2026 **Alphonse MUMBA**. Code sous licence MIT ([LICENCE](LICENCE)) ; le nom et le logo restent la propriété de l'auteur.
- **Contact / support** : **mumbalpha@gmail.com**

---

## 7. Feuille de route

1. ✅ **Sécurité** : code PIN + chiffrement de bout en bout (cette version).
2. ⏭️ **Connexion par QR code** : l'ordinateur affiche un QR code (adresse + code), le **smartphone le scanne** et se connecte ; plus rien à saisir.
3. 🔶 **Rôles symétriques** : ✅ **téléphone → téléphone** (version 1.4). ⏭️ **PC diffuseur** (PC → téléphone, PC ↔ PC) : le PC devra capturer son écran et son son système, puis les encoder ; le canal sécurisé actuel est déjà conçu pour cela (le sens de connexion est indépendant du sens des médias).
