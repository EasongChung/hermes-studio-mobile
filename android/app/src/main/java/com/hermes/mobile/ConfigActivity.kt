package com.hermes.mobile

import android.content.Intent
import android.content.SharedPreferences
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hermes.mobile.config.ServerEntry
import com.hermes.mobile.config.ServerManager

/**
 * ConfigActivity - 服务器列表管理界面（APP 启动首页）
 *
 * 功能：
 * 1. 查看已添加的服务器列表，选择、添加、编辑、删除
 * 2. 倒计时自动登录默认服务器（可开关、自定义倒计时长）
 * 3. 底部显示项目简述和 GitHub 超链接
 *
 * 设计原则：每次启动 APP 默认进入此界面，
 * 用户可手动连接服务器，或开启倒计时让 APP 自动连接默认服务器。
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
        // SharedPreferences 键名
        private const val PREFS_AUTO_LOGIN = "hermes_auto_login_prefs"
        private const val KEY_AUTO_LOGIN_ENABLED = "auto_login_enabled"
        private const val KEY_COUNTDOWN_DURATION = "countdown_duration"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 切回正常主题（如果有 Splash 主题）
        setTheme(R.style.Theme_HermesStudioMobile)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config)

        serverManager = ServerManager(this)

        // === 初始化控件 ===
        serverList = findViewById(R.id.serverList)
        emptyHint = findViewById(R.id.emptyHint)
        connectButton = findViewById(R.id.connectButton)
        val addServerBtn = findViewById<TextView>(R.id.addServerBtn)

        // 倒计时区域
        autoLoginSection = findViewById(R.id.autoLoginSection)
        autoLoginSwitch = findViewById(R.id.autoLoginSwitch)
        countdownSettingsRow = findViewById(R.id.countdownSettingsRow)
        countdownText = findViewById(R.id.countdownText)
        countdownDurationText = findViewById(R.id.countdownDurationText)
        countdownMinusBtn = findViewById(R.id.countdownMinusBtn)
        countdownPlusBtn = findViewById(R.id.countdownPlusBtn)
        cancelCountdownBtn = findViewById(R.id.cancelCountdownBtn)

        // 项目信息
        githubLinkBtn = findViewById(R.id.githubLinkBtn)

        // === 配置 RecyclerView ===
        serverList.layoutManager = LinearLayoutManager(this)

        // 创建适配器
        adapter = ServerListAdapter(
            servers = emptyList(),
            selectedId = serverManager.getActiveServerId(),
            onItemClick = { server -> selectServer(server) },
            onItemLongClick = { server -> editServer(server) }
        )
        serverList.adapter = adapter

        // === 按钮事件 ===

        // + 添加按钮
        addServerBtn.setOnClickListener {
            val intent = Intent(this, ServerEditActivity::class.java)
            startActivityForResult(intent, REQUEST_ADD_SERVER)
        }

        // 连接按钮
        connectButton.setOnClickListener {
            cancelCountdown() // 手动点击连接时取消倒计时
            connectToServer()
        }

        // === 倒计时自动登录设置 ===

        // 从 SharedPreferences 读取保存的设置
        loadAutoLoginSettings()

        // 开关切换：显示/隐藏倒计时设置区
        autoLoginSwitch.setOnCheckedChangeListener { _, isChecked ->
            saveAutoLoginEnabled(isChecked)
            updateAutoLoginSectionVisibility()
            if (!isChecked) {
                cancelCountdown() // 关闭开关时取消倒计时
            } else {
                // 开启开关时，如果有已选服务器，启动倒计时
                startCountdownIfNeeded()
            }
        }

        // 倒计时秒数减少
        countdownMinusBtn.setOnClickListener {
            if (countdownSeconds > 3) {
                countdownSeconds -= 1
                updateCountdownDurationDisplay()
                saveCountdownDuration(countdownSeconds)
                // 如果倒计时正在进行，重启倒计时
                if (isCountdownActive) {
                    restartCountdown()
                }
            } else {
                Toast.makeText(this, "最少 3 秒", Toast.LENGTH_SHORT).show()
            }
        }

        // 倒计时秒数增加
        countdownPlusBtn.setOnClickListener {
            if (countdownSeconds < 30) {
                countdownSeconds += 1
                updateCountdownDurationDisplay()
                saveCountdownDuration(countdownSeconds)
                // 如果倒计时正在进行，重启倒计时
                if (isCountdownActive) {
                    restartCountdown()
                }
            } else {
                Toast.makeText(this, "最多 30 秒", Toast.LENGTH_SHORT).show()
            }
        }

        // 取消倒计时按钮
        cancelCountdownBtn.setOnClickListener {
            cancelCountdown()
        }

        // === 项目信息 - GitHub 链接 ===
        githubLinkBtn.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/EasongChung/hermes-studio-mobile"))
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "无法打开浏览器", Toast.LENGTH_SHORT).show()
            }
        }

        // 初始化显示
        refreshList()
    }

    /**
     * 从 SharedPreferences 读取倒计时设置
     */
    private fun loadAutoLoginSettings() {
        val prefs = getSharedPreferences(PREFS_AUTO_LOGIN, MODE_PRIVATE)
        val enabled = prefs.getBoolean(KEY_AUTO_LOGIN_ENABLED, false)
        countdownSeconds = prefs.getInt(KEY_COUNTDOWN_DURATION, 5)
        autoLoginSwitch.isChecked = enabled
        updateCountdownDurationDisplay()
    }

    /**
     * 保存倒计时开关状态
     */
    private fun saveAutoLoginEnabled(enabled: Boolean) {
        getSharedPreferences(PREFS_AUTO_LOGIN, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_LOGIN_ENABLED, enabled)
            .apply()
    }

    /**
     * 保存倒计时秒数
     */
    private fun saveCountdownDuration(seconds: Int) {
        getSharedPreferences(PREFS_AUTO_LOGIN, MODE_PRIVATE)
            .edit()
            .putInt(KEY_COUNTDOWN_DURATION, seconds)
            .apply()
    }

    /**
     * 更新倒计时设置区的显示状态。
     *
     * 【为什么这样做】
     * R37 实测发现旧实现把 autoLoginSection 整块设为 gone，
     * 而开关 autoLoginSwitch 又放在 autoLoginSection 内部，导致用户看不到入口。
     * 因此这里固定显示外层区域和开关行，只根据开关状态显示/隐藏“倒计时秒数设置”这类高级选项。
     */
    private fun updateAutoLoginSectionVisibility() {
        // 外层区域必须始终可见，否则用户无法看到并开启“倒计时自动登录”开关。
        autoLoginSection.visibility = View.VISIBLE

        val enabled = autoLoginSwitch.isChecked
        countdownSettingsRow.visibility = if (enabled) View.VISIBLE else View.GONE

        // 开关关闭时，倒计时显示和取消按钮也必须隐藏，避免界面残留旧状态。
        if (!enabled && !isCountdownActive) {
            countdownText.visibility = View.GONE
            cancelCountdownBtn.visibility = View.GONE
        }
    }

    /**
     * 更新倒计时秒数显示
     */
    private fun updateCountdownDurationDisplay() {
        countdownDurationText.text = countdownSeconds.toString()
    }

    /**
     * 条件启动倒计时：开关开启 + 有已选服务器
     */
    private fun startCountdownIfNeeded() {
        if (!autoLoginSwitch.isChecked) return
        val activeServer = serverManager.getActiveServer()
        if (activeServer == null) {
            // 没有已选服务器，倒计时不启动，但保留开关状态
            return
        }
        startCountdown(activeServer)
    }

    /**
     * 启动倒计时
     * @param server 要自动登录的服务器
     */
    private fun startCountdown(server: ServerEntry) {
        cancelCountdown() // 取消已有倒计时
        isCountdownActive = true
        countdownText.visibility = android.view.View.VISIBLE
        cancelCountdownBtn.visibility = android.view.View.VISIBLE
        connectButton.isEnabled = false // 倒计时期间禁用连接按钮

        countDownTimer = object : CountDownTimer((countdownSeconds * 1000).toLong(), 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val secondsRemaining = (millisUntilFinished / 1000).toInt() + 1
                countdownText.text = "${secondsRemaining}s"
            }

            override fun onFinish() {
                isCountdownActive = false
                countdownText.visibility = android.view.View.GONE
                cancelCountdownBtn.visibility = android.view.View.GONE
                connectButton.isEnabled = true
                // 倒计时结束，自动连接服务器
                autoConnectToServer(server)
            }
        }.start()
    }

    /**
     * 取消倒计时
     */
    private fun cancelCountdown() {
        countDownTimer?.cancel()
        countDownTimer = null
        isCountdownActive = false
        countdownText.visibility = android.view.View.GONE
        cancelCountdownBtn.visibility = android.view.View.GONE
        connectButton.isEnabled = true
    }

    /**
     * 重启倒计时（修改秒数后）
     */
    private fun restartCountdown() {
        val activeServer = serverManager.getActiveServer()
        if (activeServer != null) {
            startCountdown(activeServer)
        }
    }

    /**
     * 倒计时结束自动连接服务器
     */
    private fun autoConnectToServer(server: ServerEntry) {
        val intent = Intent(this, MainActivity::class.java)
        intent.putExtra("server_url", server.url)
        intent.putExtra("server_username", server.username)
        intent.putExtra("server_password", server.password)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        finish()
    }

    /**
     * 手动连接服务器
     */
    private fun connectToServer() {
        val server = serverManager.getActiveServer()
        if (server != null) {
            autoConnectToServer(server)
        }
    }

    /**
     * 选中服务器
     */
    private fun selectServer(server: ServerEntry) {
        serverManager.setActiveServerId(server.id)
        refreshList()
        // 选中后如果倒计时开关已开启，启动倒计时
        if (autoLoginSwitch.isChecked) {
            startCountdownIfNeeded()
        }
    }

    /**
     * 编辑服务器
     */
    private fun editServer(server: ServerEntry) {
        val intent = Intent(this, ServerEditActivity::class.java)
        intent.putExtra("server_id", server.id)
        intent.putExtra("server_name", server.name)
        intent.putExtra("server_url", server.url)
        intent.putExtra("server_username", server.username)
        intent.putExtra("server_password", server.password)
        startActivityForResult(intent, REQUEST_EDIT_SERVER)
    }

    /**
     * 刷新列表
     */
    private fun refreshList() {
        val servers = serverManager.getAllServers()
        val selectedId = serverManager.getActiveServerId()

        adapter.updateData(servers, selectedId)

        // 显示/隐藏空状态
        val hasServers = servers.isNotEmpty()
        emptyHint.visibility = if (hasServers) android.view.View.GONE else android.view.View.VISIBLE
        serverList.visibility = if (hasServers) android.view.View.VISIBLE else android.view.View.GONE

        // 连接按钮状态
        val hasSelected = selectedId != null && servers.any { it.id == selectedId }
        connectButton.isEnabled = hasSelected && !isCountdownActive
        connectButton.text = if (hasSelected) "连接当前服务器" else "请先选择服务器"

        // 更新倒计时区域可见性
        updateAutoLoginSectionVisibility()

        // 如果开关开启且有服务器，启动倒计时
        if (hasSelected) {
            startCountdownIfNeeded()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK) {
            refreshList()
        }
    }

    /**
     * 界面暂停时取消倒计时，避免内存泄漏
     */
    override fun onPause() {
        super.onPause()
        // 不取消倒计时（让倒计时在后台继续），但标记已暂停
    }

    /**
     * 界面销毁时清理倒计时
     */
    override fun onDestroy() {
        cancelCountdown()
        super.onDestroy()
    }
}