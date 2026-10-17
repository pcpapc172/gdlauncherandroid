# GD Instance Launcher

Android launcher for making separate installations of a user's own Geometry Dash APK and managing their shortcuts. Android 7.0+; Android SDK 34; Java 17.

## Instance types

- **Clone installed Geometry Dash:** read the installed base APK and all configuration splits, patch and sign each, then install the APK set through Android's installer.
- **Clone a local APK:** select an original, standalone Geometry Dash APK with the document picker. Split APK sets should be cloned from an installed game instead.
- **Shortcut:** launch an already installed app. Shortcuts do not modify or duplicate the app.

Each game clone needs a valid **24-character ASCII package name**. The dialog generates one and checks for duplicates. Arbitrary-length names are deliberately rejected: fixed-size replacements preserve offsets in native libraries and resource tables. Existing game saves are not copied; each package receives its own Android app storage.

## APK transformation

The patcher does not invoke apktool or a shell. It performs the equivalent package retargeting directly in APK entries:

1. Rebuild the binary AndroidManifest string pool, change the package and component references, and set the instance label.
2. Replace dotted and slash-form references to `com.robtopx.geometryjump` across entries, including UTF-8 and UTF-16 strings. Stream native libraries and assets instead of holding large libraries in memory.
3. Patch all native-library ABIs, including `libcocos2d.so` and `libcocos2dcpp.so`. Refuse unsupported JNI package-symbol changes, which would also require rebuilding ELF symbol hashes.
4. Re-serialize DEX files with dexlib2 so string/type ordering, signatures, and checksums are valid.
5. Remove obsolete APK signatures, store native libraries at 16 KiB ZIP alignment and resources.arsc at 4-byte alignment.
6. Sign APKs using a private, locally generated identity and verify the output signatures before publishing the prepared files.

ZIP traversal, duplicate entries, excessive expanded sizes, memory limits, and cancellation are checked. Unsupported formats fail with an error rather than installing a partially patched output. Preparation preserves the previous successful output until the new result is complete.

This does not bypass game licence checks or alter Geode's game-origin checks. Compatibility with future game and Geode releases needs device testing.

## Geode

Check the Geode option on a clone to download the latest stable APK from [`geode-sdk/android-launcher`](https://github.com/geode-sdk/android-launcher). Release metadata is retrieved over HTTPS; the published SHA-256 digest is checked when available. The game library ABI determines which Geode asset is selected.

The launcher is installed as a **separate package per game instance**. Its game-package references and runtime application identity/provider authorities are retargeted while its original Java classes and native JNI bindings are preserved. Install the game first, then its Geode launcher. Removing an entry does not uninstall either package.

## Downloads and installation

WorkManager owns preparation/download tasks across rotation and process recreation. Rows use instance IDs, not RecyclerView positions. Cancel/retry, progress/error text, prepared-APK installation, explicit rebuilding, and storage cleanup are available. Android's installer handles user consent; its results are returned to the app. Unknown-source installation permission is requested before installation.

Launcher updates are downloaded separately, validated against the installed launcher's signing certificate, and exposed through an Install update button. The server cannot force an update or bypass Android installation consent.

## Instance server

The launcher uses HTTPS only:

- `https://api.pcpapc172.ir/archive/android_instances.json`
- `https://api.pcpapc172.ir/archive/latestandroidlauncher.json`

Server unavailability keeps cached entries. The API must provide valid HTTPS and JSON; there is no insecure HTTP fallback.

Minimal instance array:

```json
[
  { "id": "vanilla", "name": "My game", "version": "2.2" },
  { "id": "modded", "name": "My modded game", "geode_url": "latest" }
]
```

Without `pkg`, a deterministic 24-character package is generated from the ID. Without `url`, the installed original game is patched. A supplied `url` instead downloads an already prepared APK whose package must match `pkg`.

Optional fields: `pkg`, `url`, `version`, `version_code` (positive integer), `settings`, `main`, `geode_url`, `sha256`, `signer_sha256`. Activity components use `package/.Activity` or `package/full.Activity` and must belong to the selected game or Geode launcher. IDs must contain only letters, numbers, underscores and hyphens. The ID `launcher_update` is reserved. Server values cannot mark an entry as custom.

Update JSON:

```json
{
  "version_code": 4,
  "version_name": "2.1.1",
  "download_url": "https://example.org/GDLauncher.apk",
  "sha256": "<optional 64 hexadecimal characters>",
  "changelog": "Release notes"
}
```

Omit `sha256` when unavailable; do not send the placeholder above.

## Management and backups

Search names/packages; sort by name or installation status; favourite entries; drag custom entries in Custom order; edit entries; undo list removal; browse installed launchable apps; choose light/dark/system appearance; copy diagnostics; and remove prepared APKs from Storage.

JSON export/import preserves custom entry definitions, not game saves or local APK source files. Local-APK entries need their source selected again on another device. Invalid legacy data is retained under Export recovery data; old shortcut entries are migrated.

**Back up the signing key before reinstalling the launcher.** Use Back up signing key and a password of at least 12 characters. The encrypted PKCS#12 backup is restored with Restore signing key. Android automatic backup excludes signing keys, prepared APKs, source APKs and WorkManager's device-specific state. Losing the key means existing clones cannot be updated with a newly generated key; uninstalling a clone can erase its saves.

No signing key is shipped in this repository. A previously committed key was removed from the current tree; removal does not remove it from Git history. If it was a real release key, treat it as exposed and arrange a signing-key migration before publishing updates.

## Build and checks

```sh
bash gradlew testDebugUnitTest lintDebug assembleDebug
```

CI runs those checks and uploads the APK and reports even when later release signing is unavailable. Non-tag builds work without release secrets. Tagged releases require all signing secrets and a tag matching `versionName`; increment `versionCode` for every published update.

Release signing environment variables: `KEYSTORE_PATH`, `KEYSTORE_STORE_PASSWORD`, `KEYSTORE_KEY_ALIAS`, `KEYSTORE_KEY_PASSWORD`. GitHub Actions obtains the keystore from `KEYSTORE_BASE64` and deletes the temporary copy afterward.

## Device acceptance checks

Before publishing, exercise a legitimate game copy on physical devices:

- Base + split installation on Android 7, 12, and 14+; both available ARM ABIs.
- Vanilla launch and save isolation; UTF-8/UTF-16 package references.
- Per-instance Geode launch, settings, permissions and provider access.
- Rotation, process termination, cancellation, failed networks and insufficient storage.
- Installation cancellation/failure/retry, certificate conflict and successful update.
- Signing-key backup/restore and an update to an existing clone.

Do not redistribute patched game APKs. Users must supply their own legitimate game copy.
