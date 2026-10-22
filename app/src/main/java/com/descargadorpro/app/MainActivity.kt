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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    // Variables de la interfaz (Asegúrate de que estos IDs existan en tu activity_main.xml)
    private lateinit var editUrl: EditText
    private lateinit var btnPegar: Button
    private lateinit var btnAnalizar: Button
    private lateinit var btnDescargar: Button
    private lateinit var spinnerCalidad: Spinner
    
    private lateinit var layoutPreview: View // Contenedor de la vista previa
    private lateinit var imgMiniatura: ImageView
    private lateinit var txtTitulo: TextView
    private lateinit var btnCopiarTitulo: ImageButton
    private lateinit var txtEstado: TextView
    private lateinit var progressBar: ProgressBar
    
    private var videoUrl: String = ""
    private var videoInfo: VideoInfo? = null
    private var selectedFormatId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        inicializarVistas()
        configurarListeners()
    }

    private fun inicializarVistas() {
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
        // 1. Botón Pegar
        btnPegar.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (clipboard.hasPrimaryClip()) {
                val item = clipboard.primaryClip?.getItemAt(0)
                editUrl.setText(item?.text.toString())
            }
        }

        // 2. Botón Analizar
        btnAnalizar.setOnClickListener {
            analizarVideo()
        }

        // 3. Botón Copiar Título
        btnCopiarTitulo.setOnClickListener {
            videoInfo?.let { info ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Título del Video", info.title)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Título copiado al portapapeles", Toast.LENGTH_SHORT).show()
            }
        }

        // 4. Botón Descargar
        btnDescargar.setOnClickListener {
            descargarVideo()
        }
    }

    private fun ocultarTeclado() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(editUrl.windowToken, 0)
    }

    private fun analizarVideo() {
        ocultarTeclado() // <- SOLUCIÓN: Oculta el teclado al analizar

        val url = editUrl.text.toString().trim()
        if (url.isEmpty()) {
            Toast.makeText(this, "Por favor, pega un enlace válido", Toast.LENGTH_SHORT).show()
            return
        }

        // NOTA: Se eliminó por completo la validación del CheckBox de derechos.

        videoUrl = url
        txtEstado.text = "Analizando enlace..."
        progressBar.visibility = View.VISIBLE
        layoutPreview.visibility = View.GONE

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Obtener información del video usando youtubedl-android
                val info = YoutubeDL.getInstance().getInfo(url)
                videoInfo = info

                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    layoutPreview.visibility = View.VISIBLE
                    txtEstado.text = "Video listo para descargar"

                    // Configurar Vista Previa
                    txtTitulo.text = info.title
                    // Usamos Glide para cargar la miniatura (Asegúrate de tener la dependencia)
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
                }
            }
        }
    }

    private fun configurarCalidades(info: VideoInfo) {
        // Extraemos las calidades disponibles
        val formatos = info.formats ?: emptyList()
        
        // Filtramos solo los que tienen video y son MP4 (o los que tú prefieras)
        val opcionesCalidad = formatos.filter { it.vcodec != "none" }
            .map { "${it.formatNote} - ${it.ext}" }
            .distinct()

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, opcionesCalidad)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerCalidad.adapter = adapter

        // Guardamos el ID del formato seleccionado (por defecto el primero)
        if (formatos.isNotEmpty()) {
            selectedFormatId = formatos.first().formatId
        }
    }

    private fun descargarVideo() {
        if (videoUrl.isEmpty() || videoInfo == null) return

        val formatSeleccionado = spinnerCalidad.selectedItemPosition
        val formato = videoInfo!!.formats.filter { it.vcodec != "none" }[formatSeleccionado]
        selectedFormatId = formato.formatId

        txtEstado.text = "Iniciando descarga..."
        progressBar.visibility = View.VISIBLE
        btnDescargar.isEnabled = false

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val request = YoutubeDLRequest(videoUrl)
                request.addOption("-f", selectedFormatId)
                
                // Carpeta de descargas de la app
                val dir = getExternalFilesDir(null)?.absolutePath ?: filesDir.absolutePath
                request.addOption("-o", "$dir/%(title)s.%(ext)s")

                // Ejecutar descarga con callback de progreso
                YoutubeDL.getInstance().execute(request) { progress, etaInSeconds, line ->
                    // Este callback se ejecuta en un hilo secundario
                    runOnUiThread {
                        if (progress > 0) {
                            val porcentaje = progress.toInt()
                            
                            // CÁLCULO DE MB
                            // Intentamos obtener el tamaño total del formato
                            val tamanoTotalBytes = formato.filesize.takeIf { it > 0 } ?: (formato.filesizeApprox ?: 0L)
                            val tamanoTotalMB = tamanoTotalBytes / (1024.0 * 1024.0)
                            
                            val mbDescargados = (progress / 100.0) * tamanoTotalMB

                            if (tamanoTotalMB > 0) {
                                txtEstado.text = "Descargando: $porcentaje% ($mbDescargados MB de $tamanoTotalMB MB)"
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
                    btnDescargar.isEnabled = true
                    Toast.makeText(this@MainActivity, "Video guardado en la carpeta de la app", Toast.LENGTH_LONG).show()
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    txtEstado.text = "Error al descargar: ${e.message}"
                    btnDescargar.isEnabled = true
                }
            }
        }
    }
}
