plugins {
    id("com.android.application")
}

// 签名口令只从环境变量读（见工作区 docs/KEYSTORE.md，用 wskeys run 注入）
val envKeystore = System.getenv("B50_KEYSTORE")
val envStorePass = System.getenv("B50_STORE_PASS")
val envKeyAlias = System.getenv("B50_ALIAS") ?: "KeySigned_By_Rinkyu"
val envKeyPass = System.getenv("B50_KEY_PASS")

android {
    namespace = "me.yun.usagipass"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "me.yun.usagipass"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "0.6.0"
        manifestPlaceholders["appLabel"] = "UsagiPass (LSP)"
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (envKeystore != null && envStorePass != null && envKeyPass != null) {
            create("release") {
                storeFile = file(envKeystore)
                storePassword = envStorePass
                keyAlias = envKeyAlias
                keyPassword = envKeyPass
            }
        }
    }

    buildTypes {
        getByName("debug") {
            // 开发版：有设置界面（仅日志开关）、有桌面图标
            manifestPlaceholders["appLabel"] = "UsagiPass (LSP) DEV"
        }
        getByName("release") {
            // 发行版：无设置界面、无桌面入口、无开关，全部行为写死
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        // libxposed 的模块元信息在 resources/META-INF/xposed/ 下，必须放行
        resources { merges += "META-INF/xposed/*" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// libxposed api 的 Gradle 元数据会把 kotlin-stdlib 带进来（2.4 MB / 1084 类），本模块纯 Java 用不到。
// 只从**应用自身的** runtime/compile classpath 里排除；不能用全局 exclude，
// 那会把 AGP 自己的 lint worker 依赖一起排掉（release 会挂在 kotlin/enums/EnumEntriesKt）。
configurations.configureEach {
    val n = name
    if (n.endsWith("RuntimeClasspath") || n.endsWith("CompileClasspath")) {
        exclude(group = "org.jetbrains.kotlin")
    }
}
dependencies {
    compileOnly("io.github.libxposed:api:102.0.0") { isTransitive = false }
}
