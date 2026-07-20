package com.printagent.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class PrintAgentService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollJob: Job? = null

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private val notifManager: NotificationManager by lazy {
        getSystemService(NotificationManager::class.java)
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, buildNotification(getString(R.string.notif_starting)))
        if (pollJob?.isActive != true) {
            pollJob = scope.launch { pollLoop() }
        }
        return START_STICKY
    }

    private suspend fun pollLoop() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val ledger = PrintLedger(prefs)
        var lastProblem: String? = null

        while (true) {
            val baseUrl = (prefs.getString("base_url", "") ?: "").trimEnd('/')
            val token = prefs.getString("token", "") ?: ""
            val ip = prefs.getString("printer_ip", "") ?: ""
            val port = prefs.getString("printer_port", "9100")?.toIntOrNull() ?: 9100
            val lineWidth = prefs.getInt("line_width", EscPos.WIDTH_80MM)
            val checkStatus = prefs.getBoolean("check_printer_status", true)

            if (baseUrl.isBlank() || token.isBlank() || ip.isBlank()) {
                updateNotification(getString(R.string.notif_unconfigured))
            } else {
                val cycle = runCatching {
                    PrintAgent.pollAndPrintBatch(
                        http, baseUrl, token, ip, port, lineWidth, ledger, checkStatus
                    )
                }.getOrElse {
                    PrintAgent.Cycle(
                        message = "ERROR: ${it.javaClass.simpleName}: ${it.message?.take(60).orEmpty()}",
                        printerProblem = "fallo interno",
                    )
                }

                updateNotification(cycle.message)

                // Alerta sonora solo cuando el problema aparece o cambia, para no spamear.
                if (cycle.printerProblem != null && cycle.printerProblem != lastProblem) {
                    raiseAlert(cycle.printerProblem, cycle.pendingLeft)
                } else if (cycle.printerProblem == null && lastProblem != null) {
                    notifManager.cancel(ALERT_ID)
                }
                lastProblem = cycle.printerProblem
            }

            delay(POLL_INTERVAL_MS)
        }
    }

    /** Notificación de alta prioridad: la cocina no puede enterarse tarde de que no imprime. */
    private fun raiseAlert(problem: String, pending: Int) {
        val notif = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setContentTitle(getString(R.string.alert_title))
            .setContentText("$problem · $pending pedido(s) sin imprimir")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$problem\n$pending pedido(s) esperando. Se reintenta automáticamente."))
            .setSmallIcon(R.drawable.ic_print)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        notifManager.notify(ALERT_ID, notif)
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_print)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent())
            .build()

    private fun updateNotification(text: String) {
        notifManager.notify(NOTIF_ID, buildNotification(text))
    }

    private fun createChannels() {
        notifManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
        )
        notifManager.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL_ID, getString(R.string.alert_channel_name), NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    description = getString(R.string.alert_channel_desc)
                    enableVibration(true)
                }
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "print_agent"
        const val ALERT_CHANNEL_ID = "print_agent_alerts"
        const val NOTIF_ID = 1
        const val ALERT_ID = 2
        const val POLL_INTERVAL_MS = 4_000L
        const val ACTION_STOP = "com.printagent.android.action.STOP"

        fun start(context: Context) {
            val intent = Intent(context, PrintAgentService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, PrintAgentService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
