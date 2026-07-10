package com.hermes.mobile

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hermes.mobile.config.ServerEntry
import com.hermes.mobile.config.ServerManager

/**
 * ConfigActivity - 服务器列表管理界面
 *
 * 每次启动时弹出，用户可以：
 * 1. 查看已添加的服务器列表
 * 2. 选择一个服务器并连接
 * 3. 添加新的服务器
 * 4. 长按编辑/删除已有服务器
 *
 * 如果还没有任何服务器，必须先添加一个才能进入主界面。
 */
class ConfigActivity : AppCompatActivity() {

    private lateinit var serverManager: ServerManager
    private lateinit var serverList: RecyclerView
    private lateinit var adapter: ServerListAdapter
    private lateinit var emptyHint: TextView
    private lateinit var connectButton: Button

    companion object {
        private const val TAG = "HermesConfig"
        private const val REQUEST_ADD_SERVER = 1001
        private const val REQUEST_EDIT_SERVER = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config)

        serverManager = ServerManager(this)

        serverList = findViewById(R.id.serverList)
        emptyHint = findViewById(R.id.emptyHint)
        connectButton = findViewById(R.id.connectButton)
        val addServerBtn = findViewById<TextView>(R.id.addServerBtn)

        // 配置 RecyclerView
        serverList.layoutManager = LinearLayoutManager(this)

        // 创建适配器
        adapter = ServerListAdapter(
            servers = emptyList(),
            selectedId = serverManager.getActiveServerId(),
            onItemClick = { server -> selectServer(server) },
            onItemLongClick = { server -> editServer(server) }
        )
        serverList.adapter = adapter

        // + 添加按钮
        addServerBtn.setOnClickListener {
            val intent = Intent(this, ServerEditActivity::class.java)
            startActivityForResult(intent, REQUEST_ADD_SERVER)
        }

        // 连接按钮
        connectButton.setOnClickListener {
            val server = serverManager.getActiveServer()
            if (server != null) {
                val intent = Intent(this, MainActivity::class.java)
                intent.putExtra("server_url", server.url)
                intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(intent)
                finish()
            }
        }

        refreshList()
    }

    /**
     * 选中服务器
     */
    private fun selectServer(server: ServerEntry) {
        serverManager.setActiveServerId(server.id)
        refreshList()
    }

    /**
     * 编辑服务器
     */
    private fun editServer(server: ServerEntry) {
        val intent = Intent(this, ServerEditActivity::class.java)
        intent.putExtra("server_id", server.id)
        intent.putExtra("server_name", server.name)
        intent.putExtra("server_url", server.url)
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
        connectButton.isEnabled = hasSelected
        connectButton.text = if (hasSelected) "连接当前服务器" else "请先选择服务器"
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK) {
            refreshList()
        }
    }
}