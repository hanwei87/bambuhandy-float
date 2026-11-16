package com.bambu.floatwidget.network

import com.bambu.floatwidget.data.BambuDevice
import com.bambu.floatwidget.data.DeviceListResponse
import com.bambu.floatwidget.data.LoginResponse
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

sealed class SendSmsResult {
    object Success : SendSmsResult()
    data class NeedCaptcha(
        val captchaId: String,
        val apiServers: List<String> = emptyList(),
        val staticServers: List<String> = emptyList()
    ) : SendSmsResult()
}

class BambuApiClient(
    private val region: String
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    val baseUrl: String
        get() = if (region.equals("cn", ignoreCase = true)) {
            "https://api.bambulab.cn"
        } else {
            "https://api.bambulab.com"
        }

    val mqttHost: String
        get() = if (region.equals("cn", ignoreCase = true)) {
            "cn.mqtt.bambulab.com"
        } else {
            "us.mqtt.bambulab.com"
        }

    /**
     * Login via verification code (Phone SMS code in CN, Email code in Global).
     */
    suspend fun login(account: String, code: String): Result<LoginResponse> =
        withContext(Dispatchers.IO) {
            try {
                val url = "$baseUrl/v1/user-service/user/login"
                val jsonPayload = JsonObject().apply {
                    addProperty("account", account)
                    addProperty("code", code)
                }

                val requestBody = jsonPayload.toString().toRequestBody(jsonMediaType)
                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "bambu-handy/4.4.2 (Android; Mobile)")
                    .build()

                val response = client.newCall(request).execute()
                val bodyStr = response.body?.string().orEmpty()

                if (response.isSuccessful) {
                    val loginRes = gson.fromJson(bodyStr, LoginResponse::class.java)
                    Result.success(loginRes)
                } else {
                    try {
                        val errObj = gson.fromJson(bodyStr, JsonObject::class.java)
                        val errMsg = errObj.get("message")?.asString 
                            ?: errObj.get("error")?.asString 
                            ?: "登录失败 (HTTP ${response.code})"
                        Result.failure(Exception(errMsg))
                    } catch (e: Exception) {
                        Result.failure(Exception("登录失败: HTTP ${response.code}"))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Send SMS verification code for China mobile numbers.
     */
    suspend fun sendSmsCode(phone: String, captchaResultJson: String? = null): Result<SendSmsResult> =
        withContext(Dispatchers.IO) {
            try {
                val url = "$baseUrl/v1/user-service/user/sendsmscode"
                val jsonPayload = JsonObject().apply {
                    addProperty("phone", phone)
                }
                val requestBuilder = Request.Builder()
                    .url(url)
                    .post(jsonPayload.toString().toRequestBody(jsonMediaType))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "bambu-handy/4.4.2 (Android; Mobile)")

                if (!captchaResultJson.isNullOrBlank()) {
                    val encoded = android.util.Base64.encodeToString(
                        captchaResultJson.toByteArray(Charsets.UTF_8),
                        android.util.Base64.NO_WRAP
                    )
                    requestBuilder.header("X-BBL-CAPTCHA-RESULT", encoded)
                }

                val response = client.newCall(requestBuilder.build()).execute()
                val bodyStr = response.body?.string().orEmpty()

                if (response.isSuccessful) {
                    Result.success(SendSmsResult.Success)
                } else if (response.code == 418 || bodyStr.contains("captchaId") || bodyStr.contains("robot")) {
                    try {
                        val obj = gson.fromJson(bodyStr, JsonObject::class.java)
                        val captchaId = obj.get("captchaId")?.asString ?: "6605ce5a35d9cb49d4e28c00d439e239"
                        Result.success(SendSmsResult.NeedCaptcha(captchaId))
                    } catch (e: Exception) {
                        Result.success(SendSmsResult.NeedCaptcha("6605ce5a35d9cb49d4e28c00d439e239"))
                    }
                } else {
                    try {
                        val errObj = gson.fromJson(bodyStr, JsonObject::class.java)
                        val msg = errObj.get("message")?.asString
                            ?: errObj.get("error")?.asString
                            ?: "发送短信失败: HTTP ${response.code}"
                        Result.failure(Exception(msg))
                    } catch (e: Exception) {
                        Result.failure(Exception("发送短信失败: HTTP ${response.code}"))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Send Email verification code for International region.
     */
    suspend fun sendEmailCode(email: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/v1/user-service/user/sendemail/code"
            val jsonPayload = JsonObject().apply {
                addProperty("email", email)
                addProperty("type", "codeLogin")
            }
            val requestBody = jsonPayload.toString().toRequestBody(jsonMediaType)
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .header("Content-Type", "application/json")
                .header("User-Agent", "bambu-handy/4.4.2 (Android; Mobile)")
                .build()

            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string().orEmpty()

            if (response.isSuccessful) {
                Result.success(true)
            } else {
                try {
                    val errObj = gson.fromJson(bodyStr, JsonObject::class.java)
                    val msg = errObj.get("message")?.asString
                        ?: errObj.get("error")?.asString
                        ?: "发送验证码失败: HTTP ${response.code}"
                    Result.failure(Exception(msg))
                } catch (e: Exception) {
                    Result.failure(Exception("发送验证码失败: HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getDeviceList(token: String): Result<List<BambuDevice>> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/v1/iot-service/api/user/bind"
            val request = Request.Builder()
                .url(url)
                .get()
                .header("Authorization", "Bearer $token")
                .header("Cookie", "token=$token; t=$token")
                .header("User-Agent", "bambu-handy/4.4.2 (Android; Mobile)")
                .build()

            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string().orEmpty()

            if (response.isSuccessful) {
                val listRes = gson.fromJson(bodyStr, DeviceListResponse::class.java)
                val devices = listRes.devices ?: emptyList()
                Result.success(devices)
            } else {
                Result.failure(Exception("获取设备失败: HTTP ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getUserId(token: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/v1/design-user-service/my/preference"
            val request = Request.Builder()
                .url(url)
                .get()
                .header("Authorization", "Bearer $token")
                .header("User-Agent", "bambu-handy/4.4.2 (Android; Mobile)")
                .build()

            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string().orEmpty()

            if (response.isSuccessful) {
                val obj = gson.fromJson(bodyStr, JsonObject::class.java)
                val uid = obj.get("uid")?.asString.orEmpty()
                Result.success(uid)
            } else {
                Result.failure(Exception("获取UID失败: HTTP ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
