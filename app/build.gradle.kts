import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "us.jhangy.textify"
    compileSdk = 36

    defaultConfig {
        applicationId = "us.jhangy.textify"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2"
        // ponytail: arm64 only (user decision 2026-10-11) — the ML Kit .so libs would otherwise ship 4 ABIs (~118 MB APK).
        // Drops x86 emulators and 32-bit-only phones; add "armeabi-v7a" back if one of those is needed.
        ndk { abiFilters += "arm64-v8a" }
    }

    // Release signing is configured only when the keystore properties exist (~/.gradle/gradle.properties).
    val storeFile = providers.gradleProperty("TEXTIFY_STORE_FILE").orNull
    if (storeFile != null) {
        signingConfigs {
            create("release") {
                this.storeFile = file(storeFile)
                storePassword = providers.gradleProperty("TEXTIFY_STORE_PASSWORD").get()
                keyAlias = providers.gradleProperty("TEXTIFY_KEY_ALIAS").get()
                keyPassword = providers.gradleProperty("TEXTIFY_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (storeFile != null) signingConfig = signingConfigs.getByName("release")
        }
        // Same key as release, so debug and release builds install over each other without uninstalling.
        debug {
            if (storeFile != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        viewBinding = true
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")

    // On-device OCR. Every selected script's recognizer runs on the same screenshot in parallel and the
    // results are merged (OcrMerge.kt); the Chinese model also covers Latin/English.
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")

    // Traditional Chinese output (translation spec decision 7): OpenCC s2twp via the pure-Java opencc4j port.
    // 1.14.0 = Maven Central latest (2025-09-05); pulls heaven 0.13.0 + nlp-common 0.0.5 (~2.9 MB), jieba stays optional.
    implementation("com.github.houbb:opencc4j:1.14.0")

    // On-device translation (translation spec §2): ML Kit translate (models downloaded on demand through
    // RemoteModelManager) + bundled language identification. Google Maven latest, verified 2026-10-11.
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")

    // Hy-MT2-1.8B on-device LLM translation (GPU). Google Maven latest 0.18.0, verified 2026-10-11; needs Kotlin >= 2.3.
    // No R8 keep rules: release builds are not minified (LiteRT-LM issue #3739 needs them once minify is on).
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.0")

    testImplementation("junit:junit:4.13.2")
}
