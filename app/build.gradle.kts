plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.zbnreader"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.zbnreader"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }

    compileOptions {
        // Apache POI 5.2.3 требует минимум Java 11
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    
    kotlinOptions {
        jvmTarget = "11"
    }

    // Исключаем конфликтующие файлы метаданных Apache POI при сборке APK
    packaging {
        resources.excludes += "META-INF/DEPENDENCIES"
        resources.excludes += "META-INF/LICENSE"
        resources.excludes += "META-INF/LICENSE.txt"
        resources.excludes += "META-INF/NOTICE"
        resources.excludes += "META-INF/NOTICE.txt"
        resources.excludes += "META-INF/ASL2.0"
        resources.excludes += "META-INF/LGPL2.1"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("com.github.mik3y:usb-serial-for-android:3.9.0")
    implementation("org.apache.poi:poi-ooxml:5.2.3")
    
    // Корутины для чтения RS-422 и записи в файл в фоновом потоке
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    // lifecycleScope для безопасного запуска задач из Activity
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
}
