package com.printagent.android

import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val editBaseUrl = findViewById<EditText>(R.id.editBaseUrl)
        val editToken = findViewById<EditText>(R.id.editToken)
        val editPrinterIp = findViewById<EditText>(R.id.editPrinterIp)
        val editPrinterPort = findViewById<EditText>(R.id.editPrinterPort)
        val btnTest = findViewById<Button>(R.id.btnTest)
        val btnList = findViewById<Button>(R.id.btnList)
        val btnPrintTest = findViewById<Button>(R.id.btnPrintTest)
        val btnPrintPending = findViewById<Button>(R.id.btnPrintPending)
        val txtStatus = findViewById<TextView>(R.id.txtStatus)

        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        editBaseUrl.setText(prefs.getString("base_url", "https://wama.micdepos.com"))
        editToken.setText(prefs.getString("token", ""))
        editPrinterIp.setText(prefs.getString("printer_ip", ""))
        editPrinterPort.setText(prefs.getString("printer_port", "9100"))

        val allButtons = listOf(btnTest, btnList, btnPrintTest, btnPrintPending)

        fun saveSettings() {
            prefs.edit()
                .putString("base_url", editBaseUrl.text.toString().trim().trimEnd('/'))
                .putString("token", editToken.text.toString().trim())
                .putString("printer_ip", editPrinterIp.text.toString().trim())
                .putString("printer_port", editPrinterPort.text.toString().trim())
                .apply()
        }

        fun runJob(loadingMsg: Int, block: suspend () -> String) {
            saveSettings()
            txtStatus.text = getString(loadingMsg)
            allButtons.forEach { it.isEnabled = false }
            lifecycleScope.launch {
                val result = runCatching { block() }
                allButtons.forEach { it.isEnabled = true }
                txtStatus.text = result.fold(
                    onSuccess = { it },
                    onFailure = { "ERROR: ${it.javaClass.simpleName}: ${it.message}" }
                )
            }
        }

        btnTest.setOnClickListener {
            runJob(R.string.status_loading) {
                healthCheck(editBaseUrl.text.toString().trim().trimEnd('/'), editToken.text.toString().trim())
            }
        }
        btnList.setOnClickListener {
            runJob(R.string.status_loading) {
                listPending(editBaseUrl.text.toString().trim().trimEnd('/'), editToken.text.toString().trim())
            }
        }
        btnPrintTest.setOnClickListener {
            val ip = editPrinterIp.text.toString().trim()
            val port = editPrinterPort.text.toString().trim().toIntOrNull()
            if (ip.isEmpty() || port == null) {
                txtStatus.text = "ERROR: completá IP y puerto válidos"
                return@setOnClickListener
            }
            runJob(R.string.status_printing) { printTest(ip, port) }
        }
        btnPrintPending.setOnClickListener {
            val baseUrl = editBaseUrl.text.toString().trim().trimEnd('/')
            val token = editToken.text.toString().trim()
            val ip = editPrinterIp.text.toString().trim()
            val port = editPrinterPort.text.toString().trim().toIntOrNull()
            if (ip.isEmpty() || port == null) {
                txtStatus.text = "ERROR: completá IP y puerto válidos"
                return@setOnClickListener
            }
            runJob(R.string.status_printing) { printPending(baseUrl, token, ip, port) }
        }
    }

    private suspend fun healthCheck(baseUrl: String, token: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$baseUrl/api/v1/print-jobs/health")
            .header("Authorization", "Bearer $token")
            .header("X-Device-Hint", "print-agent-android-debug")
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            val pretty = runCatching { JSONObject(body).toString(2) }.getOrDefault(body)
            "HTTP ${resp.code}\n\n$pretty"
        }
    }

    private suspend fun listPending(baseUrl: String, token: String): String = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/v1/print-jobs".toHttpUrl().newBuilder()
            .addQueryParameter("status", "pending")
            .addQueryParameter("limit", "20")
            .build()
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("X-Device-Hint", "print-agent-android-debug")
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val pretty = runCatching { JSONObject(body).toString(2) }.getOrDefault(body)
                return@withContext "HTTP ${resp.code}\n\n$pretty"
            }
            summarizePending(resp.code, body)
        }
    }

    private suspend fun printTest(ip: String, port: Int): String = withContext(Dispatchers.IO) {
        val bytes = EscPos.buildTestTicket()
        val sent = PrinterClient.send(ip, port, bytes)
        "OK — enviados $sent bytes a $ip:$port"
    }

    private suspend fun printPending(baseUrl: String, token: String, ip: String, port: Int): String = withContext(Dispatchers.IO) {
        // 1. Pedir el primer pendiente
        val listUrl = "$baseUrl/api/v1/print-jobs".toHttpUrl().newBuilder()
            .addQueryParameter("status", "pending")
            .addQueryParameter("limit", "1")
            .build()
        val listReq = Request.Builder()
            .url(listUrl)
            .header("Authorization", "Bearer $token")
            .header("X-Device-Hint", "print-agent-android-debug")
            .build()
        val (job, jobUuid) = http.newCall(listReq).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                return@withContext "HTTP ${resp.code} en list:\n${prettyJson(body)}"
            }
            val jobs = JSONObject(body).optJSONArray("jobs")
            if (jobs == null || jobs.length() == 0) {
                return@withContext "Sin jobs pendientes."
            }
            val first = jobs.getJSONObject(0)
            val id = first.optString("id")
            val uuid = id.removePrefix("job_")
            first to uuid
        }

        // 2. Imprimir
        val content = job.optJSONObject("content")
            ?: return@withContext "Job sin content — imposible imprimir.\n${job.toString(2)}"
        val bytes = EscPos.buildJobTicket(content)
        val sent = runCatching { PrinterClient.send(ip, port, bytes) }
        if (sent.isFailure) {
            // 3a. Reportar fallo
            val reason = sent.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "unknown"
            markFailed(baseUrl, token, jobUuid, reason)
            return@withContext "ERROR imprimiendo: $reason\nJob marcado como failed."
        }

        // 3b. Marcar printed
        val mark = markPrinted(baseUrl, token, jobUuid)
        return@withContext "OK — job ${job.optString("id")}\n  ${sent.getOrNull()} bytes a $ip:$port\n  $mark"
    }

    private fun markPrinted(baseUrl: String, token: String, uuid: String): String {
        val req = Request.Builder()
            .url("$baseUrl/api/v1/print-jobs/$uuid/printed")
            .post("".toRequestBody(null))
            .header("Authorization", "Bearer $token")
            .header("X-Device-Hint", "print-agent-android-debug")
            .build()
        return http.newCall(req).execute().use { resp ->
            "POST /printed → HTTP ${resp.code}"
        }
    }

    private fun markFailed(baseUrl: String, token: String, uuid: String, reason: String) {
        val body = JSONObject().put("reason", reason).toString()
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("$baseUrl/api/v1/print-jobs/$uuid/failed")
            .post(body)
            .header("Authorization", "Bearer $token")
            .header("X-Device-Hint", "print-agent-android-debug")
            .build()
        runCatching { http.newCall(req).execute().close() }
    }

    private fun prettyJson(body: String): String =
        runCatching { JSONObject(body).toString(2) }.getOrDefault(body)

    private fun summarizePending(httpCode: Int, body: String): String {
        val root = JSONObject(body)
        val jobs = root.optJSONArray("jobs")
        val count = root.optJSONObject("meta")?.optInt("count") ?: jobs?.length() ?: 0
        val sb = StringBuilder()
        sb.append("HTTP $httpCode — $count pendiente(s)\n")
        if (jobs == null || jobs.length() == 0) {
            sb.append("\nSin jobs en cola.")
            return sb.toString()
        }
        val first = jobs.getJSONObject(0)
        val id = first.optString("id")
        val type = first.optString("type")
        val orderId = first.optString("order_id")
        val printerTarget = first.optString("printer_target")
        val station = first.optJSONObject("kitchen_station")
        val printer = station?.optJSONObject("printer")
        val ip = printer?.optString("ip_address")
        val port = printer?.optString("port")
        val profile = printer?.optString("capability_profile")
        val content = first.optJSONObject("content")
        val meta = content?.optJSONObject("meta")
        val mesa = meta?.takeIf { !it.isNull("mesa") }?.optString("mesa")
        val mozo = meta?.takeIf { !it.isNull("mozo") }?.optString("mozo")
        val items = content?.optJSONArray("items")
        sb.append("\nPrimer job:\n")
        sb.append("  id:        $id\n")
        sb.append("  type:      $type\n")
        sb.append("  order:     $orderId\n")
        sb.append("  target:    $printerTarget\n")
        if (printer != null) {
            sb.append("  printer:   $ip:$port ($profile)\n")
        } else {
            sb.append("  printer:   <sin kitchen_station — usar fallback printer_target>\n")
        }
        if (mesa != null || mozo != null) sb.append("  mesa/mozo: ${mesa ?: "—"} / ${mozo ?: "—"}\n")
        if (items != null) sb.append("  items:     ${items.length()}\n")
        return sb.toString()
    }
}
