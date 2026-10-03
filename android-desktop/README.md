# Hermes Desktop for Android

Hermes Desktop is the Android desktop shell inside Hermes OS. The goal is to bring a DeX-like workflow to compatible Android phones without depending on Samsung-only APIs.

## v0.2

The second milestone turns the phone into a real companion controller for the external Hermes shell.

### Included

- External/secondary display detection.
- Independent desktop UI on the external display using Android Presentation.
- Desktop and TV modes.
- Installed-app launcher with D-pad/keyboard navigation.
- Phone touchpad:
  - swipe to move app selection,
  - tap to launch the selected app.
- Search box on the phone that filters the external launcher in real time.
- Phone soft keyboard for launcher search.
- Recent-app shortcuts inside the Hermes taskbar.
- Network and battery status in the external top bar.
- Optional Accessibility service for Back, Home and Recents global Android actions.
- Initial Termux/Hermes bridge.
- Shizuku installation detection as a hook for the future privileged-input layer.
- GitHub Actions build producing a debug APK.

## Security / accessibility scope

The optional accessibility service is intentionally minimal. It does not retrieve window content and only exposes Android global navigation actions used by the controller.

## Current input limitation

Without root, system privileges or a Shizuku-backed privileged component, Android does not allow a normal APK to inject an arbitrary mouse pointer into third-party apps on another display.

Therefore v0.2 is explicit about the boundary:

- the phone touchpad controls the Hermes launcher/shell;
- physical Bluetooth/USB mouse and keyboard work normally with Android apps;
- Back/Home/Recents can be triggered through the optional accessibility service;
- deeper cross-app pointer injection is reserved for the Shizuku/root layer.

## Requirements

- Android 8.0+ for the app.
- A device with a usable external/secondary display path for true monitor mode.
- Android 16/API 36 is the current stable target.
- OEM behavior still determines external-display/freeform support on older devices.
- Termux integration requires Termux and allow-external-apps=true in ~/.termux/termux.properties, plus permission for com.termux.permission.RUN_COMMAND.

## Build

Requirements:
- JDK 17
- Android SDK API 36
- Android Build Tools 36.0.0
- Gradle 9.6.0
- Android Gradle Plugin 9.4.0

From android-desktop/:

    gradle :app:assembleDebug

APK:

    app/build/outputs/apk/debug/app-debug.apk

## Next milestone

v0.3 focuses on the privileged input layer and full desktop window orchestration:

1. Shizuku companion/service for pointer/key injection where Android allows it.
2. Per-device capability profiles.
3. Better task switching and window placement.
4. Hermes voice overlay and intent router.
5. Linux/Termux application shortcuts.
6. Streaming/virtual-display path for phones without USB video output.
