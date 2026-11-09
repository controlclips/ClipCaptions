plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.clipcaptions.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.clipcaptions.app"
        // Android 10 is the first version that lets an app capture other apps' sound.
        minSdk = 29
        targetSdk = 35
        versionCode = 20
        versionName = "6.4"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                // Whisper is far too slow unoptimised, so it's always built in Release mode.
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DANDROID_STL=c++_static")
            }
        }
    }

    // Automatic language detection (whisper.cpp), built from src/main/cpp.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Two keys:
    // - keystore/release.p12 signs the builds people install. It's locked with a long password kept
    //   only in the repository's secret RELEASE_KEY_PASSWORD (Settings > Secrets and variables >
    //   Actions), so the file being public doesn't let anyone else sign "updates".
    // - keystore/debug.keystore signs test builds, made when that secret isn't available.
    // Android only installs an update over the app if it's signed with the same key as before.
    val releasePassword: String? = System.getenv("RELEASE_KEY_PASSWORD")?.takeIf { it.isNotEmpty() }
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storeType = "pkcs12"
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (releasePassword != null) {
            create("release") {
                storeFile = rootProject.file("keystore/release.p12")
                storeType = "pkcs12"
                storePassword = releasePassword
                keyAlias = "clipcaptions"
                keyPassword = releasePassword
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            if (releasePassword != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    lint {
        // Lint's release check shouldn't stop a build; problems still show in the build log.
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // The faster Whisper build for newer phones (see whisperfast/build.gradle.kts).
    implementation(project(":whisperfast"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // On-device translation (Google ML Kit). Language packs download on first use.
    implementation("com.google.mlkit:translate:17.0.3")

    // On-device speech recognition (Vosk). Speech models download on first use.
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
}
