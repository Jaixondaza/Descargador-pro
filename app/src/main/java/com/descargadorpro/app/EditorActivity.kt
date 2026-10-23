package com.descargadorpro.app // <--- ¡ESTA LÍNEA FALTABA!

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.FileOutputStream

class EditorActivity : AppCompatActivity() {

    private lateinit var btnElegirVideo: Button
    private lateinit var spinnerAcciones: Spinner
    private lateinit var btnMejorarGuardar: Button
    private lateinit var txtEstado: TextView
    private lateinit var progressBar: ProgressBar
    
    private var videoUri: Uri? = null
    private var videoFileName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)

        // Enlazar vistas
        btnElegirVideo = findViewById(R.id.btnElegirVideo)
        spinnerAcciones = findViewById(R.id.spinnerAcciones)
        btnMejorarGuardar = findViewById(R.id.btnMejorarGuardar)
        txtEstado = findViewById(R.id.txtEstado)
        progressBar = findViewById(R.id.progressBarEditor)

        configurarSpinner()

        btnElegirVideo.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT)
            intent.type = "video/*"
            startActivityForResult(intent, 100)
        }

        btnMejorarGuardar.setOnClickListener {
            if (videoUri == null) {
                Toast.makeText(this, "Primero elige un video", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            procesarVideo()
        }
    }

    private fun configurarSpinner() {
        val opciones = arrayOf(
            "Mejorar calidad (nitidez, color, menos ruido)",
            "Vertical 9:16 - 1080x1920 (TikTok/Reels)",
            "Horizontal 16:9 - 1920x1080 (YouTube)",
            "Cuadrado 1:1 - 1080x1080 (Feed)",
            "Reescalar a 1080p (sin recortar)",
            "Solo mejorar audio (volumen normalizado)",
            "Comprimir ligero (calidad alta)"
        )
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, opciones)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerAcciones.adapter = adapter
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == RESULT_OK && data != null) {
            videoUri = data.data
            videoFileName = obtenerNombreArchivo(videoUri!!)
            btnElegirVideo.text = videoFileName
            txtEstado.text = "Video cargado. Listo para editar."
        }
    }

    private fun obtenerNombreArchivo(uri: Uri): String {
        var nombre = "video.mp4"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1) nombre = cursor.getString(index)
            }
        }
        return nombre
    }

    private fun procesarVideo() {
        val seleccion = spinnerAcciones.selectedItemPosition
        
        // 1. Copiar el archivo seleccionado a la caché
        val inputFile = File(cacheDir, "input_$videoFileName")
        try {
            contentResolver.openInputStream(videoUri!!)?.use { input ->
                FileOutputStream(inputFile).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            txtEstado.text = "Error al leer el archivo: ${e.message}"
            return
        }

        // 2. Definir carpeta de salida
        val outputDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        val outputFile = File(outputDir, "editado_${System.currentTimeMillis()}.mp4")

        // 3. Construir el comando de FFmpeg
        val comando = construirComandoFFmpeg(inputFile.absolutePath, outputFile.absolutePath, seleccion)

        // 4. Mostrar progreso
        txtEstado.text = "Procesando video... Por favor espera."
        progressBar.visibility = View.VISIBLE
        btnMejorarGuardar.isEnabled = false

        // 5. Ejecutar FFmpeg
        FFmpegKit.executeAsync(comando) { session: FFmpegSession ->
            runOnUiThread {
                progressBar.visibility = View.GONE
                btnMejorarGuardar.isEnabled = true
                
                if (ReturnCode.isSuccess(session.returnCode)) {
                    txtEstado.text = "¡Video mejorado con éxito!\nGuardado en: ${outputFile.absolutePath}"
                    Toast.makeText(this, "Guardado en la galería de la app", Toast.LENGTH_LONG).show()
                } else {
                    txtEstado.text = "Error al procesar. Revisa el formato del video."
                    android.util.Log.e("FFmpegError", session.failStackTrace ?: "Error desconocido")
                }
                
                // Borrar archivo temporal
                inputFile.delete()
            }
        }
    }

    private fun construirComandoFFmpeg(input: String, output: String, opcion: Int): String {
        var filtroVideo = ""
        var filtroAudio = ""
        var ajustesExtra = ""

        when (opcion) {
            0 -> {
                filtroVideo = "hqdn3d=4:4:6:6,eq=contrast=1.08:brightness=0.03:saturation=1.15,unsharp=5:5:0.8:5:5:0.0"
                filtroAudio = "loudnorm=I=-16:TP=-1.5:LRA=11"
            }
            1 -> filtroVideo = "scale=1080:1920:force_original_aspect_ratio=increase,crop=1080:1920"
            2 -> filtroVideo = "scale=1920:1080:force_original_aspect_ratio=increase,crop=1920:1080"
            3 -> filtroVideo = "scale=1080:1080:force_original_aspect_ratio=increase,crop=1080:1080"
            4 -> filtroVideo = "scale=-2:1080"
            5 -> {
                filtroVideo = "copy"
                filtroAudio = "loudnorm=I=-16:TP=-1.5:LRA=11"
            }
            6 -> ajustesExtra = "-crf 23 -preset fast"
        }

        val sb = StringBuilder()
        sb.append("-y -i \"$input\" ")
        
        if (filtroVideo.isNotEmpty()) {
            if (filtroVideo == "copy") {
                sb.append("-c:v copy ")
            } else {
                sb.append("-vf \"$filtroVideo\" ")
                sb.append("-c:v libx264 ")
            }
        }

        if (filtroAudio.isNotEmpty()) {
            sb.append("-af \"$filtroAudio\" ")
            sb.append("-c:a aac -b:a 192k ")
        } else {
            sb.append("-c:a copy ")
        }

        if (ajustesExtra.isNotEmpty()) {
            sb.append("$ajustesExtra ")
        }

        sb.append("-map 0:v:0 -map 0:a:0? ")
        sb.append("\"$output\"")

        return sb.toString()
    }
}
