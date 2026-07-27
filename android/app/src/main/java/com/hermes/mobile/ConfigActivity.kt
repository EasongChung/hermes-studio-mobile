package com.hermes.mobile

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
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
 * 6. 有 active 服务器 + 开启自动登录时：使用极短等待（FAST_AUTO_LOGIN_MS）进入 Main，
 *    减少约 2s 设置页停留；用户仍可点「取消自动登录」打断
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
    // 步进器 − / + 改为 TextView（一体式药丸内的分段），事件逻辑不变
    private lateinit var countdownMinusBtn: TextView
    private lateinit var countdownPlusBtn: TextView
    private lateinit var cancelCountdownBtn: Button
    private var countDownTimer: CountDownTimer? = null
    private var countdownSeconds = 5 // 默认 5 秒（用户自定义；快速路径用 FAST_AUTO_LOGIN_MS）
    private var isCountdownActive = false

    // 项目信息
    private lateinit var githubLinkBtn: TextView

    /**
     * 是否允许本轮使用「快速自动登录」路径。
     * 用户主动点开设置页（从 Main 退出登录 / 取消倒计时）后关闭，避免无法停留在设置页。
     */
    private var allowFastAutoLogin = true

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
        /**
         * 冷启动且自动登录开启时，设置页仅短暂展示后进入 Main。
         * 比完整倒计时（2~15s）快一个数量级，同时仍给用户一点「取消」窗口。
         */
        private const val FAST_AUTO_LOGIN_MS = 300L
        /** Intent：从 Main 退出登录回到设置页时禁止立刻再跳回 */
        const val EXTRA_SKIP_FAST_AUTO_LOGIN = "skip_fast_auto_login"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_HermesStudioMobile)
        super.onCreate(savedInstanceState)
        // 小屏手机锁定竖屏，避免横屏下设置页布局拥挤。
        ScreenOrientationHelper.lockPortraitOnPhone(this)

        serverManager = ServerManager(this)
        val skipFast = intent.getBooleanExtra(EXTRA_SKIP_FAST_AUTO_LOGIN, false)
        allowFastAutoLogin = !skipFast

        // 【方法3】冷启动直达：开启自动登录且有选中服务器、且非「退出登录返回」，
        // 则完全跳过设置页渲染，直接进入 Main，省去 Config 首帧 + 倒计时等待。
        // 用户如需修改配置，可在 Main 内退出登录（带 skip 标记）返回设置页。
        if (!skipFast && shouldDirectAutoLogin()) {
            val server = serverManager.getActiveServer()
            if (server != null) {
                Log.d(TAG, "direct auto-login skip Config -> Main server=${server.name}")
                autoConnectToServer(server)
                return
            }
        }

        setContentView(R.layout.activity_config)

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
            allowFastAutoLogin = false
            val intent = Intent(this, ServerEditActivity::class.java)
            startActivityForResult(intent, REQUEST_ADD_SERVER)
        }

        // 主按钮：空闲时连接服务器；倒计时进行中时取消自动登录。
        connectButton.setOnClickListener {
            if (isCountdownActive) {
                cancelCountdown()
                allowFastAutoLogin = false
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
                allowFastAutoLogin = false
            } else {
                // 用户手动打开开关：使用完整倒计时，便于调整秒数
                allowFastAutoLogin = false
                startCountdownIfNeeded(preferFast = false)
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
            allowFastAutoLogin = false
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

    /**
     * 【方法3】是否满足冷启动直达 Main 的条件：开启自动登录 + 存在选中服务器。
     */
    private fun shouldDirectAutoLogin(): Boolean {
        val prefs = getSharedPreferences(PREFS_AUTO_LOGIN, MODE_PRIVATE)
        val enabled = prefs.getBoolean(KEY_AUTO_LOGIN_ENABLED, false)
        if (!enabled) return false
        return serverManager.getActiveServer() != null
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

    private fun startCountdownIfNeeded(preferFast: Boolean = allowFastAutoLogin) {
        if (!autoLoginSwitch.isChecked) return
        val activeServer = serverManager.getActiveServer() ?: return
        startCountdown(activeServer, preferFast = preferFast)
    }

    /**
     * @param preferFast true：冷启动快速路径（FAST_AUTO_LOGIN_MS）；false：用户配置的完整秒数
     */
    private fun startCountdown(server: ServerEntry, preferFast: Boolean) {
        cancelCountdown()
        isCountdownActive = true
        countdownText.visibility = View.VISIBLE
        cancelCountdownBtn.visibility = View.GONE
        updateConnectButtonState()

        val useFast = preferFast && allowFastAutoLogin
        val totalMs = if (useFast) FAST_AUTO_LOGIN_MS else (countdownSeconds * 1000L)
        val tickMs = if (useFast) FAST_AUTO_LOGIN_MS else 1000L

        Log.d(
            TAG,
            "auto-login countdown start fast=$useFast totalMs=$totalMs server=${server.name}"
        )

        countDownTimer = object : CountDownTimer(totalMs, tickMs) {
            override fun onTick(millisUntilFinished: Long) {
                val secondsRemaining = if (useFast) {
                    // 快速路径：显示 1s 级提示即可
                    ((millisUntilFinished + 999L) / 1000L).toInt().coerceAtLeast(1)
                } else {
                    ((millisUntilFinished + 999L) / 1000L).toInt()
                }
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
        if (!::countdownText.isInitialized) return
        countDownTimer?.cancel()
        countDownTimer = null
        isCountdownActive = false
        countdownText.visibility = View.GONE
        if (::cancelCountdownBtn.isInitialized) {
            cancelCountdownBtn.visibility = View.GONE
        }
        updateConnectButtonState()
    }

    private fun restartCountdown() {
        val activeServer = serverManager.getActiveServer()
        if (activeServer != null && autoLoginSwitch.isChecked) {
            // 调整秒数后走完整倒计时，便于用户确认新时长
            startCountdown(activeServer, preferFast = false)
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
            // 切换服务器后用完整倒计时，避免误连
            startCountdownIfNeeded(preferFast = false)
        }
    }

    private fun editServer(server: ServerEntry) {
        cancelCountdown()
        allowFastAutoLogin = false
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
            startCountdownIfNeeded(preferFast = allowFastAutoLogin)
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
        if (!::connectButton.isInitialized) return
        val servers = if (::serverManager.isInitialized) serverManager.getAllServers() else emptyList()
        val selectedId = if (::serverManager.isInitialized) serverManager.getActiveServerId() else null
        val hasSelected = selectedId != null && servers.any { it.id == selectedId }

        if (isCountdownActive) {
            connectButton.isEnabled = true
            connectButton.text = "取消自动登录"
            connectButton.setTextColor(ContextCompat.getColor(this, R.color.hermes_on_action))
            connectButton.setBackgroundResource(R.drawable.bg_btn_stop_autologin_fab)
            return
        }

        connectButton.setBackgroundResource(R.drawable.bg_btn_connect_fab)
        connectButton.setTextColor(ContextCompat.getColor(this, R.color.hermes_on_action))
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
        // 【安全】direct auto-login 跳过 setContentView，lateinit 属性未初始化。
        // 需用 isInitialized 守卫，避免 countdownText 等未初始化就访问。
        if (::countdownText.isInitialized) {
            cancelCountdown()
        }
        super.onDestroy()
    }
}
