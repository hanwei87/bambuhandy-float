package com.bambu.floatwidget

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bambu.floatwidget.data.BambuDevice
import com.bambu.floatwidget.data.PrefsManager
import com.bambu.floatwidget.data.PrinterStateManager
import com.bambu.floatwidget.databinding.ActivityMainBinding
import com.bambu.floatwidget.network.BambuApiClient
import com.bambu.floatwidget.network.BambuMqttClient
import com.bambu.floatwidget.network.SendSmsResult
import com.bambu.floatwidget.service.FloatingService
import com.bambu.floatwidget.ui.GeetestDialog
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PrefsManager
    private var devicesList: List<BambuDevice> = emptyList()
    private var mainMqttClient: BambuMqttClient? = null
    private var countDownTimer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = (application as BambuApp).prefs

        setupViews()
        setupListeners()
        observePrinterState()

        if (prefs.isLoggedIn) {
            showLoggedInState()
            ensureUidAndLoadDevices()
        } else {
            showLoggedOutState()
        }
    }

    override fun onResume() {
        super.onResume()
        checkOverlayPermission()
        updateFloatingSwitchState()
    }

    private fun setupViews() {
        updateRegionUi()

        if (prefs.account.isNotBlank()) {
            binding.etAccount.setText(prefs.account)
        }
    }

    private fun updateRegionUi() {
        val isGlobal = prefs.region == "global"
        binding.rbGlobal.isChecked = isGlobal
        binding.rbCn.isChecked = !isGlobal

        if (isGlobal) {
            binding.tilAccount.hint = "邮箱地址"
            binding.etAccount.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            binding.tilVerifyCode.hint = "邮箱验证码"
        } else {
            binding.tilAccount.hint = "手机号码"
            binding.etAccount.inputType = InputType.TYPE_CLASS_PHONE
            binding.tilVerifyCode.hint = "短信验证码"
        }
    }

    private fun setupListeners() {
        binding.rgRegion.setOnCheckedChangeListener { _, checkedId ->
            prefs.region = if (checkedId == R.id.rbGlobal) "global" else "cn"
            updateRegionUi()
        }

        binding.btnSendCode.setOnClickListener {
            val account = binding.etAccount.text.toString().trim()
            if (account.isBlank()) {
                val hint = if (prefs.region == "global") "请先输入邮箱地址" else "请先输入手机号码"
                showToast(hint)
                return@setOnClickListener
            }
            sendVerificationCode(account)
        }

        binding.btnLogin.setOnClickListener {
            val account = binding.etAccount.text.toString().trim()
            val code = binding.etVerifyCode.text.toString().trim()

            if (account.isBlank()) {
                val hint = if (prefs.region == "global") "请输入邮箱地址" else "请输入手机号码"
                showToast(hint)
                return@setOnClickListener
            }
            if (code.isBlank()) {
                showToast("请输入6位验证码")
                return@setOnClickListener
            }

            performLogin(account, code)
        }

        binding.btnLogout.setOnClickListener {
            stopFloating()
            mainMqttClient?.stop()
            mainMqttClient = null
            prefs.clearAuth()
            countDownTimer?.cancel()
            binding.btnSendCode.text = "获取验证码"
            binding.btnSendCode.isEnabled = true
            binding.etVerifyCode.setText("")
            showLoggedOutState()
            showToast("已退出账号")
        }

        binding.btnRefreshDevices.setOnClickListener {
            ensureUidAndLoadDevices()
        }

        binding.spDevices.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position in devicesList.indices) {
                    val device = devicesList[position]
                    prefs.selectedDevId = device.devId
                    prefs.selectedDevName = device.name ?: device.modelName ?: "Printer"
                    binding.tvDeviceSn.text = "SN: ${device.devId} (${device.modelName ?: "未知型号"})"

                    // Start/switch MQTT connection in main activity
                    connectMqtt()

                    // If floating service is running, restart to switch device
                    if (prefs.isFloatingEnabled) {
                        FloatingService.start(this@MainActivity)
                    }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.btnToggleFloat.setOnClickListener {
            if (prefs.isFloatingEnabled) {
                stopFloating()
            } else {
                startFloating()
            }
        }

        binding.switchFloating.setOnClickListener {
            if (binding.switchFloating.isChecked) {
                startFloating()
            } else {
                stopFloating()
            }
        }

        binding.btnGrantPermission.setOnClickListener {
            requestOverlayPermission()
        }
    }

    private fun startFloating() {
        if (!hasOverlayPermission()) {
            binding.switchFloating.isChecked = false
            requestOverlayPermission()
            showToast("请先允许悬浮窗权限")
            return
        }
        if (prefs.selectedDevId.isBlank()) {
            binding.switchFloating.isChecked = false
            showToast("请先选择要监控的打印机")
            return
        }

        prefs.isFloatingEnabled = true
        FloatingService.start(this)
        updateFloatingSwitchState()
        showToast("悬浮胶囊已开启")
    }

    private fun stopFloating() {
        prefs.isFloatingEnabled = false
        FloatingService.stop(this)
        updateFloatingSwitchState()
        showToast("悬浮胶囊已关闭")
    }

    private fun observePrinterState() {
        lifecycleScope.launch {
            PrinterStateManager.state.collectLatest { state ->
                binding.tvMainStatusText.text = if (state.isConnected) {
                    "${state.deviceName} · ${state.getStateDisplay()}"
                } else {
                    state.connectionMessage
                }

                binding.tvMainProgressPercent.text = if (state.isPrinting() || state.percent > 0) {
                    "${state.percent}%"
                } else {
                    "--%"
                }

                binding.tvMainTimeDetail.text = if (state.isPrinting()) {
                    "剩余: ${state.formatRemainingTimeDetail()} (${state.calculateEta()}) | 任务: ${state.subtaskName.ifBlank { "未知" }}"
                } else {
                    "状态: ${state.getStateDisplay()} | ${state.connectionMessage}"
                }

                binding.vMainStatusDot.setBackgroundResource(
                    when {
                        state.isPrinting() -> R.drawable.dot_status_green
                        state.isPaused() -> R.drawable.dot_status_yellow
                        state.isConnected -> R.drawable.dot_status_green
                        else -> R.drawable.dot_status_gray
                    }
                )
            }
        }
    }

    private fun startCountDown() {
        countDownTimer?.cancel()
        binding.btnSendCode.isEnabled = false
        countDownTimer = object : CountDownTimer(60000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                binding.btnSendCode.text = "${millisUntilFinished / 1000}s"
            }

            override fun onFinish() {
                binding.btnSendCode.text = "重新获取"
                binding.btnSendCode.isEnabled = true
            }
        }.start()
    }

    private fun sendVerificationCode(account: String) {
        binding.btnSendCode.isEnabled = false
        lifecycleScope.launch {
            val apiClient = BambuApiClient(prefs.region)
            if (prefs.region == "cn") {
                val res = apiClient.sendSmsCode(account)
                res.onSuccess { result ->
                    when (result) {
                        is SendSmsResult.Success -> {
                            startCountDown()
                            showToast("短信验证码已发送，请注意查收")
                        }
                        is SendSmsResult.NeedCaptcha -> {
                            binding.btnSendCode.isEnabled = true
                            GeetestDialog(
                                context = this@MainActivity,
                                captchaId = result.captchaId,
                                onSuccess = { captchaResult ->
                                    lifecycleScope.launch {
                                        binding.btnSendCode.isEnabled = false
                                        val retryRes = apiClient.sendSmsCode(account, captchaResult)
                                        retryRes.onSuccess {
                                            startCountDown()
                                            showToast("验证成功，短信验证码已发送")
                                        }.onFailure { err ->
                                            binding.btnSendCode.isEnabled = true
                                            showToast("发送失败: ${err.message}")
                                        }
                                    }
                                },
                                onError = { errMsg ->
                                    binding.btnSendCode.isEnabled = true
                                    showToast("人机验证未完成: $errMsg")
                                }
                            ).show()
                        }
                    }
                }.onFailure { err ->
                    binding.btnSendCode.isEnabled = true
                    showToast("发送失败: ${err.message}")
                }
            } else {
                val res = apiClient.sendEmailCode(account)
                res.onSuccess {
                    startCountDown()
                    showToast("验证码已发送至您的邮箱，请查收")
                }.onFailure { err ->
                    binding.btnSendCode.isEnabled = true
                    showToast("发送失败: ${err.message}")
                }
            }
        }
    }

    private fun performLogin(account: String, code: String) {
        binding.btnLogin.isEnabled = false
        binding.btnLogin.text = "登录中..."

        lifecycleScope.launch {
            val apiClient = BambuApiClient(prefs.region)
            val result = apiClient.login(account, code)

            binding.btnLogin.isEnabled = true
            binding.btnLogin.text = "验证码登录"

            result.onSuccess { loginRes ->
                val token = loginRes.accessToken
                if (!token.isNullOrBlank()) {
                    prefs.token = token
                    prefs.account = account
                    prefs.refreshToken = loginRes.refreshToken ?: ""

                    showToast("登录成功")
                    showLoggedInState()
                    ensureUidAndLoadDevices()
                } else {
                    showToast(loginRes.message ?: "登录异常，请重试")
                }
            }.onFailure { err ->
                showToast("登录失败: ${err.message}")
            }
        }
    }

    private fun ensureUidAndLoadDevices() {
        val token = prefs.token
        if (token.isBlank()) return

        lifecycleScope.launch {
            val apiClient = BambuApiClient(prefs.region)

            // 1. Fetch UID if missing
            if (prefs.uid.isBlank()) {
                val uidRes = apiClient.getUserId(token)
                uidRes.onSuccess { uid ->
                    if (uid.isNotBlank()) {
                        prefs.uid = uid
                    }
                }
            }

            // 2. Fetch Devices
            binding.btnRefreshDevices.isEnabled = false
            val result = apiClient.getDeviceList(token)
            binding.btnRefreshDevices.isEnabled = true

            result.onSuccess { list ->
                devicesList = list
                if (list.isEmpty()) {
                    showToast("未找到绑定的打印机设备")
                    return@onSuccess
                }

                val adapter = ArrayAdapter(
                    this@MainActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    list.map { it.toString() }
                )
                binding.spDevices.adapter = adapter

                val savedDevId = prefs.selectedDevId
                val index = list.indexOfFirst { it.devId == savedDevId }
                if (index >= 0) {
                    binding.spDevices.setSelection(index)
                } else if (list.isNotEmpty()) {
                    binding.spDevices.setSelection(0)
                }

                connectMqtt()
            }.onFailure { err ->
                showToast("获取设备列表失败: ${err.message}")
            }
        }
    }

    private fun connectMqtt() {
        val devId = prefs.selectedDevId
        val token = prefs.token
        val uid = prefs.uid
        val region = prefs.region
        val devName = prefs.selectedDevName.ifBlank { "Bambu Printer" }

        if (devId.isNotBlank() && token.isNotBlank() && uid.isNotBlank()) {
            mainMqttClient?.stop()
            val apiClient = BambuApiClient(region)
            mainMqttClient = BambuMqttClient(
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

    private fun showLoggedInState() {
        binding.llLoginForm.visibility = View.GONE
        binding.llLoggedInInfo.visibility = View.VISIBLE
        binding.tvAccountName.text = "已登录: ${prefs.account}"
        binding.cardDevice.visibility = View.VISIBLE
    }

    private fun showLoggedOutState() {
        binding.llLoginForm.visibility = View.VISIBLE
        binding.llLoggedInInfo.visibility = View.GONE
        binding.cardDevice.visibility = View.GONE
        binding.switchFloating.isChecked = false
        binding.btnToggleFloat.text = "开启悬浮"
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun checkOverlayPermission() {
        if (!hasOverlayPermission()) {
            binding.cardPermissionWarning.visibility = View.VISIBLE
        } else {
            binding.cardPermissionWarning.visibility = View.GONE
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    private fun updateFloatingSwitchState() {
        binding.switchFloating.isChecked = prefs.isFloatingEnabled
        binding.btnToggleFloat.text = if (prefs.isFloatingEnabled) "关闭悬浮" else "开启悬浮"
    }

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        countDownTimer?.cancel()
        mainMqttClient?.stop()
    }
}
