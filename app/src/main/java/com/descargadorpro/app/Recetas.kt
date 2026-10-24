package com.descargadorpro.app

import java.io.File

/** Tipos de ajuste que ofrece el editor. */
enum class Tipo { MEJORAR, VERTICAL, HORIZONTAL, CUADRADO, ESCALAR, AUDIO, COMPRIMIR }

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
            Tipo.ESCALAR -> {
                val g = "scale='if(gt(iw,ih),-2,1080)':'if(gt(iw,ih),1080,-2)':flags=lanczos"
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

    private fun argsCodificador(nombre: String, comprimir: Boolean): List<String> = when (nombre) {
        "libx264" -> listOf("-c:v", "libx264", "-preset", "veryfast", "-crf", if (comprimir) "27" else "20")
        "h264_mediacodec" -> listOf("-c:v", "h264_mediacodec", "-b:v", if (comprimir) "3M" else "10M")
        else -> listOf("-c:v", "mpeg4", "-q:v", if (comprimir) "7" else "3")
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
                a += argsCodificador(cod, t == Tipo.COMPRIMIR)
                a += listOf("-pix_fmt", "yuv420p", "-movflags", "+faststart", salida.absolutePath)
                intentos += a
            }
        }
        return intentos
    }
}
