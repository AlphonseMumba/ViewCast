"""Textes de l'interface Windows : français (langue principale) et anglais."""

LANGS = ("fr", "en")
_lang = "fr"

S = {
    "app_receiver": ("Receiver", "Receiver"),
    "tab_wait": ("Réception", "Reception"),
    "tab_live": ("Diffusion", "Live"),
    "tab_settings": ("Paramètres", "Settings"),
    "status_active": ("Actif", "Active"),
    "status_waiting": ("En attente", "Waiting"),
    "status_connecting": ("Connexion…", "Connecting…"),
    # écran d'attente
    "wait_title": ("En attente de connexion", "Waiting for connection"),
    "wait_sub": ("Saisissez l'adresse affichée dans l'application ViewCast de votre téléphone, puis connectez-vous.",
                 "Enter the address shown in the ViewCast app on your phone, then connect."),
    "wait_ready": ("Prêt pour la diffusion", "Ready to receive"),
    "addr_label": ("Adresse du téléphone", "Phone address"),
    "addr_hint": ("Exemple : 192.168.43.1  ou  rtsp://192.168.43.1:8554/stream",
                  "Example: 192.168.43.1  or  rtsp://192.168.43.1:8554/stream"),
    "pin_label": ("Code PIN", "PIN code"),
    "pin_hint": ("6 chiffres affichés sur le téléphone · laissez vide si le téléphone est en mode ouvert",
                 "6 digits shown on the phone · leave empty if the phone is in open mode"),
    "msg_pin_format": ("Le code PIN doit contenir exactement 6 chiffres.", "The PIN must be exactly 6 digits."),
    "msg_auth_failed": ("Code PIN incorrect. Vérifiez le code affiché sur le téléphone puis reconnectez-vous.",
                        "Wrong PIN. Check the code shown on the phone, then connect again."),
    "msg_locked": ("Trop d'essais : un nouveau code a été généré sur le téléphone. Saisissez-le.",
                   "Too many attempts: a new code was generated on the phone. Enter it."),
    "msg_protocol": ("Le téléphone n'a pas répondu comme attendu. Mettez à jour ViewCast sur les deux appareils, ou vérifiez le mode (PIN / ouvert).",
                     "The phone did not answer as expected. Update ViewCast on both devices, or check the mode (PIN / open)."),
    "secure_badge": ("Chiffré · AES‑256", "Encrypted · AES‑256"),
    "open_badge": ("Mode ouvert (non chiffré)", "Open mode (unencrypted)"),
    "btn_connect": ("Se connecter", "Connect"),
    "btn_cancel": ("Annuler", "Cancel"),
    "steps_title": ("Comment connecter votre téléphone", "How to connect your phone"),
    "step1_t": ("Lancez ViewCast Mobile", "Open ViewCast Mobile"),
    "step1_d": ("Sur votre téléphone Android, touchez « Démarrer la diffusion ».",
                "On your Android phone, tap \"Start casting\"."),
    "step2_t": ("Utilisez le même réseau", "Use the same network"),
    "step2_d": ("PC et téléphone sur le même Wi‑Fi, ou PC connecté au point d'accès du téléphone.",
                "PC and phone on the same Wi‑Fi, or the PC joined to the phone's hotspot."),
    "step3_t": ("Saisissez l'adresse ici", "Enter the address here"),
    "step3_d": ("Recopiez l'adresse et le code PIN affichés sur le téléphone, puis cliquez sur « Se connecter ».",
                "Type the address and the PIN shown on the phone, then click \"Connect\"."),
    "shortcuts": ("F  Plein écran      M  Muet      Espace  Pause      Échap  Quitter",
                  "F  Fullscreen      M  Mute      Space  Pause      Esc  Quit"),
    "msg_connecting": ("Connexion à %s…", "Connecting to %s…"),
    "msg_unreachable": ("Impossible de joindre le téléphone (essai %d).\nVérifiez l'IP, le Wi‑Fi commun, que la diffusion est démarrée et le code PIN.",
                        "Cannot reach the phone (attempt %d).\nCheck the IP, the shared Wi‑Fi, that casting has started, and the PIN."),
    "msg_interrupted": ("Flux interrompu, reconnexion…", "Stream interrupted, reconnecting…"),
    "msg_invalid_addr": ("Adresse invalide : %s", "Invalid address: %s"),
    "msg_no_av": ("Le module « av » (PyAV) est introuvable.\nInstallez les dépendances : python -m pip install -r requirements.txt",
                  "The \"av\" (PyAV) module is missing.\nInstall dependencies: python -m pip install -r requirements.txt"),
    # diffusion
    "hud_rate": ("Débit", "Rate"),
    "hud_sound": ("Son", "Sound"),
    "hud_no_audio": ("Pas de son dans le flux", "No sound in the stream"),
    "sound_on": ("Son actif", "Sound on"),
    "sound_off": ("Son coupé", "Sound off"),
    "ctl_pause": ("Pause", "Pause"),
    "ctl_play": ("Lecture", "Play"),
    "ctl_aspect": ("Format", "Aspect"),
    "ctl_rotate": ("Rotation", "Rotate"),
    "ctl_fullscreen": ("Plein écran", "Fullscreen"),
    "ctl_exit_fullscreen": ("Réduire", "Exit fullscreen"),
    "ctl_stop": ("Arrêter", "Stop"),
    "aspect_fit": ("Adapter", "Fit"),
    "aspect_stretch": ("Étirer", "Stretch"),
    "aspect_center": ("Centrer 1:1", "Center 1:1"),
    "fs_hint": ("Appuyez sur Échap ou F pour quitter le plein écran · Bougez la souris pour afficher les commandes",
                "Press Esc or F to exit fullscreen · Move the mouse to show the controls"),
    "paused": ("Pause", "Paused"),
    # paramètres
    "set_title": ("Configuration ViewCast", "ViewCast configuration"),
    "set_sub": ("Personnalisez l'affichage, la latence, le son et la langue.", "Customize display, latency, sound and language."),
    "set_video": ("Vidéo et affichage", "Video & display"),
    "set_autofs": ("Plein écran automatique", "Automatic fullscreen"),
    "set_autofs_d": ("Passe en plein écran dès que l'image du téléphone arrive.", "Goes fullscreen as soon as the phone's picture arrives."),
    "set_top": ("Toujours au premier plan", "Always on top"),
    "set_top_d": ("Garde la fenêtre au‑dessus des autres applications.", "Keeps the window above other applications."),
    "set_aspect": ("Format d'affichage par défaut", "Default display format"),
    "set_aspect_d": ("Adapter : barres noires (recommandé) · Étirer : remplit la fenêtre · Centrer : échelle 1:1.",
                     "Fit: black bars (recommended) · Stretch: fills the window · Center: 1:1 scale."),
    "set_stream": ("Flux et latence", "Stream & latency"),
    "set_latency": ("Tampon vidéo / audio", "Video / audio buffer"),
    "lat_low": ("Ultra‑faible", "Ultra‑low"),
    "lat_balanced": ("Équilibré", "Balanced"),
    "lat_stable": ("Stable", "Stable"),
    "set_latency_d": ("Ultra‑faible : réactivité maximale (Wi‑Fi 5 GHz recommandé). Stable : image plus régulière sur un réseau chargé. Appliqué à la prochaine connexion.",
                      "Ultra‑low: maximum responsiveness (5 GHz Wi‑Fi recommended). Stable: smoother picture on a busy network. Applied at the next connection."),
    "set_audio": ("Audio", "Audio"),
    "set_volume": ("Volume", "Volume"),
    "set_mute": ("Muet", "Mute"),
    "set_lang": ("Langue", "Language"),
    "lang_fr": ("Français", "Français"),
    "lang_en": ("English", "English"),
    "set_about": ("À propos", "About"),
    "about_version": ("ViewCast Viewer · version %s", "ViewCast Viewer · version %s"),
    "about_rights": ("© 2026 Alphonse MUMBA", "© 2026 Alphonse MUMBA"),
    "about_contact": ("Contact : mumbalpha@gmail.com", "Contact: mumbalpha@gmail.com"),
    "btn_mail": ("Écrire au support", "Email support"),
    "btn_privacy": ("Politique de confidentialité", "Privacy policy"),
    "btn_terms": ("Conditions d'utilisation", "Terms of use"),
    "btn_faq": ("Dépannage et FAQ", "Troubleshooting & FAQ"),
    "btn_close": ("Fermer", "Close"),
    "faq_title": ("Dépannage et FAQ", "Troubleshooting & FAQ"),
    "faq_body": (
        "• Rien ne s'affiche : le PC et le téléphone doivent être sur le même réseau et la diffusion doit être démarrée sur le téléphone.\n\n"
        "• « Impossible de joindre le téléphone » : activez le point d'accès du téléphone, connectez-y le PC, puis saisissez l'adresse affichée dans l'onglet Connexion de l'application.\n\n"
        "• Pas de son : Android 10 minimum, permission micro accordée et option « Son interne » activée sur le téléphone. Certaines applications interdisent la capture de leur son. Vérifiez aussi le volume Windows.\n\n"
        "• Image saccadée : choisissez le tampon « Stable », ou baissez la résolution/le débit dans l'application mobile.\n\n"
        "• Le pare-feu Windows peut demander une autorisation au premier lancement : acceptez pour les réseaux privés.",
        "• Nothing shows up: the PC and phone must be on the same network and casting must be started on the phone.\n\n"
        "• \"Cannot reach the phone\": turn on the phone's hotspot, join the PC to it, then type the address shown in the app's Connect tab.\n\n"
        "• No sound: Android 10 or later, microphone permission granted and the \"Internal sound\" option on, on the phone. Some apps forbid capturing their sound. Also check the Windows volume.\n\n"
        "• Choppy picture: choose the \"Stable\" buffer, or lower the resolution/bitrate in the mobile app.\n\n"
        "• Windows Firewall may ask for permission on first launch: allow it for private networks."),
    "shortcuts_title": ("Raccourcis clavier", "Keyboard shortcuts"),
    "engine": ("Moteur ViewCast", "ViewCast engine"),
}


def set_language(code):
    global _lang
    _lang = code if code in LANGS else "fr"


def get_language():
    return _lang


def t(key, *args):
    """Texte traduit ; %s / %d remplacés par args."""
    pair = S.get(key)
    text = pair[0 if _lang == "fr" else 1] if pair else key
    return text % args if args else text
