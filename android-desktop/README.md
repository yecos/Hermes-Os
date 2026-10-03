# Hermes Desktop for Android

Hermes Desktop is the Android desktop shell inside Hermes OS. The goal is to bring a DeX-like workflow to compatible Android phones without depending on Samsung-only APIs.

## MVP 0.1

- Detects secondary/external Android displays.
- Opens an independent Hermes Desktop shell using Android Presentation.
- Lists installed launcher apps.
- Launches apps on the external display using public Android ActivityOptions APIs when the device allows it.
- Includes Termux and Hermes shortcuts.
- Keeps the phone screen as a lightweight controller/status panel.
- Uses only Android framework APIs in the first milestone: no third-party runtime dependencies.

## Requirements

- Android 8.0+ for the app itself.
- A device with a usable external/secondary display path for true monitor mode.
- Android 16 QPR3+ is preferred because supported devices expose the modern connected-display desktop environment.
- On older/vendor Android builds, external-display and freeform behavior depends on OEM capabilities.
- Termux integration requires Termux and allow-external-apps=true in ~/.termux/termux.properties, plus permission for com.termux.permission.RUN_COMMAND.

## Build

The project is in android-desktop/.

Requirements:
- JDK 17
- Android SDK API 37
- Android Build Tools 36.0.0
- Gradle 9.6.0
- Android Gradle Plugin 9.4.0

Build command:

    gradle :app:assembleDebug

APK:

    app/build/outputs/apk/debug/app-debug.apk

GitHub Actions also builds the debug APK and publishes it as a workflow artifact.

## Architecture

    Android phone
      |
      +-- MainActivity          phone controller / display discovery
      +-- DesktopPresentation   independent UI on external display
      +-- DesktopShellView      launcher + taskbar + app grid
      +-- AppRepository         installed app discovery
      +-- TermuxBridge          controlled bridge to Termux/Hermes

## Next milestones

1. Real phone-as-touchpad controller.
2. Accessibility/Shizuku optional integration for stronger window control on non-rooted devices.
3. Desktop task switching and recent apps.
4. TV mode with remote/D-pad navigation.
5. Hermes voice overlay and intent router.
6. Linux/Termux application shortcuts.
7. Per-device capability detection and OEM compatibility profiles.
8. Root/system build flavor for custom ROMs with deeper window-management control.

## Important limitation

An APK cannot add physical video output to a phone whose USB hardware does not support it. Hermes Desktop can use external displays that Android exposes, or later stream a virtual desktop to another receiver.
