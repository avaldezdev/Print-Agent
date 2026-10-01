package com.printagent.android

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.time.Duration
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

/**
 * Recuperación manual de comandas.
 *
 * WAMA no tiene ninguna función de reimpresión, así que esta pantalla es la única
 * herramienta para recuperar una comanda que no llegó a la cocina o que salió mal.
 *
 * - **Fallidos**: jobs en `status=failed`. Al reimprimir se marcan como `printed`,
 *   de modo que salen del bucket y quedan saldados.
 * - **Recientes**: jobs ya impresos, por si el ticket se atascó, salió ilegible o
 *   se perdió en la cocina.
 *
 * A diferencia del agente automático, acá **se ignora el ledger anti-duplicados**:
 * si una persona aprieta "REIMPRIMIR" es una decisión explícita y tiene que salir.
 */
class ReprintActivity : AppCompatActivity() {

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private lateinit var container: LinearLayout
    private lateinit var txtStatus: TextView
    private lateinit var btnTabFailed: Button
    private lateinit var btnTabRecent: Button
    private lateinit var btnDismissAll: Button

    private val ledger: PrintLedger by lazy {
        PrintLedger(getSharedPreferences("settings", Context.MODE_PRIVATE))
    }

    private var showingFailed = true
    private var busy = false
    private var visibleFailed: List<JobRow> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reprint)

        container = findViewById(R.id.listContainer)
        txtStatus = findViewById(R.id.txtReprintStatus)
        btnTabFailed = findViewById(R.id.btnTabFailed)
        btnTabRecent = findViewById(R.id.btnTabRecent)
        btnDismissAll = findViewById(R.id.btnDismissAll)

        btnTabFailed.setOnClickListener { switchTab(true) }
        btnTabRecent.setOnClickListener { switchTab(false) }
        findViewById<Button>(R.id.btnRefresh).setOnClickListener { load() }
        btnDismissAll.setOnClickListener { confirmDismissAll() }
        txtStatus.setOnClickListener { if (ledger.dismissedCount() > 0) confirmRestore() }

        switchTab(true)
    }

    private fun switchTab(failed: Boolean) {
        showingFailed = failed
        btnTabFailed.setTypeface(null, if (failed) Typeface.BOLD else Typeface.NORMAL)
        btnTabRecent.setTypeface(null, if (failed) Typeface.NORMAL else Typeface.BOLD)
        btnTabFailed.alpha = if (failed) 1f else 0.55f
        btnTabRecent.alpha = if (failed) 0.55f else 1f
        btnDismissAll.visibility = if (failed) View.VISIBLE else View.GONE
        load()
    }

    private fun confirmDismissAll() {
        if (visibleFailed.isEmpty()) { toast("No hay nada para descartar"); return }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.dismiss_all_confirm_title))
            .setMessage("Se van a descartar ${visibleFailed.size} pedido(s).\n\n${getString(R.string.dismiss_confirm_msg)}")
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.action_dismiss_all)) { _, _ ->
                ledger.dismiss(visibleFailed.map { it.uuid })
                load()
            }
            .show()
    }

    private fun confirmRestore() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.action_restore))
            .setMessage("Vuelven a aparecer ${ledger.dismissedCount()} pedido(s) descartado(s).")
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.action_restore)) { _, _ ->
                ledger.restoreAllDismissed()
                load()
            }
            .show()
    }

    private fun cfg(): Config {
        val p = getSharedPreferences("settings", Context.MODE_PRIVATE)
        return Config(
            baseUrl = (p.getString("base_url", "") ?: "").trimEnd('/'),
            token = p.getString("token", "") ?: "",
            ip = p.getString("printer_ip", "") ?: "",
            port = p.getString("printer_port", "9100")?.toIntOrNull() ?: 9100,
            width = p.getInt("line_width", EscPos.WIDTH_80MM),
            checkStatus = p.getBoolean("check_printer_status", true),
        )
    }

    private fun load() {
        val c = cfg()
        container.removeAllViews()
        txtStatus.text = getString(R.string.reprint_loading)
        lifecycleScope.launch {
            val status = if (showingFailed) "failed" else "printed"
            val all = withContext(Dispatchers.IO) { fetchList(c, status) }
            if (all == null) {
                txtStatus.text = "Sin conexión con el servidor"
                return@launch
            }
            // Los descartados solo se ocultan del lado nuestro; el servidor los conserva.
            val jobs = if (showingFailed) all.filter { !ledger.isDismissed(it.uuid) } else all
            if (showingFailed) visibleFailed = jobs

            val dismissed = ledger.dismissedCount()
            txtStatus.text = when {
                !showingFailed -> "Recientes: ${jobs.size}"
                dismissed > 0 -> "Fallidos: ${jobs.size} · $dismissed descartado(s) — tocá para restaurar"
                else -> "Fallidos: ${jobs.size}"
            }
            if (jobs.isEmpty()) {
                container.addView(emptyView(
                    if (showingFailed) getString(R.string.reprint_empty_failed)
                    else getString(R.string.reprint_empty_recent)
                ))
                return@launch
            }
            jobs.forEach { container.addView(rowView(it, c)) }
        }
    }

    /** Lista jobs por estado, más nuevos primero. El listado ya trae `content` para el resumen. */
    private fun fetchList(c: Config, status: String): List<JobRow>? {
        if (c.baseUrl.isBlank() || c.token.isBlank()) return null
        val url = "${c.baseUrl}/api/v1/print-jobs".toHttpUrl().newBuilder()
            .addQueryParameter("status", status)
            .addQueryParameter("limit", "50")
            .build()
        return runCatching {
            http.newCall(authed(url.toString(), c.token).build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val arr = JSONObject(resp.body?.string().orEmpty()).optJSONArray("jobs") ?: return emptyList()
                (0 until arr.length()).map { arr.getJSONObject(it) }
                    .sortedByDescending { it.optString("created_at") }
                    .map { JobRow(it) }
            }
        }.getOrNull()
    }

    private fun rowView(job: JobRow, c: Config): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 24, 28, 24)
            setBackgroundColor(Color.parseColor("#FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 20) }
        }
        card.addView(TextView(this).apply {
            text = "${job.subheader} · ${job.orderId}"
            setTypeface(null, Typeface.BOLD)
            textSize = 16f
        })
        card.addView(TextView(this).apply {
            text = listOfNotNull(job.age(), job.mesa?.let { "Mesa $it" }).joinToString(" · ")
            setTextColor(Color.parseColor("#666666"))
            textSize = 13f
        })
        card.addView(TextView(this).apply {
            text = job.itemsSummary
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setPadding(0, 12, 0, 12)
        })
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        // "Descartar" solo tiene sentido en Fallidos: saca el pedido de la lista sin imprimirlo.
        if (showingFailed) {
            actions.addView(Button(this).apply {
                text = getString(R.string.action_dismiss)
                setTextColor(Color.parseColor("#B00020"))
                setOnClickListener { confirmDismissOne(job) }
            })
        }
        actions.addView(Button(this).apply {
            text = getString(R.string.action_reprint)
            setOnClickListener { reprint(job, c, this) }
        })
        card.addView(actions)
        return card
    }

    private fun confirmDismissOne(job: JobRow) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.dismiss_confirm_title))
            .setMessage("${job.subheader} · ${job.orderId}\n\n${getString(R.string.dismiss_confirm_msg)}")
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.action_dismiss)) { _, _ ->
                ledger.dismiss(listOf(job.uuid))
                load()
            }
            .show()
    }

    private fun emptyView(msg: String) = TextView(this).apply {
        text = msg
        setPadding(24, 48, 24, 24)
        textSize = 15f
        gravity = Gravity.CENTER
    }

    private fun reprint(job: JobRow, c: Config, button: Button) {
        if (busy) return
        if (c.ip.isBlank()) {
            toast(getString(R.string.reprint_no_printer)); return
        }
        busy = true
        button.isEnabled = false
        button.text = "Imprimiendo…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { doReprint(job, c) }
            busy = false
            button.isEnabled = true
            button.text = getString(R.string.action_reprint)
            toast(result)
            if (result.startsWith("OK")) load()
        }
    }

    private fun doReprint(job: JobRow, c: Config): String {
        // Detalle completo: el listado no trae customer ni comanda_note.
        val detail = runCatching {
            http.newCall(authed("${c.baseUrl}/api/v1/print-jobs/${job.uuid}", c.token).build())
                .execute().use { resp ->
                    if (!resp.isSuccessful) return "Error ${resp.code} al leer el pedido"
                    val parsed = JSONObject(resp.body?.string().orEmpty())
                    parsed.optJSONObject("data") ?: parsed.optJSONObject("job") ?: parsed
                }
        }.getOrNull() ?: return "No se pudo leer el pedido"

        if (detail.optJSONObject("content") == null) return "El pedido no tiene contenido"

        val bytes = EscPos.buildJobTicket(detail, c.width)
        val printed = runCatching { PrinterClient.send(c.ip, c.port, bytes, checkStatus = c.checkStatus) }
        if (printed.isFailure) {
            val ex = printed.exceptionOrNull()
            val reason = if (ex is PrinterClient.NotReadyException) ex.status.reason
            else "${ex?.javaClass?.simpleName}: ${ex?.message}"
            return "Impresora: $reason"
        }

        // Salió el papel: saldar el job en el servidor y anotarlo para que el agente no lo repita.
        PrintLedger(getSharedPreferences("settings", Context.MODE_PRIVATE)).markPrintedLocally(job.uuid)
        val ack = runCatching {
            http.newCall(
                authed("${c.baseUrl}/api/v1/print-jobs/${job.uuid}/printed", c.token)
                    .post("".toRequestBody(null)).build()
            ).execute().use { it.isSuccessful || it.code == 409 }
        }.getOrDefault(false)

        return if (ack) "OK — reimpreso" else "OK — reimpreso (el servidor no confirmó)"
    }

    private fun authed(url: String, token: String): Request.Builder =
        Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("X-Device-Hint", "print-agent-android-reprint")

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private data class Config(
        val baseUrl: String, val token: String, val ip: String,
        val port: Int, val width: Int, val checkStatus: Boolean,
    )

    /** Fila de la lista, armada desde el `content` que ya trae el listado. */
    private class JobRow(json: JSONObject) {
        val uuid: String = json.optString("id").removePrefix("job_")
        val orderId: String = json.optString("order_id").takeIf { it.isNotBlank() && it != "null" } ?: "—"
        private val createdAt: String = json.optString("created_at")
        private val content: JSONObject? = json.optJSONObject("content")
        val subheader: String = content?.optString("subheader")?.takeIf { it.isNotBlank() } ?: "COMANDA"
        val mesa: String? = content?.optJSONObject("meta")?.optString("mesa")
            ?.takeIf { it.isNotBlank() && it != "null" }

        val itemsSummary: String = content?.optJSONArray("items")?.let { arr ->
            (0 until arr.length()).joinToString("\n") { i ->
                val it = arr.getJSONObject(i)
                "${it.optInt("qty", 1)}x ${it.optString("name")}"
            }
        }?.takeIf { it.isNotBlank() } ?: "(sin ítems)"

        /** "hace 12 min" / "hace 3 h" / "hace 2 días" — clave para no mandar a cocinar algo viejo. */
        fun age(): String = runCatching {
            val mins = Duration.between(OffsetDateTime.parse(createdAt), OffsetDateTime.now()).toMinutes()
            when {
                mins < 1L -> "recién"
                mins < 60L -> "hace $mins min"
                mins < 1440L -> "hace ${mins / 60} h"
                else -> "hace ${mins / 1440} día(s)"
            }
        }.getOrDefault(createdAt.take(16))
    }
}
