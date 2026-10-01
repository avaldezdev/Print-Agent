package com.printagent.android

import android.content.SharedPreferences
import org.json.JSONArray

/**
 * Registro local y persistente de lo que YA salió físicamente por la impresora.
 *
 * Es la red de seguridad contra duplicados: el ticket se anota acá **apenas sale**,
 * ANTES de avisarle al servidor. Si después el `POST /printed` falla (corte de red,
 * timeout) y el job vuelve a aparecer como pendiente, nunca se reimprime porque su
 * UUID ya está en el ledger. Sobrevive a reinicios de la app.
 *
 * También guarda los ACK que quedaron debiendo, para reintentarlos en ciclos
 * posteriores hasta que el servidor confirme.
 */
class PrintLedger(private val prefs: SharedPreferences) {

    /** ¿Este job ya se imprimió en este dispositivo? */
    fun wasPrinted(uuid: String): Boolean = read(KEY_PRINTED).contains(uuid)

    /** Llamar INMEDIATAMENTE después de que el ticket salió, antes de avisar al servidor. */
    fun markPrintedLocally(uuid: String) {
        val printed = read(KEY_PRINTED)
        if (!printed.contains(uuid)) {
            printed.add(uuid)
            while (printed.size > MAX_LEDGER) printed.removeAt(0)
            write(KEY_PRINTED, printed)
        }
        val acks = read(KEY_PENDING_ACK)
        if (!acks.contains(uuid)) {
            acks.add(uuid)
            write(KEY_PENDING_ACK, acks)
        }
    }

    /** El servidor confirmó el /printed (200 o 409): ya no hace falta reintentar. */
    fun ackConfirmed(uuid: String) {
        val acks = read(KEY_PENDING_ACK)
        if (acks.remove(uuid)) write(KEY_PENDING_ACK, acks)
    }

    /** ACKs que quedaron debiendo y hay que reintentar. */
    fun pendingAcks(): List<String> = read(KEY_PENDING_ACK)

    fun printedCount(): Int = read(KEY_PRINTED).size

    // --- Descartados -------------------------------------------------------
    // El API no tiene endpoint para eliminar ni cancelar un job. Marcarlos como
    // `printed` para sacarlos de la lista seria mentir: ese bucket es la auditoria
    // que usamos para detectar pedidos perdidos. Por eso el descarte es LOCAL:
    // desaparece de la pantalla del operario, el servidor conserva el registro.

    fun isDismissed(uuid: String): Boolean = read(KEY_DISMISSED).contains(uuid)

    fun dismiss(uuids: Collection<String>) {
        val current = read(KEY_DISMISSED)
        var changed = false
        uuids.forEach { if (!current.contains(it)) { current.add(it); changed = true } }
        if (changed) {
            while (current.size > MAX_LEDGER) current.removeAt(0)
            write(KEY_DISMISSED, current)
        }
    }

    fun dismissedCount(): Int = read(KEY_DISMISSED).size

    fun restoreAllDismissed() = write(KEY_DISMISSED, emptyList())

    private fun read(key: String): MutableList<String> {
        val raw = prefs.getString(key, "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: JSONArray()
        return MutableList(arr.length()) { arr.optString(it) }
    }

    private fun write(key: String, values: List<String>) {
        prefs.edit().putString(key, JSONArray(values).toString()).apply()
    }

    companion object {
        private const val KEY_PRINTED = "ledger_printed"
        private const val KEY_PENDING_ACK = "ledger_pending_ack"
        private const val KEY_DISMISSED = "ledger_dismissed"

        /** Tope del historial. Alcanza de sobra: los jobs pendientes tienen TTL de 60 min. */
        private const val MAX_LEDGER = 500
    }
}
