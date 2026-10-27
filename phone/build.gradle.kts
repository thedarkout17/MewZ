import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * The iPhone app's Kotlin side: its screens (Compose Multiplatform), playback
 * (AVPlayer), saving (UserDefaults) and the network sources (YouTube, LRCLIB).
 * iosApp/ is the small Xcode project that wraps this as an app.
 *
 * Also builds for desktop JVM ("desktop"): not shipped anywhere, it's how the
 * data sources are tested and the screens are rendered to pictures on CI
 * (phone/src/desktopTest), since the iPhone side can only be built on a Mac.
 */
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.compose")
}

val ktor = "3.2.0"
val coil = "3.2.0"

kotlin {
    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "MewZPhone"
            isStatic = true
        }
    }

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            implementation("io.ktor:ktor-client-core:$ktor")
            implementation("io.coil-kt.coil3:coil-compose:$coil")
            implementation("io.coil-kt.coil3:coil-network-ktor3:$coil")
        }
        iosMain.dependencies {
            implementation("io.ktor:ktor-client-darwin:$ktor")
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation("io.ktor:ktor-client-okhttp:$ktor")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
            }
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
    }
}

compose.resources {
    packageOfResClass = "com.headunit.music.phone.res"
    publicResClass = false
}

// The CI-only network checks (phone/src/desktopTest/.../LiveYouTubeTest) hit the real
// YouTube; they run only when asked: ./gradlew :phone:desktopTest -Plive
tasks.withType<Test>().configureEach {
    systemProperty("mewz.live", (project.findProperty("live") != null).toString())
    systemProperty("mewz.screenshots", rootProject.layout.buildDirectory.dir("screenshots").get().asFile.path)
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = true }
}
