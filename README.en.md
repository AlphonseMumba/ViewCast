# ViewCast

**Stream your Android phone's screen and sound to your Windows PC, cable-free, over your local network.**

🇫🇷 [Français](README.md) · 🇬🇧 English (this document) · [Privacy / Confidentialité](PRIVACY.md) · [Terms / Conditions](TERMS.md)

© 2026 **Alphonse MUMBA** — name, logo and interface rights reserved; code published under the MIT license (see [LICENCE](LICENCE)). Contact: **mumbalpha@gmail.com**

---

## 1. What ViewCast does

| Side | App | Role |
|---|---|---|
| Phone | **ViewCast** (Android 7.0+, sound: Android 10+) | Captures the screen and internal sound, encodes them (H.264 + AAC) and streams them |
| PC | **ViewCast Viewer** (Windows 10/11) | Connects to the phone, shows the picture and plays the sound |

The phone is the **server** (RTSP, TCP port 8554): the **PC connects to the phone** by typing its address. Nothing goes through the Internet or any publisher server.

**New (1.4)**: a phone can also **receive** another phone's screen and sound (**Receive** tab, see §2.7).

Languages: **French** (default) and **English**, selectable in the *Settings* of both apps.

---

## 2. Using ViewCast

### 2.1 Prepare the network (pick one)

1. **Same Wi‑Fi**: phone and PC on the same router (5 GHz is ideal).
2. **Phone hotspot** *(most reliable)*: turn on the phone's Wi‑Fi hotspot and join the PC to it.
3. **Wi‑Fi Direct**: *Discover* tab of the mobile app (see 2.4). Less universal on Windows PCs.

> 🔒 By default the stream is **encrypted (AES‑256) and protected by a 6‑digit PIN** (see §2.6 and [docs/SECURITY.md](docs/SECURITY.md)). Still, use a trusted network.

### 2.2 Android — cast

1. Install `ViewCast.apk` (allow "Install unknown apps" for your browser/file manager).
2. Open **ViewCast**. **Connect** tab: the app shows the **cast address**, e.g. `rtsp://192.168.43.1:8554/stream`. Tap **Copy address** if needed.
3. (Optional) **Settings** tab: resolution (480p/720p/1080p), frame rate (30/60), bitrate, *Low latency* or *Visual quality* mode, and **internal sound** (on by default). Settings are locked while casting.
4. Tap **Start casting**. Android asks for:
   - the **microphone** permission (required by Android to capture *internal sound*; the physical microphone is not used);
   - **screen capture** permission → *Start now*.
5. The **6‑digit PIN** is shown in the *Connect* tab (and repeated in *Cast*): write it down, it **changes with every cast**. The app switches to the **Cast** tab: real bitrate, resolution, sound state, number of connected receivers. You can **mute the sound** any time or **Stop casting** (with confirmation). The ongoing notification also has a Stop button.

### 2.3 Windows — receive

1. Run **`ViewCastViewer.exe`** (or from source: `python viewcast_viewer.py`, see §4).
   *On first launch Windows Firewall may ask permission: allow it for private networks.*
2. On the **Reception** screen, type the phone's address — the IP is enough (`192.168.43.1`), or the full `rtsp://192.168.43.1:8554/stream` — and the **PIN** shown on the phone, then **Connect** (Enter key). The HUD shows "Encrypted · AES‑256" when the secure channel is active.
3. When the picture arrives the receiver switches to **Live**: the phone screen with a **HUD** (resolution, fps, bitrate, codecs) and a **control bar**:
   Pause · Sound (+ volume) · Format (Fit / Stretch / Center 1:1) · Rotate · Fullscreen · Stop.
4. The viewer **reconnects automatically** if the stream drops.

**Keyboard**: `F` fullscreen · `M` mute · `Space` pause · `Esc` exit fullscreen, then stop · double-click the picture = fullscreen. In fullscreen, controls hide after 2.5 s; move the mouse to show them.

**Settings tab (PC)**: automatic fullscreen, always on top, default display format, *Ultra-low / Balanced / Stable* buffer, volume, mute, **language (Français / English)**, about, privacy policy, terms of use, troubleshooting. Settings are saved in `%APPDATA%\ViewCast\settings.json`.

### 2.4 Wi‑Fi Direct (optional)

In the phone's **Discover** tab tap **Refresh**: nearby Wi‑Fi Direct devices appear; tap **Connect** to form the group. The address shown in *Connect* then updates (the phone is usually `192.168.49.1`). Android then asks for "Nearby Wi‑Fi devices" (or location before Android 13) — for this search only.

### 2.5 Troubleshooting

| Symptom | What to do |
|---|---|
| "Wrong PIN" | Re-read the 6-digit code on the phone. The viewer does not retry by itself: fix it and connect again. |
| "Too many attempts: a new code was generated" | After 5 wrong attempts the phone changes its code: enter the new one. |
| VLC / ffplay cannot connect | The encrypted stream is only readable by the ViewCast viewer. For VLC/ffplay turn off **Protect with a PIN** (*Settings ▸ Security*): *open mode, no protection*. |
| "Cannot reach the phone" | Same network? Casting started? Try the phone's hotspot. Check the IP. Allow Windows Firewall. |
| Black screen, then nothing | Wait 2–3 s (first key frame). If needed: *Stop* then reconnect. |
| No sound | Android 10+, microphone permission granted, "Internal sound" on, Windows volume OK. Some apps (calls, protected streaming) forbid sound capture. The viewer shows "No sound in the stream". |
| Choppy picture | Lower resolution/bitrate on the phone, or pick the **Stable** buffer on the PC; prefer 5 GHz. |
| Sound out of sync | Pick the **Ultra-low** buffer on the PC. |
| Viewer won't start from source | `python -m pip install -r desktop/python/requirements.txt` (Python 3.10–3.12, 64-bit). |

### 2.6 Security: PIN and encryption

- **On by default** (*Settings ▸ Security ▸ Protect with a PIN*). The phone generates a 6‑digit code for every cast; the receiver must enter it.
- The code is **never sent** over the network (SRP‑6a cryptographic proof) nor stored. The whole stream — picture, sound, control messages — is **encrypted with AES‑256‑GCM**; a modified or replayed packet is rejected.
- **5 wrong attempts ⇒ new code** generated automatically (the app tells you).
- **Open mode** (switch off): plain RTSP, works with VLC/ffplay, but **anyone on the network can watch**. For tests only.
- Details, protocol and limits: [docs/SECURITY.md](docs/SECURITY.md). The protocol has not been audited by a third party.

### 2.7 Phone ↔ phone (new)

One phone **casts** (as usual), the other **receives**:

1. **Phone A (sender)**: *Connect* tab → **Start casting**. Note the address (e.g. `192.168.43.1`) and the **PIN**.
2. **Phone B (receiver)**: **Receive** tab → enter A's address and PIN (leave the PIN empty if A is in open mode) → **Connect and receive**.
3. A's screen appears on B, with sound. Tap the screen to show the controls: **Sound / Muted**, **Fullscreen**, **Disconnect**; resolution, bitrate and fps are shown.
4. Network: both phones on the same Wi‑Fi, or B joined to A's hotspot (most reliable: A turns on its hotspot, B joins it).

Notes: receiving stops when you leave the app; a phone **cannot cast and receive at the same time** (it would see itself); the same encryption and PIN protect the link (see §2.6). Errors (address, wrong PIN, too many attempts, lost connection) are explained on screen.

---

## 3. Mockups ↔ app (done, adapted or deferred)

The design follows your 8 mockups (dark "obsidian" theme, coral `#FF6B4A`, amber, green/cyan status colors).

**Android (4 tabs)** — *Connect*: network status, welcome card, 3 steps, address + copy, start/stop. The mockup's "PC IP" entry becomes "the phone's address to type on the PC" (the PC connects to the phone); a **PIN code** card is added; QR code and mDNS probe are **not implemented** (planned, see §7); encryption is done with PIN + AES‑256‑GCM (see `docs/SECURITY.md`). *Discover*: real Wi‑Fi Direct search/connection, device statuses, manual entry; mDNS, PIN, per-device latency not implemented. *Cast*: "Live · 720p @ 30 FPS", connected receivers, real bitrate, resolution, audio, mute, stop confirmation; pause/fullscreen/pointer/battery not applicable. *Settings*: resolution, fps, bitrate, optimization mode, audio, **security (PIN switch)**, **FR/EN language**, about (version, © author, contact, privacy, terms, FAQ); commentary mic, hide notifications, auto-connect, bandwidth test, "Pro license" out of scope.

**Windows (4 screens)** — *Reception*: state, address and PIN entry, steps, shortcuts (no QR code). *Live*: HUD resolution/fps/bitrate/codecs, control bar, pause, volume, format, rotation ("Encrypted · AES‑256" badge in the HUD; ping, battery/temperature not applicable). *Settings*: auto fullscreen, on top, format, buffer, volume, language (GPU/CPU decoding, listening port, WebRTC: not applicable). *Fullscreen*: auto-hiding controls, `Esc`/`F` hints.

> Fonts: the mockups use Roboto Flex / Inter / JetBrains Mono; the apps use the system fonts (Roboto on Android, Segoe UI/Consolas on Windows) so nothing has to be bundled.

---

## 4. Building the project

### 4.1 Android — `ViewCast.apk`

**Requirements**: JDK 17, Android SDK (platform 34 + build-tools) — easiest: **Android Studio** (Hedgehog or newer).

*Android Studio*: *File ▸ Open* → `android/` folder → wait for Gradle sync → *Run ▸ Run 'app'* on a USB-debugging phone.

*Command line* (inside `android/`):

```bash
# Windows: gradlew.bat ... — Linux/macOS: ./gradlew ...
./gradlew assembleDebug      # test APK    → app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # release APK → app/build/outputs/apk/release/app-release.apk
```

**Sign the release APK** (once):

```bash
keytool -genkey -v -keystore viewcast-release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias viewcast
```

Then create `android/keystore.properties` (already git-ignored — **never commit it**):

```properties
storeFile=../viewcast-release.jks
storePassword=YOUR_PASSWORD
keyAlias=viewcast
keyPassword=YOUR_PASSWORD
```

Run `./gradlew assembleRelease` again. ⚠️ **Back up the `.jks`**: without it you can no longer publish updates installable over the old version. (Without `keystore.properties` the release is signed with the debug key: for testing only.)

### 4.2 Windows — `ViewCastViewer.exe`

**Requirements**: Windows 10/11, **Python 3.10–3.12 (64-bit)** with Tkinter (included by default; tick "Add Python to PATH").

*Run from source*:

```bat
cd desktop\python
python -m venv .venv
.venv\Scripts\activate
python -m pip install -r requirements.txt
python viewcast_viewer.py               :: or: python viewcast_viewer.py 192.168.43.1
```

*Build the executable* (single file, no console):

```bat
desktop\python\build_windows.bat        :: result: desktop\python\dist\ViewCastViewer.exe
desktop\windows\build.bat               :: same, copied to desktop\windows\dist\
```

The executable bundles FFmpeg (PyAV), PortAudio and the icon; expect tens of MB. Windows SmartScreen may say "unknown publisher" until the executable is code-signed: *More info ▸ Run anyway*.

### 4.3 Testing without a phone

```bash
python tools/test_viewer_logic.py          # 63 viewer-logic tests (no display needed)
python tools/test_secure_protocol.py       # 17 secure-channel tests, incl. Python <-> Java (the real Android code)
python tools/test_e2e_secure.py            # 14 end-to-end tests: encryption, wrong PIN, network sniffer (ffmpeg required)
python tools/test_receiver_java.py         # 14 tests of the Android RECEIVE client (real Java code): picture, sound, PIN, open (ffmpeg required)
(cd android && ./gradlew test)             # same cryptographic vectors on Android (JUnit)

python tools/fake_phone_server.py --pin 482913     # encrypted fake phone: test pattern + 440 Hz tone (ffmpeg required)
python desktop/python/viewcast_viewer.py 127.0.0.1:8554    # then type PIN 482913 in the window
```

### 4.4 Publishing a release (GitHub Releases)

1. Merge into `main`, then `git tag v1.4.0 && git push origin v1.4.0`.
2. On GitHub: *Releases ▸ Draft a new release* → pick the tag → attach `ViewCast.apk` and `ViewCastViewer.exe` → *Publish release*.
3. Link `https://github.com/AlphonseMumba/ViewCast/releases/latest` from your pages.

---

## 5. Repository layout

```
android/            Android app (Kotlin, Jetpack Compose): ui/, rtsp/, capture/, security/ (Java: SRP-6a PIN, AES-256-GCM), receive/ (RTSP client in Java + decoding), res/values (FR) + values-en (EN), res/raw (legal texts)
desktop/python/     Windows viewer: vc_ui (interface), vc_stream (stream, audio), vc_secure (encrypted channel + local bridge), vc_i18n (languages), vc_settings, vc_legal
desktop/windows/    icon and build script
tools/              fake phone, tests, legal text generator (gen_legal.py)
docs/SECURITY.md                            security protocol, threat model, limits
PRIVACY.md TERMS.md NOTICE.md LICENCE      legal documents and licenses
```

Legal texts have **one source**: `tools/gen_legal.py`; edit it then run `python tools/gen_legal.py` to regenerate `PRIVACY.md`, `TERMS.md` and the Android/Windows in-app texts.

---

## 6. Privacy, legal and intellectual property

- **No data collected** by the publisher: no account, server, ads or analytics. Screen and sound go **directly** from the phone to the PC on your local network; nothing is recorded.
- The stream is **encrypted (AES‑256) and PIN‑protected** by default; the *open mode* (can be turned off) protects nothing. See [docs/SECURITY.md](docs/SECURITY.md).
- Details: [Privacy Policy](PRIVACY.md) · [Terms of Use](TERMS.md) · [Third-party notices](NOTICE.md) (also available in both apps, *Settings ▸ About*).
- **Intellectual property**: ViewCast (name, logo, interface, code, documentation) © 2026 **Alphonse MUMBA**. Code under the MIT license ([LICENCE](LICENCE)); the name and logo remain the author's property.
- **Contact / support**: **mumbalpha@gmail.com**

---

## 7. Roadmap

1. ✅ **Security**: PIN + end-to-end encryption (this version).
2. ⏭️ **QR-code connection**: the computer shows a QR code (address + code), the **smartphone scans it** and connects; nothing to type.
3. 🔶 **Symmetric roles**: ✅ **phone → phone** (version 1.4). ⏭️ **PC as sender** (PC → phone, PC ↔ PC): the PC will have to capture its screen and system sound, then encode them; the current secure channel is already designed for it (the connection direction is independent of the media direction).
