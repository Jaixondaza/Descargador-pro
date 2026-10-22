package com.descargadorpro.app

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Pantalla principal: pegar / analizar / descargar.
 * Las descargas se guardan directo en la galería (Movies/DescargadorPro).
 * Uso previsto: videos propios o con permiso/licencia del usuario.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var inputUrl: EditText
    private lateinit var txtInfo: TextView
    private lateinit var txtEstado: TextView
    private lateinit var progreso: ProgressBar
    private lateinit var spinnerCalidad: Spinner
    private lateinit var checkDerechos: CheckBox

    @Volatile private var motorListo = false
    @Volatile private var ffmpegListo = false
    private var ultimoUri: Uri? = null
    private var ultimoTipo: String = "video/mp4"

    private data class Calidad(val etiqueta: String, val altura: Int?, val audio: Boolean = false)

    private val calidades = listOf(
        Calidad("Mejor calidad disponible", null),
        Calidad("4K  — 2160p (si existe)", 2160),
        Calidad("2K  — 1440p (si existe)", 1440),
        Calidad("Full HD — 1080p", 1080),
        Calidad("HD — 720p", 720),
        Calidad("480p (ahorra datos)", 480),
        Calidad("Solo audio — MP3", null, audio = true)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        supportActionBar?.hide()
        window.statusBarColor = Color.parseColor("#13111C")
        window.navigationBarColor = Color.parseColor("#13111C")

        inputUrl = findViewById(R.id.inputUrl)
        txtInfo = findViewById(R.id.txtInfo)
        txtEstado = findViewById(R.id.txtEstado)
        progreso = findViewById(R.id.progreso)
        spinnerCalidad = findViewById(R.id.spinnerCalidad)
        checkDerechos = findViewById(R.id.checkDerechos)

        spinnerCalidad.adapter = ArrayAdapter(
            this, R.layout.item_spinner,
            calidades.map { it.etiqueta }
        )

        intent?.let { recibirEnlaceCompartido(it) }

        findViewById<Button>(R.id.btnPegar).setOnClickListener {
            val clip = getSystemService(ClipboardManager::class.java)
                .primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
            val url = extraerUrl(clip)
            if (url != null) inputUrl.setText(url)
            else toast("El portapapeles no tiene un enlace")
        }

        findViewById<Button>(R.id.btnAnalizar).setOnClickListener { analizar() }
        findViewById<Button>(R.id.btnDescargar).setOnClickListener { descargar() }

        findViewById<Button>(R.id.btnEditor).setOnClickListener {
            startActivity(Intent(this, EditorActivity::class.java))
        }

        // "Mis videos": abre el último descargado o, si no hay, la galería de videos
        findViewById<Button>(R.id.btnMisVideos).setOnClickListener {
            val uri = ultimoUri
            val intento = if (uri != null) {
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, ultimoTipo)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                Intent(Intent.ACTION_VIEW, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            }
            try { startActivity(intento) } catch (e: Exception) { toast("No se encontró una app de galería") }
        }

        iniciarMotor()
    }

    private fun iniciarMotor() {
        estado("Preparando el motor de descarga…")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                YoutubeDL.getInstance().init(applicationContext)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { estado("No se pudo iniciar el motor: ${e.message}") }
                return@launch
            }

            // FFmpeg de yt-dlp: hace falta para unir video+audio y para sacar MP3.
            // Se inicia por reflexión para que compile aunque el paquete cambie de nombre.
            try {
                val clase = Class.forName("com.yausername.ffmpeg.FFmpeg")
                val instancia = clase.getMethod("getInstance").invoke(null)
                clase.getMethod("init", Context::class.java).invoke(instancia, applicationContext)
                ffmpegListo = true
            } catch (e: Throwable) {
                ffmpegListo = false
            }

            // Limpia restos de descargas anteriores
            try {
                val base = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
                base.listFiles()?.filter { it.isDirectory && it.name.startsWith("tmp_") }
                    ?.forEach { it.deleteRecursively() }
            } catch (_: Exception) { }

            withContext(Dispatchers.Main) { estado("Actualizando el motor de descarga…") }
            try {
                YoutubeDL.getInstance().updateYoutubeDL(applicationContext, YoutubeDL.UpdateChannel.NIGHTLY)
                withContext(Dispatchers.Main) {
                    estado("Motor actualizado. Pega un enlace y toca Analizar.")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    estado("No se pudo actualizar el motor (¿sin internet?): ${e.message}")
                }
            }
            motorListo = true
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        recibirEnlaceCompartido(intent)
    }

    private fun recibirEnlaceCompartido(intent: Intent) {
        val texto = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
        extraerUrl(texto.orEmpty())?.let { inputUrl.setText(it) }
    }

    private fun analizar() {
        val url = urlValida() ?: return
        if (!motorListo) { toast("El motor se está preparando, espera unos segundos"); return }
        estado("Analizando…")
        lifecycleScope.launch {
            try {
                val info = withContext(Dispatchers.IO) {
                    val req = YoutubeDLRequest(url)
                    req.addOption("--no-playlist")
                    YoutubeDL.getInstance().getInfo(req)
                }
                txtInfo.text = buildString {
                    append("Título: ${info.title ?: "—"}\n")
                    append("Duración: ${info.duration.toInt()} s")
                }
                estado("Análisis listo. Elige calidad y descarga.")
            } catch (e: Exception) {
                estado("No se pudo analizar: ${(e.message ?: "sin detalle").takeLast(400)}")
            }
        }
    }

    private fun formatoPara(c: Calidad): String {
        if (c.audio) return "ba/b"
        val h = c.altura
        val f = if (h != null) "[height<=$h]" else ""
        return if (ffmpegListo) {
            // Hasta 1080p prefiere H.264 (se ve en cualquier galería)
            if (h != null && h <= 1080) "bv*$f[vcodec^=avc1]+ba[ext=m4a]/bv*$f+ba/b$f/b"
            else "bv*$f+ba/b$f/b"
        } else {
            // Sin FFmpeg no se puede unir video y audio: pide un archivo ya unido
            "b$f[ext=mp4]/b$f/b"
        }
    }

    private fun descargar() {
        val url = urlValida() ?: return
        if (!checkDerechos.isChecked) {
            toast("Marca la casilla: es tu video o tienes permiso para descargarlo")
            return
        }
        if (!motorListo) { toast("El motor se está preparando, espera unos segundos"); return }
        if (!Galeria.permisoOk(this)) {
            toast("Acepta el permiso de almacenamiento y toca Descargar otra vez")
            return
        }

        val calidad = calidades[spinnerCalidad.selectedItemPosition]
        val base = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
        val tmp = File(base, "tmp_${System.currentTimeMillis()}").apply { mkdirs() }

        estado("Descargando: ${calidad.etiqueta} …")
        progreso.progress = 0

        lifecycleScope.launch {
            try {
                val guardado = withContext(Dispatchers.IO) {
                    val req = YoutubeDLRequest(url)
                    req.addOption("-f", formatoPara(calidad))
                    req.addOption("-o", File(tmp, "%(title).60s [%(id)s].%(ext)s").absolutePath)
                    req.addOption("--no-playlist")
                    req.addOption("--no-mtime")
                    if (calidad.audio) {
                        if (ffmpegListo) {
                            req.addOption("--extract-audio")
                            req.addOption("--audio-format", "mp3")
                        }
                    } else if (ffmpegListo) {
                        req.addOption("--merge-output-format", "mp4")
                    }

                    YoutubeDL.getInstance().execute(req, "descarga") { progress, _, _ ->
                        runOnUiThread {
                            val p = progress.toInt().coerceIn(0, 100)
                            progreso.progress = p
                            estado("Descargando… $p %")
                        }
                    }

                    val validas = setOf("mp4", "mkv", "webm", "mov", "mp3", "m4a", "opus", "ogg")
                    val archivo = tmp.listFiles()
                        ?.filter { it.isFile && it.extension.lowercase() in validas }
                        ?.maxByOrNull { it.length() }
                        ?: throw IllegalStateException("yt-dlp terminó pero no dejó ningún archivo")

                    runOnUiThread { estado("Guardando en la galería…") }
                    val uri = Galeria.guardar(applicationContext, archivo)
                    Pair(uri, Galeria.mime(archivo))
                }
                ultimoUri = guardado.first
                ultimoTipo = guardado.second
                progreso.progress = 100
                estado("✅ Listo. El video ya está en tu galería (carpeta DescargadorPro). Toca «Mis videos» para abrirlo.")
            } catch (e: Exception) {
                estado("Error de descarga:\n${(e.message ?: "sin detalle").takeLast(500)}")
            } finally {
                withContext(Dispatchers.IO) { tmp.deleteRecursively() }
            }
        }
    }

    private fun urlValida(): String? {
        val url = extraerUrl(inputUrl.text.toString())
        if (url == null) toast("Pega primero un enlace válido (http…)")
        return url
    }

    private fun extraerUrl(texto: String): String? =
        Regex("""https?://\S+""").find(texto)?.value?.trimEnd('.', ',', ')')

    private fun estado(msg: String) { txtEstado.text = msg }
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
