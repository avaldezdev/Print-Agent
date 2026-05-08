package com.printagent.android

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var btnToggleAgent: Button

    private val requestNotifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* result irrelevant: service starts either way */ }

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
        btnToggleAgent = findViewById(R.id.btnToggleAgent)
        val txtStatus = findViewById<TextView>(R.id.txtStatus)

        prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        editBaseUrl.setText(prefs.getString("base_url", "https://wama.micdepos.com"))
        editToken.setText(prefs.getString("token", ""))
        editPrinterIp.setText(prefs.getString("printer_ip", ""))
        editPrinterPort.setText(prefs.getString("printer_port", "9100"))
        refreshToggleLabel()

        val allManualButtons = listOf(btnTest, btnList, btnPrintTest, btnPrintPending)

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
            allManualButtons.forEach { it.isEnabled = false }
            lifecycleScope.launch {
                val result = runCatching { block() }
                allManualButtons.forEach { it.isEnabled = true }
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
            runJob(R.string.status_printing) {
                withContext(Dispatchers.IO) {
                    PrintAgent.pollAndPrintOne(http, baseUrl, token, ip, port)
                }
            }
        }
        btnToggleAgent.setOnClickListener {
            saveSettings()
            val nowActive = !prefs.getBoolean("agent_active", false)
            prefs.edit().putBoolean("agent_active", nowActive).apply()
            refreshToggleLabel()
            if (nowActive) {
                ensureNotifPermission()
                PrintAgentService.start(this)
            } else {
                PrintAgentService.stop(this)
            }
        }

        if (prefs.getBoolean("agent_active", false)) {
            ensureNotifPermission()
            PrintAgentService.start(this)
        }
    }

    private fun refreshToggleLabel() {
        val active = prefs.getBoolean("agent_active", false)
        btnToggleAgent.text = getString(
            if (active) R.string.action_deactivate else R.string.action_activate
        )
    }

    private fun ensureNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
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
