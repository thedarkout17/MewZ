pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") } // NewPipeExtractor
    }
}

rootProject.name = "HeadunitMusic"
include(":app")    // the Android headunit app
include(":shared") // the rules both apps share (core/), plain Kotlin
include(":phone")  // the iPhone app's Kotlin side: screens, playback, data (see iosApp/)
