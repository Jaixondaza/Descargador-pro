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
        // youtubedl-android trae binarios nativos grandes; arm64 cubre casi
        // todos los celulares Android modernos. Quita el filtro si necesitas x86.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
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
    kotlinOptions { jvmTarget = "17" }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        jniLibs { useLegacyPackaging = true }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Motor de descarga: yt-dlp para Android. Soporta YouTube, TikTok,
    // Facebook, Instagram y cientos de sitios mas, con selector de formato.
    // Repo: https://github.com/yausername/youtubedl-android (via JitPack)
    implementation("io.github.junkfood02.youtubedl-android:library:0.17.4")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.17.4")

    // Editor de video: FFmpegKit (filtros de mejora, recorte, loudnorm, etc.)
    // CORRECCION 2026-10-22: el FFmpegKit original (com.arthenica:6.0-2) fue
    // retirado y sus binarios se ELIMINARON de Maven Central el 2025-04-01,
    // por lo que esa linea rompe la compilacion hoy ("Could not find
    // com.arthenica:ffmpeg-kit-full:6.0-2"). Se usa el fork mantenido,
    // publicado en Maven Central con la misma API (com.arthenica.ffmpegkit.*).
    implementation("io.github.maitrungduc1410:ffmpeg-kit-full:8.1.2")
}
