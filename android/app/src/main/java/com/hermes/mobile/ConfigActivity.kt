package com.hermes.mobile

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hermes.mobile.config.ServerEntry
import com.hermes.mobile.config.ServerManager

/**
 * ConfigActivity - 服务器列表管理界面（APP 启动首页）
 *
 * 功能：
 * 1. 查看已添加的服务器列表，选择、添加、编辑、删除
 * 2. 倒计时自动登录默认服务器（可开关、自定义倒计时长 2~15 秒）
 * 3. 自动登录进行中时，主按钮切换为「取消自动登录」
 * 4. 底部显示项目简述和 GitHub 超链接
 * 5. 小屏手机锁定竖屏
 */
class ConfigActivity : AppCompatActivity() {

    private lateinit var serverManager: ServerManager
    private lateinit var serverList: RecyclerView
    private lateinit var adapter: ServerListAdapter
    private lateinit var emptyHint: TextView
    private lateinit var connectButton: Button

    // 倒计时自动登录相关
    private lateinit var autoLoginSection: LinearLayout
    private lateinit var autoLoginSwitch: SwitchCompat
    private lateinit var countdownSettingsRow: LinearLayout
    private lateinit var countdownText: TextView
    private lateinit var countdownDurationText: TextView
    private lateinit var countdownMinusBtn: Button
    private lateinit var countdownPlusBtn: Button
    private lateinit var cancelCountdownBtn: Button
    private var countDownTimer: CountDownTimer? = null
    private var countdownSeconds = 5 // 默认 5 秒
    private var isCountdownActive = false

    // 项目信息
    private lateinit var githubLinkBtn: TextView

    companion object {
        private const val TAG = "HermesConfig"
        private const val REQUEST_ADD_SERVER = 1001
        private const val REQUEST_EDIT_SERVER = 1002
        private const val PREFS_AUTO_LOGIN = "hermes_auto_login_prefs"
        private const val KEY_AUTO_LOGIN_ENABLED = "auto_login_enabled"
        private const val KEY_COUNTDOWN_DURATION = "countdown_duration"
        private const val MIN_COUNTDOWN_SECONDS = 2
        private const val MAX_COUNTDOWN_SECONDS = 15
        private const val DEFAULT_COUNTDOWN_SECONDS = 5
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_HermesStudioMobile)
        super.onCreate(savedInstanceState)
        // 小屏手机锁定竖屏，避免横屏下设置页布局拥挤。
        ScreenOrientationHelper.lockPortraitOnPhone(this)
        setContentView(R.layout.activity_config)

        serverManager = ServerManager(this)

        serverList = findViewById(R.id.serverList)
        emptyHint = findViewById(R.id.emptyHint)
        connectButton = findViewById(R.id.connectButton)
        val addServerBtn = findViewById<TextView>(R.id.addServerBtn)

        autoLoginSection = findViewById(R.id.autoLoginSection)
        autoLoginSwitch = findViewById(R.id.autoLoginSwitch)
        countdownSettingsRow = findViewById(R.id.countdownSettingsRow)
        countdownText = findViewById(R.id.countdownText)
        countdownDurationText = findViewById(R.id.countdownDurationText)
        countdownMinusBtn = findViewById(R.id.countdownMinusBtn)
        countdownPlusBtn = findViewById(R.id.countdownPlusBtn)
        cancelCountdownBtn = findViewById(R.id.cancelCountdownBtn)

        githubLinkBtn = findViewById(R.id.githubLinkBtn)

        serverList.layoutManager = LinearLayoutManager(this)

        adapter = ServerListAdapter(
            servers = emptyList(),
            selectedId = serverManager.getActiveServerId(),
            onItemClick = { server -> selectServer(server) },
            onItemLongClick = { server -> editServer(server) }
        )
        serverList.adapter = adapter

        addServerBtn.setOnClickListener {
            cancelCountdown()
            val intent = Intent(this, ServerEditActivity::class.java)
            startActivityForResult(intent, REQUEST_ADD_SERVER)
        }

        // 主按钮：空闲时连接服务器；倒计时进行中时取消自动登录。
        connectButton.setOnClickListener {
            if (isCountdownActive) {
                cancelCountdown()
                Toast.makeText(this, "已取消自动登录", Toast.LENGTH_SHORT).show()
            } else {
                connectToServer()
            }
        }

        loadAutoLoginSettings()

        autoLoginSwitch.setOnCheckedChangeListener { _, isChecked ->
            saveAutoLoginEnabled(isChecked)
            updateAutoLoginSectionVisibility()
            if (!isChecked) {
                cancelCountdown()
            } else {
                startCountdownIfNeeded()
            }
            updateConnectButtonState()
        }

        countdownMinusBtn.setOnClickListener {
            if (countdownSeconds > MIN_COUNTDOWN_SECONDS) {
                countdownSeconds -= 1
                updateCountdownDurationDisplay()
                saveCountdownDuration(countdownSeconds)
                if (isCountdownActive) {
                    restartCountdown()
                }
            } else {
                Toast.makeText(this, "最少 ${MIN_COUNTDOWN_SECONDS} 秒", Toast.LENGTH_SHORT).show()
            }
        }

        countdownPlusBtn.setOnClickListener {
            if (countdownSeconds < MAX_COUNTDOWN_SECONDS) {
                countdownSeconds += 1
                updateCountdownDurationDisplay()
                saveCountdownDuration(countdownSeconds)
                if (isCountdownActive) {
                    restartCountdown()
                }
            } else {
                Toast.makeText(this, "最多 ${MAX_COUNTDOWN_SECONDS} 秒", Toast.LENGTH_SHORT).show()
            }
        }

        cancelCountdownBtn.setOnClickListener {
            cancelCountdown()
        }

        githubLinkBtn.setOnClickListener {
            try {
                val intent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/EasongChung/hermes-studio-mobile")
                )
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "无法打开浏览器", Toast.LENGTH_SHORT).show()
            }
        }

        refreshList()
    }

    private fun loadAutoLoginSettings() {
        val prefs = getSharedPreferences(PREFS_AUTO_LOGIN, MODE_PRIVATE)
        val enabled = prefs.getBoolean(KEY_AUTO_LOGIN_ENABLED, false)
        countdownSeconds = prefs.getInt(KEY_COUNTDOWN_DURATION, DEFAULT_COUNTDOWN_SECONDS)
            .coerceIn(MIN_COUNTDOWN_SECONDS, MAX_COUNTDOWN_SECONDS)
        autoLoginSwitch.isChecked = enabled
        updateCountdownDurationDisplay()
        updateAutoLoginSectionVisibility()
    }

    private fun saveAutoLoginEnabled(enabled: Boolean) {
        getSharedPreferences(PREFS_AUTO_LOGIN, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_LOGIN_ENABLED, enabled)
            .apply()
    }

    private fun saveCountdownDuration(seconds: Int) {
        getSharedPreferences(PREFS_AUTO_LOGIN, MODE_PRIVATE)
            .edit()
            .putInt(KEY_COUNTDOWN_DURATION, seconds.coerceIn(MIN_COUNTDOWN_SECONDS, MAX_COUNTDOWN_SECONDS))
            .apply()
    }

    /**
     * 更新倒计时设置区的显示状态。
     * 外层区域和开关始终可见，仅控制秒数设置行的显隐。
     */
    private fun updateAutoLoginSectionVisibility() {
        autoLoginSection.visibility = View.VISIBLE
        val enabled = autoLoginSwitch.isChecked
        countdownSettingsRow.visibility = if (enabled) View.VISIBLE else View.GONE

        if (!enabled && !isCountdownActive) {
            countdownText.visibility = View.GONE
            cancelCountdownBtn.visibility = View.GONE
        }
    }

    private fun updateCountdownDurationDisplay() {
        countdownDurationText.text = countdownSeconds.toString()
    }

    private fun startCountdownIfNeeded() {
        if (!autoLoginSwitch.isChecked) return
        val activeServer = serverManager.getActiveServer() ?: return
        startCountdown(activeServer)
    }

    private fun startCountdown(server: ServerEntry) {
        cancelCountdown()
        isCountdownActive = true
        countdownText.visibility = View.VISIBLE
        // 主按钮已承担“取消自动登录”，兼容旧按钮保持隐藏。
        cancelCountdownBtn.visibility = View.GONE
        updateConnectButtonState()

        countDownTimer = object : CountDownTimer((countdownSeconds * 1000L), 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val secondsRemaining = ((millisUntilFinished + 999L) / 1000L).toInt()
                countdownText.text = "${secondsRemaining}s"
            }

            override fun onFinish() {
                isCountdownActive = false
                countdownText.visibility = View.GONE
                cancelCountdownBtn.visibility = View.GONE
                updateConnectButtonState()
                autoConnectToServer(server)
            }
        }.start()
    }

    private fun cancelCountdown() {
        countDownTimer?.cancel()
        countDownTimer = null
        isCountdownActive = false
        countdownText.visibility = View.GONE
        cancelCountdownBtn.visibility = View.GONE
        updateConnectButtonState()
    }

    private fun restartCountdown() {
        val activeServer = serverManager.getActiveServer()
        if (activeServer != null && autoLoginSwitch.isChecked) {
            startCountdown(activeServer)
        }
    }

    private fun autoConnectToServer(server: ServerEntry) {
        val intent = Intent(this, MainActivity::class.java)
        intent.putExtra("server_url", server.url)
        intent.putExtra("server_username", server.username)
        intent.putExtra("server_password", server.password)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        finish()
    }

    private fun connectToServer() {
        val server = serverManager.getActiveServer()
        if (server != null) {
            autoConnectToServer(server)
        }
    }

    private fun selectServer(server: ServerEntry) {
        serverManager.setActiveServerId(server.id)
        refreshList()
        if (autoLoginSwitch.isChecked) {
            startCountdownIfNeeded()
        }
    }

    private fun editServer(server: ServerEntry) {
        cancelCountdown()
        val intent = Intent(this, ServerEditActivity::class.java)
        intent.putExtra("server_id", server.id)
        intent.putExtra("server_name", server.name)
        intent.putExtra("server_url", server.url)
        intent.putExtra("server_username", server.username)
        intent.putExtra("server_password", server.password)
        startActivityForResult(intent, REQUEST_EDIT_SERVER)
    }

    private fun refreshList() {
        val servers = serverManager.getAllServers()
        val selectedId = serverManager.getActiveServerId()

        adapter.updateData(servers, selectedId)

        val hasServers = servers.isNotEmpty()
        emptyHint.visibility = if (hasServers) View.GONE else View.VISIBLE
        serverList.visibility = if (hasServers) View.VISIBLE else View.GONE

        updateAutoLoginSectionVisibility()
        updateConnectButtonState()

        val hasSelected = selectedId != null && servers.any { it.id == selectedId }
        if (hasSelected) {
            startCountdownIfNeeded()
        }
    }

    /**
     * 根据是否有选中服务器、是否正在倒计时，更新主按钮文案/样式/可点击状态。
     *
     * 【交互规则】
     * - 倒计时进行中：按钮变为「取消自动登录」，始终可点
     * - 空闲且有选中服务器：按钮为「连接当前服务器」
     * - 空闲且无选中服务器：按钮为「请先选择服务器」并禁用
     */
    private fun updateConnectButtonState() {
        val servers = if (::serverManager.isInitialized) serverManager.getAllServers() else emptyList()
        val selectedId = if (::serverManager.isInitialized) serverManager.getActiveServerId() else null
        val hasSelected = selectedId != null && servers.any { it.id == selectedId }

        if (isCountdownActive) {
            connectButton.isEnabled = true
            connectButton.text = "取消自动登录"
            connectButton.setTextColor(ContextCompat.getColor(this, R.color.hermes_text_primary))
            connectButton.setBackgroundResource(R.drawable.bg_btn_stop_autologin)
            return
        }

        connectButton.setBackgroundResource(R.drawable.bg_btn_connect)
        connectButton.setTextColor(ContextCompat.getColor(this, R.color.hermes_text_primary))
        if (hasSelected) {
            connectButton.isEnabled = true
            connectButton.text = "连接当前服务器"
        } else {
            connectButton.isEnabled = false
            connectButton.text = "请先选择服务器"
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK) {
            refreshList()
        }
    }

    override fun onPause() {
        super.onPause()
        // 不取消倒计时（让倒计时在后台继续）
    }

    override fun onDestroy() {
        cancelCountdown()
        super.onDestroy()
    }
}
