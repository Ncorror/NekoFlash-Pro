plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:diagnostics"))
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit4)
}
