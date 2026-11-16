package com.bambu.floatwidget.network

import android.util.Log
import com.bambu.floatwidget.data.MqttReport
import com.bambu.floatwidget.data.PrinterStateManager
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class BambuMqttClient(
    private val host: String,
    private val devId: String,
    private val uid: String,
    private val token: String,
    private val devName: String = "Bambu Printer"
) {
    private val TAG = "BambuMqttClient"
    private var mqttClient: MqttClient? = null
    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO)
    private var reconnectJob: Job? = null
    private var isIntentionallyClosed = false

    fun start() {
        isIntentionallyClosed = false
        PrinterStateManager.updateState {
            it.copy(
                deviceName = devName,
                connectionMessage = "正在连接云端 MQTT...",
                isConnected = false
            )
        }
        connect()
    }

    private fun connect() {
        scope.launch {
            try {
                val serverUri = "ssl://$host:8883"
                val clientId = "bambu_float_" + UUID.randomUUID().toString().substring(0, 8)
                val client = MqttClient(serverUri, clientId, MemoryPersistence())
                mqttClient = client

                val options = MqttConnectOptions().apply {
                    isCleanSession = true
                    connectionTimeout = 20
                    keepAliveInterval = 60
                    isAutomaticReconnect = true

                    // Username for Bambu Cloud MQTT is u_<uid> or uid
                    userName = if (uid.startsWith("u_")) uid else "u_$uid"
                    password = token.toCharArray()

                    socketFactory = createTrustAllSocketFactory()
                }

                client.setCallback(object : MqttCallbackExtended {
                    override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                        Log.i(TAG, "MQTT Connected to $serverURI (reconnect: $reconnect)")
                        PrinterStateManager.updateState {
                            it.copy(
                                isConnected = true,
                                connectionMessage = "已连接云端"
                            )
                        }
                        subscribeToDevice(client)
                        requestPushAll(client)
                    }

                    override fun connectionLost(cause: Throwable?) {
                        Log.w(TAG, "MQTT Connection lost: ${cause?.message}")
                        PrinterStateManager.updateState {
                            it.copy(
                                isConnected = false,
                                connectionMessage = "连接断开，正在重连..."
                            )
                        }
                        scheduleReconnect()
                    }

                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        val payload = message?.payload?.let { String(it) } ?: return
                        handleMessage(payload)
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })

                Log.i(TAG, "Connecting to $serverUri with user=$options.userName...")
                client.connect(options)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to MQTT: ${e.message}", e)
                PrinterStateManager.updateState {
                    it.copy(
                        isConnected = false,
                        connectionMessage = "连接失败: ${e.message}"
                    )
                }
                scheduleReconnect()
            }
        }
    }

    private fun subscribeToDevice(client: MqttClient) {
        try {
            val topic = "device/$devId/report"
            Log.i(TAG, "Subscribing to topic: $topic")
            client.subscribe(topic, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to subscribe to topic: ${e.message}")
        }
    }

    fun requestPushAll(client: MqttClient? = mqttClient) {
        scope.launch {
            try {
                if (client != null && client.isConnected) {
                    val requestTopic = "device/$devId/request"
                    val payload = "{\"pushing\":{\"sequence_id\":\"1\",\"command\":\"pushall\"}}"
                    val msg = MqttMessage(payload.toByteArray(Charsets.UTF_8)).apply {
                        qos = 0
                    }
                    client.publish(requestTopic, msg)
                    Log.i(TAG, "Published pushall command to $requestTopic")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send pushall: ${e.message}")
            }
        }
    }

    private fun handleMessage(jsonStr: String) {
        try {
            val report = gson.fromJson(jsonStr, MqttReport::class.java)
            val print = report.print ?: return

            PrinterStateManager.updateState { current ->
                current.copy(
                    gcodeState = print.gcodeState ?: current.gcodeState,
                    percent = print.percent ?: current.percent,
                    remainingMinutes = print.remainingMinutes ?: current.remainingMinutes,
                    nozzleTemp = print.nozzleTemp ?: current.nozzleTemp,
                    nozzleTargetTemp = print.nozzleTargetTemp ?: current.nozzleTargetTemp,
                    bedTemp = print.bedTemp ?: current.bedTemp,
                    bedTargetTemp = print.bedTargetTemp ?: current.bedTargetTemp,
                    subtaskName = print.subtaskName ?: current.subtaskName,
                    layerNum = print.layerNum ?: current.layerNum,
                    totalLayerNum = print.totalLayerNum ?: current.totalLayerNum,
                    lastUpdated = System.currentTimeMillis()
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing telemetry message: ${e.message}")
        }
    }

    private fun scheduleReconnect() {
        if (isIntentionallyClosed) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(5000)
            if (!isIntentionallyClosed && mqttClient?.isConnected != true) {
                Log.i(TAG, "Attempting reconnect...")
                connect()
            }
        }
    }

    fun stop() {
        isIntentionallyClosed = true
        reconnectJob?.cancel()
        scope.launch {
            try {
                mqttClient?.let {
                    if (it.isConnected) {
                        it.disconnect()
                    }
                    it.close()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error closing MQTT client: ${e.message}")
            } finally {
                mqttClient = null
            }
        }
    }

    private fun createTrustAllSocketFactory(): javax.net.ssl.SSLSocketFactory {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, SecureRandom())
        return sslContext.socketFactory
    }
}
