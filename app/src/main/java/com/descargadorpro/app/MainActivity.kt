import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoInfo
import com.yausername.youtubedl_android.mapper.VideoFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    // 1. Declaración de variables de la interfaz
    private lateinit var editUrl: EditText
    private lateinit var btnPegar: Button
    private lateinit var btnAnalizar: Button
    private lateinit var btnDescargar: Button
    private lateinit var spinnerCalidad: Spinner
    
    private lateinit var layoutPreview: View 
    private lateinit var imgMiniatura: ImageView
    private lateinit var txtTitulo: TextView
    private lateinit var btnCopiarTitulo: ImageButton
    private lateinit var txtEstado: TextView
    private lateinit var progressBar: ProgressBar
    
    // Variables para guardar los datos del video analizado
    private var videoUrl: String = ""
    private var videoInfo: VideoInfo? = null
    private var formatosDisponibles: List<VideoFormat> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        inicializarVistas()
        configurarListeners()
    }

    private fun inicializarVistas() {
        // Enlazar los IDs del XML
        editUrl = findViewById(R.id.editUrl)
        btnPegar = findViewById(R.id.btnPegar)
        btnAnalizar = findViewById(R.id.btnAnalizar)
        btnDescargar = findViewById(R.id.btnDescargar)
        spinnerCalidad = findViewById(R.id.spinnerCalidad)
        
        layoutPreview = findViewById(R.id.layoutPreview)
        imgMiniatura = findViewById(R.id.imgMiniatura)
        txtTitulo = findViewById(R.id.txtTitulo)
        btnCopiarTitulo = findViewById(R.id.btnCopiarTitulo)
        txtEstado = findViewById(R.id.txtEstado)
        progressBar = findViewById(R.id.progressBar)
        
        // Ocultar elementos hasta que se analice un video
        layoutPreview.visibility = View.GONE
        progressBar.visibility = View.GONE
    }

    private fun configurarListeners() {
        // Botón Pegar
        btnPegar.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (clipboard.hasPrimaryClip()) {
                val item = clipboard.primaryClip?.getItemAt(0)
                editUrl.setText(item?.text.toString())
            }
        }

        // Botón Analizar
        btnAnalizar.setOnClickListener {
            analizarVideo()
        }

        // Botón Copiar Título
        btnCopiarTitulo.setOnClickListener {
            videoInfo?.let { info ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Título del Video", info.title)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Título copiado", Toast.LENGTH_SHORT).show()
            }
        }

        // Botón Descargar
        btnDescargar.setOnClickListener {
            descargarVideo()
        }
    }

    // Función para ocultar el teclado
    private fun ocultarTeclado() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(editUrl.windowToken, 0)
    }

    private fun analizarVideo() {
        ocultarTeclado() // Oculta el teclado automáticamente

        val url = editUrl.text.toString().trim()
        if (url.isEmpty()) {
            Toast.makeText(this, "Por favor, pega un enlace válido", Toast.LENGTH_SHORT).show()
            return
        }

        videoUrl = url
        txtEstado.text = "Analizando enlace..."
        txtEstado.setTextColor(android.graphics.Color.WHITE)
        progressBar.visibility = View.VISIBLE
        layoutPreview.visibility = View.GONE
        btnDescargar.isEnabled = false

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val info = YoutubeDL.getInstance().getInfo(url)
                videoInfo = info

                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    layoutPreview.visibility = View.VISIBLE
                    btnDescargar.isEnabled = true
                    txtEstado.text = "Video listo para descargar"
                    txtEstado.setTextColor(android.graphics.Color.parseColor("#00E676"))

                    // Configurar Vista Previa
                    txtTitulo.text = info.title
                    Glide.with(this@MainActivity)
                        .load(info.thumbnail)
                        .placeholder(android.R.drawable.ic_menu_gallery)
                        .into(imgMiniatura)

                    // Configurar Selector de Calidad
                    configurarCalidades(info)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    txtEstado.text = "Error al analizar: ${e.message}"
                    txtEstado.setTextColor(android.graphics.Color.RED)
                }
            }
        }
    }

    private fun configurarCalidades(info: VideoInfo) {
        // Filtramos los formatos que contienen video (evitando los que son solo audio)
        val formatos = info.formats ?: emptyList()
        formatosDisponibles = formatos.filter { it.vcodec != "none" && it.vcodec != null }
        
        val opcionesCalidad = formatosDisponibles.map { 
            if (it.formatNote.isNullOrEmpty()) "${it.formatId} - ${it.ext}" else "${it.formatNote} - ${it.ext}" 
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, opcionesCalidad)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerCalidad.adapter = adapter
    }

    private fun descargarVideo() {
        if (videoUrl.isEmpty() || videoInfo == null || formatosDisponibles.isEmpty()) return

        val posicionSeleccionada = spinnerCalidad.selectedItemPosition
        val formatoSeleccionado = formatosDisponibles[posicionSeleccionada]

        txtEstado.text = "Iniciando descarga..."
        txtEstado.setTextColor(android.graphics.Color.WHITE)
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        btnDescargar.isEnabled = false
        btnAnalizar.isEnabled = false

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val request = YoutubeDLRequest(videoUrl)
                
                // Usamos el ID del formato seleccionado
                request.addOption("-f", formatoSeleccionado.formatId)
                
                // Carpeta de descargas
                val dir = getExternalFilesDir(null)?.absolutePath ?: filesDir.absolutePath
                request.addOption("-o", "$dir/%(title)s.%(ext)s")

                YoutubeDL.getInstance().execute(request) { progress, _, _ ->
                    runOnUiThread {
                        if (progress > 0) {
                            val porcentaje = progress.toInt().coerceIn(0, 100)
                            
                            // Cálculo de MB
                            // Algunas plataformas devuelven el tamaño en 'filesize' o 'filesizeApprox'
                            val tamanoTotalBytes = if (formatoSeleccionado.filesize > 0) formatoSeleccionado.filesize else (formatoSeleccionado.filesizeApprox ?: 0L)
                            val tamanoTotalMB = tamanoTotalBytes / (1024.0 * 1024.0)
                            
                            val mbDescargados = (progress / 100.0) * tamanoTotalMB

                            if (tamanoTotalMB > 0) {
                                txtEstado.text = "Descargando: $porcentaje% (${"%.2f".format(mbDescargados)} MB / ${"%.2f".format(tamanoTotalMB)} MB)"
                            } else {
                                txtEstado.text = "Descargando: $porcentaje%"
                            }
                            progressBar.progress = porcentaje
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    txtEstado.text = "¡Descarga completada con éxito!"
                    txtEstado.setTextColor(android.graphics.Color.parseColor("#00E676"))
                    btnDescargar.isEnabled = true
                    btnAnalizar.isEnabled = true
                    Toast.makeText(this@MainActivity, "Video guardado en la memoria del teléfono", Toast.LENGTH_LONG).show()
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    txtEstado.text = "Error al descargar: ${e.message}"
                    txtEstado.setTextColor(android.graphics.Color.RED)
                    btnDescargar.isEnabled = true
                    btnAnalizar.isEnabled = true
                }
            }
        }
    }
}
