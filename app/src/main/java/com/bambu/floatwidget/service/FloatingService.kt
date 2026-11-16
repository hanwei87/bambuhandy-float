package com.bambu.floatwidget.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.bambu.floatwidget.MainActivity
import com.bambu.floatwidget.R
import com.bambu.floatwidget.data.PrefsManager
import com.bambu.floatwidget.data.PrinterStateManager
import com.bambu.floatwidget.data.PrinterUiState
import com.bambu.floatwidget.network.BambuApiClient
import com.bambu.floatwidget.network.BambuMqttClient
import com.bambu.floatwidget.ui.FloatingViewManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class FloatingService : Service() {

    private lateinit var prefs: PrefsManager
    private var floatManager: FloatingViewManager? = null
    private var mqttClient: BambuMqttClient? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main)
    private var stateCollectJob: Job? = null

    companion object {
        private const val CHANNEL_ID = "bambu_float_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"

        fun start(context: Context) {
            val intent = Intent(context, FloatingService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatingService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = PrefsManager(this)
        createNotificationChannel()

        floatManager = FloatingViewManager(this, prefs) {
            // User requested to close floating window from card
            prefs.isFloatingEnabled = false
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundNotification(PrinterStateManager.state.value)

        floatManager?.show()
        startMqtt()
        observeState()

        return START_STICKY
    }

    private fun startMqtt() {
        val devId = prefs.selectedDevId
        val token = prefs.token
        val uid = prefs.uid
        val region = prefs.region
        val devName = prefs.selectedDevName.ifBlank { "Bambu Printer" }

        if (devId.isNotBlank() && token.isNotBlank()) {
            mqttClient?.stop()
            val apiClient = BambuApiClient(region)
            mqttClient = BambuMqttClient(
                host = apiClient.mqttHost,
                devId = devId,
                uid = uid,
                token = token,
                devName = devName
            ).apply {
                start()
            }
        }
    }

    private fun observeState() {
        stateCollectJob?.cancel()
        stateCollectJob = serviceScope.launch {
            PrinterStateManager.state.collectLatest { state ->
                floatManager?.updateData(state)
                updateNotification(state)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_desc)
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(state: PrinterUiState): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title = "${state.deviceName} · ${state.getStateDisplay()}"
        val content = if (state.isPrinting()) {
            "进度: ${state.percent}% · 剩余: ${state.formatRemainingTimeDetail()}"
        } else {
            "状态: ${state.getStateDisplay()} · ${state.connectionMessage}"
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_printer)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (state.isPrinting() && state.percent > 0) {
            builder.setProgress(100, state.percent, false)
        }

        return builder.build()
    }

    private fun startForegroundNotification(state: PrinterUiState) {
        val notification = buildNotification(state)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(state: PrinterUiState) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(state))
    }

    override fun onDestroy() {
        super.onDestroy()
        stateCollectJob?.cancel()
        mqttClient?.stop()
        floatManager?.dismiss()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
