package com.hermes.mobile

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.hermes.mobile.config.ServerManager
import com.hermes.mobile.config.UrlValidator

/**
 * ServerEditActivity - 添加/编辑服务器界面
 *
 * 通过 Intent extra 区分模式：
 * - 无 extra：添加模式，保存后返回 ConfigActivity
 * - 有 server_id extra：编辑模式，可修改名称/地址或删除
 */
class ServerEditActivity : AppCompatActivity() {

    private lateinit var serverManager: ServerManager
    private var editServerId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_server_edit)

        serverManager = ServerManager(this)

        val formTitle = findViewById<TextView>(R.id.formTitle)
        val nameInput = findViewById<EditText>(R.id.nameInput)
        val urlInput = findViewById<EditText>(R.id.urlInput)
        val errorText = findViewById<TextView>(R.id.errorText)
        val saveButton = findViewById<Button>(R.id.saveButton)
        val deleteButton = findViewById<Button>(R.id.deleteButton)

        // 判断模式：编辑还是添加
        editServerId = intent.getStringExtra("server_id")

        if (editServerId != null) {
            // 编辑模式
            formTitle.text = "编辑服务器"
            nameInput.setText(intent.getStringExtra("server_name") ?: "")
            urlInput.setText(intent.getStringExtra("server_url") ?: "http://")
            urlInput.setSelection(urlInput.text?.length ?: 0)
            saveButton.text = "保存修改"
            deleteButton.visibility = android.view.View.VISIBLE
        } else {
            // 添加模式
            deleteButton.visibility = android.view.View.GONE
        }

        // 保存
        saveButton.setOnClickListener {
            val name = nameInput.text.toString().trim()
            val url = urlInput.text.toString().trim()

            // 校验名称
            if (name.isEmpty()) {
                errorText.text = "请输入服务器名称"
                errorText.visibility = TextView.VISIBLE
                return@setOnClickListener
            }

            // 校验 URL
            val (isValid, errorMsg) = UrlValidator.validate(url)
            if (!isValid) {
                errorText.text = errorMsg
                errorText.visibility = TextView.VISIBLE
                return@setOnClickListener
            }

            errorText.visibility = TextView.GONE
            val normalizedUrl = UrlValidator.normalize(url)

            if (editServerId != null) {
                // 更新
                serverManager.updateServer(editServerId!!, name, normalizedUrl)
                Toast.makeText(this, "服务器已更新", Toast.LENGTH_SHORT).show()
            } else {
                // 添加
                val entry = serverManager.addServer(name, normalizedUrl)
                // 新添加的自动设为当前选中
                serverManager.setActiveServerId(entry.id)
                Toast.makeText(this, "服务器已添加", Toast.LENGTH_SHORT).show()
            }

            setResult(RESULT_OK)
            finish()
        }

        // 删除
        deleteButton.setOnClickListener {
            if (editServerId != null) {
                android.app.AlertDialog.Builder(this)
                    .setTitle("删除服务器")
                    .setMessage("确定要删除「${nameInput.text}」吗？")
                    .setPositiveButton("删除") { _, _ ->
                        serverManager.deleteServer(editServerId!!)
                        Toast.makeText(this, "服务器已删除", Toast.LENGTH_SHORT).show()
                        setResult(RESULT_OK)
                        finish()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
    }
}