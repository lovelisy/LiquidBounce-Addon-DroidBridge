# LiquidBounce DroidBridge

A [LiquidBounce](https://github.com/CCBlueX/LiquidBounce) add-on that shows the client's menus in the web view of
Android, drawn on top of the game, so the client runs on a phone without the built-in Chromium.

The web view of the system is not off-screen: it is a view of Android that sits directly above the game surface. The
add-on finds the game surface in the launcher's view tree and adds the web view right above it, so the launcher's own
buttons and the mouse cursor stay on top.

Touch and mouse input are not given to the web view through the system. They come from the launcher as Minecraft
events and are replayed into the page, which keeps the page's coordinates and the game's coordinates the same.

## Requirements

| | |
|---|---|
| Android | 8.0 or newer, with a web view (the one that comes with the system) |
| Launcher | Zalith Launcher 2 |
| Game | Minecraft 26.3, Fabric 0.20.0 or newer |
| Client | LiquidBounce `nextgen` |

## How it fits together

| Part | What it does |
|---|---|
| `src/main/kotlin` | The browser backend of the client: one web view per browser tab |
| `native/src` | Rust, drives the web view through JNI |
| `src/DroidBridge` | Java, runs on the Android side of the same process and touches the web view on its main thread |
| `src/main/resources/natives` | The Rust library, put into the jar by CI |

The game runs on the JVM, which cannot create a web view. The native library therefore finds the Android runtime of
the same process, loads the dex of the bridge with a `DexClassLoader`, and from then on posts every web view call to
the main thread of Android. Every frame the page is still drawn by Android, not by the game, so there is no texture to
import and `take_frame` always reports none.

## Building

```
./gradlew build -Pnatives=<directory with natives/android-arm64>
```

Without `-Pnatives` the library is built with cargo for the machine you are on. CI builds it for `android-arm64` and
puts it into the jar, which is what a release contains.

## Installing

Put the jar into the `mods` folder of the client, next to LiquidBounce. The client asks which browser to use the next
time it starts; pick `DroidBridge`. The choice is remembered, and holding Shift while the client starts asks again.

## Limits

The page is drawn by the web view of the system, which is a web view for a phone: it is Chromium 116 on most devices,
whatever the system ships. Anything that needs a newer engine, such as WebGPU, is not available.

`DLSS` and the deep learning parts of the client need a native PyTorch, which has no Android build that this add-on
could load.
