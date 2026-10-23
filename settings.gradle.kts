pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral() // Vital para descargar la librería
        maven { url = uri("https://jitpack.io") }
    }
}

// ESTAS DOS LÍNEAS FALTABAN Y SON OBLIGATORIAS:
rootProject.name = "Descargador-pro"
include(":app")
