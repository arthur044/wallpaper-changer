import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Signing lives in keystore.properties, which is gitignored along with the key
// itself. Without it the project still builds; only the release APK comes out
// unsigned, so a fresh clone isn't broken by a missing secret.
// debugStoreFile (optional) names the debug key shared by this PC and CI, so a
// debug APK from either updates one from the other. Without it, debug builds
// use this machine's own ~/.android/debug.keystore, as before.
val signing = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use(::load)
}

// The version comes from git, so a build of one commit gets the same number
// here and on CI, and every new commit on main updates the app it follows.
// versionCode counts the commits up to HEAD; a shallow clone would count only
// the few it fetched and go backwards, so it is refused rather than guessed.
// Without git at all (a source zip), versionCode falls back to 1.
fun git(vararg args: String): String? = runCatching {
    val run = providers.exec {
        commandLine("git", *args)
        isIgnoreExitValue = true
    }
    run.standardOutput.asText.get().trim().takeIf { run.result.get().exitValue == 0 && it.isNotEmpty() }
}.getOrNull()

check(git("rev-parse", "--is-shallow-repository") != "true") {
    "Shallow git clone: versionCode counts commits, so fetch the whole history (actions/checkout: fetch-depth: 0)"
}
val gitCommitCount = git("rev-list", "--count", "HEAD")?.toIntOrNull() ?: 1
val gitShortSha = git("rev-parse", "--short=7", "HEAD") ?: "nogit"
// On CI HEAD may be detached; GitHub names the branch that triggered the run.
val gitBranch = System.getenv("GITHUB_REF_NAME") ?: git("rev-parse", "--abbrev-ref", "HEAD") ?: "nogit"

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
        versionCode = gitCommitCount
        // Kept a plain literal: build-apk.ps1 reads it from this file.
        versionName = "0.1.0"
        // The update screen preselects this branch's debug build.
        buildConfigField("String", "GIT_BRANCH", "\"${gitBranch.filter { it != '"' && it.code != 92 }}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // AppAuth's RedirectUriReceiverActivity claims this scheme; the full
        // redirect URI registered in the Spotify dashboard is "<scheme>://callback".
        manifestPlaceholders["appAuthRedirectScheme"] = "io.github.arthur044.wallpaperchanger"
    }

    signingConfigs {
        signing.getProperty("debugStoreFile")?.let { debugStore ->
            create("sharedDebug") {
                storeFile = file(debugStore)
                // The Android debug-key conventions: not a secret, the key file is.
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        if (signing.getProperty("storeFile") != null) {
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
            signingConfigs.findByName("sharedDebug")?.let { signingConfig = it }
            // Debug builds come from any branch: say which.
            versionNameSuffix = " ($gitShortSha, $gitBranch)"
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            versionNameSuffix = " ($gitShortSha)"
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

// For CI and scripts: the version a build of this checkout gets.
tasks.register("printVersion") {
    val code = gitCommitCount
    val sha = gitShortSha
    val branch = gitBranch
    doLast { println("versionCode=$code sha=$sha branch=$branch") }
}
