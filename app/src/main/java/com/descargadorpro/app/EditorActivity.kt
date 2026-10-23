package com.descargadorpro.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.min

/**
 * Editor de video (FFmpeg). Detecta qué filtros y codificadores trae el FFmpeg
 * instalado y arma la receta con lo que SÍ existe; si un método falla, prueba otro.
 * El resultado queda en la galería (carpeta DescargadorPro).
 */
class EditorActivity : AppCompatActivity() {

    private enum class Tipo { MEJORAR, VERTICAL, HORIZONTAL, CUADRADO, ESCALAR, AUDIO, COMPRIMIR }

    private data class Preset(val icono: String, val titulo: String, val detalle: String, val tipo: Tipo)

    private val presets = listOf(
        Preset("✨", "Mejorar calidad", "Más nitidez y color, menos ruido", Tipo.MEJORAR),
        Preset("📱", "Vertical 9:16", "1080×1920 · TikTok, Reels, Shorts", Tipo.VERTICAL),
        Preset("🖥", "Horizontal 16:9", "1920×1080 · YouTube", Tipo.HORIZONTAL),
        Preset("⬛", "Cuadrado 1:1", "1080×1080 · Feed de Instagram", Tipo.CUADRADO),
        Preset("🔎", "Subir a 1080p", "Agranda sin recortar", Tipo.ESCALAR),
        Preset("🔊", "Mejorar audio", "Volumen parejo · se guarda solo el audio (M4A)", Tipo.AUDIO),
        Preset("🗜", "Comprimir", "Archivo más liviano con buena calidad", Tipo.COMPRIMIR)
    )

    private lateinit var imgPreview: ImageView
    private lateinit var txtElegido: TextView
    private lateinit var txtInfo: TextView
    private lateinit var btnElegir: Button
    private lateinit var listaPresets: LinearLayout
    private lateinit var btnProcesar: Button
    private lateinit var cardProgreso: View
    private lateinit var txtPorcentaje: TextView
    private lateinit var progreso: ProgressBar
    private lateinit var txtEstado: TextView
    private lateinit var btnCancelar: Button
    private lateinit var filaResultado: View
    private lateinit var btnVer: Button
    private lateinit var btnCompartir: Button

    private var seleccion = 0
    private var videoOriginal: File? = null
    private var duracionMs = 0L
    private var tieneAudio = true
    private var procesando = false
    private var cancelado = false
    private var uriResultado: Uri? = null
    private var resultadoEsAudio = false

    private var filtros: Set<String>? = null
    private var codificadores: Set<String>? = null
    // Si no se pudo detectar, se asume solo lo que siempre trae un FFmpeg sin licencia GPL
    private val filtrosSeguros = setOf(
        "crop", "scale", "unsharp", "curves", "hue", "loudnorm", "dynaudnorm", "atadenoise", "format"
    )

    private val reFiltro = Regex("""\s[TSC.]{3}\s+(\w+)\s+[AVN|]+->[AVN|]+""")
    private val reCodificador = Regex("""\s[VAS][.A-Z]{5}\s+(\w+)\s""")

    private val elegirVideo = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { cargarVideo(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)
        supportActionBar?.hide()
        window.statusBarColor = Color.parseColor("#13111C")
        window.navigationBarColor = Color.parseColor("#13111C")

        imgPreview = findViewById(R.id.imgPreviewEditor)
        txtElegido = findViewById(R.id.txtVideoElegido)
        txtInfo = findViewById(R.id.txtVideoInfo)
        btnElegir = findViewById(R.id.btnElegirVideo)
        listaPresets = findViewById(R.id.listaPresets)
        btnProcesar = findViewById(R.id.btnProcesar)
        cardProgreso = findViewById(R.id.cardProgresoEditor)
        txtPorcentaje = findViewById(R.id.txtPorcentajeEditor)
        progreso = findViewById(R.id.progresoEditor)
        txtEstado = findViewById(R.id.txtEstadoEditor)
        btnCancelar = findViewById(R.id.btnCancelarEditor)
        filaResultado = findViewById(R.id.filaResultado)
        btnVer = findViewById(R.id.btnVerResultado)
        btnCompartir = findViewById(R.id.btnCompartir)

        btnElegir.setOnClickListener { if (!procesando) elegirVideo.launch("video/*") }
        imgPreview.setOnClickListener { if (!procesando) elegirVideo.launch("video/*") }
        btnProcesar.setOnClickListener { procesar() }
        btnCancelar.setOnClickListener {
            cancelado = true
            try { FFmpegKit.cancel() } catch (e: Exception) { }
        }
        btnVer.setOnClickListener { verResultado() }
        btnCompartir.setOnClickListener { compartir() }
        findViewById<Button>(R.id.btnVolverEditor).setOnClickListener { finish() }

        btnProcesar.isEnabled = false
        construirPresets()
    }

    // ------------------------------------------------------------ lista de opciones

    private fun construirPresets() {
        listaPresets.removeAllViews()
        presets.forEachIndexed { i, p ->
            val elegido = i == seleccion
            val fila = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
                background = if (elegido) fondo("#2B2350", "#7C5CFF", 16, 2) else fondo("#1E1C2E", "#3A3460", 16, 1)
                setOnClickListener {
                    if (procesando) return@setOnClickListener
                    seleccion = i
                    construirPresets()
                }
            }
            fila.addView(TextView(this).apply {
                text = p.icono
                textSize = 24f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            })
            val textos = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = dp(8) }
            }
            textos.addView(TextView(this).apply {
                text = p.titulo
                textSize = 16f
                setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            textos.addView(TextView(this).apply {
                text = p.detalle
                textSize = 13f
                setTextColor(Color.parseColor("#A09CB8"))
            })
            fila.addView(textos)
            fila.addView(TextView(this).apply {
                text = if (elegido) "●" else "○"
                textSize = 20f
                setTextColor(if (elegido) Color.parseColor("#7C5CFF") else Color.parseColor("#6D6890"))
                gravity = Gravity.CENTER
            })
            listaPresets.addView(fila)
        }
    }

    // ------------------------------------------------------------ cargar video

    private data class Datos(
        val duracionMs: Long, val ancho: Int, val alto: Int, val conAudio: Boolean, val cuadro: Bitmap?
    )

    private fun cargarVideo(uri: Uri) {
        txtEstado.text = ""
        cardProgreso.visibility = View.GONE
        filaResultado.visibility = View.GONE
        txtInfo.text = "Cargando el video…"
        lifecycleScope.launch {
            try {
                val nombre = nombreDesdeUri(uri) ?: "video"
                val (archivo, datos) = withContext(Dispatchers.IO) {
                    cacheDir.listFiles()?.filter { it.name.startsWith("entrada_") }?.forEach { it.delete() }
                    val f = File(cacheDir, "entrada_${System.currentTimeMillis()}.mp4")
                    contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                        ?: throw IllegalStateException("No se pudo abrir el video")
                    Pair(f, leerDatos(f))
                }
                videoOriginal = archivo
                duracionMs = datos.duracionMs
                tieneAudio = datos.conAudio
                txtElegido.text = nombre
                val partes = mutableListOf<String>()
                if (datos.duracionMs > 0) partes += fmtDuracion((datos.duracionMs / 1000).toInt())
                if (datos.ancho > 0) partes += "${datos.ancho}×${datos.alto}"
                partes += fmtBytes(archivo.length())
                if (!datos.conAudio) partes += "sin audio"
                txtInfo.text = partes.joinToString("  ·  ")
                if (datos.cuadro != null) imgPreview.setImageBitmap(datos.cuadro)
                btnElegir.text = "Cambiar video"
                btnProcesar.isEnabled = true
            } catch (e: Exception) {
                txtInfo.text = "No se pudo abrir el video: ${e.message}"
            }
        }
    }

    private fun leerDatos(f: File): Datos {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(f.absolutePath)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            val audio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            val cuadro = try {
                r.getFrameAtTime(min(dur * 100L, 1_500_000L), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } catch (e: Exception) { null }
            return Datos(dur, w, h, audio, cuadro)
        } finally {
            try { r.release() } catch (e: Exception) { }
        }
    }

    // ------------------------------------------------------------ recetas de FFmpeg

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

    private fun armarIntentos(entrada: File, salida: File, t: Tipo): List<List<String>> {
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

    private suspend fun detectarCapacidades() = withContext(Dispatchers.IO) {
        if (filtros != null && codificadores != null) return@withContext
        try {
            val sf = FFmpegKit.execute("-hide_banner -filters")
            val f = reFiltro.findAll(sf.allLogsAsString.orEmpty()).map { it.groupValues[1] }.toSet()
            if (f.size > 20) filtros = f
            val se = FFmpegKit.execute("-hide_banner -encoders")
            val c = reCodificador.findAll(se.allLogsAsString.orEmpty()).map { it.groupValues[1] }.toSet()
            if (c.size > 10) codificadores = c
        } catch (e: Throwable) { }
    }

    // ------------------------------------------------------------ procesar

    private fun procesar() {
        val entrada = videoOriginal
        if (entrada == null) { toast("Primero elige un video"); return }
        if (procesando) return
        val p = presets[seleccion]
        if (p.tipo == Tipo.AUDIO && !tieneAudio) { toast("Este video no tiene audio"); return }
        if (!Galeria.permisoOk(this)) { toast("Acepta el permiso y toca el botón otra vez"); return }

        procesando = true
        cancelado = false
        uriResultado = null
        resultadoEsAudio = p.tipo == Tipo.AUDIO
        ocupado(true)
        txtEstado.text = "Preparando…"
        motivoFallo = ""
        irAlFinal()

        lifecycleScope.launch {
            detectarCapacidades()
            val carpeta = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "EditorPro").apply { mkdirs() }
            val ext = if (resultadoEsAudio) "m4a" else "mp4"
            val salida = File(carpeta, "editado_${System.currentTimeMillis()}.$ext")
            val intentos = armarIntentos(entrada, salida, p.tipo)
            correr(intentos, 0, salida)
        }
    }

    private var motivoFallo = ""

    /** Comprueba que el archivo generado sea reproducible: duración razonable y, en video, un cuadro legible. */
    private fun resultadoValido(f: File): Boolean = try {
        val d = leerDatos(f)
        val duracionOk = duracionMs <= 0 || d.duracionMs >= duracionMs * 7 / 10
        duracionOk && (resultadoEsAudio || (d.ancho > 0 && d.cuadro != null))
    } catch (e: Exception) { false }

    private fun correr(intentos: List<List<String>>, i: Int, salida: File) {
        runOnUiThread {
            progreso.progress = 0
            txtPorcentaje.text = "0%"
            txtEstado.text = if (i == 0) "Procesando: ${presets[seleccion].titulo}…"
            else "Probando otro método (${i + 1} de ${intentos.size})…"
        }
        FFmpegKit.executeWithArgumentsAsync(intentos[i].toTypedArray(), { session ->
            val ejecutoOk = ReturnCode.isSuccess(session.returnCode) && salida.exists() && salida.length() > 0
            val ok = ejecutoOk && resultadoValido(salida)
            if (ejecutoOk && !ok) {
                val a = intentos[i]
                val k = a.indexOf("-c:v")
                val cod = if (k >= 0) a.getOrNull(k + 1) ?: "?" else "?"
                motivoFallo = "Un método generó un archivo dañado (${fmtBytes(salida.length())}, codificador: $cod) y se descartó."
            }
            if (ok) {
                terminar(salida)
            } else {
                salida.delete()
                if (cancelado) {
                    runOnUiThread { ocupado(false); txtEstado.text = "Proceso cancelado." }
                } else if (i + 1 < intentos.size) {
                    correr(intentos, i + 1, salida)
                } else {
                    val log = session.allLogsAsString.orEmpty().takeLast(300)
                    runOnUiThread {
                        ocupado(false)
                        txtEstado.text = "No se pudo procesar este video con ese ajuste. Prueba con otro ajuste.\n\n" +
                            (if (motivoFallo.isNotEmpty()) motivoFallo + "\n\n" else "") + "Detalle técnico:\n$log"
                    }
                }
            }
        }, null, { stats ->
            if (duracionMs > 0) {
                val pct = (stats.time.toLong() * 100 / duracionMs).toInt().coerceIn(0, 100)
                runOnUiThread { progreso.progress = pct * 10; txtPorcentaje.text = "$pct%" }
            }
        })
    }

    private fun terminar(salida: File) {
        try {
            val tam = salida.length()
            val uri = Galeria.guardar(applicationContext, salida)
            salida.delete()
            uriResultado = uri
            runOnUiThread {
                ocupado(false)
                progreso.progress = 1000
                txtPorcentaje.text = "100%"
                txtEstado.text = "✅ Listo (${fmtBytes(tam)}). Se guardó en tu galería, carpeta DescargadorPro."
                filaResultado.visibility = View.VISIBLE
                irAlFinal()
            }
        } catch (e: Exception) {
            runOnUiThread {
                ocupado(false)
                txtEstado.text = "Se procesó, pero no se pudo guardar en la galería: ${e.message}"
            }
        }
    }

    private fun ocupado(si: Boolean) {
        procesando = si
        cardProgreso.visibility = View.VISIBLE
        btnProcesar.isEnabled = !si && videoOriginal != null
        btnElegir.isEnabled = !si
        btnCancelar.visibility = if (si) View.VISIBLE else View.GONE
        filaResultado.visibility = View.GONE
        if (si) {
            progreso.progress = 0
            txtPorcentaje.text = "0%"
        }
    }

    private fun verResultado() {
        val uri = uriResultado ?: return
        Galeria.abrir(this, uri, if (resultadoEsAudio) "audio/mp4" else "video/mp4")
    }

    /** Baja la pantalla hasta el progreso / resultado para que no haya que buscarlo. */
    private fun irAlFinal() {
        val sv = findViewById<ScrollView>(R.id.scrollEditor)
        sv.post { sv.fullScroll(View.FOCUS_DOWN) }
    }

    private fun compartir() {
        val uri = uriResultado ?: return
        if (uri.scheme != "content") { toast("Compártelo desde tu galería, carpeta DescargadorPro"); return }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (resultadoEsAudio) "audio/mp4" else "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Compartir"))
    }

    // ------------------------------------------------------------ utilidades

    private fun nombreDesdeUri(uri: Uri): String? {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) return cursor.getString(idx)
        }
        return uri.lastPathSegment
    }

    private fun fondo(color: String, borde: String, radioDp: Int, grosorDp: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radioDp).toFloat()
        setColor(Color.parseColor(color))
        setStroke(dp(grosorDp), Color.parseColor(borde))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun fmtBytes(b: Long): String = when {
        b >= 1_000_000_000L -> String.format(Locale.getDefault(), "%.2f GB", b / 1e9)
        b >= 1_000_000L -> String.format(Locale.getDefault(), "%.1f MB", b / 1e6)
        else -> String.format(Locale.getDefault(), "%.0f KB", b / 1e3)
    }

    private fun fmtDuracion(seg: Int): String {
        val h = seg / 3600
        val m = (seg % 3600) / 60
        val s = seg % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
