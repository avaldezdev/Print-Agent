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
        val rgPaperWidth = findViewById<android.widget.RadioGroup>(R.id.rgPaperWidth)
        val chkCheckStatus = findViewById<android.widget.CheckBox>(R.id.chkCheckStatus)
        val btnTest = findViewById<Button>(R.id.btnTest)
        val btnList = findViewById<Button>(R.id.btnList)
        val btnRawJson = findViewById<Button>(R.id.btnRawJson)
        val btnPrintTest = findViewById<Button>(R.id.btnPrintTest)
        val btnPrintPending = findViewById<Button>(R.id.btnPrintPending)
        btnToggleAgent = findViewById(R.id.btnToggleAgent)
        val txtStatus = findViewById<TextView>(R.id.txtStatus)

        prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        editBaseUrl.setText(prefs.getString("base_url", "https://wama.micdepos.com"))
        editToken.setText(prefs.getString("token", ""))
        editPrinterIp.setText(prefs.getString("printer_ip", ""))
        editPrinterPort.setText(prefs.getString("printer_port", "9100"))
        val savedWidth = prefs.getInt("line_width", EscPos.WIDTH_80MM)
        rgPaperWidth.check(if (savedWidth == EscPos.WIDTH_58MM) R.id.rbPaper58 else R.id.rbPaper80)
        chkCheckStatus.isChecked = prefs.getBoolean("check_printer_status", true)
        refreshToggleLabel()

        val allManualButtons = listOf(btnTest, btnList, btnRawJson, btnPrintTest, btnPrintPending)

        fun currentWidth(): Int =
            if (rgPaperWidth.checkedRadioButtonId == R.id.rbPaper58) EscPos.WIDTH_58MM else EscPos.WIDTH_80MM

        fun saveSettings() {
            prefs.edit()
                .putString("base_url", editBaseUrl.text.toString().trim().trimEnd('/'))
                .putString("token", editToken.text.toString().trim())
                .putString("printer_ip", editPrinterIp.text.toString().trim())
                .putString("printer_port", editPrinterPort.text.toString().trim())
                .putInt("line_width", currentWidth())
                .putBoolean("check_printer_status", chkCheckStatus.isChecked)
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
        btnRawJson.setOnClickListener {
            saveSettings()
            val baseUrl = editBaseUrl.text.toString().trim().trimEnd('/')
            val token = editToken.text.toString().trim()
            txtStatus.text = getString(R.string.status_loading)
            allManualButtons.forEach { it.isEnabled = false }
            lifecycleScope.launch {
                val result = runCatching { rawJson(baseUrl, token) }
                    .getOrElse { "ERROR: ${it.javaClass.simpleName}: ${it.message}" }
                allManualButtons.forEach { it.isEnabled = true }
                txtStatus.text = getString(R.string.status_idle)
                showJsonDialog(result)
            }
        }
        btnPrintTest.setOnClickListener {
            val ip = editPrinterIp.text.toString().trim()
            val port = editPrinterPort.text.toString().trim().toIntOrNull()
            if (ip.isEmpty() || port == null) {
                txtStatus.text = "ERROR: completá IP y puerto válidos"
                return@setOnClickListener
            }
            runJob(R.string.status_printing) { printTest(ip, port, currentWidth(), chkCheckStatus.isChecked) }
        }
        btnPrintPending.setOnClickListener {
            val baseUrl = editBaseUrl.text.toString().trim().trimEnd('/')
            val token = editToken.text.toString().trim()
            val ip = editPrinterIp.text.toString().trim()
            val port = editPrinterPort.text.toString().trim().toIntOrNull()
            val width = currentWidth()
            val checkStatus = chkCheckStatus.isChecked
            if (ip.isEmpty() || port == null) {
                txtStatus.text = "ERROR: completá IP y puerto válidos"
                return@setOnClickListener
            }
            runJob(R.string.status_printing) {
                withContext(Dispatchers.IO) {
                    PrintAgent.pollAndPrintBatch(
                        http, baseUrl, token, ip, port, width, PrintLedger(prefs), checkStatus
                    ).message
                }
            }
        }
        findViewById<Button>(R.id.btnOpenReprint).setOnClickListener {
            saveSettings()
            startActivity(android.content.Intent(this, ReprintActivity::class.java))
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

    private fun showJsonDialog(text: String) {
        val tv = TextView(this).apply {
            setText(text)
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11f
            setPadding(48, 32, 48, 32)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(tv) }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("JSON del pedido")
            .setView(scroll)
            .setPositiveButton("Cerrar", null)
            .show()
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

    private suspend fun rawJson(baseUrl: String, token: String): String = withContext(Dispatchers.IO) {
        fun get(url: String): Pair<Int, String> {
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("X-Device-Hint", "print-agent-android-debug")
                .build()
            return http.newCall(req).execute().use { it.code to (it.body?.string().orEmpty()) }
        }
        fun listUrl(status: String, limit: Int): String =
            "$baseUrl/api/v1/print-jobs".toHttpUrl().newBuilder()
                .addQueryParameter("status", status)
                .addQueryParameter("limit", limit.toString())
                .build().toString()

        // Elige el job MÁS RECIENTE (por created_at) de una lista.
        fun newestUuid(status: String, limit: Int): String? {
            val (_, body) = get(listUrl(status, limit))
            val jobs = runCatching { JSONObject(body).optJSONArray("jobs") }.getOrNull() ?: return null
            if (jobs.length() == 0) return null
            var best = jobs.getJSONObject(0)
            for (i in 1 until jobs.length()) {
                val j = jobs.getJSONObject(i)
                if (j.optString("created_at") > best.optString("created_at")) best = j
            }
            return best.optString("id").removePrefix("job_")
        }

        // 1) el pendiente más nuevo; 2) si no hay, el impreso más nuevo.
        var origin = "pending"
        var uuid = newestUuid("pending", 50)
        if (uuid == null) {
            origin = "printed (más reciente)"
            uuid = newestUuid("printed", 50)
        }
        if (uuid == null) {
            return@withContext "No hay pedidos para mostrar.\nCargá un pedido con cliente y nota, enviálo, y tocá este botón."
        }

        // 2) DETALLE (show) por UUID — el payload completo que usa la impresión.
        val (dCode, dBody) = get("$baseUrl/api/v1/print-jobs/$uuid")
        val root = runCatching { JSONObject(dBody) }.getOrNull()
        val job = root?.optJSONObject("data") ?: root
        val meta = job?.optJSONObject("content")?.optJSONObject("meta")
        val hasCustomer = meta != null && meta.has("customer") && !meta.isNull("customer")
        val hasNote = meta != null && meta.has("order_note") && !meta.isNull("order_note")
        val summary = "customer: ${if (hasCustomer) "PRESENTE" else "AUSENTE"}    " +
            "order_note: ${if (hasNote) "PRESENTE" else "AUSENTE"}"
        val pretty = runCatching { JSONObject(dBody).toString(2) }.getOrDefault(dBody)
        "GET /print-jobs/$uuid  [$origin]  HTTP $dCode\n$summary\n\n$pretty"
    }

    private suspend fun printTest(ip: String, port: Int, width: Int, checkStatus: Boolean): String =
        withContext(Dispatchers.IO) {
            val bytes = EscPos.buildTestTicket(width)
            val sent = PrinterClient.send(ip, port, bytes, checkStatus = checkStatus)
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
