# Licence and credits

Clip Captions
Copyright (C) 2026 controlclips

This program is free software: you can redistribute it and/or modify it under the terms of the GNU
General Public License as published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
General Public License for more details. You should have received a copy of the GNU General Public
License along with this program (the [LICENSE](LICENSE) file). If not, see
<https://www.gnu.org/licenses/>.

## Additional permission (GNU GPL version 3, section 7)

If you modify this Program, or any covered work, by linking or combining it with Google ML Kit or
other Google Play services libraries (or a modified version of those libraries), containing parts
covered by the terms of their licences, the licensors of this Program grant you additional
permission to convey the resulting work.

## Parts made by others

Built into the app:

| Part | What it does | Licence |
|---|---|---|
| [whisper.cpp](https://github.com/ggml-org/whisper.cpp) | Whisper speech recognition ("Any language" mode, language spotting) | MIT |
| [Vosk](https://github.com/alphacep/vosk-api) and [JNA](https://github.com/java-native-access/jna) | Word-by-word recognition ("Basic" mode) | Apache 2.0 |
| [fxtranslate](https://crates.io/crates/fxtranslate) ([source](https://github.com/gregtatum/translations)) | Mozilla's Firefox Translations engine, in Rust: offline translation. Lightly changed for phones; see [`ClipCaptions/translator/fxtranslate/CLIP_CAPTIONS_CHANGES.md`](ClipCaptions/translator/fxtranslate/CLIP_CAPTIONS_CHANGES.md) | MPL 2.0 |
| [gemmology](https://github.com/mozilla/gemmology), [xsimd](https://github.com/xtensor-stack/xsimd) | Fast maths used by fxtranslate | MIT, BSD 3-Clause |
| [jni-rs](https://github.com/jni-rs/jni-rs) | Connects the translator to the app | MIT / Apache 2.0 |
| [Google ML Kit Translation](https://developers.google.com/ml-kit/language/translation) | Backup translator for languages Mozilla doesn't cover | [ML Kit terms](https://developers.google.com/ml-kit/terms) |
| AndroidX, Material Components | App framework and design | Apache 2.0 |
| [Unbounded](https://github.com/googlefonts/unbounded), [Atkinson Hyperlegible](https://www.brailleinstitute.org/freefont/) | Fonts | SIL Open Font License 1.1 |

Downloaded by the app when you choose languages (not part of this repository):

| Files | From | Licence |
|---|---|---|
| Firefox Translations models | Clip Captions' copy on this repository's `models` release, or Mozilla | MPL 2.0 |
| Whisper models, Silero speech detector | Hugging Face (ggerganov/whisper.cpp, ggml-org/whisper-vad) | MIT |
| Vosk speech models | alphacephei.com | Apache 2.0 |
| ML Kit translation packs, Google speech packs | Google, through Android | Google's terms |

Clip Captions isn't made by, or connected with, TikTok, Instagram, YouTube, Mozilla or Google.
