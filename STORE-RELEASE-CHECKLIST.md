# P4 release checklist — free 0.7 + The Circuit 1.0

This checklist prepares both apps from the same commit. Creating the paid Play
Console entry and pressing the store submission controls are deliberately
outside the repository workflow.

## 1. Locked release identity

| App | Package | versionCode | versionName | Tag |
|---|---|---:|---|---|
| Opcode Arena | `robofight.android` | 8 | `0.7` | `free-0.7` |
| Opcode Arena: The Circuit | `robofight.android.pro` | 1 | `1.0` | `pro-1.0` |

Both artifacts must come from the same Git commit. Do not change engine/ISA
behavior between the two builds; `ChallengeFormat.ENGINE_VERSION` remains 1.

## 2. Automated release gate

Run from a clean checkout with the local, git-ignored signing files present:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --no-daemon clean :engine:test :app:testFreeDebugUnitTest :app:testProDebugUnitTest :app:bundleFreeRelease :app:bundleProRelease :app:assembleFreeRelease :app:assembleProRelease
```

Expected store bundles:

- `app/build/outputs/bundle/freeRelease/app-free-release.aab`
- `app/build/outputs/bundle/proRelease/app-pro-release.aab`

Expected device-smoke APKs:

- `app/build/outputs/apk/free/release/app-free-release.apk`
- `app/build/outputs/apk/pro/release/app-pro-release.apk`

Answer the deterministic-release question in the changelog: **No — this
release does not change deterministic behavior; `ENGINE_VERSION` stays 1.**

## 3. Device smoke gate

- Fresh-install both release APKs side by side; verify labels and distinct icons.
- Upgrade the existing free app and verify its files and saved codes remain.
- Free: build/import/run/save/share a free code; open a Circuit code as a locked
  preview and follow `PLAY IN THE CIRCUIT >`.
- The Circuit: run every Arena Pack 1 arena, including multiple seeds for
  `GEN-MAZE` and `GEN-ISLES`; import a free ZIP and open an imported code.
- Rotate portrait/landscape and test once with the soft keyboard visible.
- Confirm neither manifest requests Internet permission.

## 4. Store package

Everything needed before creating/updating the listings is under
`assets/store/release/`:

- free and paid 512×512 icons;
- free and paid 1024×500 feature graphics;
- phone screenshots for each listing;
- copy source in `STORE-LISTINGS.md`;
- the shared privacy policy in `privacy/index.html`.

Console declarations for both apps: no ads, no account, no data collected or
shared, no Internet permission, app access unrestricted, target audience per the
chosen content-rating questionnaire. Verify those answers against the final
bundle before submission.

## 5. Final handoff (store UI; intentionally not automated here)

- Create the paid app entry, reserve `robofight.android.pro`, set the €1.99
  one-time price, and paste the prepared Circuit listing.
- Upload the two AABs to their respective tracks, attach their matching assets
  and privacy-policy URL, complete policy questionnaires, and review warnings.
- Tag the exact uploaded commit `free-0.7` and `pro-1.0` only after Play accepts
  the artifacts.
