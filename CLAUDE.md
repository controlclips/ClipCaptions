# Clip Captions: notes for Claude

Android app showing live translated captions over TikTok, Reels and Shorts by capturing the phone's
playback sound (MediaProjection). Package `com.clipcaptions.app`, minSdk 29, targetSdk 35, Kotlin,
plus native code (whisper.cpp in C/C++, Mozilla's translator in Rust). Public repo
`controlclips/ClipCaptions`, GPL-3.0 (see NOTICE.md for the ML Kit linking permission and credits).

## The owner

- Not a developer (works in IT). Explain in plain English, UK spelling; no jargon dumps. They test
  on a Samsung phone with AirPods and want a finished APK, not instructions to build one.
- Hard requirements: **free with no running costs** (no paid APIs, servers or cloud speech),
  **speed first** (captions must appear before people swipe), works on **all phones**, covers most
  languages, **downloads happen once up front, never while a video plays**, updates **install over
  the top** (never change the signing key), works across apps.
- Plans: publish on Orion Store (pulls APKs from this repo's GitHub Releases); pitch is "learn a
  language while you scroll".

## Layout

- `ClipCaptions/app/` the app. Key files in `src/main/java/com/clipcaptions/app/`:
  - `MainActivity.kt` main screen, language choice, all up-front downloads, start flow.
  - `CaptionService.kt` foreground service: sound capture, the three caption modes, overlay, battery saving.
  - `GoogleSpeech.kt` **Instant** mode (Android on-device recogniser, segmented session, language switching).
  - `WhisperEngine.kt`, `WhisperLib.kt`, `WhisperModels.kt`, `LanguageSpotter.kt` **Any language** mode and language spotting (whisper.cpp; JNI in `src/main/cpp/whisper_jni.c`).
  - `VoskModels.kt` **Basic** mode (Vosk).
  - `VideoSwitch.kt` pure logic (swipe detection, chunking, language pick, leftover words, line splitting, speed stats). Tested by `ClipCaptions/tools/logic-tests/run.sh`.
  - `CaptionTranslator.kt` translation front door: Mozilla when its files are on the phone, else ML Kit.
    Every caption goes through `TranslationText.kt` (pure logic, also in logic-tests): capital and full
    stop added before translating, stuck repeats removed after. `SentenceJoiner.kt` (pure logic):
    every mode shows each piece of speech (split at pauses) at once, then re-translates it together
    with the pieces just before it and swaps that in (`CaptionService.joinPiece`, `CaptionOverlay.mergeLines`).
    Punctuation is only a hint: Whisper full-stops nearly every piece.
  - `MozTranslate.kt` (JNI + picks the i8mm or plain-NEON library from /proc/cpuinfo; `MozEngines` loads pairs on one thread), `MozModels.kt` (routes via English, downloads from the repo's `models` release first, Mozilla's CDN second), `MozCatalogue.kt` (pairs Mozilla publishes).
  - `CaptionOverlay.kt`, `PhraseBook.kt`/`PhrasesActivity.kt` (☆ saved phrases), `SetupActivity.kt` (first-time guide), `Diagnostics.kt` (crash saver, Report a problem → GitHub issue).
- `ClipCaptions/whisperfast/` second whisper build for ARMv8.2 dotprod phones.
- `ClipCaptions/translator/` Rust JNI crate `cctranslate` wrapping vendored **fxtranslate 0.5.0** (MPL-2.0, `translator/fxtranslate`, changes listed in `CLIP_CAPTIONS_CHANGES.md`: `FXT_ARM_KERNEL=neon64` builds a plain-NEON kernel, because the stock aarch64 kernel needs i8mm and crashes older phones).
- `ClipCaptions/branding/`, `fastlane/metadata/android/en-US/` logo, banner, store text.

## Build and release (GitHub Actions)

- `.github/workflows/main.yml` runs on every push (not `models-request`/`stats-request`): builds the Rust translator
  with cargo-ndk (neon64 for arm64/armv7/x86_64 into `app/src/main/jniLibs`, plus
  `libcctranslate_i8mm.so`), then Gradle.
  - With secret `RELEASE_KEY_PASSWORD`: `assembleRelease` signed with `keystore/release.p12`
    (alias `clipcaptions`), published as `ClipCaptions-<ver>.apk`, marked latest.
  - Without it: debug build signed with `keystore/debug.keystore`, published as a pre-release
    `ClipCaptions-<ver>-test.apk`. Test and release builds can't install over each other.
  - Cleanup keeps the 5 newest test builds and **every proper release from 6.3 on** (their
    download counts feed DOWNLOADS.md); deletes releases before 6.3 (test-key builds); never
    touches the `models` release.
- `.github/workflows/models.yml` (monthly, manual, or push to `models-request`) runs
  `.github/scripts/mirror_models.py`: copies Mozilla's Firefox Translations models (Android
  release builds, version 3) to the `models` release with `models.json`.
- `.github/workflows/stats.yml` (Mondays, manual, or push to `stats-request`) runs
  `.github/scripts/download_stats.py`: adds up APK downloads of proper releases (6.3 on, no test
  builds or model files), keeps per-version counts in `stats/downloads.json`, writes
  `DOWNLOADS.md`, commits to main with `[skip ci]`. Orion Store has no stats of its own.
- Every release bumps `versionCode` and `versionName` in `ClipCaptions/app/build.gradle.kts`
  (the app shows the version on its main screen).
- Put `[skip ci]` in commit messages that only touch docs, to save build minutes.
- Helper branches (orphan, not app code): `apk-request` → workflow copies the newest APK to branch
  `apk` (fetch an APK with plain git when the GitHub API isn't reachable); `translate-probe`
  (+ `-out`) tried the translator with real models and checked the ARM kernels under QEMU;
  `mirror-check` (+ `-out`) reports what's on the `models` release.

## Checking changes

- CI is the real build, and runs the logic tests first (a failure stops it). Locally: `ClipCaptions/tools/logic-tests/run.sh` (kotlinc + java), Rust
  `cargo build` in `ClipCaptions/translator` (host), `gcc -fsyntax-only` for the JNI C.
- Before handing over an APK: check its signing certificate matches the previous build, that
  `lib/*/libcctranslate*.so` and `libclipwhisper*.so` are present, and the version.
- Release-key cert sha256 (proper releases, from 6.6): `0c58ee0bf575605d6204b585452844fc957dbf19ca12518b4adb2d25cba68385`.
- Test-key cert sha256 (test builds): `e0a5017631f2c6999f0c1dd25e4bf767cc72f02692963773eb66ab345f4a7db8`.

## Don't

- Add paid services, analytics, ads or anything sending captions off the phone.
- Download anything while captions are running (prepare everything on the main screen first).
- Commit secrets, change or regenerate `release.p12`, or delete the `models` release.
- Ship the stock fxtranslate aarch64 build alone (i8mm only: crashes phones without it).

## Open items (as of 6.6, 13 November 2026)

- Done: `RELEASE_KEY_PASSWORD` added, repo public, first signed release 6.6 (v6.6-build23),
  screenshots 1_main.jpg and 2_languages.jpg in `fastlane/metadata/android/en-US/images/phoneScreenshots/`.
- Orion Store submission not posted yet: an issue on RookieEnough/Orion-Data titled
  "App Submission [1 App] - Clip Captions" with the JSON format Orion's app generates
  (name, id, description, icon, repoUrl, githubRepo, releaseKeyword "apk", packageName,
  category "Education", author, screenshots, platform). Owner may add a third screenshot
  (captions over a video with no recognisable person).
