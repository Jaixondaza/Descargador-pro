package com.descargadorpro.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * Conexión a internet: opción "solo con Wi-Fi" y reintentos automáticos.
 * Si se corta el internet (o se pierde el Wi-Fi con la opción activada), la descarga espera
 * y se reanuda sola; yt-dlp continúa desde donde se quedó porque la carpeta temporal se conserva.
 */
object Red {
    private const val PREFS = "ajustes"
    private const val CLAVE_WIFI = "solo_wifi"
    private const val MAX_FALLOS = 6
    private const val MAX_ESPERA_MS = 30L * 60 * 1000

    private val PATRONES_DE_RED = listOf(
        "timed out", "timeout", "connection", "urlopen error", "name resolution",
        "network is unreachable", "no route to host", "temporary failure", "incompleteread",
        "reset by peer", "broken pipe", "eof occurred", "http error 5", "ssl",
        "remote end closed", "unable to connect", "failed to resolve"
    )

    fun soloWifi(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(CLAVE_WIFI, false)

    fun fijarSoloWifi(ctx: Context, valor: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(CLAVE_WIFI, valor).apply()
    }

    private fun capacidades(ctx: Context): NetworkCapabilities? {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val red = cm.activeNetwork ?: return null
        return cm.getNetworkCapabilities(red)
    }

    fun hayRed(ctx: Context): Boolean =
        capacidades(ctx)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    fun esWifi(ctx: Context): Boolean {
        val c = capacidades(ctx) ?: return false
        return c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /** Hay internet y, si está activada la opción "solo con Wi-Fi", la conexión es Wi-Fi. */
    fun redPermitida(ctx: Context): Boolean = hayRed(ctx) && (!soloWifi(ctx) || esWifi(ctx))

    private fun pareceDeRed(e: Throwable): Boolean {
        val mensaje = (e.message ?: "").lowercase()
        return PATRONES_DE_RED.any { mensaje.contains(it) }
    }

    private suspend fun esperarRed(ctx: Context, cancelado: () -> Boolean, aviso: (String) -> Unit) {
        val inicio = System.currentTimeMillis()
        while (!redPermitida(ctx)) {
            if (cancelado()) throw IllegalStateException("Descarga cancelada")
            if (System.currentTimeMillis() - inicio > MAX_ESPERA_MS) {
                throw IllegalStateException("Sin conexión durante más de 30 minutos")
            }
            aviso(
                if (soloWifi(ctx)) "Esperando Wi-Fi… la descarga sigue sola cuando vuelva."
                else "Sin conexión. Esperando internet…"
            )
            delay(3000)
        }
    }

    /**
     * Ejecuta [accion] (la descarga) y la repite si falla por la red.
     * [detener] corta el proceso de descarga; se usa si se pierde el Wi-Fi con "solo con Wi-Fi" activado.
     */
    suspend fun conReintentos(
        ctx: Context,
        cancelado: () -> Boolean,
        aviso: (String) -> Unit,
        detener: () -> Unit,
        accion: () -> Unit
    ) {
        var fallos = 0
        while (true) {
            esperarRed(ctx, cancelado, aviso)
            try {
                coroutineScope {
                    val vigilante = launch {
                        while (isActive) {
                            delay(3000)
                            if (!redPermitida(ctx)) {
                                detener()
                                break
                            }
                        }
                    }
                    try {
                        accion()
                    } finally {
                        vigilante.cancel()
                    }
                }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (cancelado()) throw e
                coroutineContext.ensureActive()
                // Sin red permitida: no cuenta como fallo, se espera al inicio de la siguiente vuelta
                if (!redPermitida(ctx)) continue
                fallos++
                if (fallos > MAX_FALLOS || !pareceDeRed(e)) throw e
                aviso("Se cortó la conexión. Reintentando ($fallos de $MAX_FALLOS)…")
                delay(minOf(30, 3 * (1 shl (fallos - 1))) * 1000L)
            }
        }
    }
}
