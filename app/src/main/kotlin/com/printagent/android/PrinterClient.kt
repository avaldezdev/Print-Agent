package com.printagent.android

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Envío ESC/POS por TCP con verificación de estado y watchdog de escritura.
 *
 * Dos problemas que resuelve respecto de un `write()` pelado:
 *
 * 1. **`write()` exitoso NO significa que imprimió.** Los bytes solo entran al buffer
 *    del sistema operativo. Si la impresora está sin papel o con la tapa abierta, no
 *    hay excepción y el pedido se pierde en silencio. Por eso consultamos el estado
 *    real (`DLE EOT`) antes de mandar: si no está lista, no imprimimos y el job queda
 *    pendiente para reintentar.
 * 2. **En Java, `soTimeout` NO aplica a escrituras.** Si la impresora deja de drenar
 *    el buffer, `write()` se cuelga para siempre y frena toda la cola. El watchdog
 *    cierra el socket desde afuera para desbloquearlo.
 */
object PrinterClient {

    /** Estado leído de la impresora. `supported=false` = no contestó (best-effort). */
    data class Status(
        val supported: Boolean,
        val paperOut: Boolean = false,
        val coverOpen: Boolean = false,
        val error: Boolean = false,
    ) {
        /** Solo bloqueamos si la impresora efectivamente contestó y reportó un problema. */
        val blocking: Boolean get() = supported && (paperOut || coverOpen || error)

        val reason: String
            get() = when {
                paperOut -> "SIN PAPEL"
                coverOpen -> "TAPA ABIERTA"
                error -> "ERROR DE IMPRESORA"
                else -> "ok"
            }
    }

    /** La impresora contestó y no está en condiciones de imprimir. No consumir el job. */
    class NotReadyException(val status: Status) : IOException("Impresora no lista: ${status.reason}")

    /**
     * Manda el ticket. Devuelve la cantidad de bytes enviados.
     * @throws NotReadyException si la impresora reporta sin papel / tapa abierta / error.
     * @throws IOException ante cualquier problema de conexión o escritura.
     */
    fun send(
        ip: String,
        port: Int,
        bytes: ByteArray,
        connectTimeoutMs: Int = 5000,
        writeTimeoutMs: Int = 10000,
        checkStatus: Boolean = true,
    ): Int {
        val sock = Socket()
        var watchdog: Thread? = null
        try {
            sock.connect(InetSocketAddress(ip, port), connectTimeoutMs)
            sock.soTimeout = STATUS_READ_TIMEOUT_MS

            if (checkStatus) {
                val status = queryStatus(sock)
                if (status.blocking) throw NotReadyException(status)
            }

            // Watchdog: si el write se cuelga, cerrar el socket lo desbloquea con excepción.
            watchdog = Thread {
                try {
                    Thread.sleep(writeTimeoutMs.toLong())
                    runCatching { sock.close() }
                } catch (_: InterruptedException) {
                    // terminó a tiempo
                }
            }.apply { isDaemon = true; start() }

            val out = sock.getOutputStream()
            out.write(bytes)
            out.flush()
            return bytes.size
        } finally {
            watchdog?.interrupt()
            runCatching { sock.close() }
        }
    }

    /**
     * Consulta de estado en tiempo real (`DLE EOT n`). Best-effort: si la impresora no
     * contesta dentro del timeout, asumimos que no lo soporta y seguimos adelante —
     * nunca bloqueamos una impresión por falta de respuesta.
     */
    private fun queryStatus(sock: Socket): Status {
        val out = sock.getOutputStream()
        val ins = sock.getInputStream()
        var answered = false
        var paperOut = false
        var coverOpen = false
        var error = false

        fun ask(n: Int): Int? = runCatching {
            out.write(byteArrayOf(0x10, 0x04, n.toByte()))
            out.flush()
            ins.read().takeIf { it >= 0 }
        }.getOrNull()

        // n=4 → estado del papel; n=2 → estado offline (tapa); n=3 → estado de error
        ask(4)?.let { answered = true; if (it and 0x60 != 0) paperOut = true }
        ask(2)?.let { answered = true; if (it and 0x04 != 0) coverOpen = true }
        ask(3)?.let { answered = true; if (it and 0x28 != 0) error = true }

        return Status(supported = answered, paperOut = paperOut, coverOpen = coverOpen, error = error)
    }

    private const val STATUS_READ_TIMEOUT_MS = 600
}
