import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Signing lives in keystore.properties, which is gitignored along with the key
// itself. Without it the project still builds; only the release APK comes out
// unsigned, so a fresh clone isn't broken by a missing secret.
val signing = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use(::load)
}

android {
    namespace = "io.github.arthur044.wallpaperchanger"
    // Current AndroidX artifacts refuse to build against anything older than 37.
    // compileSdk only widens the visible API surface; runtime behavior is pinned
    // by targetSdk below, which deliberately stays at 36.
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.arthur044.wallpaperchanger"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // AppAuth's RedirectUriReceiverActivity claims this scheme; the full
        // redirect URI registered in the Spotify dashboard is "<scheme>://callback".
        manifestPlaceholders["appAuthRedirectScheme"] = "io.github.arthur044.wallpaperchanger"
    }

    signingConfigs {
        if (signing.isNotEmpty()) {
            create("release") {
                storeFile = file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
                // v2 covers everything from Android 7; v3 also allows rotating
                // to a new key later without breaking updates. v1 is dead weight
                // at minSdk 26.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            // Installs beside the release app instead of over it: the release
            // is signed with another key, so updating it in place fails, and
            // uninstalling it to make room would wipe the phone's login and
            // settings. The redirect scheme stays shared, so a login from the
            // debug app may ask which app should open the callback.
            applicationIdSuffix = ".debug"
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            // R8 stays off: AppAuth, Tink and kotlinx.serialization would each
            // need keep rules, and this build is the one tested on the phone.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // For BuildConfig.DEBUG: the debug screens exist in every build but are
        // only reachable from a debug one.
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.appauth)
    implementation(libs.tink.android)

    testImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
