package com.descargadorpro.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Estados de WhatsApp: lee la carpeta oculta ".Statuses" (la que WhatsApp llena al ver un estado)
 * y deja guardar cada foto o video en la galería. No descarga nada de internet.
 * El acceso a la carpeta se da una sola vez con el selector de carpetas de Android.
 */
class EstadosActivity : AppCompatActivity() {

    private data class Item(val uri: Uri, val nombre: String, val esVideo: Boolean, val fecha: Long)

    private val prefs by lazy { getSharedPreferences("estados", Context.MODE_PRIVATE) }
    private var business = false
    private var items: List<Item> = emptyList()
    private val guardados = mutableSetOf<String>()
    private var trabajo: Job? = null
    private var ocupado = false

    private lateinit var grid: GridView
    private lateinit var vacio: View
    private lateinit var txtVacio: TextView
    private lateinit var btnAcceso: Button
    private lateinit var txtEstado: TextView
    private lateinit var btnWhatsapp: Button
    private lateinit var btnBusiness: Button
    private lateinit var btnGuardarTodos: Button

    private val adaptador = Adaptador()

    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val selector = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        val id = try { DocumentsContract.getTreeDocumentId(uri) } catch (e: Exception) { "" }
        if (!id.endsWith(".Statuses")) {
            toast("Elige la carpeta «.Statuses» y toca «Usar esta carpeta».")
            return@registerForActivityResult
        }
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) { }
        prefs.edit().putString(clave(), uri.toString()).apply()
        cargar()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_estados)

        grid = findViewById(R.id.grid)
        vacio = findViewById(R.id.vacio)
        txtVacio = findViewById(R.id.txtVacio)
        btnAcceso = findViewById(R.id.btnAcceso)
        txtEstado = findViewById(R.id.txtEstado)
        btnWhatsapp = findViewById(R.id.btnWhatsapp)
        btnBusiness = findViewById(R.id.btnBusiness)
        btnGuardarTodos = findViewById(R.id.btnGuardarTodos)

        grid.adapter = adaptador
        grid.setOnItemClickListener { _, _, pos, _ -> items.getOrNull(pos)?.let { guardarUno(it) } }
        btnWhatsapp.setOnClickListener { cambiarFuente(false) }
        btnBusiness.setOnClickListener { cambiarFuente(true) }
        btnAcceso.setOnClickListener { pedirAcceso() }
        btnGuardarTodos.setOnClickListener { guardarTodos() }
        findViewById<View>(R.id.btnVolver).setOnClickListener { finish() }
        pintarFuente()
    }

    override fun onResume() {
        super.onResume()
        cargar()
    }

    // ---------------------------------------------------------------- fuente (WhatsApp / Business)

    private fun clave() = if (business) "uri_business" else "uri_whatsapp"

    private fun cambiarFuente(b: Boolean) {
        if (business == b) return
        business = b
        pintarFuente()
        cargar()
    }

    private fun pintarFuente() {
        btnWhatsapp.setBackgroundResource(if (!business) R.drawable.bg_btn_primary else R.drawable.bg_btn_secondary)
        btnBusiness.setBackgroundResource(if (business) R.drawable.bg_btn_primary else R.drawable.bg_btn_secondary)
    }

    // ---------------------------------------------------------------- acceso a la carpeta

    private fun uriInicial(): Uri {
        val ruta = if (Build.VERSION.SDK_INT >= 30) {
            if (business) "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/.Statuses"
            else "Android/media/com.whatsapp/WhatsApp/Media/.Statuses"
        } else {
            if (business) "WhatsApp Business/Media/.Statuses" else "WhatsApp/Media/.Statuses"
        }
        return DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$ruta")
    }

    private fun pedirAcceso() {
        AlertDialog.Builder(this)
            .setTitle("Dar acceso a los estados")
            .setMessage(
                "En la pantalla que sigue toca «Usar esta carpeta» y luego «Permitir». " +
                    "Solo se lee esa carpeta, nada más.\n\n" +
                    "Si no se abre sola, entra a Android > media > com.whatsapp > WhatsApp > Media > .Statuses."
            )
            .setPositiveButton("Continuar") { _, _ ->
                try { selector.launch(uriInicial()) } catch (e: Exception) { toast("No se pudo abrir el selector de carpetas.") }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------------------------------------------------------------- lista

    private fun mostrarVacio(mensaje: String, conBoton: Boolean) {
        items = emptyList()
        adaptador.notifyDataSetChanged()
        grid.visibility = View.GONE
        vacio.visibility = View.VISIBLE
        txtVacio.text = mensaje
        btnAcceso.visibility = if (conBoton) View.VISIBLE else View.GONE
        btnGuardarTodos.visibility = View.GONE
        txtEstado.text = ""
    }

    private fun cargar() {
        guardados.clear()
        guardados.addAll(prefs.getStringSet("guardados", emptySet()) ?: emptySet())
        val arbol = prefs.getString(clave(), null)?.let { Uri.parse(it) }
        if (arbol == null) {
            mostrarVacio("Para ver tus estados, da acceso a su carpeta. Se hace una sola vez.", true)
            return
        }
        trabajo?.cancel()
        trabajo = lifecycleScope.launch {
            val lista = withContext(Dispatchers.IO) { try { listar(arbol) } catch (e: Exception) { null } }
            if (lista == null) {
                prefs.edit().remove(clave()).apply()
                mostrarVacio("Se perdió el acceso a la carpeta. Vuelve a darlo.", true)
            } else if (lista.isEmpty()) {
                mostrarVacio("Todavía no hay estados.\nAbre WhatsApp, mira los que quieras guardar y vuelve aquí.", false)
            } else {
                items = lista
                adaptador.notifyDataSetChanged()
                vacio.visibility = View.GONE
                grid.visibility = View.VISIBLE
                btnGuardarTodos.visibility = View.VISIBLE
                txtEstado.text = "${lista.size} estados · toca uno para guardarlo"
            }
        }
    }

    private fun listar(arbol: Uri): List<Item> {
        val hijos = DocumentsContract.buildChildDocumentsUriUsingTree(arbol, DocumentsContract.getTreeDocumentId(arbol))
        val lista = ArrayList<Item>()
        val columnas = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        contentResolver.query(hijos, columnas, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val nombre = c.getString(1) ?: continue
                val mime = c.getString(2) ?: ""
                val video = mime.startsWith("video")
                if (!video && !mime.startsWith("image")) continue
                lista.add(Item(DocumentsContract.buildDocumentUriUsingTree(arbol, id), nombre, video, c.getLong(3)))
            }
        }
        return lista.sortedByDescending { it.fecha }
    }

    // ---------------------------------------------------------------- miniaturas

    private fun reducir(b: Bitmap, ancho: Int = 320): Bitmap {
        if (b.width <= ancho) return b
        val alto = (b.height * ancho.toFloat() / b.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(b, ancho, alto, true)
    }

    private fun cargarMiniatura(item: Item): Bitmap? = try {
        if (item.esVideo) {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(this, item.uri)
                r.getFrameAtTime(0)?.let { reducir(it) }
            } finally {
                try { r.release() } catch (e: Exception) { }
            }
        } else {
            val limites = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(item.uri)?.use { BitmapFactory.decodeStream(it, null, limites) }
            var muestra = 1
            while (limites.outWidth / (muestra * 2) >= 320) muestra *= 2
            val opciones = BitmapFactory.Options().apply { inSampleSize = muestra }
            contentResolver.openInputStream(item.uri)?.use { BitmapFactory.decodeStream(it, null, opciones) }
        }
    } catch (e: Exception) {
        null
    }

    private inner class Adaptador : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_estado, parent, false)
            val item = items.getOrNull(position) ?: return v
            val clave = item.uri.toString()
            val img = v.findViewById<ImageView>(R.id.img)
            img.tag = clave
            val guardada = cache.get(clave)
            if (guardada != null) {
                img.setImageBitmap(guardada)
            } else {
                img.setImageDrawable(null)
                lifecycleScope.launch {
                    val bmp = withContext(Dispatchers.IO) { cargarMiniatura(item) }
                    if (bmp != null) {
                        cache.put(clave, bmp)
                        if (img.tag == clave) img.setImageBitmap(bmp)
                    }
                }
            }
            v.findViewById<View>(R.id.marcaVideo).visibility = if (item.esVideo) View.VISIBLE else View.GONE
            v.findViewById<View>(R.id.marcaGuardado).visibility =
                if (guardados.contains(item.nombre)) View.VISIBLE else View.GONE
            return v
        }
    }

    // ---------------------------------------------------------------- guardar

    private suspend fun guardarArchivo(item: Item): Boolean = withContext(Dispatchers.IO) {
        try {
            val ext = item.nombre.substringAfterLast('.', "").lowercase()
            val conocida = ext in setOf("jpg", "jpeg", "png", "webp", "mp4", "mkv", "webm", "mov", "m4v")
            val nombre = "Estado_" + item.nombre + if (conocida) "" else if (item.esVideo) ".mp4" else ".jpg"
            val temporal = File(cacheDir, nombre)
            val entrada = contentResolver.openInputStream(item.uri) ?: throw IOException("No se pudo leer el estado")
            entrada.use { i -> temporal.outputStream().use { o -> i.copyTo(o) } }
            try {
                Galeria.guardar(applicationContext, temporal)
            } finally {
                temporal.delete()
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun marcar(item: Item) {
        guardados.add(item.nombre)
        prefs.edit().putStringSet("guardados", guardados.toSet()).apply()
        adaptador.notifyDataSetChanged()
    }

    private fun guardarUno(item: Item) {
        if (ocupado) return
        if (!Galeria.permisoOk(this)) return
        ocupado = true
        lifecycleScope.launch {
            val ok = guardarArchivo(item)
            ocupado = false
            if (ok) {
                marcar(item)
                toast("✅ Guardado en tu galería, carpeta DescargadorPro")
            } else {
                toast("No se pudo guardar este estado.")
            }
        }
    }

    private fun guardarTodos() {
        if (ocupado) return
        val pendientes = items.filter { it.nombre !in guardados }
        if (pendientes.isEmpty()) {
            toast("Ya guardaste todos los estados.")
            return
        }
        if (!Galeria.permisoOk(this)) return
        ocupado = true
        btnGuardarTodos.isEnabled = false
        lifecycleScope.launch {
            var correctos = 0
            for ((n, item) in pendientes.withIndex()) {
                txtEstado.text = "Guardando ${n + 1} de ${pendientes.size}…"
                if (guardarArchivo(item)) {
                    marcar(item)
                    correctos++
                }
            }
            ocupado = false
            btnGuardarTodos.isEnabled = true
            txtEstado.text = "${items.size} estados · toca uno para guardarlo"
            toast("✅ Guardados $correctos de ${pendientes.size}")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
