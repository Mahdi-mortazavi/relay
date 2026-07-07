plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release version comes from the tag via -PrelayVersion=x.y.z (android-release.yml).
val relayVersion: String = (project.findProperty("relayVersion") as String?) ?: "0.1.0"
val relayVersionCode: Int = relayVersion.split(".").let { (major, minor, patch) ->
    major.toInt() * 10_000 + minor.toInt() * 100 + patch.toInt()
}

android {
    namespace = "io.relay.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.relay.app"
        minSdk = 26
        targetSdk = 35
        versionCode = relayVersionCode
        versionName = relayVersion
    }

    // Signing material lives only in GitHub Secrets (docs/release.md); local and
    // PR builds simply have no release signing config.
    val keystorePath = System.getenv("RELAY_KEYSTORE_FILE")
    if (keystorePath != null) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("RELAY_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELAY_KEY_ALIAS")
                keyPassword = System.getenv("RELAY_KEY_PASSWORD")
                storeType = "pkcs12"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // The primary sideload artifact is the arm64-v8a APK (docs/release.md).
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = false
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
        compose = true
    }

    testOptions {
        unitTests.all { it.useJUnit() }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
