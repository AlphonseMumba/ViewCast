# Politique de confidentialité / Privacy Policy

## Politique de confidentialité — ViewCast

Dernière mise à jour : 9 octobre 2026  
Éditeur et responsable : Alphonse MUMBA — mumbalpha@gmail.com

### 1. Objet

ViewCast (application Android « ViewCast » et récepteur Windows « ViewCast Viewer ») diffuse l'écran et le son d'un téléphone Android vers un ordinateur situé sur le même réseau local. Ce document explique quelles données sont traitées, pourquoi, et comment vous les contrôlez.

### 2. Principe général

Alphonse MUMBA ne collecte, ne reçoit et ne stocke aucune donnée personnelle vous concernant. ViewCast n'a ni compte utilisateur, ni serveur de l'éditeur, ni publicité, ni outil d'analyse, ni traceur. Tout le traitement a lieu sur votre téléphone et votre ordinateur.

### 3. Données traitées sur vos appareils

• Image de l'écran et son interne du téléphone : capturés uniquement après votre autorisation explicite (fenêtre système de capture d'écran, permission micro pour le son) et seulement pendant la diffusion. Ils sont envoyés directement au récepteur que vous connectez sur votre réseau. Ils ne sont ni enregistrés, ni conservés, ni transmis à l'éditeur ou à un tiers.
• Ce flux peut contenir des informations personnelles ou sensibles affichées à l'écran (messages, mots de passe, notifications). Vous en êtes responsable : vérifiez ce qui est visible avant de diffuser. Les fenêtres protégées par Android (par exemple certaines applications bancaires) ne sont pas capturées.
• Réglages : langue, qualité, débit, options et dernière adresse saisie, stockés localement (Android : stockage privé de l'application ; Windows : dossier %APPDATA%\ViewCast).
• Code PIN : généré sur le téléphone pour chaque diffusion et affiché à l'écran ; il n'est jamais enregistré, ni transmis sur le réseau (la preuve de connaissance est cryptographique), ni conservé par le récepteur Windows.
• Données réseau techniques : adresses IP locales du téléphone et du PC, noms d'appareils Wi‑Fi Direct, utilisés uniquement pour établir la connexion locale.

### 4. Autorisations Android et finalités

• Capture d'écran (MediaProjection) : capturer l'image à diffuser. Demandée à chaque démarrage.
• Micro / Enregistrer l'audio (RECORD_AUDIO) : nécessaire à Android pour capturer le son interne des applications. Le micro physique n'est pas utilisé.
• Internet, état du réseau, Wi‑Fi : ouvrir le port de diffusion local et connaître l'adresse IP à saisir sur le PC. Aucune donnée n'est envoyée sur Internet.
• Appareils Wi‑Fi à proximité / Localisation (Wi‑Fi Direct) : exigées par Android pour rechercher des appareils Wi‑Fi Direct. Votre position n'est ni lue ni enregistrée.
• Service au premier plan et notifications : maintenir la diffusion active et vous permettre de l'arrêter.

### 5. Réseau et sécurité

Le flux circule sur votre réseau local (port TCP 8554). Par défaut il est chiffré (AES‑256‑GCM) et protégé par un code PIN à 6 chiffres, affiché sur le téléphone et renouvelé à chaque diffusion : l'authentification utilise le protocole SRP‑6a, qui ne transmet jamais le code, et le téléphone génère un nouveau code après 5 essais incorrects. Seul un appareil qui connaît le code peut recevoir et déchiffrer la diffusion. Vous pouvez désactiver cette protection (« mode ouvert », RTSP en clair, utile pour VLC ou ffplay) : toute personne connectée au même réseau et connaissant l'adresse peut alors regarder la diffusion tant qu'elle est active. Même chiffré, utilisez un réseau de confiance (domicile, point d'accès personnel protégé WPA2/WPA3), évitez les Wi‑Fi publics et arrêtez la diffusion après usage.

### 6. Partage avec des tiers

Aucune donnée n'est partagée, vendue ou cédée. L'application embarque des bibliothèques libres (voir le fichier NOTICE) qui s'exécutent localement et ne transmettent rien.

### 7. Conservation

Aucune donnée de diffusion n'est conservée. Les réglages restent jusqu'à ce que vous les supprimiez : Android → Paramètres → Applications → ViewCast → Stockage → Effacer les données (ou désinstallation) ; Windows → supprimer le dossier %APPDATA%\ViewCast.

### 8. Vos droits

L'éditeur ne détenant aucune donnée personnelle vous concernant, il n'a rien à vous communiquer ni à effacer ; vous gardez le contrôle local de vos réglages. Pour toute demande d'accès, de rectification, d'effacement ou d'opposition prévue par la loi applicable (dont le RGPD), ou toute question : mumbalpha@gmail.com.

### 9. Enfants

ViewCast ne s'adresse pas aux enfants de moins de 13 ans et ne collecte aucune donnée auprès d'eux.

### 10. Propriété intellectuelle

ViewCast — nom, logo, interface, code et documentation — est la propriété de Alphonse MUMBA. © 2026 Alphonse MUMBA. Le code source est publié sous licence MIT (fichier LICENCE) ; le nom et le logo ViewCast restent la propriété de l'auteur. Les bibliothèques tierces restent la propriété de leurs auteurs respectifs et sont soumises à leurs licences.

### 11. Modifications

Cette politique peut évoluer avec l'application. La date de mise à jour figure en tête ; en cas de changement important, il sera signalé dans les notes de version.

### 12. Contact

Alphonse MUMBA — mumbalpha@gmail.com

---

## Privacy Policy — ViewCast

Last updated: October 9, 2026  
Publisher and controller: Alphonse MUMBA — mumbalpha@gmail.com

### 1. Purpose

ViewCast (the "ViewCast" Android app and the "ViewCast Viewer" Windows receiver) streams the screen and sound of an Android phone to a computer on the same local network. This document explains what data is processed, why, and how you control it.

### 2. General principle

Alphonse MUMBA does not collect, receive or store any personal data about you. ViewCast has no user accounts, no publisher servers, no advertising, no analytics and no trackers. All processing happens on your phone and your computer.

### 3. Data processed on your devices

• Screen image and internal phone sound: captured only after your explicit permission (system screen-capture prompt, microphone permission for sound) and only while casting. They are sent directly to the receiver you connect on your network. They are not recorded, kept, or sent to the publisher or any third party.
• The stream may contain personal or sensitive information shown on screen (messages, passwords, notifications). You are responsible for it: check what is visible before casting. Windows protected by Android (for example some banking apps) are not captured.
• Settings: language, quality, bitrate, options and the last address entered, stored locally (Android: the app's private storage; Windows: the %APPDATA%\ViewCast folder).
• PIN code: generated on the phone for every cast and shown on screen; it is never stored, never sent over the network (proof of knowledge is cryptographic), and never kept by the Windows receiver.
• Technical network data: local IP addresses of the phone and PC, Wi‑Fi Direct device names, used only to establish the local connection.

### 4. Android permissions and purposes

• Screen capture (MediaProjection): capture the image to stream. Requested every time you start.
• Microphone / Record audio (RECORD_AUDIO): required by Android to capture the internal sound of apps. The physical microphone is not used.
• Internet, network state, Wi‑Fi: open the local streaming port and show the IP address to enter on the PC. No data is sent over the Internet.
• Nearby Wi‑Fi devices / Location (Wi‑Fi Direct): required by Android to look for Wi‑Fi Direct devices. Your location is neither read nor stored.
• Foreground service and notifications: keep casting active and let you stop it.

### 5. Network and security

The stream travels over your local network (TCP port 8554). By default it is encrypted (AES‑256‑GCM) and protected by a 6‑digit PIN, shown on the phone and renewed for every cast: authentication uses the SRP‑6a protocol, which never transmits the code, and the phone generates a new code after 5 wrong attempts. Only a device that knows the code can receive and decrypt the cast. You may turn this protection off ("open mode", plain RTSP, useful for VLC or ffplay): anyone on the same network who knows the address can then watch the cast while it is active. Even when encrypted, use a trusted network (home, personal hotspot protected with WPA2/WPA3), avoid public Wi‑Fi, and stop casting when you are done.

### 6. Sharing with third parties

No data is shared, sold or transferred. The app bundles open-source libraries (see the NOTICE file) that run locally and transmit nothing.

### 7. Retention

No streaming data is retained. Settings remain until you delete them: Android → Settings → Apps → ViewCast → Storage → Clear data (or uninstall); Windows → delete the %APPDATA%\ViewCast folder.

### 8. Your rights

Since the publisher holds no personal data about you, there is nothing to disclose or erase; you keep local control of your settings. For any access, rectification, erasure or objection request provided by applicable law (including the GDPR), or any question: mumbalpha@gmail.com.

### 9. Children

ViewCast is not directed to children under 13 and collects no data from them.

### 10. Intellectual property

ViewCast — name, logo, interface, code and documentation — is the property of Alphonse MUMBA. © 2026 Alphonse MUMBA. The source code is published under the MIT license (LICENCE file); the ViewCast name and logo remain the author's property. Third-party libraries remain the property of their respective authors and are subject to their licenses.

### 11. Changes

This policy may change with the app. The update date is shown at the top; significant changes will be mentioned in the release notes.

### 12. Contact

Alphonse MUMBA — mumbalpha@gmail.com
