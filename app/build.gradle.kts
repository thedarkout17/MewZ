import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Secrets live in local.properties (git-ignored), never in the repo:
//   youtube.apiKey=AIza...
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.headunit.music"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.headunit.music"
        minSdk = 24          // covers nearly all Android headunits (Android 7+)
        targetSdk = 35
        versionCode = 1
        versionName = "0.2"

        buildConfigField(
            "String",
            "YOUTUBE_API_KEY",
            "\"${localProps.getProperty("youtube.apiKey", "")}\"",
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // NewPipeExtractor uses newer Java APIs; this backports them to Android 7+.
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // The rules shared with the iPhone app (queue, libraries, text clean-up...): ../shared
    implementation(project(":shared"))

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("io.coil-kt:coil-compose:2.7.0") // thumbnails

    // Audio-only playback: NewPipeExtractor finds each song's audio stream (search
    // stays on the YouTube Data API), Media3 ExoPlayer plays it.
    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.26.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.2")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    // Media session + background service: steering wheel, Bluetooth, headunit media controls
    implementation("androidx.media3:media3-session:1.4.1")

    // (The core's unit tests moved with it: shared/src/commonTest.)
    debugImplementation("androidx.compose.ui:ui-tooling")
}
