# IconMod

An LSPosed module for customizing icons in Samsung One UI Home.

## Features

- Icon corner and size adjustments
- Icon rendering and icon-pack support
- Backdrop blur and visual effects
- Tint and related visual customization

> **Status:** Experimental / initial public release. Behavior may vary by device, One UI version, launcher build, and installed modules.

## Requirements

- Android 10 (API 29) or newer
- Samsung One UI Home
- LSPosed-compatible framework
- Enable the module for One UI Home (`com.sec.android.app.launcher`)

## Build

1. Open this project in Android Studio.
2. Allow Gradle to sync.
3. Select **Build > Build Bundle(s) / APK(s) > Build APK(s)**.

The project uses Android Gradle Plugin 8.5.2, Java 17, compile SDK 34, and the Xposed API as a compile-only dependency.

## Installation

1. Build and install the APK.
2. Enable IconMod in LSPosed and select One UI Home as its scope.
3. Restart One UI Home or reboot if needed.

Use at your own risk. Keep a way to disable the module if the launcher becomes unstable.

## Disclaimer

This is an unofficial community project. It is not affiliated with or endorsed by Samsung, LSPosed, or the Xposed developers. Launcher updates may cause instability or compatibility issues.

## Bug reports

When reporting an issue, include your device model, Android version, One UI version, LSPosed version, and relevant logs.
