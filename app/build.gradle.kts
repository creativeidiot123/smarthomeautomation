plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "dev.homedj.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.homedj.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // The vendored Matter controller is built for arm64 only (both phones).
        ndk { abiFilters += "arm64-v8a" }
    }
    sourceSets["main"].jniLibs.srcDir("libs/matter/jniLibs")
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.09.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.3")
    implementation("androidx.media3:media3-exoplayer:1.8.1")
    implementation("androidx.media3:media3-session:1.8.1")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.26.5")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    implementation("com.google.android.gms:play-services-auth:21.6.0")
    implementation("com.google.android.gms:play-services-location:21.4.0")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    // QR display only; scanning reuses Play services. Apache-2.0, GPL-3.0-compatible.
    implementation("com.google.zxing:core:3.5.3")
    // Project CHIP connectedhomeip v1.6.1.0 Android controller (Apache-2.0), built by
    // .github/workflows/matter-sdk.yml; provenance in libs/matter/VERSION.txt and SHA256SUMS.
    implementation(fileTree("libs/matter") { include("*.jar") })
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
