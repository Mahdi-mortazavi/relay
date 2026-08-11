import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release version comes from the tag via -PrelayVersion=x.y.z (android-release.yml).
// A pre-release suffix (e.g. "0.0.0-dry-run") is allowed for the versionName but
// stripped before computing the integer versionCode.
val relayVersion: String = (project.findProperty("relayVersion") as String?) ?: "0.1.0"
val relayVersionCode: Int = relayVersion.substringBefore("-").split(".").let { parts ->
    val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
    val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
    // Floor at 1. release.yml's dry-run path builds "0.0.0-dry-run", which
    // computes to 0 — and AGP 9 rejects versionCode 0 outright, so the
    // workflow's own rehearsal path could not build. A dry run never
    // publishes, so any valid number will do; a real tag never lands here.
    (major * 10_000 + minor * 100 + patch).coerceAtLeast(1)
}

// Full Mode's forwarder is an AAR built from /wg by gomobile. A checkout
// without it builds fine and honestly reports Full Mode as unavailable — but a
// *release* built that way would ship an app quietly missing a mode it
// advertises, and nothing would have said so. Builds that ship, and the device
// tests that prove the mode works, pass -PrelayRequireWg=true.
val relayRequireWg: Boolean =
    (project.findProperty("relayRequireWg") as String?)?.toBoolean() ?: false
if (relayRequireWg && fileTree("libs") { include("*.aar") }.isEmpty) {
    throw GradleException(
        "No Full Mode library in android/app/libs. Build it with " +
            "scripts/build-wg-aar.sh, or drop -PrelayRequireWg.",
    )
}

android {
    namespace = "io.relay.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.relay.app"
        minSdk = 26
        targetSdk = 37
        versionCode = relayVersionCode
        versionName = relayVersion
        // Instrumented tests are the device half of the test pyramid: they run
        // the real app on a real Android image in CI (.github/workflows/e2e.yml).
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    //
    // The device lab additionally needs an x86_64 APK: a GitHub emulator is
    // x86_64, and while the Google APIs images can translate arm64, the AOSP
    // images cannot — `installDebug` there fails with "Could not find build of
    // variant which supports ... an ABI in x86_64, x86" and no test runs at all.
    // Gated behind a property so release builds are completely unaffected and
    // keep shipping exactly one APK.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            if (project.findProperty("relayTestAbis") == "true") include("x86_64")
            // Also emit an APK carrying every ABI. The arm64 split is the one
            // most people want — it is a third of the size — but it silently
            // fails to install on a 32-bit ARM phone, an x86 Chromebook, or an
            // emulator. The universal build is the answer to "it says the app
            // isn't compatible with my device".
            isUniversalApk = true
        }
    }

    packaging {
        jniLibs {
            // com.wireguard.android:tunnel is here for one class —
            // com.wireguard.crypto.KeyPair, which is pure Java. The rest of it
            // is GoBackend: a second, complete copy of wireguard-go for running
            // a *client* tunnel through a VpnService, which is the opposite of
            // what Relay does and which nothing in this app ever constructs.
            // Left in, it costs 3.5 MB per ABI — 14 MB on the universal APK
            // people download when the arm64 one says "not compatible" —
            // to ship code that cannot execute.
            excludes += listOf(
                "**/libwg-go.so",
                "**/libwg-quick.so",
                "**/libwg.so",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.all { it.useJUnit() }
    }
}

// Kotlin 2.4 removed the `kotlinOptions` DSL that used to live in `android { }`,
// and AGP 9 folded Kotlin support into the Android plugin itself — the standalone
// `org.jetbrains.kotlin.android` plugin is gone from the block above because
// applying it alongside AGP 9 is now a hard error.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
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
    // Official WireGuard key generation + crypto (Full Mode, ADR-0008).
    implementation(libs.wireguard.tunnel)

    // The userspace forwarder itself: /wg, built by gomobile. Picked up from a
    // directory rather than declared as a coordinate because it is built by a
    // Go toolchain and an NDK, which no Android developer should need installed
    // to compile this app. CI builds it once and drops it here (see the
    // wireguard job in ci.yml and `scripts/fetch-wg-aar.sh`); a checkout
    // without it still builds, and Full Mode then reports itself unavailable
    // instead of failing the build or -- worse -- offering a mode that cannot
    // start. `relayRequireWg` turns that tolerance off for the builds that ship.
    implementation(fileTree("libs") { include("*.aar") })

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
