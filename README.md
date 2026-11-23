# OpenCode for Android

An Android host for the official [OpenCode](https://opencode.ai) desktop application.

The app runs the official desktop renderer and engine on the device, inside a
GeckoView host. It does not reimplement or reskin the OpenCode interface: the
renderer assets are loaded byte-for-byte from the upstream desktop build.

> **This is not an official mobile edition of OpenCode.**
> It is an independent runtime project. It is not affiliated with, endorsed by,
> or supported by OpenCode or Anomaly. OpenCode is a trademark of its respective
> owner; the upstream application is distributed under the MIT license.

## What it does

- Launches the official OpenCode desktop renderer in a GeckoView session.
- Serves that renderer from a loopback HTTP server with a narrow host bridge.
- Starts a bundled OpenCode engine (`PRoot` + Alpine) on the device, so the app
  works without a desktop machine and without downloading an engine on first run.
- Keeps the Android shell out of the way: system bars, lifecycle, keyboard,
  touch input and background execution are adapted in the host layer only.

The result is the normal OpenCode desktop experience, running locally on Android.

## Requirements

- Android 10 (API 29) or newer.
- `arm64-v8a` device.
- About 1.5 GB of free storage for the installed app and runtime data.

## Install

1. Open [Releases](https://github.com/TianErCode/opencode-android/releases).
2. Download the latest `OpenCode-Android-*-arm64-v8a.apk`.
3. Allow installation from unknown sources if Android asks.
4. Grant "All files access" when prompted. The engine runs in a Linux guest and
   needs this access to read project directories on the device.

The APK bundles the engine and runtime; after installation, normal use is local
and does not require network access for the app itself. Model providers may of
course require network access, as configured by the user in OpenCode.

## Architecture

```text
┌──────────────────────────── Android app (this repository) ───────────────────────────┐
│ MainActivity                                                                          │
│   ├─ GeckoView  ──loads──>  http://127.0.0.1:<local UI server>/                       │
│   └─ LocalUiServer: serves the official renderer, proxies /api to the engine          │
│                                                                                       │
│ Host bridge scripts (assets/bridge) expose the small desktop-only surface the        │
│ renderer expects: window.electron, window.api, local file/attachment pickers, menu    │
│ routing, back handling and touch/hover adaptation.                                    │
│                                                                                       │
│ LocalRuntimeService: foreground service that manages the engine process and PRoot     │
│ guest. The guest runs Alpine and the official OpenCode engine on 127.0.0.1:4097.      │
└───────────────────────────────────────────────────────────────────────────────────────┘
```

More detail: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Building

The official renderer and runtime assets are **not redistributed in this
repository**. They are large upstream artifacts and remain under their original
licenses. A full APK build therefore needs those assets staged locally; see
[docs/BUILDING.md](docs/BUILDING.md). The supported binary artifact is the
signed APK published in Releases.

## Known limitations

- `arm64-v8a` only.
- The built-in updater works in this release: Settings › Updates checks
  the build's `versionCode` against
  [`update.json`](https://github.com/TianErCode/opencode-android/blob/main/update.json),
  downloads the APK, verifies its SHA-256 and size, and hands it to the Android
  system installer. Android still asks the user to confirm the install.
- The local engine stays resident through its foreground service. It does not
  hold a CPU wake lock while merely ready: real work keeps the CPU busy or is
  woken by its own I/O, and an idle phone is allowed to suspend. Automatic idle
  shutdown is off by default; use the Stop action on the notification to stop
  the engine explicitly.
- The engine runs in a single-user PRoot sandbox. Android permission checks and
  POSIX ownership inside the guest are not security boundaries; do not use the
  guest as a security isolation layer.

## License

The Android host layer in this repository is MIT licensed; see [LICENSE](LICENSE).
The official OpenCode renderer and engine are separate upstream works, are not
included in this repository, and remain under the upstream MIT license. See
[NOTICE](NOTICE) for attribution details.
