# Changes made for Clip Captions

This folder is fxtranslate 0.5.0 from crates.io (https://crates.io/crates/fxtranslate,
source https://github.com/gregtatum/translations), licensed MPL-2.0 (see LICENSE).

Modified files:
- `Cargo.toml`: removed test, example and dev-only entries; builds as a plain Rust library.
- `build.rs`: `FXT_ARM_KERNEL=neon64` selects the plain NEON kernel on 64-bit ARM;
  32-bit ARM uses the NEON kernel.
- `src/gemmology_shim.cpp`: accepts the NEON kernels.

Removed: `tests/`, `examples/` (not needed to build).
