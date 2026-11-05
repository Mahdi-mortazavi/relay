// Build-tooling transitive dependencies (pulled in by the Android Gradle Plugin) with published CVEs.
// Constraints force the patched versions without touching the app's own dependency pins.
buildscript {
    dependencies {
        constraints {
            val patched = mapOf(
                "org.bouncycastle:bcprov-jdk18on" to "1.85",
                "org.bouncycastle:bcpkix-jdk18on" to "1.84",
                "com.google.guava:guava" to "33.7.2-jre",
                "org.bitbucket.b_c:jose4j" to "0.9.6",
                "org.jdom:jdom2" to "2.0.6.1",
                "org.apache.commons:commons-lang3" to "3.18.0",
                "org.apache.httpcomponents:httpclient" to "4.5.13",
            )
            patched.forEach { (coordinate, version) ->
                classpath("$coordinate:$version") { because("security: patched version of build-tooling dependency") }
            }
        }
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
