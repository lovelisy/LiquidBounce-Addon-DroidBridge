# DroidBridge Host

The CEF host for [LiquidBounce DroidBridge](https://github.com/lovelisy/LiquidBounce-Addon-DroidBridge/tree/main).

It packages Cefrium (Chromium Embedded Framework for Android) into a standalone APK and runs the browser in a
foreground service. The add-on in the game process connects over `127.0.0.1:39090` and receives BGRA frames as a
texture.

`libcef.so` has to live in this APK: Cefrium's renderer runs in an isolated process, and an isolated UID can only
read the `nativeLibraryDir` of its own APK.

## Building

```
./gradlew :app:assembleDebug
```

CI builds the APK on the `app` branch and uploads it as an artifact.

## Installing

1. Install the APK.
2. Open it once. The host service starts and stays in the notification bar.
3. Install the add-on jar from `main` and start the game.

## Credits and licenses

This project is based on the MCEF Engine Plugin by Rain_Clay, licensed under GPL-3.0. The original copyright
headers are kept in the source files.

| Component | License | Source |
|---|---|---|
| MCEF Engine Plugin | GPL-3.0, Copyright (C) 2026 Rain_Clay and contributors | MCEF Engine Plugin |
| Cefrium 0.19.0 | LGPL-3.0-or-later | https://codeberg.org/cefrium/cef-android |
| Chromium Embedded Framework | BSD-3-Clause | https://bitbucket.org/chromiumembedded/cef |

This project is licensed under GPL-3.0 (see `LICENSE`).
