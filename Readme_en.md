# PickupAssistant · Release Repository (qlm-release)

> This repository hosts **only the APK builds and the update manifest** (`version.json`). Source code is not public.
> The app reads `version.json` at the repository root to detect and install updates.

| Item | Value |
| --- | --- |
| App | 取了吗 (PickupAssistant) |
| Package | `com.hnu.pickupassistant` |
| Latest | **3.0.1 (versionCode 301)** |
| OS | Android 10+ (minSdk 29, targetSdk 34) |
| APK naming | `qlm_re_<version>.apk` (e.g. `qlm_re_3.0.1.apk`) |
| Stack | Kotlin · Jetpack Compose (Material 3) · ML Kit on-device Chinese OCR |

A fully **offline** parcel-pickup-code manager: paste an SMS, or pick a screenshot, and every pickup code lands in one list with station, shelf and courier — tap a card at the station to check it off.

---

## Software Architecture

Single-module Android app, Kotlin + Compose, unidirectional state with `StateFlow`, local persistence via `SharedPreferences` and app-private files. **No backend, no data upload** (only update checks touch the network).

| Package | Responsibility |
| --- | --- |
| `ui/` | Compose screens, dialogs and the `HyperTheme` design tokens (light/dark palettes) |
| `data/` | `LocalStorageManager` — single source of truth exposed as Flows |
| `model/` | `PickupItem` (station, shelf, tracking no., note, todo/picked flags) |
| `ocr/` | ML Kit wrapper: image enhancement, long-screenshot tiling, block splitting, text normalisation, rule matching |
| `service/` | Overlay window (single window, fixed size), screen-capture foreground service, quick-settings tile |
| `shizuku/` | Optional Shizuku screen capture (falls back to system capture) |
| `update/` | Update check (6-hour throttle, anti-downgrade), download, integrity check, save to public Downloads, launch installer |
| `util/` | Clipboard/text parsing, code matching, outbound-code detection, formatting, sampled bitmap decoding |

**Update flow**: `MainActivity.onCreate` → `UpdateChecker` (fetch manifest, compare `versionCode`, `VersionRules` blocks downgrades) → `ApkDownloader` (OkHttp, progress) → `PublicDownloadStore` (MediaStore public Downloads) → `UpdateManager` (FileProvider URI to the system installer). `forceUpdate: true` makes the dialog non-dismissible.

**Privacy**: all OCR runs on-device; screenshots are never uploaded or stored. Only update checks use the network.

---

## Installation

1. **Download** the latest `qlm_re_<version>.apk` from Releases or the repository root.
2. **Allow install**: when prompted for "unknown sources", grant it to your browser/file manager, then tap Install.
3. **Upgrade in place**: all builds share the same signing certificate (SHA-256 `05e33049…`), so installing over an older version keeps your data. Do **not** uninstall first — that wipes local data.
4. **First launch**: grant "Display over other apps" and notification permission as needed. The "看见没" quick-settings tile additionally requires Shizuku (optional).

---

## Usage

1. **Paste text** — copy a pickup SMS/chat message, tap "粘贴文字"; station, shelf and code are parsed automatically (multiple items supported).
2. **Import from screenshot** — tap "识图添加", pick a screenshot from Cainiao / Mama Post / station list / multi-parcel detail layouts. Every field (station, courier, tracking no., note) can be edited before saving. Add the "看见没" quick-settings tile to recognise the current screen in one tap.
3. **Todos & check-off** — add free-text todos; tap a code card to check it off; swipe left to delete with a 5-second undo.
4. **Floating card** — a white frosted overlay lists pending parcels grouped by shelf. Tap to check off, long-press to copy, "读取剪贴板" to import, "出库码" to open the outbound QR code (static-screenshot fallback for weak signal).
5. **Settings** — station whitelist, quick buttons, overlay options, dark mode (follows system), manual update check.

---

## Update manifest (`version.json`)

```json
{
  "versionCode": 301,
  "versionName": "3.0.1",
  "apkUrl": "https://.../qlm_re_3.0.1.apk",
  "forceUpdate": true,
  "updateLog": "Release notes shown in the update dialog",
  "releaseTime": "2026-11-12"
}
```

`versionCode` must be strictly greater than the installed one (`X.Y.Z` → `XYZ`, e.g. 3.0.1 → 301). `apkUrl` must be a direct download link. When releasing: upload APK → fill `apkUrl` → overwrite `version.json`, keeping all three version fields identical to the APK.

---

## Version history

| Version | Date | Highlights |
| --- | --- | --- |
| 3.0.1 | 2026-11-12 | Fix whole-desktop blur caused by the overlay window; system blur removed, self-drawn frosted panel, outside touches pass through |
| 3.0.0 | 2026-11-12 | White frosted overlay + motion polish; redesigned empty state |
| 2.9.9 | 2026-11-12 | Overlay reverted to a single window, full-screen blur layer removed |
| 2.9.5 | 2026-11-12 | Six OCR improvements (two-segment anchors, image enhancement, long-screenshot tiling…) |

---

## Contributing

1. Fork this repository
2. Create a `Feat_xxx` branch
3. Commit your changes
4. Open a Pull Request

This repository only hosts release artifacts. For bugs or feature requests, open an Issue with your OS version, app version and reproduction steps (screenshots welcome).

---

## Multi-language

1. Use `Readme_XXX.md` for other languages, e.g. `Readme_en.md`, `Readme_zh.md`
2. Chinese: [`README.md`](README.md) · English: this file

---

## License & disclaimer

For personal parcel organisation only; not affiliated with any courier company or station. Redistribution of modified, ad-injected or re-signed builds is prohibited.
