plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.ncorror.nekoflash.pro"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ncorror.nekoflash.pro"
        minSdk = 26
        targetSdk = 36
        versionCode = 19
        versionName = "0.19.0-dev"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":engine"))
    implementation(project(":core:diagnostics"))
    implementation(project(":core:transport"))
    implementation(project(":core:fastboot"))
    implementation(project(":core:adb"))
    val composeBom = platform("androidx.compose:compose-bom:2026.05.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.13.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
