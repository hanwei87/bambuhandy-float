package com.bambu.floatwidget.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PrinterUiState(
    val isConnected: Boolean = false,
    val connectionMessage: String = "未连接",
    val gcodeState: String = "IDLE", // IDLE, RUNNING, PAUSE, FINISH, FAILED, PREPARE
    val percent: Int = 0,
    val remainingMinutes: Int = 0,
    val nozzleTemp: Float = 0f,
    val nozzleTargetTemp: Float = 0f,
    val bedTemp: Float = 0f,
    val bedTargetTemp: Float = 0f,
    val subtaskName: String = "",
    val layerNum: Int = 0,
    val totalLayerNum: Int = 0,
    val deviceName: String = "Bambu Printer",
    val lastUpdated: Long = 0L
) {
    fun formatRemainingTime(): String {
        if (remainingMinutes <= 0) return "--"
        val hours = remainingMinutes / 60
        val mins = remainingMinutes % 60
        return if (hours > 0) {
            "${hours}h ${mins}m"
        } else {
            "${mins}m"
        }
    }

    fun formatRemainingTimeDetail(): String {
        if (remainingMinutes <= 0) return "-- 分钟"
        val hours = remainingMinutes / 60
        val mins = remainingMinutes % 60
        return if (hours > 0) {
            "${hours} 小时 ${mins} 分钟"
        } else {
            "${mins} 分钟"
        }
    }

    fun calculateEta(): String {
        if (remainingMinutes <= 0) return "预计 --:--"
        val etaMillis = System.currentTimeMillis() + remainingMinutes * 60 * 1000L
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        return "预计 ${sdf.format(Date(etaMillis))}"
    }

    fun getStateDisplay(): String {
        return when (gcodeState.uppercase(Locale.ROOT)) {
            "RUNNING" -> "正在打印"
            "PAUSE" -> "已暂停"
            "FINISH" -> "打印完成"
            "FAILED" -> "打印异常"
            "PREPARE" -> "准备中"
            "IDLE" -> "待机空闲"
            else -> gcodeState
        }
    }

    fun isPrinting(): Boolean = gcodeState.equals("RUNNING", ignoreCase = true)
    fun isPaused(): Boolean = gcodeState.equals("PAUSE", ignoreCase = true)
}

object PrinterStateManager {
    private val _state = MutableStateFlow(PrinterUiState())
    val state: StateFlow<PrinterUiState> = _state.asStateFlow()

    fun updateState(transform: (PrinterUiState) -> PrinterUiState) {
        _state.value = transform(_state.value)
    }

    fun reset() {
        _state.value = PrinterUiState()
    }
}
