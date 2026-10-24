package com.descargadorpro.app

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Registro de errores: si la app se cierra por un fallo, guarda el motivo y lo muestra
 * en pantalla la próxima vez que se abre, con un botón para copiarlo.
 */
class App : Application() {

    private val archivoError get() = File(filesDir, "ultimo_error.txt")
    private var avisado = false

    override fun onCreate() {
        super.onCreate()

        val anterior = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { hilo, error ->
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                archivoError.writeText(("Hilo: " + hilo.name + "\n" + sw).take(12000))
            } catch (e: Throwable) { }
            anterior?.uncaughtException(hilo, error)
        }

        registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { avisar(activity) }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private fun avisar(activity: Activity) {
        if (avisado) return
        val texto = leerMotivo() ?: return
        avisado = true
        try {
            AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("La app se cerró por un error")
                .setMessage(texto)
                .setPositiveButton("Copiar") { _, _ ->
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("error", texto))
                }
                .setNegativeButton("Cerrar", null)
                .show()
        } catch (e: Throwable) { }
    }

    /** Resume el error: las primeras líneas y todas las causas ("Caused by"). */
    private fun resumir(texto: String): String {
        val lineas = texto.lines()
        val causas = lineas.filter { it.startsWith("Caused by") }
        return (lineas.take(8) + causas).joinToString("\n").take(2500)
    }

    private fun leerMotivo(): String? {
        val f = archivoError
        if (f.exists()) {
            val t = try { f.readText() } catch (e: Throwable) { "" }
            f.delete()
            if (t.isNotBlank()) return resumir(t)
        }
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val u = am.getHistoricalProcessExitReasons(packageName, 0, 1).firstOrNull() ?: return null
                val prefs = getSharedPreferences("diagnostico", MODE_PRIVATE)
                if (u.timestamp <= prefs.getLong("visto", 0L)) return null
                prefs.edit().putLong("visto", u.timestamp).apply()
                if (u.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                    return "Cierre nativo: falló una librería de video (sin detalle de Java).\n" + (u.description ?: "")
                }
            } catch (e: Throwable) { }
        }
        return null
    }
}
