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
include(":core:transport", ":core:fastboot", ":core:adb", ":core:diagnostics")
