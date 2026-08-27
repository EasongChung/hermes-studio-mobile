package com.hermes.mobile

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.URLUtil

/**
 * DownloadManagerHelper - 文件下载辅助工具
 *
 * 【是什么】
 * 封装 Android DownloadManager 的下载请求，供 WebView 的 DownloadListener 使用。
 * 用户点击 WebView 中的下载链接时，由系统 DownloadManager 接管下载过程，
 * 在通知栏显示下载进度，完成后可点击打开。
 *
 * 【为什么需要】
 * WebView 默认点击下载链接会静默下载到系统 Downloads 目录，但用户无视觉反馈。
 * 通过 DownloadManager 显示通知栏进度和完成提醒，提升文件下载体验。
 * 不修改前端代码，不依赖 WebView 自身行为，纯 Android 原生层实现。
 *
 * 【安全边界】
 * 只处理 URL 合法性校验，不拦截、不修改下载内容。
 * 使用系统 DownloadManager，其沙箱隔离和权限模型由系统保障。
 */
object DownloadManagerHelper {

    private const val TAG = "HermesDownload"

    /**
     * 将文件下载任务加入系统 DownloadManager 队列。
     *
     * @param context 上下文（用于获取 DownloadManager 系统服务）
     * @param url 下载链接
     * @param userAgent 用户代理字符串（可选，由 WebView 提供）
     * @param contentDisposition HTTP Content-Disposition 头（可选，用于推断文件名）
     * @param mimeType 文件 MIME 类型（可选）
     */
    fun enqueueDownload(
        context: Context,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        try {
            // 校验 URL 合法性
            val uri = Uri.parse(url)
            if (uri.scheme.isNullOrBlank() || uri.host.isNullOrBlank()) {
                Log.w(TAG, "Invalid download URL: $url")
                return
            }

            // 从 URL 或 Content-Disposition 推断文件名
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                .ifBlank { "download_${System.currentTimeMillis()}" }

            val request = DownloadManager.Request(uri).apply {
                // 设置用户代理（部分 Server 依赖此头判断客户端类型）
                if (!userAgent.isNullOrBlank()) {
                    // 【为什么用 addRequestHeader】DownloadManager.Request 没有公开的
                    // setUserAgent(String) 方法，直接调用会在编译期报 unresolved reference
                    // （run #52/#53 失败根因）。官方等价做法是添加 "User-Agent" 请求头，
                    // DownloadManager 实际发起下载时会携带该头，效果与 setUserAgent 一致。
                    addRequestHeader("User-Agent", userAgent)
                }
                // 设置 MIME 类型（帮助系统选择打开方式）
                if (!mimeType.isNullOrBlank()) {
                    setMimeType(mimeType)
                }
                // 通知栏可见：下载中 + 完成后通知
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                // 通知栏显示标题
                setTitle(fileName)
                // 通知栏描述
                setDescription("正在下载文件...")
                // 允许移动数据网络下下载
                setAllowedOverMetered(true)
                // 允许在漫游时下载
                setAllowedOverRoaming(false)
            }

            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val downloadId = manager.enqueue(request)
            Log.d(TAG, "Download queued: id=$downloadId fileName=$fileName url=$url")
        } catch (e: SecurityException) {
            // DownloadManager 权限不足（极少数定制 ROM）
            Log.e(TAG, "Download failed: SecurityException ${e.message}")
        } catch (e: IllegalArgumentException) {
            // URL 格式异常
            Log.e(TAG, "Download failed: IllegalArgumentException ${e.message}")
        } catch (e: Exception) {
            // 兜底：任何异常不阻塞 WebView 正常流程
            Log.e(TAG, "Download failed to queue: ${e.message}")
        }
    }
}