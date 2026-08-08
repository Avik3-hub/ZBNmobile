plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.zbnreader"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.zbnreader"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    } // <-- Закрывающая скобка для defaultConfig

    applicationVariants.all {
        val variant = this
        variant.outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "app-debug.apk"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("com.github.mik3y:usb-serial-for-android:3.9.0")

    // Корутины для чтения RS-422 и записи в файл в фоновом потоке
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    // lifecycleScope для безопасного запуска задач из Activity
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
}
