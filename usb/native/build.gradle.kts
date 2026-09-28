import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
}

// Нативный backend USBFS для Fastboot DATA OUT (D077): `native_usbfs.cpp` и
// `NativeUsbfsBackend` перенесены из A2 дословно. Версии NDK и CMake закреплены:
// NDK — та, что AGP 9.3.2 берёт по умолчанию, CMake — не ниже 3.18.1 из
// `CMakeLists.txt` архива. Обе ставит `scripts/dev/provision_android_toolchain.sh`
// и шаг `setup-android` в CI.
android {
    namespace = "io.github.ncorror.nekoflash.usb.usbfs"
    compileSdk = 37
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 26
        // Правила сжатия A2 (`app/proguard-rules.pro`) — вместе с модулем, чтобы
        // имена JNI пережили R8, если выпуск начнут сжимать.
        consumerProguardFiles("consumer-rules.pro")
        // Воспроизводимость (D074): Build ID считается по библиотеке вместе с
        // отладочными сведениями, где лежит абсолютный путь дерева, и сборка
        // того же коммита в другом каталоге давала другую `.so`.
        externalNativeBuild {
            cmake {
                cppFlags("-ffile-prefix-map=${rootDir.absolutePath}=.")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
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
    api(project(":usb:api"))
    testImplementation(libs.junit4)
}
