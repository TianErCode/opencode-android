# Building

The repository contains the Android host-layer source and the bridge assets. It
does **not** redistribute the official OpenCode renderer or the Linux engine /
Alpine runtime: those are large upstream artifacts under their own licenses and
are fetched from the upstream distribution at packaging time.

The signed APKs in [Releases](https://github.com/TianErCode/opencode-android/releases)
are the supported binary artifact. The instructions below are for developers who
want to work on the Android host.

## Prerequisites

- JDK 17
- Android SDK with API 35 build tools
- Python 3.10+ (for the runtime asset preparation scripts)
- A POSIX-ish shell on Windows: use PowerShell; `gradlew.bat` is included.

`andcode/gradle.properties` intentionally does not contain a machine-specific
Python path. The Gradle project resolves the interpreter in this order:

1. `-Popencode.python=<path>`
2. `OPENCODE_PYTHON` environment variable
3. `python` (Windows) or `python3` (other platforms)

## Assets that are not in the repository

A full packaging build expects these local paths (they are in `.gitignore`):

```text
andcode/app/src/main/assets/desktop-web/          official desktop renderer tree
andcode/app/src/arm64/assets/runtime/arm64-v8a/   engine, Alpine rootfs and package repo
andcode/app/src/x8664/assets/runtime/x86_64/      same, for emulators
```

The official renderer comes from the upstream desktop distribution; the engine
and rootfs are the upstream OpenCode release and an Alpine minirootfs. Obtain
those from upstream and place them at the paths above. The exact versions this
source was developed against are recorded in
`andcode/app/src/main/assets/local-runtime-manifest.json`.

If an asset is missing, Gradle fails early in the packaging task rather than
producing an APK that is missing the engine.

## Checks and tests

With the assets staged:

```bash
cd andcode
./gradlew :app:testFdroidArm64DebugUnitTest
```

For a release build, provide a signing keystore through the four Gradle/
environment values used by `andcode/app/build.gradle.kts`:

```text
OPENCODE_STORE_FILE
OPENCODE_STORE_PASSWORD
OPENCODE_KEY_ALIAS
OPENCODE_KEY_PASSWORD
```

Then:

```bash
./gradlew :app:assembleFdroidArm64Release
```

The output is under `andcode/app/build/outputs/apk/`.

> Without the upstream assets a source checkout cannot produce the full APK.
> That is intentional: the repository publishes the host-layer source, not a
> redistribution of the official renderer or runtime.
