# ViewCast — Guide d'intégration de la refonte

## Structure du ZIP

```
viewcast-refonte/
├── android/          → Fichiers Android (Kotlin + Compose)
├── ios/              → Fichiers iOS (SwiftUI)
├── viewcast-desktop/ → Viewer Desktop (Tauri + React)
└── INTEGRATION_GUIDE.md
```

---

## 🟢 ANDROID

### Fichiers à copier

1. **Remplace** `android/app/build.gradle`
2. **Crée** les dossiers :
   - `android/app/src/main/java/com/example/viewcast/ui/theme/`
   - `android/app/src/main/java/com/example/viewcast/ui/components/`
   - `android/app/src/main/java/com/example/viewcast/ui/viewmodel/`
3. **Copie** les fichiers `.kt` dans leur package respectif
4. **Remplace** `android/app/src/main/AndroidManifest.xml`
5. **Remplace** `MainActivity.kt`

### Dépendances ajoutées
- Material 3 Compose
- ZXing (QR Code)
- ViewModel Compose

### Build
```bash
cd android
./gradlew assembleDebug
```

---

## 🔵 iOS

### Fichiers à copier

Dans `ios/ViewCast/`, **remplace** ou **crée** :
- `AppDelegate.swift` (CORRIGÉ — n'était pas vide)
- `SceneDelegate.swift` (CORRIGÉ — n'était pas vide)
- `ViewCastApp.swift` (Entry point iOS 14+)
- `ContentView.swift` (UI complète)
- `CastViewModel.swift` (Nouveau — ViewModel)
- `Info.plist` (Mis à jour avec Scene Manifest)

### Important
- Si tu cibles **iOS 13** uniquement : garde `AppDelegate.swift` avec `@UIApplicationMain`, supprime `ViewCastApp.swift`
- Si tu cibles **iOS 14+** : garde `ViewCastApp.swift` avec `@main`

### Build
```bash
cd ios
xcodebuild -scheme ViewCast -destination 'platform=iOS Simulator,name=iPhone 15'
```

---

## 🖥️ DESKTOP (Tauri + React)

### Prérequis
- Node.js 18+
- Rust + Cargo
- Tauri CLI : `cargo install tauri-cli`
- FFmpeg / FFplay installé et dans le PATH

### Setup
```bash
cd viewcast-desktop
npm install
cargo tauri dev
```

### Build release
```bash
cargo tauri build
# Génère : .exe (Windows), .dmg (macOS), .AppImage / .deb (Linux)
```

### Architecture
- **Frontend** : React + Vite + CSS custom (dark theme)
- **Backend** : Rust (Tauri) — gère FFplay pour la lecture RTSP
- **Fonctionnalités** :
  - Connexion manuelle par URL RTSP
  - Scan QR (caméra ou collage URL)
  - Visionneuse avec contrôles
  - Affichage IP locale

---

## ⚠️ Notes importantes

### Serveur RTSP
Le serveur RTSP reste **minimal** (démo). Pour la production :
- Migrer vers **GStreamer** ou **live555**
- Ou passer à **WebRTC** pour réduire la latence

### Sécurité
- Le stream RTSP actuel est **non chiffré**
- Ajouter un **token** dans l'URL pour l'authentification
- Exemple : `rtsp://192.168.49.1:8554/stream?token=abc123`

### Réseau
- Android : Wi-Fi Direct ou Point d'accès
- iOS : Partage de connexion (Hotspot) obligatoire
- Desktop : doit être sur le même réseau local

---

## 🚀 Prochaines étapes suggérées

1. [ ] Ajouter mDNS/Bonjour pour discovery auto
2. [ ] Implémenter le scan QR caméra sur Desktop (react-qr-reader)
3. [ ] Ajouter un token d'authentification au stream
4. [ ] CI/CD GitHub Actions pour builds auto
5. [ ] Tests sur appareils réels
