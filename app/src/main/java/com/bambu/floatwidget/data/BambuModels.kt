package com.bambu.floatwidget.data

import com.google.gson.annotations.SerializedName

// Login request
data class LoginRequest(
    @SerializedName("account") val account: String,
    @SerializedName("password") val password: String? = null,
    @SerializedName("code") val code: String? = null
)

// Login response
data class LoginResponse(
    @SerializedName("code") val code: Int? = null,
    @SerializedName("message") val message: String? = null,
    @SerializedName("accessToken") val accessToken: String? = null,
    @SerializedName("refreshToken") val refreshToken: String? = null,
    @SerializedName("loginType") val loginType: String? = null,
    @SerializedName("uid") val uid: String? = null,
    @SerializedName("username") val username: String? = null
)

// Device item from /v1/iot-service/api/user/bind
data class DeviceListResponse(
    @SerializedName("code") val code: Int? = null,
    @SerializedName("message") val message: String? = null,
    @SerializedName("devices") val devices: List<BambuDevice>? = null
)

data class BambuDevice(
    @SerializedName("dev_id") val devId: String,
    @SerializedName("name") val name: String?,
    @SerializedName("dev_model_name") val modelName: String?,
    @SerializedName("online") val online: Boolean? = true,
    @SerializedName("print_status") val printStatus: String?,
    @SerializedName("dev_access_code") val accessCode: String?
) {
    override fun toString(): String {
        val displayName = if (!name.isNullOrBlank()) name else (modelName ?: devId)
        return "$displayName (${modelName ?: "Printer"})"
    }
}

// MQTT telemetry payload wrapper
data class MqttReport(
    @SerializedName("print") val print: PrintData?
)

data class PrintData(
    @SerializedName("gcode_state") val gcodeState: String?,
    @SerializedName("mc_percent") val percent: Int?,
    @SerializedName("mc_remaining_time") val remainingMinutes: Int?,
    @SerializedName("nozzle_temper") val nozzleTemp: Float?,
    @SerializedName("nozzle_target_temper") val nozzleTargetTemp: Float?,
    @SerializedName("bed_temper") val bedTemp: Float?,
    @SerializedName("bed_target_temper") val bedTargetTemp: Float?,
    @SerializedName("subtask_name") val subtaskName: String?,
    @SerializedName("layer_num") val layerNum: Int?,
    @SerializedName("total_layer_num") val totalLayerNum: Int?
)
