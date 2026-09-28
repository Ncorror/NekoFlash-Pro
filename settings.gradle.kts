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
    }
}

rootProject.name = "NekoFlash-Pro"
include(":engine")
include(":app")
include(":core:model", ":core:diagnostics", ":core:operation", ":core:artifact")
include(":usb:api", ":usb:android")
include(":protocol:adb", ":protocol:fastboot")
include(":vendor:xiaomi")
