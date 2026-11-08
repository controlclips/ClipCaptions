//! JNI bridge for com.clipcaptions.app.MozTranslate.
//!
//! One engine per language pair (e.g. Spanish -> English). The Kotlin side owns
//! the handle, never calls translate and free at the same time, and frees it
//! when done. Every entry point catches panics so a bad model file can't take
//! the whole app down; failures come back as a Java exception or an error text.

use fxtranslate::cache::verify_and_decompress;
use fxtranslate::engine::Engine;
use fxtranslate::shortlist::Shortlist;
use jni::objects::{JClass, JString};
use jni::sys::{jlong, jstring};
use jni::JNIEnv;
use std::panic::{catch_unwind, AssertUnwindSafe};

fn text(env: &mut JNIEnv, s: &JString) -> Option<String> {
    if s.is_null() {
        return None;
    }
    env.get_string(s).ok().map(|v| v.into())
}

fn jtext(env: &mut JNIEnv, s: &str) -> jstring {
    env.new_string(s)
        .map(|v| v.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

fn panic_text(p: Box<dyn std::any::Any + Send>) -> String {
    if let Some(s) = p.downcast_ref::<&str>() {
        s.to_string()
    } else if let Some(s) = p.downcast_ref::<String>() {
        s.clone()
    } else {
        "unknown failure".into()
    }
}

fn load(model: &str, src: &str, trg: &str, lex: Option<&str>) -> Result<Engine, String> {
    let engine = Engine::load_mmapped(model, src, trg)?;
    Ok(match lex {
        Some(path) => engine.with_shortlist(Shortlist::load(path).map_err(|e| e.to_string())?),
        None => engine,
    })
}

#[no_mangle]
pub extern "system" fn Java_com_clipcaptions_app_MozTranslate_nativeLoad<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    model: JString<'l>,
    src_vocab: JString<'l>,
    trg_vocab: JString<'l>,
    lex: JString<'l>,
) -> jlong {
    let (Some(model), Some(src), Some(trg)) = (
        text(&mut env, &model),
        text(&mut env, &src_vocab),
        text(&mut env, &trg_vocab),
    ) else {
        let _ = env.throw_new("java/lang/IllegalArgumentException", "missing model file");
        return 0;
    };
    let lex = text(&mut env, &lex);
    let result = catch_unwind(AssertUnwindSafe(|| load(&model, &src, &trg, lex.as_deref())));
    let error = match result {
        Ok(Ok(engine)) => return Box::into_raw(Box::new(engine)) as jlong,
        Ok(Err(e)) => e,
        Err(p) => panic_text(p),
    };
    let _ = env.throw_new("java/lang/IllegalStateException", format!("translator: {error}"));
    0
}

#[no_mangle]
pub extern "system" fn Java_com_clipcaptions_app_MozTranslate_nativeTranslate<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    input: JString<'l>,
) -> jstring {
    if handle == 0 {
        return std::ptr::null_mut();
    }
    let Some(input) = text(&mut env, &input) else {
        return std::ptr::null_mut();
    };
    // SAFETY: `handle` came from nativeLoad and Kotlin keeps it alive and
    // unshared while this call runs.
    let engine = unsafe { &*(handle as *const Engine) };
    match catch_unwind(AssertUnwindSafe(|| engine.translate_long(&input))) {
        Ok(out) => jtext(&mut env, &out),
        Err(p) => {
            let _ = env.throw_new(
                "java/lang/IllegalStateException",
                format!("translator: {}", panic_text(p)),
            );
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_clipcaptions_app_MozTranslate_nativeFree<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    if handle != 0 {
        // SAFETY: matches the Box::into_raw in nativeLoad; Kotlin frees once.
        let _ = catch_unwind(|| unsafe { drop(Box::from_raw(handle as *mut Engine)) });
    }
}

/// Unpacks a downloaded model file (zstd) to `output`, checking it against the
/// expected SHA-256 when one is given. Returns null on success, or what went wrong.
#[no_mangle]
pub extern "system" fn Java_com_clipcaptions_app_MozTranslate_nativeUnpack<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    input: JString<'l>,
    output: JString<'l>,
    sha256: JString<'l>,
) -> jstring {
    let (Some(input), Some(output)) = (text(&mut env, &input), text(&mut env, &output)) else {
        return jtext(&mut env, "missing file name");
    };
    let sha = text(&mut env, &sha256).filter(|s| !s.is_empty());
    let work = || -> Result<(), String> {
        let packed = std::fs::read(&input).map_err(|e| format!("read: {e}"))?;
        let bytes = verify_and_decompress(&packed, sha.as_deref())?;
        drop(packed);
        let tmp = format!("{output}.part");
        std::fs::write(&tmp, &bytes).map_err(|e| format!("write: {e}"))?;
        std::fs::rename(&tmp, &output).map_err(|e| format!("rename: {e}"))?;
        Ok(())
    };
    match catch_unwind(AssertUnwindSafe(work)) {
        Ok(Ok(())) => std::ptr::null_mut(),
        Ok(Err(e)) => jtext(&mut env, &e),
        Err(p) => jtext(&mut env, &panic_text(p)),
    }
}

/// Which maths kernel this build uses ("neon64", "i8mm+neon64", "avx2", "scalar").
#[no_mangle]
pub extern "system" fn Java_com_clipcaptions_app_MozTranslate_nativeBackend<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
) -> jstring {
    jtext(&mut env, &backend())
}

fn backend() -> String {
    fxtranslate::gemm::backend().to_string()
}
