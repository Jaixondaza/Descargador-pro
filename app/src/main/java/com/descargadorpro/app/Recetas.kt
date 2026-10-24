package com.descargadorpro.app

import java.io.File

/** Tipos de ajuste que ofrece el editor. */
enum class Tipo { MEJORAR, VERTICAL, HORIZONTAL, CUADRADO, ESCALAR, ESCALAR_2K, ESCALAR_4K, AUDIO, COMPRIMIR }

/** Un ajuste que se muestra en la lista del editor. */
data class Preset(val icono: String, val titulo: String, val detalle: String, val tipo: Tipo)

/** Lista de ajustes del editor, en el orden en que aparecen. */
val PRESETS = listOf(
    Preset("✨", "Mejorar calidad", "Más nitidez y color, menos ruido", Tipo.MEJORAR),
    Preset("📱", "Vertical 9:16", "1080×1920 · TikTok, Reels, Shorts", Tipo.VERTICAL),
    Preset("🖥", "Horizontal 16:9", "1920×1080 · YouTube", Tipo.HORIZONTAL),
    Preset("⬛", "Cuadrado 1:1", "1080×1080 · Feed de Instagram", Tipo.CUADRADO),
    Preset("🔎", "Subir a 1080p", "Agranda sin recortar", Tipo.ESCALAR),
    Preset("🔍", "Subir a 1440p (2K)", "2560×1440 · Agranda sin recortar", Tipo.ESCALAR_2K),
    Preset("🎬", "Subir a 4K (2160p)", "3840×2160 · Lento y pesado, mejor con videos cortos", Tipo.ESCALAR_4K),
    Preset("🔊", "Mejorar audio", "Volumen parejo · se guarda solo el audio (M4A)", Tipo.AUDIO),
    Preset("🗜", "Comprimir", "Archivo más liviano con buena calidad", Tipo.COMPRIMIR)
)

/** Avisa que un video largo en 4K tarda muchísimo; si la persona acepta, ejecuta [seguir]. */
fun confirmarVideoLargo(act: android.app.Activity, segundos: Long, seguir: () -> Unit) {
    android.app.AlertDialog.Builder(act)
        .setTitle("Video largo en 4K")
        .setMessage(
            "Este video dura unos $segundos segundos. Pasarlo a 4K puede tardar muchísimo y el archivo " +
                "quedará muy pesado. Funciona mejor con videos de menos de 1 minuto.\n\n¿Continuar de todos modos?"
        )
        .setPositiveButton("Continuar") { _, _ -> seguir() }
        .setNegativeButton("Cancelar", null)
        .show()
}

/**
 * Arma los comandos de FFmpeg con lo que SÍ trae el FFmpeg instalado.
 * [filtros] y [codificadores] son los detectados (null si no se pudo detectar).
 */
class Recetas(
    private val filtros: Set<String>?,
    private val codificadores: Set<String>?,
    private val tieneAudio: Boolean
) {

    // Si no se pudo detectar, se asume solo lo que siempre trae un FFmpeg sin licencia GPL
    private val filtrosSeguros = setOf(
        "crop", "scale", "unsharp", "curves", "hue", "loudnorm", "dynaudnorm", "atadenoise", "format"
    )

    private fun tiene(f: String) = (filtros ?: filtrosSeguros).contains(f)

    private fun mejoras(suave: Boolean): List<String> {
        val l = mutableListOf<String>()
        if (tiene("hqdn3d")) l += if (suave) "hqdn3d=2:2:4:4" else "hqdn3d=4:4:6:6"
        else if (tiene("atadenoise")) l += "atadenoise=s=5"
        if (tiene("eq")) {
            l += if (suave) "eq=saturation=1.08" else "eq=contrast=1.08:brightness=0.03:saturation=1.15"
        } else {
            if (tiene("curves")) l += "curves=preset=medium_contrast"
            if (tiene("hue")) l += if (suave) "hue=s=1.08" else "hue=s=1.15"
        }
        if (tiene("unsharp")) l += if (suave) "unsharp=5:5:0.6:5:5:0.0" else "unsharp=5:5:0.8:5:5:0.0"
        return l
    }

    /** Devuelve (receta completa, receta mínima de respaldo). */
    private fun cadenas(t: Tipo): Pair<String?, String?> {
        fun unir(base: String, extra: List<String>) = (listOf(base) + extra).joinToString(",")
        return when (t) {
            Tipo.MEJORAR -> Pair(mejoras(false).joinToString(",").ifEmpty { null }, null)
            Tipo.VERTICAL -> {
                val g = "crop='min(iw,ih*9/16)':'min(ih,iw*16/9)',scale=1080:1920:flags=lanczos"
                Pair(unir(g, mejoras(true)), g)
            }
            Tipo.HORIZONTAL -> {
                val g = "crop='min(iw,ih*16/9)':'min(ih,iw*9/16)',scale=1920:1080:flags=lanczos"
                Pair(unir(g, mejoras(true)), g)
            }
            Tipo.CUADRADO -> {
                val g = "crop='min(iw,ih)':'min(iw,ih)',scale=1080:1080:flags=lanczos"
                Pair(unir(g, mejoras(true)), g)
            }
            Tipo.ESCALAR, Tipo.ESCALAR_2K, Tipo.ESCALAR_4K -> {
                val alto = when (t) {
                    Tipo.ESCALAR_2K -> 1440
                    Tipo.ESCALAR_4K -> 2160
                    else -> 1080
                }
                val g = "scale='if(gt(iw,ih),-2,$alto)':'if(gt(iw,ih),$alto,-2)':flags=lanczos"
                val nitidez = if (tiene("unsharp")) listOf("unsharp=5:5:0.7:5:5:0.0") else emptyList()
                Pair(unir(g, nitidez), g)
            }
            Tipo.COMPRIMIR -> {
                val g = "scale='if(gt(iw,ih),-2,min(1080,iw))':'if(gt(iw,ih),min(1080,ih),-2)'"
                Pair(g, g)
            }
            Tipo.AUDIO -> Pair(null, null)
        }
    }

    private fun listaCodificadores(): List<String> {
        val orden = listOf("libx264", "h264_mediacodec", "mpeg4")
        val c = codificadores ?: return orden
        val disp = orden.filter { it in c }
        return if (disp.isEmpty()) listOf("mpeg4") else disp
    }

    private fun argsCodificador(nombre: String, t: Tipo): List<String> {
        val comprimir = t == Tipo.COMPRIMIR
        return when (nombre) {
            "libx264" -> listOf(
                "-c:v", "libx264", "-preset", "veryfast",
                "-crf", if (comprimir) "27" else if (t == Tipo.ESCALAR_4K) "22" else "20"
            )
            "h264_mediacodec" -> listOf(
                "-c:v", "h264_mediacodec",
                "-b:v", when (t) {
                    Tipo.COMPRIMIR -> "3M"
                    Tipo.ESCALAR_2K -> "16M"
                    Tipo.ESCALAR_4K -> "35M"
                    else -> "10M"
                }
            )
            else -> listOf("-c:v", "mpeg4", "-q:v", if (comprimir) "7" else "3")
        }
    }

    private fun filtroAudio(): String? = when {
        tiene("loudnorm") -> "loudnorm=I=-16:TP=-1.5:LRA=11"
        tiene("dynaudnorm") -> "dynaudnorm"
        else -> null
    }

    fun armarIntentos(entrada: File, salida: File, t: Tipo): List<List<String>> {
        val af = filtroAudio()
        if (t == Tipo.AUDIO) {
            val a = mutableListOf("-y", "-i", entrada.absolutePath, "-vn")
            if (af != null) a += listOf("-af", af)
            a += listOf("-c:a", "aac", "-b:a", "192k", "-ar", "48000", salida.absolutePath)
            return listOf(a)
        }
        val (completa, minima) = cadenas(t)
        val recetas = mutableListOf<String?>()
        recetas += completa
        if (minima != null && minima != completa) recetas += minima
        val intentos = mutableListOf<List<String>>()
        for (vf in recetas) {
            for (cod in listaCodificadores()) {
                val a = mutableListOf("-y", "-i", entrada.absolutePath)
                if (vf != null) a += listOf("-vf", vf)
                if (tieneAudio) {
                    if (af != null) a += listOf("-af", af)
                    a += listOf("-c:a", "aac", "-b:a", "192k", "-ar", "48000")
                } else a += "-an"
                a += argsCodificador(cod, t)
                a += listOf("-pix_fmt", "yuv420p", "-movflags", "+faststart", salida.absolutePath)
                intentos += a
            }
        }
        return intentos
    }
}
