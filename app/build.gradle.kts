import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.Certificate
import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * OneDrive 接入的 Entra 应用注册 client id。**唯一填写点** —— 见
 * `docs/PlaningStages/onedrive-setup.md`。它驱动 `BuildConfig.MSAL_CLIENT_ID`，
 * 后者在运行时生成 MSAL 配置文件（`res/raw` 无法插值）。
 */
val msalClientId: String = (findProperty("msalClientId") as String?)
    ?.takeIf { it.isNotBlank() && !it.startsWith("REPLACE") }
    ?: "REPLACE-WITH-CLIENT-ID"

/**
 * 签名哈希（证书 SHA-1 的 base64）。MSAL Android 的回调地址是
 * `msauth://<包名>/<URL 编码后的这个值>` —— 不是 `msal<clientId>://auth`
 * （那是 MSAL.NET 的形式，在 Android 上属于 legacy）。
 *
 * 缺省直接从 debug keystore 现算，所以调试构建**不需要手工维护**它；release 或换签名
 * 时用 `-PmsalSignatureHash=…` 或在 gradle.properties 覆盖。注意：这是配置期读取的
 * 未声明输入，换了 keystore 后配置缓存可能仍用旧值，`--rerun-tasks` 可强制刷新。
 */
fun debugKeystoreSignatureHash(): String? {
    val keystore = File(System.getProperty("user.home"), ".android/debug.keystore")
    if (!keystore.isFile) return null
    val certificate: Certificate? = try {
        val store = KeyStore.getInstance("JKS")
        keystore.inputStream().use { stream -> store.load(stream, "android".toCharArray()) }
        store.getCertificate("androiddebugkey")
    } catch (e: Exception) {
        null
    }
    if (certificate == null) return null
    val digest = MessageDigest.getInstance("SHA-1").digest(certificate.encoded)
    return Base64.getEncoder().encodeToString(digest)
}

val msalSignatureHash: String = (findProperty("msalSignatureHash") as String?)
    ?.takeIf { it.isNotBlank() }
    ?: debugKeystoreSignatureHash()
    ?: "REPLACE-WITH-SIGNATURE-HASH"

android {
    namespace = "com.yangz3720.simplywork"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yangz3720.simplywork"
        minSdk = 34
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "MSAL_CLIENT_ID", "\"$msalClientId\"")
        buildConfigField("String", "MSAL_SIGNATURE_HASH", "\"$msalSignatureHash\"")
        // manifest 的 BrowserTabActivity path 用**未编码**的哈希（且要带前导 `/`）；
        // 配置文件里的 redirect_uri 才用 URL 编码后的。两者取值同源。
        manifestPlaceholders["msalSignatureHash"] = msalSignatureHash
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(11)
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    // 图标（compose BOM 管理的官方 Compose 家族工件，icons-core 已被传递缓存在本地）
    implementation("androidx.compose.material:material-icons-core")
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // KotlinX
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Ktor Client
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.logging)

    // AndroidX JavaScriptEngine
    implementation(libs.androidx.javascriptengine)

    // MSAL（OneDrive 个人账号 OAuth 2.0 登录）
    implementation(libs.msal)

    // Markdown rendering（用户授权的依赖例外：自写解析器换官方库方案）
    implementation(libs.mikepenz.markdown.renderer)
    implementation(libs.mikepenz.markdown.renderer.m3)

    // Unit Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    testImplementation(libs.ktor.client.mock)

    // Android Testing
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.turbine)

    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
