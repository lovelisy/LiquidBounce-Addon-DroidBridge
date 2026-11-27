# LiquidBounce DroidBridge

A [LiquidBounce](https://github.com/CCBlueX/LiquidBounce) add-on that renders the client's web UI with CEF on Android.

Chromium runs in the official [MCEF Engine Plugin](https://github.com/RainClay/Mcef-Android) host process, not inside
Minecraft's JVM. Frames come back as BGRA over `127.0.0.1:39090` and are uploaded as a texture. This repository only
builds the add-on jar; the host APK is not built or bundled here.

| Host APK | Source |
|---|---|
| `McefEnginePlugin-0.18.1-arm64-v8a.apk` | [MCEF Engine Plugin v0.18.1](https://github.com/RainClay/Mcef-Android/releases/tag/v0.18.1-plugin) |

## Requirements

| | |
|---|---|
| Android | 8.0 or newer, arm64-v8a |
| Launcher | Zalith Launcher 2 |
| Game | Minecraft 26.3, Fabric 0.20.0 or newer |
| Client | LiquidBounce `nextgen` |
| Host | [MCEF Engine Plugin 0.18.1](https://github.com/RainClay/Mcef-Android/releases/tag/v0.18.1-plugin) (Cefrium 0.18.0, Chromium 156) |

## How it fits together

| Part | What it does |
|---|---|
| `src/main/kotlin/.../cef` | CEF browser backend: creates the browser and uploads its frames as a texture |
| `src/main/java/com/cinemamod/mcef` | JNI bindings for `libmcef.so`, loaded from the jar |
| `src/main/resources/natives/android-arm64/libmcef.so` | The MCEF bridge to the host process, taken from the 0.18.1 release |
| MCEF Engine Plugin APK | Owns `libcef.so`, runs the CEF browser and serves frames over `127.0.0.1:39090` |

The game process cannot load `libcef.so` itself, so the browser runs in the engine plugin's host process. The add-on
talks to it over the local IPC bridge and draws the returned BGRA frames through LiquidBounce's texture pipeline.

The bridge uses MCEF IPC protocol version 2. Since 0.18.1 the plugin validates a handshake
(`{"cmd":"hello","protocol":2,"token":"mcef-ipc-v2"}`) within three seconds of every connection, so the bundled
`libmcef.so` and the installed plugin must come from the same release. Mixing versions fails with
`handshake rejected: protocol ...` in the plugin log.

## Building

```
./gradlew build
```

CI builds the jar on `main` and publishes it to the `snapshot` release.

## Installing

1. Install `McefEnginePlugin-0.18.1-arm64-v8a.apk` from the
   [official release](https://github.com/RainClay/Mcef-Android/releases/tag/v0.18.1-plugin). It provides `libcef.so`
   and `libmcef.so`, and its host service starts automatically once the launcher loads the plugin.
2. Put the jar into the `mods` folder of the client.
3. Start the game. `droidbridge-cef` is the only selectable browser backend.

> The former `app` branch, which built a private host APK, has been removed. The official plugin replaces it, so there
> is no separate host build or manual "start host" step anymore.

## Credits and licenses

This project is licensed under GPL-3.0 (see `LICENSE`).

| Component | License | Source |
|---|---|---|
| LiquidBounce | GPL-3.0 | https://github.com/CCBlueX/LiquidBounce |
| MCEF Engine Plugin (host APK, IPC protocol 2) | GPL-3.0 | [RainClay/Mcef-Android](https://github.com/RainClay/Mcef-Android) · [v0.18.1-plugin](https://github.com/RainClay/Mcef-Android/releases/tag/v0.18.1-plugin) |
| MCEF-Android JNI bindings (`McefNative`, `GlfwToAndroidKey`) | GPL-3.0-or-later, Copyright (C) 2026 Rain_Clay and contributors | https://github.com/RainClay/Mcef-Android |
| `libmcef.so` | GPL-3.0-or-later, from the MCEF Engine Plugin | https://github.com/RainClay/Mcef-Android |
| Cefrium (host runtime) | LGPL-3.0-or-later | https://codeberg.org/cefrium/cef-android |
| Chromium Embedded Framework | BSD-3-Clause | https://bitbucket.org/chromiumembedded/cef |

The original copyright headers are kept in the source files they belong to.
