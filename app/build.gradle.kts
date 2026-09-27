import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

// Постоянная debug-подпись из окружения CI (секрет NEKOFLASH_DEBUG_KEYSTORE_BASE64).
// Без неё каждая сборка подписана временным ключом, поверх прежней не ставится,
// а удаление приложения уносит ключ хоста ADB. Значения читаются через providers,
// чтобы configuration cache видел их как вход. Ключ в дерево не кладётся.
val debugKeystorePath = providers.environmentVariable("NEKOFLASH_DEBUG_KEYSTORE")
val debugKeystorePassword = providers.environmentVariable("NEKOFLASH_DEBUG_KEYSTORE_PASSWORD")
val debugKeyAlias = providers.environmentVariable("NEKOFLASH_DEBUG_KEY_ALIAS")
val debugKeyPassword = providers.environmentVariable("NEKOFLASH_DEBUG_KEY_PASSWORD")

android {
    namespace = "com.ncorror.nekoflash.pro"
    compileSdk = 37

    signingConfigs {
        if (debugKeystorePath.isPresent) {
            getByName("debug") {
                storeFile = file(debugKeystorePath.get())
                storePassword = debugKeystorePassword.getOrElse("android")
                keyAlias = debugKeyAlias.getOrElse("androiddebugkey")
                keyPassword = debugKeyPassword.getOrElse("android")
            }
        }
    }

    defaultConfig {
        applicationId = "com.ncorror.nekoflash.pro"
        minSdk = 26
        targetSdk = 36
        versionCode = 20
        versionName = "0.20.0-dev"
    }

    androidResources {
        generateLocaleConfig = true
        localeFilters += listOf("en", "ru")
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":engine"))
    implementation(project(":core:transport"))
    implementation(project(":core:fastboot"))
    implementation(project(":core:adb"))

    implementation(libs.androidx.activity.compose)
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
