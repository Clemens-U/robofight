# Shield timing release — Opcode Arena 0.8 + The Circuit 1.1

Build both apps from the same tagged commit after merging the shield branch.
This release changes deterministic combat behavior: `SHIELD` activates over two
ticks and protects against every projectile during five world resolutions.
`ChallengeFormat.ENGINE_VERSION` is 2. Existing `eng=1` Challenge Codes remain
playable, with `OLD ENGINE — REPLAY USES CURRENT RULES` shown on import; replay
results may differ from those produced by engine version 1.

| App | Package | versionCode | versionName |
|---|---|---:|---|
| Opcode Arena | `robofight.android` | 10 | `0.8` |
| Opcode Arena: The Circuit | `robofight.android.pro` | 3 | `1.1` |

## Automated gate

From the release commit with the local signing files present:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --no-daemon clean :engine:test :app:testFreeDebugUnitTest :app:testProDebugUnitTest :app:bundleFreeRelease :app:bundleProRelease :app:assembleFreeRelease :app:assembleProRelease :app:lintFreeRelease :app:lintProRelease
```

Verify the signed AABs and APKs under `app/build/outputs/`, their package IDs,
version codes, upload certificate, and absence of Internet permission. A build
without the git-ignored signing files produces unsigned release APKs and does
not satisfy this gate.

## Device gate

- Upgrade both installed signed apps without uninstalling. Confirm firmware,
  saved codes, and imported files survive.
- Run a regular fight in each app. Test shield activation, multiple hits during
  one window, and the end of protection after five resolutions.
- Import a frozen `eng=1` code in each app and verify the warning. Generate a
  new code and verify it carries `eng=2`.
- Free: open a Circuit code as a locked preview. Circuit: run Arena Pack 1 and
  import a Free export ZIP.
- Check portrait and landscape layouts, the soft keyboard, export/import, and
  both app labels and icons.

## Play handoff

The update notes are in `assets/store/release/free/` and
`assets/store/release/circuit/`. Upload the matching signed AAB to each app's
existing Play listing, review the Console policy and device warnings, and
record the exact accepted commit and artifact hashes. Publish only after both
updates have passed review. Keep the release tag on that same commit.
