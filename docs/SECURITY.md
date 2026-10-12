# Sécurité de ViewCast / ViewCast security

🇫🇷 Français · 🇬🇧 [English summary below](#english-summary)

## 1. Objectif et modèle de menace

ViewCast diffuse l'écran et le son sur un réseau local. La protection doit tenir face à une personne **sur le même réseau** (Wi‑Fi partagé, point d'accès) qui peut écouter ou modifier le trafic, et qui peut essayer de se connecter au téléphone.

| Menace | Protection |
|---|---|
| Voisin qui tente de regarder la diffusion | Connexion refusée sans le **code PIN** à 6 chiffres affiché sur le téléphone |
| Écoute du réseau (sniffing) | Flux **chiffré AES‑256‑GCM** : image, son et messages RTSP sont illisibles |
| Modification / rejeu de paquets | Étiquette d'authentification GCM + compteur de nonce : tout paquet altéré, supprimé ou rejoué est rejeté |
| Deviner le PIN (essais en ligne) | Protocole **SRP‑6a** + renouvellement du code après **5 échecs** + 2 négociations simultanées maximum |
| Deviner le PIN hors ligne (en captant l'échange) | Impossible : SRP ne révèle rien qui permette de tester des codes hors ligne |
| Faux téléphone (usurpation) | Le récepteur vérifie la preuve du serveur (M2) : un faux téléphone qui ne connaît pas le code est détecté |
| Déchiffrer une capture réseau enregistrée, même si le PIN fuite plus tard | Clés de session éphémères : un PIN connu après coup ne permet pas de déchiffrer d'anciennes captures |

**Hors périmètre** (à connaître) : un appareil déjà compromis ; ce que le propriétaire du téléphone laisse affiché à l'écran ; quelqu'un qui **voit le code PIN** à l'écran (il faut protéger le code comme un mot de passe) ; le **mode ouvert** (désactivable dans les Paramètres), qui ne chiffre rien et n'authentifie personne.

## 2. Protocole « VCS1 »

Même code, mêmes octets côté Android (`android/.../security/*.java`) et côté Windows (`desktop/python/vc_secure.py`) : les deux implémentations sont vérifiées l'une contre l'autre (vecteurs de test communs, voir §4).

**Poignée de main (SRP‑6a)** — groupe MODP 2048 bits (RFC 3526 n°14, g = 2), SHA‑256, identité fixe `viewcast`, mot de passe = PIN.

```
C → S  HELLO   "VCS1" | A (256 o)             A = g^a mod N
S → C  HELLO   sel (16 o) | B (256 o)         B = k·v + g^b mod N,  v = g^x mod N,  x = H(sel | H("viewcast:" PIN))
C → S  PROOF   M1 (32 o)                      M1 = H( H(N)⊕H(g) | H(I) | sel | A | B | K )
S → C  PROOF   M2 (32 o)  ou  FAIL (code)     M2 = H( A | M1 | K ),  K = H( S ),  u = H(A | B)
```

Les messages sont encadrés `type (1) | longueur (2) | contenu`. Toutes les valeurs numériques sont envoyées sur 256 octets (big‑endian). Les contrôles A ≠ 0, B ≠ 0, u ≠ 0 et les comparaisons à temps constant sont appliqués des deux côtés.

**Canal chiffré** : deux clés AES‑256 dérivées de K par HKDF‑SHA256 (`client-to-server`, `server-to-client`). Chaque enregistrement : `longueur (4) | AES‑GCM(texte)(+16 octets d'étiquette)`, nonce de 96 bits = compteur de 64 bits par sens, la longueur sert de donnée authentifiée. Maximum 16 Ko par enregistrement.

**Côté Windows**, le lecteur vidéo (FFmpeg) parle RTSP en clair à `127.0.0.1` ; un **pont local** (`SecureProxy`) relaie ces octets, chiffrés, vers le téléphone. Rien de lisible ne sort de l'ordinateur.

**Le sens de connexion TCP est indépendant du sens des médias** : le canal est symétrique (celui qui se connecte joue le rôle « client SRP », celui qui écoute affiche le PIN). Cette propriété sert de base aux évolutions prévues (QR code, rôles émetteur/récepteur sur tous les appareils).

## 3. Gestion du code PIN

- 6 chiffres aléatoires (`SecureRandom`), **nouveau à chaque diffusion** ; jamais enregistré, ni sur le téléphone, ni sur le PC.
- Après 5 échecs consécutifs, un **nouveau code** est généré (les essais précédents d'un attaquant deviennent inutiles) et l'application le signale.
- Le récepteur ne réessaie jamais automatiquement après un refus : chaque essai compte.

## 4. Vérifier

```bash
python tools/test_secure_protocol.py      # 17 tests : vecteurs, Python<->Python, Python<->Java (vrai code Android)
python tools/test_e2e_secure.py           # 14 tests : faux téléphone + pont + ffmpeg + espion réseau (ffmpeg requis)
cd android && ./gradlew test              # mêmes vecteurs côté Android (JUnit)
```

Le test de bout en bout place un « espion » entre le pont et le téléphone : il vérifie qu'aucun texte RTSP/SDP n'est lisible, que le trafic est incompressible, qu'un mauvais PIN est refusé, qu'un lecteur RTSP direct est refusé et que le code est renouvelé après 5 échecs.

## 5. Limites connues

Protocole conçu pour ce projet à partir de briques standard (SRP‑6a, HKDF, AES‑GCM) mais **pas audité par un tiers**. Pour un usage à risque élevé, faites‑le relire. Pas de protection contre un attaquant qui contrôle le téléphone ou le PC.

---

## English summary

ViewCast protects the stream against anyone **on the same network**: the phone shows a **6‑digit PIN** (new for every cast, never stored); the receiver proves it knows it with **SRP‑6a** (PAKE, 2048‑bit MODP group, SHA‑256), which never sends the PIN and gives an eavesdropper nothing to guess offline; all media is then carried in **AES‑256‑GCM** records (HKDF‑derived per‑direction keys, 64‑bit nonce counter: tampered, dropped or replayed records are rejected). After **5 wrong attempts** the phone generates a new PIN. On Windows, FFmpeg talks plain RTSP to `127.0.0.1` and a local proxy relays it encrypted to the phone. The TCP direction is independent of the media direction (symmetric channel). An optional **open mode** (plain RTSP, for VLC/ffplay) can be enabled in Settings and offers no protection. The protocol uses standard primitives but has **not been independently audited**. Tests: `tools/test_secure_protocol.py` (17), `tools/test_e2e_secure.py` (14), `./gradlew test`.
