package com.bambu.floatwidget.data

import android.content.Context
import android.content.SharedPreferences

class PrefsManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("bambu_float_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_REGION = "region"
        private const val KEY_ACCOUNT = "account"
        private const val KEY_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_UID = "uid"
        private const val KEY_DEV_ID = "selected_dev_id"
        private const val KEY_DEV_NAME = "selected_dev_name"
        private const val KEY_FLOAT_ENABLED = "float_enabled"
        private const val KEY_FLOAT_X = "float_pos_x"
        private const val KEY_FLOAT_Y = "float_pos_y"
    }

    var region: String
        get() = prefs.getString(KEY_REGION, "cn") ?: "cn"
        set(value) = prefs.edit().putString(KEY_REGION, value).apply()

    var account: String
        get() = prefs.getString(KEY_ACCOUNT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ACCOUNT, value).apply()

    var token: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    var refreshToken: String
        get() = prefs.getString(KEY_REFRESH_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_REFRESH_TOKEN, value).apply()

    var uid: String
        get() = prefs.getString(KEY_UID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_UID, value).apply()

    var selectedDevId: String
        get() = prefs.getString(KEY_DEV_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DEV_ID, value).apply()

    var selectedDevName: String
        get() = prefs.getString(KEY_DEV_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DEV_NAME, value).apply()

    var isFloatingEnabled: Boolean
        get() = prefs.getBoolean(KEY_FLOAT_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_FLOAT_ENABLED, value).apply()

    var floatPosX: Int
        get() = prefs.getInt(KEY_FLOAT_X, -1)
        set(value) = prefs.edit().putInt(KEY_FLOAT_X, value).apply()

    var floatPosY: Int
        get() = prefs.getInt(KEY_FLOAT_Y, 200)
        set(value) = prefs.edit().putInt(KEY_FLOAT_Y, value).apply()

    val isLoggedIn: Boolean
        get() = token.isNotBlank()

    fun clearAuth() {
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_UID)
            .remove(KEY_DEV_ID)
            .remove(KEY_DEV_NAME)
            .remove(KEY_FLOAT_ENABLED)
            .apply()
    }
}
