plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jaixondaza.descargadorpro"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jaixondaza.descargadorpro"
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

    // --- 1. Descargador de videos (YoutubeDL / yt-dlp) ---
    val youtubedlAndroid = "0.18.1"
    implementation("io.github.junkfood02.youtubedl-android:library:$youtubedlAndroid")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$youtubedlAndroid")

    // --- 2. Editor de video (FFmpeg Kit - Fork mantenido) ---
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.7")

    // --- 3. Carga de imágenes / Miniaturas (Glide) ---
    // Versión estable compatible con compileSdk 34
    implementation("com.github.bumptech.glide:glide:4.16.0")

    // --- 4. Componentes visuales (Material Design) ---
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.cardview:cardview:1.0.0")

    // --- 5. Corrutinas para hilos de descarga ---
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
