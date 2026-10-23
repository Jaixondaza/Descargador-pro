plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.descargadorpro.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.descargadorpro.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // FFmpeg necesita extraer las librerías nativas
        ndk {
            abiFilters.addAll(listOf("x86", "x86_64", "armeabi-v7a", "arm64-v8a"))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    // Necesario para librerías que usan enlaces JNI como FFmpeg
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // --- Dependencias base de Android ---
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // --- 1. Descargador de videos (Versión estable recomendada) ---
    val youtubedlAndroid = "0.17.4" 
    implementation("io.github.junkfood02.youtubedl-android:library:$youtubedlAndroid")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$youtubedlAndroid")

    // --- 2. Editor de video (FFmpeg Kit) ---
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.7")

    // --- 3. Carga de imágenes (Glide) ---
    implementation("com.github.bumptech.glide:glide:4.16.0")

    // --- 4. Componentes visuales ---
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.cardview:cardview:1.0.0")

    // --- 5. Corrutinas ---
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
