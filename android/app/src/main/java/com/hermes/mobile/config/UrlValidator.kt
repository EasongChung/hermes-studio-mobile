package com.hermes.mobile.config

import android.util.Patterns
import java.net.URL

/**
 * UrlValidator - URL 格式校验工具类
 *
 * 验证用户输入的 Server URL 是否合法：
 * - 必须是以 http:// 或 https:// 开头的合法 URL
 * - 不能为空
 * - IP 地址或域名格式必须正确
 */
object UrlValidator {

    /**
     * 校验 Server URL 是否合法
     *
     * @param url 用户输入的 URL 字符串
     * @return 校验结果 Pair(isValid, errorMessage)
     */
    fun validate(url: String): Pair<Boolean, String> {
        val trimmed = url.trim()

        // 检查是否为空
        if (trimmed.isEmpty()) {
            return Pair(false, "服务器地址不能为空")
        }

        // 自动补全协议头（如果用户没输入）
        val normalizedUrl = if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            "http://$trimmed"
        } else {
            trimmed
        }

        // 使用 Java URL 类验证格式
        return try {
            val parsed = URL(normalizedUrl)
            val host = parsed.host

            // 检查 host 是否为空
            if (host.isNullOrEmpty()) {
                return Pair(false, "地址格式不正确，请检查输入")
            }

            // 如果是 IP 地址，验证格式
            if (isIpAddress(host) && !isValidIp(host)) {
                return Pair(false, "IP 地址格式不正确（正确示例：192.168.1.100）")
            }

            // 如果指定了协议且不是 http/https
            val protocol = parsed.protocol
            if (protocol != "http" && protocol != "https") {
                return Pair(false, "协议只支持 http:// 或 https://")
            }

            // 检查端口号（如果在 URL 中有指定）
            if (parsed.port == 0) {
                return Pair(false, "端口号不正确")
            }

            return Pair(true, "")

        } catch (e: Exception) {
            return Pair(false, "地址格式不正确（${e.message}）")
        }
    }

    /**
     * 判断字符串是否是 IP 地址格式
     */
    private fun isIpAddress(host: String): Boolean {
        return Patterns.IP_ADDRESS.matcher(host).matches()
    }

    /**
     * 校验 IP 地址合法性（各段 0-255）
     */
    private fun isValidIp(ip: String): Boolean {
        return try {
            val parts = ip.split(".")
            if (parts.size != 4) return false
            parts.all {
                val num = it.toInt()
                num in 0..255
            }
        } catch (e: NumberFormatException) {
            false
        }
    }

    /**
     * 标准化 URL（补全协议、去除尾部斜杠）
     */
    fun normalize(url: String): String {
        val trimmed = url.trim()
        val withProtocol = if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            "http://$trimmed"
        } else {
            trimmed
        }
        return withProtocol.trimEnd('/')
    }
}