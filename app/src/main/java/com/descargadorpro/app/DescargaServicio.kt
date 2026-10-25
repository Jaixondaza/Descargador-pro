package com.descargadorpro.app

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Mantiene la app "viva" mientras se descarga (aunque se apague la pantalla o cambies de app)
 * y muestra una notificación con el progreso. La descarga en sí la sigue haciendo MainActivity;
 * este servicio solo le da prioridad ante Android y avisa el avance y el final.
 */
class DescargaServicio : Service() {

    companion object {
        private const val CANAL = "descargas"
        private const val ID_PROGRESO = 41
        private const val ID_FIN = 42

        @Volatile private var instancia: DescargaServicio? = null
        @Volatile private var finSolicitado = false

        /** Arranca el servicio. Pide el permiso de notificaciones (Android 13+) la primera vez. */
        fun iniciar(act: Activity) {
            finSolicitado = false
            try {
                val prefs = act.getSharedPreferences("diagnostico", Context.MODE_PRIVATE)
                if (Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("pidio_notificaciones", false) &&
                    act.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    prefs.edit().putBoolean("pidio_notificaciones", true).apply()
                    act.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 701)
                }
                ContextCompat.startForegroundService(act, Intent(act, DescargaServicio::class.java))
            } catch (e: Exception) { }
        }

        /** Actualiza el porcentaje y el texto de la notificación. */
        fun progreso(pct: Int, texto: String) {
            instancia?.mostrar(pct, texto)
        }

        /** Termina el servicio. Si no es una cancelación, avisa con una notificación si salió bien o no. */
        fun terminar(ok: Boolean, cancelado: Boolean) {
            val s = instancia
            if (s != null) s.finalizar(ok, cancelado) else finSolicitado = true
        }
    }

    private var wake: PowerManager.WakeLock? = null
    private var ultimaActualizacion = 0L
    private var ultimoAviso = 0L
    private val handler = Handler(Looper.getMainLooper())

    // Si pasan 10 minutos sin avances, el servicio se apaga solo (por si algo quedó colgado).
    private val vigilante = object : Runnable {
        override fun run() {
            if (System.currentTimeMillis() - ultimaActualizacion > 10 * 60 * 1000L) {
                detener()
            } else {
                handler.postDelayed(this, 60_000L)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instancia = this
        crearCanal()
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DescargadorPro:descarga").apply {
                setReferenceCounted(false)
                acquire(60 * 60 * 1000L)
            }
        } catch (e: Exception) { }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ultimaActualizacion = System.currentTimeMillis()
        val n = construir(0, "Preparando descarga…", true)
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(ID_PROGRESO, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(ID_PROGRESO, n)
            }
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (finSolicitado) {
            detener()
            return START_NOT_STICKY
        }
        handler.removeCallbacks(vigilante)
        handler.postDelayed(vigilante, 60_000L)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(vigilante)
        try {
            wake?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) { }
        if (instancia === this) instancia = null
        super.onDestroy()
    }

    private fun crearCanal() {
        if (Build.VERSION.SDK_INT >= 26) {
            val canal = NotificationChannel(CANAL, "Descargas", NotificationManager.IMPORTANCE_LOW)
            canal.description = "Progreso de las descargas"
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(canal)
        }
    }

    private fun abrirApp(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun construir(pct: Int, texto: String, indeterminado: Boolean): Notification =
        NotificationCompat.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_descarga)
            .setContentTitle("Descargador Pro")
            .setContentText(texto)
            .setProgress(100, pct.coerceIn(0, 100), indeterminado)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(abrirApp())
            .build()

    private fun mostrar(pct: Int, texto: String) {
        val ahora = System.currentTimeMillis()
        ultimaActualizacion = ahora
        if (ahora - ultimoAviso < 1000L) return
        ultimoAviso = ahora
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(ID_PROGRESO, construir(pct, texto, false))
        } catch (e: Exception) { }
    }

    private fun finalizar(ok: Boolean, cancelado: Boolean) {
        if (!cancelado) {
            try {
                val n = NotificationCompat.Builder(this, CANAL)
                    .setSmallIcon(R.drawable.ic_descarga)
                    .setContentTitle(if (ok) "Descarga lista ✅" else "La descarga no se completó")
                    .setContentText(
                        if (ok) "Ya está en tu galería, carpeta DescargadorPro."
                        else "Abre la app para ver el motivo."
                    )
                    .setAutoCancel(true)
                    .setContentIntent(abrirApp())
                    .build()
                (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ID_FIN, n)
            } catch (e: Exception) { }
        }
        detener()
    }

    private fun detener() {
        handler.removeCallbacks(vigilante)
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) { }
        stopSelf()
    }
}
