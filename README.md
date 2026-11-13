# LiquidBounce DroidBridge

A [LiquidBounce](https://github.com/CCBlueX/LiquidBounce) add-on that renders the client's web UI with CEF on
Android. The page is rasterized by a Chromium host APK and uploaded as a texture, so the client draws it in-game with
its own render pipeline.

## Requirements

| | |
|---|---|
| Android | 8.0 or newer, arm64-v8a |
| Launcher | Zalith Launcher 2 |
| Game | Minecraft 26.3, Fabric 0.20.0 or newer |
| Client | LiquidBounce `nextgen` |
| Host | [DroidBridge Host](https://github.com/lovelisy/LiquidBounce-Addon-DroidBridge/tree/app) (Cefrium 0.19.0) |

## How it fits together

| Part | What it does |
|---|---|
| `src/main/kotlin/.../cef` | CEF browser backend: creates the browser and uploads its frames as a texture |
| `src/main/java/com/cinemamod/mcef` | JNI bindings for `libmcef.so`, loaded from the jar |
| `src/main/resources/natives/android-arm64/libmcef.so` | The MCEF bridge to the host process |
| Host APK (`app` branch) | Owns `libcef.so`, runs the CEF browser and serves frames over `127.0.0.1:39090` |

The game process cannot load `libcef.so` itself, so the browser runs in the host APK. The add-on talks to it over the
local IPC bridge and draws the returned BGRA frames through LiquidBounce's texture pipeline.

## Building

```
./gradlew build
```

CI builds the jar on `main` and publishes it to the `snapshot` release.

## Installing

1. Install the host APK from the `app` branch and open it once to start the host service.
2. Put the jar into the `mods` folder of the client.
3. Start the game. `droidbridge-cef` is the only selectable browser backend.

## Credits and licenses

This project is licensed under GPL-3.0 (see `LICENSE`).

| Component | License | Source |
|---|---|---|
| LiquidBounce | GPL-3.0 | https://github.com/CCBlueX/LiquidBounce |
| MCEF-Android JNI bindings (`McefNative`, `GlfwToAndroidKey`) | GPL-3.0-or-later, Copyright (C) 2026 Rain_Clay and contributors | MCEF Engine Plugin |
| `libmcef.so` | GPL-3.0-or-later, from the MCEF Engine Plugin | `app` branch of this repository |
| Cefrium (host runtime) | LGPL-3.0-or-later | https://codeberg.org/cefrium/cef-android |
| Chromium Embedded Framework | BSD-3-Clause | https://bitbucket.org/chromiumembedded/cef |

The original copyright headers are kept in the source files they belong to.
