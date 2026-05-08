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
    ): String {
        val listUrl = "$baseUrl/api/v1/print-jobs".toHttpUrl().newBuilder()
            .addQueryParameter("status", "pending")
            .addQueryParameter("limit", "1")
            .build()
        val listReq = authedRequest(listUrl.toString(), token).build()

        val (job, jobUuid) = http.newCall(listReq).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) return "HTTP ${resp.code} en list"
            val jobs = JSONObject(body).optJSONArray("jobs")
            if (jobs == null || jobs.length() == 0) return "Esperando jobs…"
            val first = jobs.getJSONObject(0)
            first to first.optString("id").removePrefix("job_")
        }

        val content = job.optJSONObject("content")
            ?: run {
                markFailed(http, baseUrl, token, jobUuid, "Job sin content")
                return "Job ${job.optString("id")} sin content — failed"
            }

        val bytes = EscPos.buildJobTicket(content)
        val sendResult = runCatching { PrinterClient.send(ip, port, bytes) }
        if (sendResult.isFailure) {
            val ex = sendResult.exceptionOrNull()!!
            val reason = "${ex.javaClass.simpleName}: ${ex.message}"
            markFailed(http, baseUrl, token, jobUuid, reason)
            return "Impresora: $reason"
        }

        val printedCode = markPrinted(http, baseUrl, token, jobUuid)
        val sent = sendResult.getOrNull()
        return "OK ${job.optString("id")} — $sent bytes (HTTP $printedCode)"
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
