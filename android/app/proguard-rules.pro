# 添加 ProGuard 规则以保留 Hermes Studio Mobile 使用的类
# 当前没有需要特殊保留的类，保留此文件供后续扩展

# 保留 WebView JS 接口类
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# 保留 Kotlin 协程相关
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}