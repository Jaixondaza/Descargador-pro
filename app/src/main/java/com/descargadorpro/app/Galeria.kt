package com.descargadorpro.app

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.IOException

/** Guarda archivos en la galería (Movies/DescargadorPro o Music/DescargadorPro). */
object Galeria {

    private val EXT_AUDIO = setOf("mp3", "m4a", "aac", "opus", "ogg", "wav", "flac")

    fun esAudio(archivo: File) = archivo.extension.lowercase() in EXT_AUDIO

    fun mime(archivo: File): String = when (archivo.extension.lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "mp3" -> "audio/mpeg"
        "m4a", "aac" -> "audio/mp4"
        "opus", "ogg" -> "audio/ogg"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        else -> "video/mp4"
    }

    /** Solo Android 9 o menor necesita permiso. Devuelve true si ya se puede guardar. */
    fun permisoOk(act: Activity): Boolean {
        if (Build.VERSION.SDK_INT >= 29) return true
        val ok = ContextCompat.checkSelfPermission(
            act, Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        if (!ok) {
            ActivityCompat.requestPermissions(
                act, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 77
            )
        }
        return ok
    }

    fun guardar(ctx: Context, archivo: File): Uri {
        val audio = esAudio(archivo)
        val tipo = mime(archivo)
        val carpetaPublica = if (audio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES

        if (Build.VERSION.SDK_INT >= 29) {
            val coleccion = if (audio)
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

            val valores = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, archivo.name)
                put(MediaStore.MediaColumns.MIME_TYPE, tipo)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$carpetaPublica/DescargadorPro")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(coleccion, valores)
                ?: throw IOException("No se pudo crear el archivo en la galería")
            try {
                val salida = resolver.openOutputStream(uri)
                    ?: throw IOException("No se pudo abrir el archivo de la galería")
                salida.use { out -> archivo.inputStream().use { it.copyTo(out) } }
                valores.clear()
                valores.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, valores, null, null)
                return uri
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        } else {
            val base = Environment.getExternalStoragePublicDirectory(carpetaPublica)
            val dir = File(base, "DescargadorPro").apply { mkdirs() }
            val destino = File(dir, archivo.name)
            archivo.copyTo(destino, overwrite = true)
            MediaScannerConnection.scanFile(ctx, arrayOf(destino.absolutePath), arrayOf(tipo), null)
            return Uri.fromFile(destino)
        }
    }
}
