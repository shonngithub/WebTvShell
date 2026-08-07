# WebTvShell

WebTvShell is a minimal Android TV web player shell. It opens configured channel websites in the system WebView, applies site-specific playback/fullscreen rules, and exposes a configuration page directly from the Android box over the local network.

## MVP Design

- Pure Android Framework Java code; no runtime third-party libraries.
- System WebView with native custom-view fullscreen support.
- Remote-control and touch channel navigation.
- Embedded LAN HTTP configuration service on port `6688`.
- Static HTML/CSS/JavaScript console bundled in the APK.
- Local JSON configuration with active/previous/history rollback.
- Applying configuration restarts the app automatically.
- The bundled factory configuration includes the CCTV live index and CCTV-1 through CCTV-17, CCTV-5+, CCTV-9 Documentary, CCTV-14 Children, plus 32 Yangshipin satellite channels and a separate six-channel group for the existing local-site sources. See the source-rule guide for verification status.

See [PROJECT_PLAN.md](PROJECT_PLAN.md) for the full design and scope.

Source configuration, matching rules, and the new-site onboarding workflow are documented in [docs/SOURCE_RULE_GUIDE.md](docs/SOURCE_RULE_GUIDE.md). AI coding agents must read [AGENTS.md](AGENTS.md) before making changes.

## Build

Requirements:

- JDK 17
- Android SDK Platform 33
- Android Build Tools 34 or newer
- Android 5.0/API 21 or newer; 32-bit ARM (`armeabi-v7a`) and 64-bit ARM (`arm64-v8a`) are supported because the app has no native libraries. Live-site compatibility still depends on the device's WebView, TLS stack, and codecs.
- Gradle Wrapper 8.5 and Android Gradle Plugin 8.2.0 are included in the repository.
- No Node/npm, Docker, or runtime third-party dependency is required.

```powershell
$env:JAVA_HOME = 'D:\Program Files\Java\jdk-17'
.\gradlew.bat :app:assembleDebug :app:lintDebug :app:assembleRelease
.\gradlew.bat :app:dependencies --configuration debugRuntimeClasspath
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`; the unsigned release APK is at `app/build/outputs/apk/release/app-release-unsigned.apk`.

## Configure

1. Launch the app on an Android box connected to the local network.
2. Open the configuration address shown by the app, normally `http://BOX_IP:6688`.
3. Edit channels and site profiles, validate, then apply.
4. The app restarts and loads the new active configuration.

On the box, press MENU or tap the playing video to open the channel list. Visible group tabs separate CCTV and satellite channels; D-pad left/right or a tab switches groups, and ENTER or a single tap selects a channel.

The configuration endpoint has no authentication and must not be exposed outside a trusted LAN.
