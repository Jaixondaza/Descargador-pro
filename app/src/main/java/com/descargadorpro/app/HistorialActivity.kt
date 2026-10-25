package com.descargadorpro.app

import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.format.DateFormat
import android.text.format.Formatter
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Historial: lista lo que la app guardó en la galería (carpeta DescargadorPro de Movies, Music y Pictures).
 * Al tocar un archivo se puede abrir, compartir o eliminar.
 */
class HistorialActivity : AppCompatActivity() {

    /** tipo: 0 = video, 1 = audio, 2 = foto */
    private data class Item(
        val uri: Uri, val nombre: String, val mime: String,
        val tam: Long, val fecha: Long, val tipo: Int
    )

    private var items: List<Item> = emptyList()
    private var trabajo: Job? = null
    private var pendienteBorrar: Item? = null

    private lateinit var lista: ListView
    private lateinit var txtVacio: TextView
    private lateinit var txtResumen: TextView

    private val adaptador = Adaptador()

    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val confirmarBorrado = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val item = pendienteBorrar
        pendienteBorrar = null
        if (item != null && r.resultCode == RESULT_OK) quitar(item)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_historial)

        lista = findViewById(R.id.lista)
        txtVacio = findViewById(R.id.txtVacio)
        txtResumen = findViewById(R.id.txtResumen)

        lista.adapter = adaptador
        lista.setOnItemClickListener { _, _, pos, _ -> items.getOrNull(pos)?.let { menu(it) } }
        findViewById<View>(R.id.btnVolver).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        cargar()
    }

    // ---------------------------------------------------------------- lista

    private fun cargar() {
        // Android 9 o menor necesita el permiso de almacenamiento para ver los archivos
        if (Build.VERSION.SDK_INT < 29 && !Galeria.permisoOk(this)) return
        trabajo?.cancel()
        trabajo = lifecycleScope.launch {
            val nuevos = withContext(Dispatchers.IO) {
                try { consultar() } catch (e: Exception) { emptyList() }
            }
            items = nuevos
            adaptador.notifyDataSetChanged()
            pintarResumen()
        }
    }

    private fun pintarResumen() {
        if (items.isEmpty()) {
            lista.visibility = View.GONE
            txtVacio.visibility = View.VISIBLE
            txtResumen.text = ""
        } else {
            lista.visibility = View.VISIBLE
            txtVacio.visibility = View.GONE
            txtResumen.text = "${items.size} archivos · " + Formatter.formatShortFileSize(this, items.sumOf { it.tam })
        }
    }

    private fun consultar(): List<Item> {
        val salida = ArrayList<Item>()
        val fuentes = listOf(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI to 0,
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to 1,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI to 2
        )
        val columnas = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED
        )
        val nuevoAndroid = Build.VERSION.SDK_INT >= 29
        val filtro = if (nuevoAndroid) "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        else "${MediaStore.MediaColumns.DATA} LIKE ?"
        val args = arrayOf(if (nuevoAndroid) "%DescargadorPro%" else "%/DescargadorPro/%")

        for ((coleccion, tipo) in fuentes) {
            contentResolver.query(coleccion, columnas, filtro, args, null)?.use { c ->
                while (c.moveToNext()) {
                    salida.add(
                        Item(
                            ContentUris.withAppendedId(coleccion, c.getLong(0)),
                            c.getString(1) ?: "archivo",
                            c.getString(2) ?: "",
                            c.getLong(3),
                            c.getLong(4),
                            tipo
                        )
                    )
                }
            }
        }
        return salida.sortedByDescending { it.fecha }
    }

    // ---------------------------------------------------------------- acciones

    private fun menu(item: Item) {
        AlertDialog.Builder(this)
            .setTitle(item.nombre)
            .setItems(arrayOf("▶  Abrir", "📤  Compartir", "🗑  Eliminar")) { _, cual ->
                when (cual) {
                    0 -> Galeria.abrir(this, item.uri, item.mime.ifEmpty { "video/mp4" })
                    1 -> compartir(item)
                    else -> confirmarEliminar(item)
                }
            }
            .show()
    }

    private fun compartir(item: Item) {
        try {
            val envio = Intent(Intent.ACTION_SEND)
                .setType(item.mime.ifEmpty { "*/*" })
                .putExtra(Intent.EXTRA_STREAM, item.uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(envio, "Compartir"))
        } catch (e: Exception) {
            toast("No se pudo compartir este archivo.")
        }
    }

    private fun confirmarEliminar(item: Item) {
        AlertDialog.Builder(this)
            .setTitle("¿Eliminar?")
            .setMessage("Se borrará del teléfono:\n${item.nombre}")
            .setPositiveButton("Eliminar") { _, _ -> borrar(item) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun borrar(item: Item) {
        try {
            if (contentResolver.delete(item.uri, null, null) > 0) {
                quitar(item)
                return
            }
        } catch (e: SecurityException) {
            // Android 11+: si el archivo no es "nuestro", el sistema pide permiso con un aviso propio
            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    val pedido = MediaStore.createDeleteRequest(contentResolver, listOf(item.uri))
                    pendienteBorrar = item
                    confirmarBorrado.launch(IntentSenderRequest.Builder(pedido.intentSender).build())
                    return
                } catch (e2: Exception) { }
            }
        } catch (e: Exception) { }
        toast("No se pudo eliminar. Bórralo desde tu galería.")
    }

    private fun quitar(item: Item) {
        items = items.filter { it.uri != item.uri }
        adaptador.notifyDataSetChanged()
        pintarResumen()
        toast("Eliminado")
    }

    // ---------------------------------------------------------------- miniaturas

    private fun reducir(b: Bitmap, ancho: Int = 240): Bitmap {
        if (b.width <= ancho) return b
        val alto = (b.height * ancho.toFloat() / b.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(b, ancho, alto, true)
    }

    private fun cargarMiniatura(item: Item): Bitmap? = try {
        when (item.tipo) {
            0 -> {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(this, item.uri)
                    r.getFrameAtTime(0)?.let { reducir(it) }
                } finally {
                    try { r.release() } catch (e: Exception) { }
                }
            }
            2 -> {
                val limites = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(item.uri)?.use { BitmapFactory.decodeStream(it, null, limites) }
                var muestra = 1
                while (limites.outWidth / (muestra * 2) >= 240) muestra *= 2
                val opciones = BitmapFactory.Options().apply { inSampleSize = muestra }
                contentResolver.openInputStream(item.uri)?.use { BitmapFactory.decodeStream(it, null, opciones) }
            }
            else -> null
        }
    } catch (e: Exception) {
        null
    }

    private inner class Adaptador : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_historial, parent, false)
            val item = items.getOrNull(position) ?: return v

            val tipoTxt = when (item.tipo) {
                0 -> "🎬 Video"
                1 -> "🎵 Audio"
                else -> "🖼 Foto"
            }
            v.findViewById<TextView>(R.id.titulo).text = item.nombre
            v.findViewById<TextView>(R.id.detalle).text =
                tipoTxt + " · " + Formatter.formatShortFileSize(this@HistorialActivity, item.tam) +
                    " · " + DateFormat.format("d MMM yyyy", item.fecha * 1000)
            v.findViewById<View>(R.id.emoji).visibility = if (item.tipo == 1) View.VISIBLE else View.GONE

            val img = v.findViewById<ImageView>(R.id.img)
            val clave = item.uri.toString()
            img.tag = clave
            val guardada = cache.get(clave)
            if (guardada != null) {
                img.setImageBitmap(guardada)
            } else {
                img.setImageDrawable(null)
                if (item.tipo != 1) {
                    lifecycleScope.launch {
                        val bmp = withContext(Dispatchers.IO) { cargarMiniatura(item) }
                        if (bmp != null) {
                            cache.put(clave, bmp)
                            if (img.tag == clave) img.setImageBitmap(bmp)
                        }
                    }
                }
            }
            return v
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
