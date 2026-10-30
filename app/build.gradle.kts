plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.smartpdf.pairer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.smartpdf.pairer"
        minSdk = 24
        targetSdk = 36
        // Keep updates installable even if early GitHub run numbers are below V1.3's code.
        versionCode = maxOf(17, System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 17)
        versionName = "1.3.2"
    }

    signingConfigs {
        create("release") {
            val ksPath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/keystore/release.keystore"
            storeFile = file(ksPath)
            storePassword = System.getenv("KEYSTORE_STORE_PASSWORD") ?: "dummy_pass"
            keyAlias = System.getenv("KEYSTORE_KEY_ALIAS") ?: "smartpdf"
            keyPassword = System.getenv("KEYSTORE_KEY_PASSWORD") ?: "dummy_pass"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
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
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    // Required by the OCR implementation included in V1.3.
    implementation("com.rmtheis:tess-two:9.1.0")
}
