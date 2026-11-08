# Clip Captions (app source)

This folder is the Android app. See the [main README](../README.md) for what it does, how to install it and how to build it, [PRIVACY.md](../PRIVACY.md) for privacy, and [NOTICE.md](../NOTICE.md) for the licence and credits.

- `app/`: the app (Kotlin), with Whisper's native code in `app/src/main/cpp`
- `whisperfast/`: the faster Whisper build for newer phones
- `translator/`: Mozilla's offline translator, in Rust
- `branding/`: logo, icon and banner
- `keystore/`: signing keys (`release.p12` is password-locked; `debug.keystore` is for test builds)
