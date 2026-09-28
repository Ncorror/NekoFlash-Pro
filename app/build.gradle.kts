import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

// Отладочная подпись из окружения.
//
// Без неё AGP создаёт `debug.keystore` там, где его нет, — а на чистом раннере
// CI его нет никогда. Каждая сборка выходит подписанной другим ключом,
// установка поверх прежней невозможна, приложение приходится удалять, и вместе
// с приватным каталогом исчезает ключ хоста ADB. Устройство после этого видит
// новый хост и снова спрашивает подтверждения (`docs/07` §6.15).
//
// Значения читаются через `providers`, а не `System.getenv`: с включённым
// configuration cache прямое чтение окружения не отслеживается как вход
// конфигурации, и Gradle переиспользовал бы кеш, собранный без ключа.
//
// Ключ в дерево не кладётся: гейт гигиены запрещает `*.keystore` среди
// отслеживаемых файлов, и запрещает правильно. Если переменной нет, поведение
// прежнее.
val debugKeystorePath = providers.environmentVariable("NEKOFLASH_DEBUG_KEYSTORE")
val debugKeystorePassword = providers.environmentVariable("NEKOFLASH_DEBUG_KEYSTORE_PASSWORD")
val debugKeyAlias = providers.environmentVariable("NEKOFLASH_DEBUG_KEY_ALIAS")
val debugKeyPassword = providers.environmentVariable("NEKOFLASH_DEBUG_KEY_PASSWORD")

android {
    namespace = "io.github.ncorror.nekoflash"
    compileSdk = 37

    signingConfigs {
        if (debugKeystorePath.isPresent) {
            getByName("debug") {
                storeFile = file(debugKeystorePath.get())
                // Пароли отладочного хранилища по соглашению platform-tools:
                // `android` и `androiddebugkey`. Секретом они не являются и
                // ничего не защищают, поэтому лежат значениями по умолчанию, а
                // не третьим и четвёртым секретом репозитория.
                storePassword = debugKeystorePassword.getOrElse("android")
                keyAlias = debugKeyAlias.getOrElse("androiddebugkey")
                keyPassword = debugKeyPassword.getOrElse("android")
            }
        }
    }

    defaultConfig {
        // Namespace и пакеты — из архива; applicationId — прежний, чтобы APK
        // обновлял установленную сборку этого репозитория (docs/08_DECISIONS.md, D041).
        applicationId = "com.ncorror.nekoflash.pro"
        minSdk = 26
        targetSdk = 36
        versionCode = 51
        versionName = "0.52.0-dev"
    }

    androidResources {
        generateLocaleConfig = true
        localeFilters += listOf("en", "ru")
    }

    buildFeatures {
        compose = true
        buildConfig = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:diagnostics"))
    implementation(project(":core:operation"))
    implementation(project(":core:artifact"))
    implementation(project(":usb:api"))
    implementation(project(":usb:android"))
    implementation(project(":protocol:adb"))
    implementation(project(":protocol:fastboot"))
    implementation(project(":vendor:xiaomi"))
    implementation(project(":engine"))
    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.material3.adaptive)

    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit4)
}
