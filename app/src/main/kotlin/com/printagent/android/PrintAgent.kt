package com.printagent.android

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

object PrintAgent {

    private const val DEVICE_HINT = "print-agent-android"

    suspend fun pollAndPrintOne(
        http: OkHttpClient,
        baseUrl: String,
        token: String,
        ip: String,
        port: Int,
        lineWidth: Int = EscPos.WIDTH_80MM,
    ): String {
        val listUrl = "$baseUrl/api/v1/print-jobs".toHttpUrl().newBuilder()
            .addQueryParameter("status", "pending")
            .addQueryParameter("limit", "1")
            .build()
        val listReq = authedRequest(listUrl.toString(), token).build()

        val jobUuid = http.newCall(listReq).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) return "HTTP ${resp.code} en list"
            val jobs = JSONObject(body).optJSONArray("jobs")
            if (jobs == null || jobs.length() == 0) return "Esperando jobs…"
            jobs.getJSONObject(0).optString("id").removePrefix("job_")
        }

        // El listado ("recorrido") viene liviano; el DETALLE ("show" por UUID) trae el
        // payload completo (customer, order_note, etc.). Imprimimos siempre desde el detalle.
        val detailReq = authedRequest("$baseUrl/api/v1/print-jobs/$jobUuid", token).build()
        val job = http.newCall(detailReq).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) return "HTTP ${resp.code} en detalle $jobUuid"
            unwrapJob(runCatching { JSONObject(body) }.getOrNull() ?: return "Detalle sin JSON válido")
        }

        if (job.optJSONObject("content") == null) {
            markFailed(http, baseUrl, token, jobUuid, "Job sin content")
            return "Job $jobUuid sin content — failed"
        }

        val bytes = EscPos.buildJobTicket(job, lineWidth)
        val sendResult = runCatching { PrinterClient.send(ip, port, bytes) }
        if (sendResult.isFailure) {
            val ex = sendResult.exceptionOrNull()!!
            val reason = "${ex.javaClass.simpleName}: ${ex.message}"
            markFailed(http, baseUrl, token, jobUuid, reason)
            return "Impresora: $reason"
        }

        val printedCode = markPrinted(http, baseUrl, token, jobUuid)
        val sent = sendResult.getOrNull()
        return "OK $jobUuid — $sent bytes (HTTP $printedCode)"
    }

    /** El detalle puede venir directo o envuelto en `data`/`job`. Devuelve el objeto del job. */
    private fun unwrapJob(parsed: JSONObject): JSONObject = when {
        parsed.has("content") -> parsed
        parsed.optJSONObject("data") != null -> parsed.getJSONObject("data")
        parsed.optJSONObject("job") != null -> parsed.getJSONObject("job")
        else -> parsed
    }

    private fun markPrinted(http: OkHttpClient, baseUrl: String, token: String, uuid: String): Int {
        val req = authedRequest("$baseUrl/api/v1/print-jobs/$uuid/printed", token)
            .post("".toRequestBody(null))
            .build()
        return http.newCall(req).execute().use { it.code }
    }

    private fun markFailed(http: OkHttpClient, baseUrl: String, token: String, uuid: String, reason: String) {
        val body = JSONObject().put("reason", reason).toString()
            .toRequestBody("application/json".toMediaType())
        val req = authedRequest("$baseUrl/api/v1/print-jobs/$uuid/failed", token)
            .post(body)
            .build()
        runCatching { http.newCall(req).execute().close() }
    }

    private fun authedRequest(url: String, token: String): Request.Builder =
        Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("X-Device-Hint", DEVICE_HINT)
}
