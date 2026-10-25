// ESTE ES EL ARCHIVO DE LA CARPETA app/ (no el de la raíz del proyecto)
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

        // arm64 y armv7 cubren los celulares Android reales (x86 es solo para emuladores)
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // Clave de firma FIJA: la entrega GitHub (secretos) mediante FIRMA_ARCHIVO y FIRMA_CLAVE.
    // Sin ellas se usa la clave temporal de Android (el APK no podrá actualizarse encima de otro).
    signingConfigs {
        create("fija") {
            val archivo = System.getenv("FIRMA_ARCHIVO")
            if (archivo != null) {
                storeFile = file(archivo)
                storeType = "pkcs12"
                storePassword = System.getenv("FIRMA_CLAVE")
                keyAlias = "descargadorpro"
                keyPassword = System.getenv("FIRMA_CLAVE")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (System.getenv("FIRMA_ARCHIVO") != null) {
                signingConfig = signingConfigs.getByName("fija")
            }
        }
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
    // material 1.12.0 es la última que funciona con compileSdk 34 (la 1.13.0 exige compileSdk 36)
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Descargador (yt-dlp): YouTube, TikTok, Facebook, Instagram...
    implementation("io.github.junkfood02.youtubedl-android:library:0.17.4")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.17.4")

    // Editor de video (FFmpegKit mantenido). Versión "gpl": incluye libx264 para generar H.264
    // reproducible en cualquier teléfono. Misma API (com.arthenica.ffmpegkit), solo cambia el groupId.
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-full-gpl:8.1.7")
    // Librería auxiliar que FFmpegKit necesita en tiempo de ejecución (sin ella: NoClassDefFoundError)
    implementation("com.arthenica:smart-exception-java:0.2.1")
}
