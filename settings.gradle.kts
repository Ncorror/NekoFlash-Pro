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

rootProject.name = "NekoFlash-Pro"
include(":engine")
include(":app")
include(":core:transport", ":core:fastboot")
