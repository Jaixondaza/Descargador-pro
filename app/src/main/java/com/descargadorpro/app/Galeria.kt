package com.descargadorpro.app

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.IOException

/** Guarda archivos en la galería (Movies, Music o Pictures / DescargadorPro). */
object Galeria {

    private val EXT_AUDIO = setOf("mp3", "m4a", "aac", "opus", "ogg", "wav", "flac")

    private val EXT_IMAGEN = setOf("jpg", "jpeg", "png", "webp")

    fun esAudio(archivo: File) = archivo.extension.lowercase() in EXT_AUDIO

    fun esImagen(archivo: File) = archivo.extension.lowercase() in EXT_IMAGEN

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
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
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
        val imagen = esImagen(archivo)
        val tipo = mime(archivo)
        val carpetaPublica = when {
            audio -> Environment.DIRECTORY_MUSIC
            imagen -> Environment.DIRECTORY_PICTURES
            else -> Environment.DIRECTORY_MOVIES
        }

        if (Build.VERSION.SDK_INT >= 29) {
            val coleccion = when {
                audio -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                imagen -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }

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

    /**
     * Abre un archivo ya guardado. Primero prueba con otra app (galería, VLC...).
     * Si no hay ninguna que lo acepte, o el sistema lo rechaza por cualquier motivo,
     * lo reproduce dentro de la propia app para que SIEMPRE se pueda ver el resultado.
     */
    fun abrir(act: Activity, uri: Uri, mime: String) {
        // Android 9 o menor devuelve file://, que el sistema NO deja pasar a otras apps.
        if (uri.scheme == "content") {
            try {
                act.startActivity(
                    Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
                return
            } catch (e: Exception) {
                Log.w("Galeria", "Sin app externa (${e.javaClass.simpleName}): ${e.message}")
            }
        }
        reproducirEnApp(act, uri, mime.startsWith("audio"))
    }

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    /** Reproductor mínimo a pantalla completa (VideoView del sistema, sin librerías extra). */
    private fun reproducirEnApp(act: Activity, uri: Uri, esAudio: Boolean) {
        val dialogo = Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val raiz = FrameLayout(act).apply { setBackgroundColor(Color.BLACK) }
        val todo = { FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER
        ) }

        val video = VideoView(act)
        val controles = MediaController(act)
        controles.setAnchorView(video)
        video.setMediaController(controles)
        raiz.addView(video, todo())

        if (esAudio) {
            raiz.addView(TextView(act).apply { text = "🔊"; textSize = 72f; gravity = Gravity.CENTER }, todo())
        }

        val cerrar = TextView(act).apply {
            text = "✕"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setOnClickListener { dialogo.dismiss() }
        }
        raiz.addView(
            cerrar,
            FrameLayout.LayoutParams(dp(act, 52), dp(act, 52), Gravity.TOP or Gravity.END)
                .apply { topMargin = dp(act, 24) }
        )

        video.setOnPreparedListener { video.start() }
        video.setOnErrorListener { _, what, extra ->
            Log.w("Galeria", "Error de reproducción: $what / $extra")
            Toast.makeText(
                act, "No se pudo reproducir (error $what/$extra). Está en tu galería, carpeta DescargadorPro.",
                Toast.LENGTH_LONG
            ).show()
            dialogo.dismiss()
            true
        }
        dialogo.setOnDismissListener { video.stopPlayback() }
        dialogo.setContentView(raiz)
        video.setVideoURI(uri)
        dialogo.show()
    }
}
