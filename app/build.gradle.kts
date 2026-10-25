import com.google.protobuf.gradle.id
import java.util.Properties

// 本机发布签名：keystore.properties 不入库（见 .gitignore），仓库里保留构建逻辑即可。
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.protobuf)
}

android {
    namespace = "com.teslalink.app"

    // JVM 单测里放行 android.util.Log（DebugLog 底层用它；桩默认抛"not mocked"）。
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    compileSdk = 35
    buildToolsVersion = "35.0.1"

    defaultConfig {
        applicationId = "com.teslalink.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "0.0.2"
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources.excludes += "META-INF/INDEX.LIST"
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                id("java") { option("lite") }
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.protobuf.javalite)
    implementation(libs.datastore.preferences)

    // 地图瓦片（仅「统计」页轨迹底图）：OSM/CARTO 栅瓦，无 API key，自带磁盘缓存。
    implementation("org.osmdroid:osmdroid-android:6.1.18")

    // 行车记录仪多视角连续播放（工具页）：ExoPlayer 播放列表 + 解码。
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    // SAF 文档树遍历（工具页选 U 盘）。
    implementation("androidx.documentfile:documentfile:1.0.1")

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    // JVM 单测里用真 org.json（Android 的 stub 在单测里"not mocked"）：
    // TripRecorder 的落盘/加载回归用例需要真实的 JSON 读写。
    testImplementation("org.json:json:20240303")
}
