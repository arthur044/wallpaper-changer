plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    // Coverage for the pure module; :app is covered by instrumented tests instead.
    // JaCoCo (built into Gradle) rather than Kover: Kover 0.9.1, its newest
    // release, fails against Kotlin 2.4.20's Gradle plugin.
    jacoco
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Flow is part of SettingsRepository's public API.
    api(libs.kotlinx.coroutines.core)
    // datastore-core is plain JVM (no Android), so config persistence stays
    // testable here with temp files.
    implementation(libs.androidx.datastore.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okhttp.coroutines)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver3.junit5)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
}
