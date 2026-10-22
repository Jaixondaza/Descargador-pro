package com.descargadorpro.app

import android.content.Intent
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Editor integrado (FFmpeg). Mejora imagen/sonido y adapta el formato.
 * El resultado se guarda directo en la galería (Movies/DescargadorPro).
 * Para re-subir material de terceros necesitas permiso o licencia.
 */
class EditorActivity : AppCompatActivity() {

    private var videoOriginal: File? = null
    private var archivoSalida: File? = null
    private var uriGaleria: Uri? = null
    private var salidaEsAudio = false
    private var duracionMs = 0L

    private lateinit var txtElegido: TextView
    private lateinit var txtEstado: TextView
    private lateinit var progreso: ProgressBar
    private lateinit var spinnerPreset: Spinner
    private lateinit var btnElegir: Button
    private lateinit var btnProcesar: Button
    private lateinit var btnCompartir: Button

    private data class Preset(val nombre: String, val filtro: String?, val soloAudio: Boolean = false)

    private val presets = listOf(
        Preset(
            "Mejorar calidad (nitidez, color, menos ruido)",
            "hqdn3d=4:4:6:6,eq=contrast=1.08:brightness=0.03:saturation=1.15,unsharp=5:5:0.8:5:5:0.0"
        ),
        Preset(
            "Vertical 9:16 — 1080×1920 (TikTok / Reels / Shorts)",
            "crop='min(iw,ih*9/16)':'min(ih,iw*16/9)',scale=1080:1920,hqdn3d=4,eq=saturation=1.1,unsharp=5:5:0.7"
        ),
        Preset(
            "Horizontal 16:9 — 1920×1080 (YouTube)",
            "crop='min(iw,ih*16/9)':'min(ih,iw*9/16)',scale=1920:1080,eq=saturation=1.08"
        ),
        Preset(
            "Cuadrado 1:1 — 1080×1080 (feed)",
            "crop='min(iw,ih)':'min(iw,ih)',scale=1080:1080"
        ),
        Preset(
            "Reescalar a 1080p (sin recortar)",
            "scale=-2:1080:flags=lanczos,unsharp=5:5:0.7"
        ),
        Preset("Solo mejorar audio (volumen normalizado)", null, soloAudio = true),
        Preset(
            "Comprimir ligero (calidad alta)",
            "scale=-2:'min(1080,ih)'"
        )
    )

    private val elegirVideo = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { copiarAInterno(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)
        supportActionBar?.hide()
        window.statusBarColor = Color.parseColor("#13111C")
        window.navigationBarColor = Color.parseColor("#13111C")

        txtElegido = findViewById(R.id.txtVideoElegido)
        txtEstado = findViewById(R.id.txtEstadoEditor)
        progreso = findViewById(R.id.progresoEditor)
        spinnerPreset = findViewById(R.id.spinnerPreset)
        btnElegir = findViewById(R.id.btnElegirVideo)
        btnProcesar = findViewById(R.id.btnProcesar)
        btnCompartir = findViewById(R.id.btnCompartir)

        spinnerPreset.adapter = ArrayAdapter(
            this, R.layout.item_spinner,
            presets.map { it.nombre }
        )

        btnElegir.setOnClickListener { elegirVideo.launch("video/*") }
        btnProcesar.setOnClickListener { procesar() }
        btnCompartir.setOnClickListener { compartir() }
        findViewById<Button>(R.id.btnVolverEditor).setOnClickListener { finish() }

        btnCompartir.isEnabled = false
    }

    private fun copiarAInterno(uri: Uri) {
        txtEstado.text = "Cargando el video…"
        lifecycleScope.launch {
            try {
                val nombre = nombreDesdeUri(uri) ?: "video"
                val destino = withContext(Dispatchers.IO) {
                    cacheDir.listFiles()?.filter { it.name.startsWith("entrada_") }?.forEach { it.delete() }
                    val f = File(cacheDir, "entrada_${System.currentTimeMillis()}.mp4")
                    contentResolver.openInputStream(uri)?.use { i ->
                        f.outputStream().use { o -> i.copyTo(o) }
                    } ?: throw IllegalStateException("No se pudo abrir el video")
                    f
                }
                videoOriginal = destino
                txtElegido.text = nombre
                txtEstado.text = "Listo. Elige un ajuste y toca «Mejorar y guardar»."
                btnCompartir.isEnabled = false
            } catch (e: Exception) {
                txtEstado.text = "No se pudo abrir el video: ${e.message}"
            }
        }
    }

    private fun procesar() {
        val entrada = videoOriginal
        if (entrada == null) {
            Toast.makeText(this, "Primero elige un video", Toast.LENGTH_SHORT).show()
            return
        }
        if (!Galeria.permisoOk(this)) {
            Toast.makeText(this, "Acepta el permiso y toca el botón otra vez", Toast.LENGTH_LONG).show()
            return
        }
        val preset = presets[spinnerPreset.selectedItemPosition]
        val carpeta = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "EditorPro").apply { mkdirs() }
        val ext = if (preset.soloAudio) "m4a" else "mp4"
        val salida = File(carpeta, "editado_${System.currentTimeMillis()}.$ext")
        archivoSalida = salida
        salidaEsAudio = preset.soloAudio
        uriGaleria = null
        duracionMs = leerDuracion(entrada)

        ocupado(true)
        txtEstado.text = "Procesando: ${preset.nombre}…"
        ejecutar(entrada, salida, preset, usarX264 = true)
    }

    private fun ejecutar(entrada: File, salida: File, p: Preset, usarX264: Boolean) {
        val args = mutableListOf("-y", "-i", entrada.absolutePath)
        if (p.soloAudio) {
            args += listOf("-vn", "-af", "loudnorm=I=-16:TP=-1.5:LRA=11", "-c:a", "aac", "-b:a", "192k")
        } else {
            if (p.filtro != null) args += listOf("-vf", p.filtro)
            args += listOf("-af", "loudnorm=I=-16:TP=-1.5:LRA=11")
            args += if (usarX264)
                listOf("-c:v", "libx264", "-preset", "medium", "-crf", "18", "-pix_fmt", "yuv420p")
            else
                // Si esta versión de FFmpeg no trae x264, usa el codificador del celular
                listOf("-c:v", "h264_mediacodec", "-b:v", "10M", "-pix_fmt", "yuv420p")
            args += listOf("-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart")
        }
        args += salida.absolutePath

        FFmpegKit.executeWithArgumentsAsync(args.toTypedArray(), { session ->
            if (ReturnCode.isSuccess(session.returnCode)) {
                guardarResultado(salida)
            } else if (!p.soloAudio && usarX264) {
                salida.delete()
                runOnUiThread { txtEstado.text = "Probando otro codificador…" }
                ejecutar(entrada, salida, p, usarX264 = false)
            } else {
                val log = (session.allLogsAsString ?: "").takeLast(400)
                runOnUiThread {
                    ocupado(false)
                    txtEstado.text = "No se pudo procesar. Prueba con otro ajuste.\n\n$log"
                }
            }
        }, null, { stats ->
            if (duracionMs > 0) {
                val pct = (stats.time.toLong() * 100 / duracionMs).toInt().coerceIn(0, 100)
                runOnUiThread { progreso.progress = pct }
            }
        })
    }

    private fun guardarResultado(salida: File) {
        try {
            val uri = Galeria.guardar(applicationContext, salida)
            uriGaleria = uri
            runOnUiThread {
                ocupado(false)
                progreso.progress = 100
                btnCompartir.isEnabled = true
                txtEstado.text = "✅ Listo. Se guardó en tu galería (carpeta DescargadorPro)."
            }
        } catch (e: Exception) {
            runOnUiThread {
                ocupado(false)
                txtEstado.text = "Se procesó, pero no se pudo guardar en la galería: ${e.message}"
            }
        }
    }

    private fun compartir() {
        val uri = uriGaleria?.takeIf { it.scheme == "content" }
            ?: archivoSalida?.let { FileProvider.getUriForFile(this, "${packageName}.fileprovider", it) }
            ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (salidaEsAudio) "audio/mp4" else "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Compartir video"))
    }

    private fun ocupado(si: Boolean) {
        progreso.visibility = if (si) View.VISIBLE else View.GONE
        if (si) progreso.progress = 0
        btnProcesar.isEnabled = !si
        btnElegir.isEnabled = !si
        if (si) btnCompartir.isEnabled = false
    }

    private fun leerDuracion(f: File): Long = try {
        val r = MediaMetadataRetriever()
        r.setDataSource(f.absolutePath)
        val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        r.release()
        d
    } catch (e: Exception) { 0L }

    private fun nombreDesdeUri(uri: Uri): String? {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) return cursor.getString(idx)
        }
        return uri.lastPathSegment
    }
}
