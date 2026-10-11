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
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
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

    testImplementation("junit:junit:4.13.2")
}
