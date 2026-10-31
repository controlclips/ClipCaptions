// The faster Whisper build for 64-bit phones with ARMv8.2 dot-product and half-precision
// instructions (most phones from about 2018 onwards). It is built from the same source as the
// app's standard build, just with those instructions switched on. The app checks the phone's
// processor before loading it, and uses the standard build otherwise.
plugins {
    id("com.android.library")
}

android {
    namespace = "com.clipcaptions.whisperfast"
    compileSdk = 35

    defaultConfig {
        minSdk = 29

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DANDROID_STL=c++_static", "-DCLIP_FAST_ARM=ON")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../app/src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}
