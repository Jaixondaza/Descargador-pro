package com.descargadorpro.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Descargador de videos: YouTube, TikTok, Facebook e Instagram.
 * Pega el enlace -> Analizar (vista previa, título, calidades con tamaño)
 * -> Descargar (progreso en % y en MB). Todo queda en la galería.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var raiz: View
    private lateinit var txtMotor: TextView
    private lateinit var inputUrl: EditText
    private lateinit var btnAnalizar: Button
    private lateinit var cardPreview: View
    private lateinit var imgMiniatura: ImageView
    private lateinit var txtDuracion: TextView
    private lateinit var txtTitulo: TextView
    private lateinit var txtAutor: TextView
    private lateinit var contenedorChips: LinearLayout
    private lateinit var txtTamanoSel: TextView
    private lateinit var btnDescargar: Button
    private lateinit var cardProgreso: View
    private lateinit var txtPorcentaje: TextView
    private lateinit var txtTamano: TextView
    private lateinit var progreso: ProgressBar
    private lateinit var txtVelocidad: TextView
    private lateinit var txtEstado: TextView
    private lateinit var btnCancelar: Button
    private lateinit var btnAbrir: Button

    @Volatile private var motorListo = false
    @Volatile private var ffmpegListo = false
    private var pendienteAnalizar = false
    private var descargando = false
    private var cancelado = false
    private var analizadaUrl: String? = null
    private var tituloActual = ""
    private var ultimoUri: Uri? = null
    private var ultimoTipo = "video/mp4"

    private data class Opcion(
        val etiqueta: String,
        val altura: Int?,        // null = la mejor
        val audio: Boolean,
        val bytes: Long,         // 0 = tamaño desconocido
        val detalle: String,
        val dosFlujos: Boolean   // video y audio vienen por separado (YouTube)
    )

    private val opcionesPorDefecto = listOf(
        Opcion("Mejor", null, false, 0, "", false),
        Opcion("1080p", 1080, false, 0, "", false),
        Opcion("720p", 720, false, 0, "", false),
        Opcion("480p", 480, false, 0, "", false),
        Opcion("MP3", null, true, 0, "audio", false)
    )
    private var opciones = opcionesPorDefecto
    private var seleccion = 0

    private val reTotal = Regex("""of\s+~?\s*([\d.]+)\s*(B|KiB|MiB|GiB|KB|MB|GB)""")
    private val reVel = Regex("""at\s+([\d.]+)\s*(B|KiB|MiB|GiB|KB|MB|GB)/s""")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        supportActionBar?.hide()
        window.statusBarColor = Color.parseColor("#13111C")
        window.navigationBarColor = Color.parseColor("#13111C")
        // El teclado solo aparece cuando tocas el campo del enlace
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )

        raiz = findViewById(R.id.raiz)
        txtMotor = findViewById(R.id.txtMotor)
        inputUrl = findViewById(R.id.inputUrl)
        btnAnalizar = findViewById(R.id.btnAnalizar)
        cardPreview = findViewById(R.id.cardPreview)
        imgMiniatura = findViewById(R.id.imgMiniatura)
        txtDuracion = findViewById(R.id.txtDuracion)
        txtTitulo = findViewById(R.id.txtTitulo)
        txtAutor = findViewById(R.id.txtAutor)
        contenedorChips = findViewById(R.id.contenedorChips)
        txtTamanoSel = findViewById(R.id.txtTamanoSel)
        btnDescargar = findViewById(R.id.btnDescargar)
        cardProgreso = findViewById(R.id.cardProgreso)
        txtPorcentaje = findViewById(R.id.txtPorcentaje)
        txtTamano = findViewById(R.id.txtTamano)
        progreso = findViewById(R.id.progreso)
        txtVelocidad = findViewById(R.id.txtVelocidad)
        txtEstado = findViewById(R.id.txtEstado)
        btnCancelar = findViewById(R.id.btnCancelar)
        btnAbrir = findViewById(R.id.btnAbrir)

        txtDuracion.background = fondo("#CC000000", "#00000000", 8)
        construirChips()

        findViewById<Button>(R.id.btnPegar).setOnClickListener {
            val clip = getSystemService(ClipboardManager::class.java)
                .primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
            val url = extraerUrl(clip)
            if (url != null) {
                inputUrl.setText(url)
                ocultarTeclado()
            } else toast("El portapapeles no tiene un enlace")
        }
        findViewById<View>(R.id.btnLimpiar).setOnClickListener {
            inputUrl.setText("")
        }
        btnAnalizar.setOnClickListener { analizar() }
        btnDescargar.setOnClickListener { descargar() }
        btnCancelar.setOnClickListener { cancelar() }
        btnAbrir.setOnClickListener { abrirUltimo() }
        findViewById<CheckBox>(R.id.chkSoloWifi)?.let { chk ->
            chk.isChecked = Red.soloWifi(this)
            chk.setOnCheckedChangeListener { _, marcado -> Red.fijarSoloWifi(this, marcado) }
        }
        findViewById<Button>(R.id.btnCopiarTitulo).setOnClickListener { copiarTitulo() }
        imgMiniatura.setOnClickListener {
            val url = analizadaUrl ?: return@setOnClickListener
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: Exception) { }
        }
        findViewById<Button>(R.id.btnEditor).setOnClickListener {
            startActivity(Intent(this, EditorActivity::class.java))
        }
        findViewById<Button>(R.id.btnMisVideos).setOnClickListener {
            startActivity(Intent(this, HistorialActivity::class.java))
        }

        inputUrl.imeOptions = EditorInfo.IME_ACTION_GO
        inputUrl.setOnEditorActionListener { _, accion, _ ->
            if (accion == EditorInfo.IME_ACTION_GO) { analizar(); true } else false
        }
        inputUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val actual = extraerUrl(s?.toString().orEmpty())
                if (analizadaUrl != null && actual != analizadaUrl && !descargando) {
                    analizadaUrl = null
                    cardPreview.visibility = View.GONE
                    opciones = opcionesPorDefecto
                    seleccion = 0
                    construirChips()
                }
            }
        })

        intent?.let { if (recibirEnlaceCompartido(it)) pendienteAnalizar = true }
        iniciarMotor()
    }

    // ---------------------------------------------------------------- motor

    private fun iniciarMotor() {
        txtMotor.text = "⏳  Preparando el motor de descarga…"
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                YoutubeDL.getInstance().init(applicationContext)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { txtMotor.text = "⚠  No se pudo iniciar el motor: ${e.message}" }
                return@launch
            }
            // FFmpeg de yt-dlp: une video+audio y saca MP3 (por reflexión para evitar errores de compilación)
            try {
                val clase = Class.forName("com.yausername.ffmpeg.FFmpeg")
                val instancia = clase.getMethod("getInstance").invoke(null)
                clase.getMethod("init", Context::class.java).invoke(instancia, applicationContext)
                ffmpegListo = true
            } catch (e: Throwable) {
                ffmpegListo = false
            }
            try {
                val base = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
                base.listFiles()?.filter { it.isDirectory && it.name.startsWith("tmp_") }
                    ?.forEach { it.deleteRecursively() }
            } catch (e: Exception) { }

            withContext(Dispatchers.Main) { txtMotor.text = "⏳  Actualizando el motor de descarga…" }
            try {
                YoutubeDL.getInstance().updateYoutubeDL(applicationContext, YoutubeDL.UpdateChannel.NIGHTLY)
                withContext(Dispatchers.Main) { txtMotor.text = "✅  Motor al día" }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    txtMotor.text = "⚠  No se pudo actualizar el motor (¿sin internet?). Puedes intentar igual."
                }
            }
            motorListo = true
            withContext(Dispatchers.Main) {
                if (pendienteAnalizar) { pendienteAnalizar = false; analizar() }
            }
        }
    }

    // ---------------------------------------------------------------- enlaces

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (recibirEnlaceCompartido(intent)) {
            if (motorListo) analizar() else pendienteAnalizar = true
        }
    }

    private fun recibirEnlaceCompartido(intent: Intent): Boolean {
        val texto = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
        val url = extraerUrl(texto.orEmpty()) ?: return false
        inputUrl.setText(url)
        return true
    }

    private fun urlValida(): String? {
        val url = extraerUrl(inputUrl.text.toString())
        if (url == null) toast("Pega primero un enlace válido (http…)")
        return url
    }

    private fun extraerUrl(texto: String): String? =
        Regex("""https?://\S+""").find(texto)?.value?.trimEnd('.', ',', ')')

    // ---------------------------------------------------------------- análisis

    private fun analizar() {
        val url = urlValida() ?: return
        ocultarTeclado()
        if (descargando) return
        if (!motorListo) {
            pendienteAnalizar = true
            toast("El motor se está preparando; analizo en cuanto termine")
            return
        }
        btnAnalizar.isEnabled = false
        btnAnalizar.text = "Analizando…"
        cardPreview.visibility = View.GONE
        lifecycleScope.launch {
            try {
                val json = withContext(Dispatchers.IO) { obtenerInfo(url) }
                mostrarAnalisis(url, json)
            } catch (e: Exception) {
                cardProgreso.visibility = View.VISIBLE
                progreso.progress = 0
                btnAbrir.visibility = View.GONE
                txtPorcentaje.text = ""
                txtTamano.text = ""
                txtVelocidad.text = ""
                txtEstado.text = "No se pudo analizar:\n${mensajeAmable(e)}"
            } finally {
                btnAnalizar.isEnabled = true
                btnAnalizar.text = "Analizar"
            }
        }
    }

    private fun obtenerInfo(url: String): JSONObject {
        val req = YoutubeDLRequest(url)
        req.addOption("-J")
        req.addOption("--no-playlist")
        req.addOption("--no-warnings")
        val resp = YoutubeDL.getInstance().execute(req, "info") { _, _, _ -> }
        val texto = resp.out.orEmpty()
        val ini = texto.indexOf('{')
        if (ini < 0) throw IllegalStateException("No se pudo leer la información del video")
        return JSONObject(texto.substring(ini))
    }

    private fun mostrarAnalisis(url: String, json: JSONObject) {
        analizadaUrl = url
        tituloActual = json.optString("title", "").ifBlank { json.optString("fulltitle", "Video") }
        val autor = json.optString("uploader", "").ifBlank { json.optString("channel", "") }
        val seg = json.optDouble("duration", 0.0).toInt()
        var miniatura = json.optString("thumbnail", "")
        if (miniatura.isBlank()) {
            val arr = json.optJSONArray("thumbnails")
            if (arr != null && arr.length() > 0) {
                miniatura = arr.optJSONObject(arr.length() - 1)?.optString("url", "").orEmpty()
            }
        }

        txtTitulo.text = tituloActual
        txtAutor.text = autor
        txtAutor.visibility = if (autor.isBlank()) View.GONE else View.VISIBLE
        txtDuracion.text = fmtDuracion(seg)
        txtDuracion.visibility = if (seg > 0) View.VISIBLE else View.GONE
        imgMiniatura.setImageDrawable(null)
        cardPreview.visibility = View.VISIBLE
        if (miniatura.isNotBlank()) cargarMiniatura(miniatura)

        opciones = construirOpciones(json)
        seleccion = 0
        construirChips()
        cardProgreso.visibility = View.GONE
    }

    private fun cargarMiniatura(url: String) {
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                try {
                    val u = if (url.startsWith("http:")) url.replaceFirst("http:", "https:") else url
                    val c = URL(u).openConnection() as HttpURLConnection
                    c.connectTimeout = 15_000
                    c.readTimeout = 15_000
                    c.setRequestProperty("User-Agent", "Mozilla/5.0")
                    c.inputStream.use { BitmapFactory.decodeStream(it) }
                } catch (e: Exception) { null }
            }
            if (bmp != null) imgMiniatura.setImageBitmap(bmp)
        }
    }

    /** Arma las calidades REALES del video, con su tamaño aproximado si el sitio lo informa. */
    private fun construirOpciones(json: JSONObject): List<Opcion> {
        val formatos = json.optJSONArray("formats") ?: return opcionesPorDefecto

        class F(val res: Int, val bytes: Long, val tbr: Double, val conAudio: Boolean)
        val videos = mutableListOf<F>()
        var mejorAudio: F? = null

        for (i in 0 until formatos.length()) {
            val f = formatos.optJSONObject(i) ?: continue
            val vc = f.optString("vcodec", "")
            val ac = f.optString("acodec", "")
            val w = f.optInt("width", 0)
            val h = f.optInt("height", 0)
            var bytes = f.optLong("filesize", 0L)
            if (bytes <= 0L) bytes = f.optLong("filesize_approx", 0L)
            val tbr = f.optDouble("tbr", 0.0).let { if (it.isNaN()) 0.0 else it }
            val esVideo = vc != "none" && (h > 0 || w > 0)
            val esAudio = !esVideo && ac != "none" && ac.isNotEmpty()
            if (esVideo) {
                val res = if (w > 0 && h > 0) min(w, h) else max(w, h)
                videos += F(res, bytes, tbr, ac != "none" && ac.isNotEmpty())
            } else if (esAudio) {
                if (mejorAudio == null || tbr > mejorAudio.tbr) mejorAudio = F(0, bytes, tbr, true)
            }
        }
        if (videos.isEmpty()) return opcionesPorDefecto

        val audioBytes = mejorAudio?.bytes ?: 0L
        val resoluciones = videos.map { it.res }.filter { it >= 144 }.distinct().sortedDescending()
        if (resoluciones.isEmpty()) return opcionesPorDefecto

        fun etiqueta(r: Int) = when {
            r >= 2160 -> "4K"
            r >= 1440 -> "2K"
            else -> "${r}p"
        }

        val lista = mutableListOf<Opcion>()
        val porRes = resoluciones.map { r ->
            val mejor = videos.filter { it.res == r }
                .maxWithOrNull(compareBy<F>({ if (it.bytes > 0) 1 else 0 }, { it.tbr }))!!
            val dos = !mejor.conAudio
            val total = if (mejor.bytes > 0) mejor.bytes + (if (dos) audioBytes else 0L) else 0L
            Opcion(etiqueta(r), r, false, total, if (total > 0) "≈ ${fmtBytes(total)}" else "", dos)
        }
        val top = porRes.first()
        lista += Opcion(
            "Mejor", null, false, top.bytes,
            listOf(top.etiqueta, if (top.bytes > 0) "≈ ${fmtBytes(top.bytes)}" else "")
                .filter { it.isNotEmpty() }.joinToString(" · "),
            top.dosFlujos
        )
        lista += porRes
        lista += Opcion("MP3", null, true, audioBytes, if (audioBytes > 0) "≈ ${fmtBytes(audioBytes)}" else "audio", false)
        return lista
    }

    // ---------------------------------------------------------------- chips de calidad

    private fun construirChips() {
        contenedorChips.removeAllViews()
        opciones.forEachIndexed { i, o ->
            val elegido = i == seleccion
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                minimumWidth = dp(92)
                setPadding(dp(18), dp(12), dp(18), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(10) }
                background = if (elegido) fondo("#7C5CFF", "#7C5CFF", 16) else fondo("#1E1C2E", "#4B3F8F", 16)
                setOnClickListener {
                    if (descargando) return@setOnClickListener
                    seleccion = i
                    construirChips()
                }
            }
            chip.addView(TextView(this).apply {
                text = (if (o.audio) "🎵 " else "") + o.etiqueta
                textSize = 16f
                setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
            })
            if (o.detalle.isNotEmpty()) {
                chip.addView(TextView(this).apply {
                    text = o.detalle
                    textSize = 12f
                    setTextColor(if (elegido) Color.parseColor("#E6DFFF") else Color.parseColor("#A09CB8"))
                    gravity = Gravity.CENTER
                })
            }
            contenedorChips.addView(chip)
        }
        val o = opciones[seleccion]
        txtTamanoSel.text = when {
            o.audio -> "Solo audio en MP3" + if (o.bytes > 0) " · aprox. ${fmtBytes(o.bytes)}" else ""
            o.bytes > 0 -> "Elegida: ${o.etiqueta} · tamaño aprox. ${fmtBytes(o.bytes)}"
            analizadaUrl != null -> "Elegida: ${o.etiqueta} · este sitio no informa el tamaño"
            else -> "Toca Analizar para ver las calidades reales de este video"
        }
    }

    // ---------------------------------------------------------------- descarga

    private fun argsFormato(o: Opcion): List<Pair<String, String?>> {
        if (o.audio) {
            return if (ffmpegListo)
                listOf("-f" to "ba/b", "-x" to null, "--audio-format" to "mp3", "--audio-quality" to "0")
            else
                listOf("-f" to "ba[ext=m4a]/ba/b")
        }
        val res = o.altura
        return if (ffmpegListo) {
            val orden = (if (res != null) "res:$res" else "res") + ",vcodec:h264,acodec:aac"
            listOf("-f" to "bv*+ba/b", "-S" to orden, "--merge
