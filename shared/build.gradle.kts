import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * The rules of the app (queue, libraries, title clean-up, album matching,
 * lyrics parsing), shared by the Android headunit app and the iPhone app.
 * Plain Kotlin: nothing here may import android.*, iOS frameworks, or any
 * networking library. See docs/PORTING.md.
 */
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
}

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    // Plain JVM: fast unit tests, and the iPhone module's desktop preview build.
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "com.headunit.music.shared"
    compileSdk = 35
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
