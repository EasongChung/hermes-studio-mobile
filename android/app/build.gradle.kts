// 模块级 build.gradle.kts
// Android APP 模块配置：SDK 版本、依赖、构建类型
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hermes.mobile"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hermes.mobile"
        // minSdk 24 = Android 7.0，覆盖绝大多数设备
        minSdk = 24
        targetSdk = 34
        // versionCode 在 CI 中自动递增（GITHUB_RUN_NUMBER），本地编译默认为 1
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "0.1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // ===== 签名配置 =====
    // 从环境变量读取签名信息（CI 中由 GitHub Secrets 传入）
    // 如果环境变量缺失，则跳过签名（仅构建 unsigned APK）
    val keystorePath = System.getenv("KEYSTORE_PATH") ?: ""
    val keystorePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
    val envKeyAlias = System.getenv("KEY_ALIAS") ?: ""
    val envKeyPassword = System.getenv("KEY_PASSWORD") ?: ""

    val hasSigningConfig = keystorePath.isNotBlank() && keystorePassword.isNotBlank() &&
            envKeyAlias.isNotBlank() && envKeyPassword.isNotBlank()

    signingConfigs {
        create("release") {
            if (hasSigningConfig) {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                keyAlias = envKeyAlias
                keyPassword = envKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // 开启混淆，减小 APK 体积
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 如果有签名配置，Release 使用签名
            if (hasSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            // Debug 默认使用 Android 调试签名
            isMinifyEnabled = false
        }
    }

    // ===== 前端资源：直接引用仓库根 dist/client（不拷贝进 android 源码树） =====
    // 【是什么】把 upstream 前端构建产物 dist/client 注册为 main assets 源目录，
    //           Gradle 打包时将其内容直接合并进 APK assets 根目录（assets/index.html …）。
    // 【为什么】同步上游后只需 npm run build + 构建 APK，即可完成 APP 内置前端升级；
    //           不再需要 CI / 本地把 dist 拷贝到 android/app/src/main/assets/hermes，
    //           既消除「忘拷贝导致内置版本陈旧」，源码树也不再出现前端副本。
    // 【配套】MainActivity.ASSETS_FRONTEND_PATH 已置空：拦截器把请求路径直接映射到 assets 根；
    //           CI（build-apk.yml）也已移除拷贝步骤，仅保留 dist/client/index.html 存在性校验。
    sourceSets {
        getByName("main") {
            assets.srcDir(rootProject.file("../dist/client"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // AndroidX 核心库
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    // Material Design 3 组件
    implementation("com.google.android.material:material:1.11.0")
    // RecyclerView（服务器列表管理）
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // WebView 相关（AndroidX WebKit）
    implementation("androidx.webkit:webkit:1.9.0")
}