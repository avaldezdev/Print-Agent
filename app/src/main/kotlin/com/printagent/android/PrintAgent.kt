package com.printagent.android

import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Ciclo de impresión con garantía de entrega.
 *
 * Reglas que sostienen la confiabilidad (no romperlas al modificar):
 *
 * - **Nunca `markFailed` por un error transitorio de impresora.** El servidor saca el
 *   job de `pending` y el agente solo consulta `pending` → el pedido se perdería para
 *   siempre. Ante fallo de impresión lo dejamos pendiente y reintentamos al ciclo
 *   siguiente, indefinidamente. `markFailed` queda solo para errores de datos.
 * - **El ledger se escribe ANTES del ACK.** Si el `POST /printed` falla, el job vuelve
 *   a aparecer pendiente pero el ledger impide reimprimirlo.
 * - **409 en `/printed` es éxito**, no error: significa que ya estaba marcado.
 * - Cada job va a la impresora de su estación (ver [PrinterRoute]). Si una impresora
 *   falla, se **saltean solo sus jobs** durante el resto del ciclo: no tiene sentido
 *   insistir, se conserva el orden de llegada por impresora, y las demás estaciones
 *   siguen imprimiendo.
 */
object PrintAgent {

    private const val DEVICE_HINT = "print-agent-android"
    private const val BATCH_LIMIT = 10
    private const val PRINT_ATTEMPTS = 3
    private val RETRY_BACKOFF_MS = longArrayOf(400, 1200)

    /** Resultado de un ciclo, para mostrar en la notificación y decidir alertas. */
    data class Cycle(
        val message: String,
        val printed: Int = 0,
        val duplicatesAvoided: Int = 0,
        val pendingLeft: Int = 0,
        val printerProblem: String? = null,
    )

    suspend fun pollAndPrintBatch(
        http: OkHttpClient,
        baseUrl: String,
        token: String,
        defaultIp: String,
        defaultPort: Int,
        lineWidth: Int = EscPos.WIDTH_80MM,
        ledger: PrintLedger,
        checkPrinterStatus: Boolean = true,
    ): Cycle {
        // 0) Saldar ACKs que quedaron debiendo de ciclos anteriores.
        var reacked = 0
        for (uuid in ledger.pendingAcks()) {
            if (ackPrinted(http, baseUrl, token, uuid)) {
                ledger.ackConfirmed(uuid)
                reacked++
            }
        }

        // 1) Listar pendientes (lote, no de a uno).
        val pending = listPending(http, baseUrl, token)
            ?: return Cycle("Sin conexión con el servidor")
        if (pending.isEmpty()) {
            val extra = if (reacked > 0) " · $reacked ACK saldado(s)" else ""
            return Cycle("Esperando pedidos…$extra")
        }

        var printed = 0
        var duplicates = 0
        var waiting = 0
        // Impresoras que fallaron en este ciclo (ip:port → "Nombre: motivo").
        val blocked = linkedMapOf<String, String>()

        for (listed in pending) {
            val uuid = listed.optString("id").removePrefix("job_")

            // 2) Dedup: si ya salió por esta impresora, no reimprimir — solo re-avisar.
            if (ledger.wasPrinted(uuid)) {
                if (ackPrinted(http, baseUrl, token, uuid)) ledger.ackConfirmed(uuid)
                duplicates++
                continue
            }

            // 3) Destino. El listado ya trae kitchen_station: decidimos antes de pedir el
            //    detalle, así no gastamos requests en jobs de una impresora caída.
            val target = PrinterRoute.resolve(listed, defaultIp, defaultPort)
            if (target == null) {
                // Error de configuración, no de datos: queda pendiente hasta que se cargue la IP.
                blocked["sin-impresora"] = "Pedido sin estación y sin impresora por defecto"
                waiting++
                continue
            }
            if (target.key in blocked) {
                waiting++
                continue
            }

            // 4) Detalle completo (el listado viene liviano).
            val job = fetchDetail(http, baseUrl, token, uuid)
            if (job == null || job.optJSONObject("content") == null) {
                // Error de datos, no de impresora: este job no se va a poder imprimir nunca.
                markFailed(http, baseUrl, token, uuid, "Job sin content o detalle inválido")
                continue
            }

            val bytes = EscPos.buildJobTicket(job, lineWidth)

            // 5) Imprimir con reintentos.
            val failure = printWithRetries(target.ip, target.port, bytes, checkPrinterStatus)
            if (failure != null) {
                // NO marcamos failed: el job queda pendiente y se reintenta al próximo ciclo.
                blocked[target.key] = "${target.label}: $failure"
                waiting++
                continue
            }

            // 6) Salió el papel: anotar en el ledger ANTES de avisar al servidor.
            ledger.markPrintedLocally(uuid)
            if (ackPrinted(http, baseUrl, token, uuid)) ledger.ackConfirmed(uuid)
            printed++
        }

        val parts = buildList {
            if (printed > 0) add("$printed impreso(s)")
            if (duplicates > 0) add("$duplicates duplicado(s) evitado(s)")
            if (reacked > 0) add("$reacked ACK saldado(s)")
        }
        if (blocked.isNotEmpty()) {
            val problem = blocked.values.joinToString(" · ")
            return Cycle(
                message = "$problem · $waiting pendiente(s) en cola" +
                    if (parts.isEmpty()) "" else " · " + parts.joinToString(" · "),
                printed = printed,
                duplicatesAvoided = duplicates,
                pendingLeft = waiting,
                printerProblem = problem,
            )
        }
        return Cycle(
            message = if (parts.isEmpty()) "Sin novedades" else "OK · " + parts.joinToString(" · "),
            printed = printed,
            duplicatesAvoided = duplicates,
        )
    }

    /** Devuelve null si imprimió bien, o el motivo del fallo tras agotar los reintentos. */
    private suspend fun printWithRetries(
        ip: String,
        port: Int,
        bytes: ByteArray,
        checkStatus: Boolean,
    ): String? {
        var lastReason = "desconocido"
        repeat(PRINT_ATTEMPTS) { attempt ->
            val result = runCatching { PrinterClient.send(ip, port, bytes, checkStatus = checkStatus) }
            if (result.isSuccess) return null

            val ex = result.exceptionOrNull()
            if (ex is PrinterClient.NotReadyException) {
                // Sin papel / tapa abierta: reintentar no ayuda, hay que avisar YA.
                return ex.status.reason
            }
            lastReason = "${ex?.javaClass?.simpleName}: ${ex?.message}"
            if (attempt < PRINT_ATTEMPTS - 1) delay(RETRY_BACKOFF_MS[attempt])
        }
        return lastReason
    }

    /** Lista los jobs pendientes (más viejos primero), versión liviana. null = fallo de red. */
    private fun listPending(http: OkHttpClient, baseUrl: String, token: String): List<JSONObject>? {
        val url = "$baseUrl/api/v1/print-jobs".toHttpUrl().newBuilder()
            .addQueryParameter("status", "pending")
            .addQueryParameter("limit", BATCH_LIMIT.toString())
            .build()
        return runCatching {
            http.newCall(authedRequest(url.toString(), token).build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val jobs = JSONObject(resp.body?.string().orEmpty()).optJSONArray("jobs") ?: return emptyList()
                (0 until jobs.length())
                    .map { jobs.getJSONObject(it) }
                    .sortedBy { it.optString("created_at") }
                    .filter { it.optString("id").removePrefix("job_").isNotBlank() }
            }
        }.getOrNull()
    }

    private fun fetchDetail(http: OkHttpClient, baseUrl: String, token: String, uuid: String): JSONObject? =
        runCatching {
            http.newCall(authedRequest("$baseUrl/api/v1/print-jobs/$uuid", token).build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                unwrapJob(JSONObject(resp.body?.string().orEmpty()))
            }
        }.getOrNull()

    /** El detalle puede venir directo o envuelto en `data`/`job`. */
    private fun unwrapJob(parsed: JSONObject): JSONObject = when {
        parsed.has("content") -> parsed
        parsed.optJSONObject("data") != null -> parsed.getJSONObject("data")
        parsed.optJSONObject("job") != null -> parsed.getJSONObject("job")
        else -> parsed
    }

    /** true si el servidor quedó al día (200 = marcado, 409 = ya estaba marcado). */
    private fun ackPrinted(http: OkHttpClient, baseUrl: String, token: String, uuid: String): Boolean =
        runCatching {
            val req = authedRequest("$baseUrl/api/v1/print-jobs/$uuid/printed", token)
                .post("".toRequestBody(null))
                .build()
            http.newCall(req).execute().use { it.isSuccessful || it.code == 409 }
        }.getOrDefault(false)

    /** Solo para errores de datos: un job así no se va a poder imprimir nunca. */
    private fun markFailed(http: OkHttpClient, baseUrl: String, token: String, uuid: String, reason: String) {
        val body = JSONObject().put("reason", reason).toString()
            .toRequestBody("application/json".toMediaType())
        val req = authedRequest("$baseUrl/api/v1/print-jobs/$uuid/failed", token).post(body).build()
        runCatching { http.newCall(req).execute().close() }
    }

    private fun authedRequest(url: String, token: String): Request.Builder =
        Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("X-Device-Hint", DEVICE_HINT)
}
