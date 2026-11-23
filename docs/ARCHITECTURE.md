# Architecture

This project keeps the official OpenCode desktop renderer unchanged and adds a
thin Android host around it.

## Layers

| Layer | Responsibility |
|---|---|
| `MainActivity` | Owns the Activity, GeckoView session, permissions and lifecycle. |
| `LocalUiServer` | Serves the renderer from a stable loopback origin, proxies `/api/*` to the engine, brokers host events and injects the bridge assets into `index.html`. |
| `GeckoHost` | Creates the process-wide `GeckoRuntime`, forwards configuration changes and hosts the content delegate. |
| `runtime/local` | `LocalRuntimeService`, the PRoot/Alpine guest, engine install/start/stop, the foreground notification and idle policy. |
| `bridge` assets | Small scripts that expose the subset of the desktop host API the official renderer uses. They are injected at the top of `index.html` before the renderer loads. |
| `core/storage` | Android storage access and the document/attachment pickers used by the renderer. |
| `update` | Optional app-update manifest checking. Download/install is deliberately not implemented yet. |

## Request flow

1. `MainActivity` starts the local runtime service and waits for the engine to
   listen on `127.0.0.1:4097`.
2. `LocalUiServer` listens on a fixed loopback port. Its origin is stable across
   restarts because GeckoView stores `localStorage` per origin.
3. The GeckoView session navigates to the local UI server.
4. The renderer calls `/api/*` through `LocalUiServer`, which proxies those
   requests to the engine.
5. The renderer calls `window.electron` / `window.api` for desktop host features.
   The bridge scripts translate those calls into host RPCs over the same loopback
   server.

The engine and renderer are released together upstream. This repository ships a
matching pair; the exact versions are pinned in
`andcode/app/src/main/assets/local-runtime-manifest.json`.

## Host-boundary rules

- The renderer and its assets are never patched. All adaptation lives in the
  Android host or in the bridge assets injected into the page.
- The local UI server binds to loopback only and requires a per-process boot
  token for host RPC.
- `platform.version` reported to the renderer is the upstream OpenCode version,
  not the Android shell version; the renderer uses it for layout migrations.

## Why PRoot

The official OpenCode engine is a Linux binary. PRoot provides a user-space
`chroot`-style environment without requiring root, using a bundled Alpine
rootfs. It is a compatibility layer, not a security sandbox; see the limitations
in the README.
