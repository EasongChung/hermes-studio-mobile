package com.hermes.mobile

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.hermes.mobile.config.ServerConfig
import com.hermes.mobile.config.UrlValidator

/**
 * ConfigActivity - 首次启动配置界面
 *
 * 用户在此界面输入 Hermes Server 的 HTTP 地址。
 * 输入后校验格式，保存到 SharedPreferences，然后跳转到 MainActivity。
 *
 * 用户可以随时从菜单重新进入此界面修改服务器地址。
 */
class ConfigActivity : AppCompatActivity() {

    private lateinit var serverConfig: ServerConfig

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config)

        serverConfig = ServerConfig(this)

        val urlInput = findViewById<EditText>(R.id.urlInput)
        val saveButton = findViewById<Button>(R.id.saveButton)
        val errorText = findViewById<TextView>(R.id.errorText)

        // 如果已有配置，回显到输入框方便修改
        val existingUrl = serverConfig.getServerUrl()
        if (!existingUrl.isNullOrEmpty()) {
            urlInput.setText(existingUrl)
            urlInput.setSelection(existingUrl.length) // 光标移到末尾
        }

        saveButton.setOnClickListener {
            val input = urlInput.text.toString().trim()

            // 校验 URL 格式
            val (isValid, errorMsg) = UrlValidator.validate(input)
            if (!isValid) {
                errorText.text = errorMsg
                errorText.visibility = TextView.VISIBLE
                return@setOnClickListener
            }

            // 校验通过，隐藏错误提示，保存 URL
            errorText.visibility = TextView.GONE
            val normalizedUrl = UrlValidator.normalize(input)
            serverConfig.saveServerUrl(normalizedUrl)

            Toast.makeText(this, "服务器地址已保存", Toast.LENGTH_SHORT).show()

            // 跳转到主界面
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
            finish()
        }
    }
}