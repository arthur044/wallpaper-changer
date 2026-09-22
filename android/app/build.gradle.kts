plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
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
