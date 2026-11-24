pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "88Rcan"
include(":app")

// O motor de streaming (RTSP → RTMP) vive em ../core e é testável sem Android.
includeBuild("../core")
