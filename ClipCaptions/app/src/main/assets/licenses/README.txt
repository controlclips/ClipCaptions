Third-party parts of Clip Captions

Fonts
- Unbounded (SIL Open Font License 1.1) - Unbounded-OFL.txt
- Atkinson Hyperlegible (SIL Open Font License 1.1) - AtkinsonHyperlegible-OFL.txt

Offline translator
- fxtranslate 0.5.0, a Rust version of the Firefox Translations engine, Mozilla Public License 2.0 -
  fxtranslate-MPL-2.0.txt. Source: https://crates.io/crates/fxtranslate
  (https://github.com/gregtatum/translations). Clip Captions builds it with small changes so it
  runs fast on phones without the newest CPU features; the changed files are listed in
  translator/fxtranslate/CLIP_CAPTIONS_CHANGES.md in the Clip Captions source.
- Translation models: Mozilla's Firefox Translations models, downloaded to the phone when you
  choose your languages (Mozilla Public License 2.0).
