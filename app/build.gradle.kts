plugins {
    id("com.android.application") version "8.3.1"
    id("org.jetbrains.kotlin.android") version "1.9.10"
}

android {
    namespace = "com.example.vocalapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.vocalapp"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }

    sourceSets {
    getByName("main") {
        java.srcDirs("src/main/kotlin")
    }
}

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.3"
    }
}



// Файл: app/build.gradle.kts

dependencies {
    // --- Стандартные зависимости (остаются как есть) ---
    implementation("androidx.core:core-ktx:1.10.1")
    implementation("androidx.appcompat:appcompat:1.7.0-alpha01")
    implementation("com.google.android.material:material:1.10.0")

    // --- Compose (ИСПРАВЛЕННЫЙ БЛОК) ---
    // 1. Добавляем BOM. Он будет управлять версиями всех библиотек Compose.
    implementation(platform("androidx.compose:compose-bom:2024.05.00"))

    // 2. Убираем версии из зависимостей ниже. BOM подставит их сам.
    implementation("androidx.activity:activity-compose")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    implementation("com.github.wendykierp:JTransforms:3.1")

    // --- TarsosDSP (остается как есть) ---
    //implementation("be.tarsos.dsp:core:2.5")
    //implementation("be.tarsos.dsp:jvm:2.5")

    // --- TARSOSDSP (ФИНАЛЬНЫЙ ВАРИАНТ) ---
    //implementation("be.tarsos.dsp:jvm:2.5") // Основная библиотека

    // ---> ЭТО САМАЯ ВАЖНАЯ СТРОКА <---
    // Она добавляет недостающие классы, из-за которых был вылет
    //implementation("com.github.axet:TarsosDSP-Android:2.4")

    // --- Тесты (остаются как есть) ---
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}




