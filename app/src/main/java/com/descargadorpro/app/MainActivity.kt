package com.descargadorpro.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.bumptech.glide.Glide
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoFormat
import com.yausername.youtubedl_android.mapper.VideoInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var editUrl: EditText
    private lateinit var btnPegar: Button
    private lateinit var btnAnalizar: Button
    private lateinit var btnDescargar: Button
    private lateinit var btnIrEditor: Button
    private lateinit var spinnerCalidad: Spinner
    
    private lateinit var layoutPreview: View 
    private lateinit var imgMiniatura: ImageView
    private lateinit var txtTitulo: TextView
    private lateinit var btnCopiarTitulo: ImageButton
    private lateinit var txtEstado: TextView
    private lateinit var progressBar: ProgressBar
    
    private var videoUrl: String = ""
    private var videoInfo: VideoInfo? = null
    private var formatosDisponibles: List<VideoFormat> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // INICIALIZACIÓN CORREGIDA (Sin getInstance)
        CoroutineScope(Dispatchers.IO).launch {
            try {
    YoutubeDL.getInstance().init(applicationContext) 
    FFmpegKitConfig.init()
} catch (e: Exception) {
    e.printStackTrace()
}
        }

        inicializarVistas()
        configurarListeners()
        pedirPermisos()
    }

    private fun inicializarVistas() {
        editUrl = findViewById(R.id.editUrl)
        btnPegar = findViewById(R.id.btnPegar)
        btnAnalizar = findViewById(R.id.btnAnalizar)
        btnDescargar = findViewById(R.id.btnDescargar)
        btnIrEditor = findViewById(R.id.btnIrEditor)
        spinnerCalidad = findViewById(R.id.spinnerCalidad)
        
        layoutPreview = findViewById(R.id.layoutPreview)
        imgMiniatura = findViewById(R.id.imgMiniatura)
        txtTitulo = findViewById(R.id.txtTitulo)
        btnCopiarTitulo = findViewById(R.id.btnCopiarTitulo)
        txtEstado = findViewById(R.id.txtEstado)
        progressBar = findViewById(R.id.progressBar)
        
        layoutPreview.visibility = View.GONE
        progressBar.visibility = View.GONE
    }

    private fun configurarListeners() {
        btnPegar.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (clipboard.hasPrimaryClip()) {
                val item = clipboard.primaryClip?.getItemAt(0)
                editUrl.setText(item?.text.toString())
            }
        }

        btnAnalizar.setOnClickListener { analizarVideo() }

        btnCopiarTitulo.setOnClickListener {
            videoInfo?.let { info ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Título del Video", info.title ?: "Video")
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Título copiado", Toast.LENGTH_SHORT).show()
            }
        }

        btnDescargar.setOnClickListener { descargarVideo() }

        btnIrEditor.setOnClickListener {
            val intent = Intent(this, EditorActivity::class.java)
            startActivity(intent)
        }
    }

    private fun pedirPermisos() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_MEDIA_VIDEO), 101)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE), 101)
            }
        }
    }

    private fun ocultarTeclado() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(editUrl.windowToken, 0)
    }

    private fun analizarVideo() {
        ocultarTeclado()

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
                // CORRECCIÓN: También sin getInstance() aquí
                val info = YoutubeDL.getInfo(url)
                videoInfo = info

                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    layoutPreview.visibility = View.VISIBLE
                    btnDescargar.isEnabled = true
                    txtEstado.text = "Video listo para descargar"
                    txtEstado.setTextColor(android.graphics.Color.parseColor("#00E676"))

                    txtTitulo.text = info.title ?: "Título no disponible"
                    Glide.with(this@MainActivity)
                        .load(info.thumbnail)
                        .placeholder(android.R.drawable.ic_menu_gallery)
                        .into(imgMiniatura)

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
        val formatos = info.formats ?: emptyList()
        formatosDisponibles = formatos.filter { it.vcodec != "none" && it.vcodec != null }
        
        val opcionesCalidad = formatosDisponibles.map { 
            val note = it.formatNote ?: it.formatId ?: "Formato"
            val ext = it.ext ?: "mp4"
            "$note - $ext"
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
                request.addOption("-f", formatoSeleccionado.formatId ?: "")
                
                val dir = getExternalFilesDir(null)?.absolutePath ?: filesDir.absolutePath
                request.addOption("-o", "$dir/%(title)s.%(ext)s")

                // CORRECCIÓN: También sin getInstance() aquí
                YoutubeDL.execute(request) { progress, _, _ ->
                    runOnUiThread {
                        if (progress > 0) {
                            val porcentaje = progress.toInt().coerceIn(0, 100)
                            txtEstado.text = "Descargando: $porcentaje%"
                            progressBar.progress = porcentaje
                        }
                    }
                }

                val archivoDescargado = File(dir).listFiles()?.maxByOrNull { it.lastModified() }

                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    btnDescargar.isEnabled = true
                    btnAnalizar.isEnabled = true

                    if (archivoDescargado != null) {
                        val guardadoExitoso = guardarEnGaleria(archivoDescargado)
                        if (guardadoExitoso) {
                            txtEstado.text = "¡Descarga completada y guardada en Galería!"
                            txtEstado.setTextColor(android.graphics.Color.parseColor("#00E676"))
                        } else {
                            txtEstado.text = "Descargado, pero falló al guardar en Galería."
                            txtEstado.setTextColor(android.graphics.Color.YELLOW)
                        }
                    } else {
                        txtEstado.text = "¡Descarga completada!"
                        txtEstado.setTextColor(android.graphics.Color.parseColor("#00E676"))
                    }
                    Toast.makeText(this@MainActivity, "Proceso finalizado", Toast.LENGTH_LONG).show()
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

    private fun guardarEnGaleria(archivoOriginal: File): Boolean {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.TITLE, archivoOriginal.name)
                put(MediaStore.Video.Media.DISPLAY_NAME, archivoOriginal.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/DescargadorPro")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }

            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)

            if (uri != null) {
                val outputStream = resolver.openOutputStream(uri)
                val inputStream = archivoOriginal.inputStream()
                inputStream.copyTo(outputStream!!)
                inputStream.close()
                outputStream.close()

                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                
                archivoOriginal.delete()
                true
            } else {
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
