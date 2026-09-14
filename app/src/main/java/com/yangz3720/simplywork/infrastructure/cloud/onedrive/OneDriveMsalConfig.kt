package com.yangz3720.simplywork.infrastructure.cloud.onedrive

import android.content.Context
import com.yangz3720.simplywork.BuildConfig
import java.io.File
import java.net.URLEncoder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 生成 MSAL 的单账号配置文件（v1.4）。
 *
 * 为什么是**运行时生成**而不是 `res/raw`：client id 与签名哈希都要参与拼装，而 Android
 * 资源不做字符串插值。把取值都收敛到 `BuildConfig`（来源 = `gradle.properties` 的
 * `msalClientId` + 签名哈希）派生出来，就不会出现"改了 JSON 忘了改 manifest"的静默失配。
 * MSAL 提供 `createSingleAccountPublicClientApplication(context, File)` 重载，
 * 配置来自任意文件是官方支持的路径。
 */
object OneDriveMsalConfig {

    /**
     * 委托权限（个人账号只支持 Delegated）。刻意不带 `openid`/`profile`/`offline_access`：
     * 这三个是保留作用域，MSAL 会自动补齐并据此下发 refresh token，显式传入反而多余。
     * `Files.ReadWrite` 在个人账号上同样覆盖"与自己共享的文件"。
     */
    val SCOPES = listOf("User.Read", "Files.ReadWrite")

    /** 未填的哨兵前缀（与 `app/build.gradle.kts` 保持一致）。 */
    private const val PLACEHOLDER_PREFIX = "REPLACE"

    fun isConfigured(): Boolean = !BuildConfig.MSAL_CLIENT_ID.startsWith(PLACEHOLDER_PREFIX)

    /**
     * MSAL **Android** 的回调地址格式是 `msauth://<包名>/<URL 编码后的签名哈希>`。
     * 注意不是 `msal<clientId>://auth` —— 那是 MSAL.NET 的形式，在本库里属 legacy
     * （`PublicClientApplicationConfiguration` 对此会打 legacy 警告），且与本库的
     * 签名校验/自检工具（`showExpectedMsalRedirectUriInfo`）不一致。
     *
     * 这个字符串必须与 Entra 应用注册里登记的**逐字符**相同，否则服务器会回
     * `AADSTS50011: The reply URL specified in the request does not match...`。
     * 哈希用 `URLEncoder` 编码：base64 里的 `/` `+` `=` 正好变成 `%2F` `%2B` `%3D`。
     */
    fun redirectUri(): String =
        "msauth://${BuildConfig.APPLICATION_ID}/${URLEncoder.encode(BuildConfig.MSAL_SIGNATURE_HASH, "UTF-8")}"

    /** 写入 `cacheDir/msal/` 并返回文件；内容不变时不重写（避免无谓 I/O）。 */
    fun writeConfigFile(context: Context): File {
        val content = buildJsonObject {
            put("client_id", BuildConfig.MSAL_CLIENT_ID)
            put("redirect_uri", redirectUri())
            put("account_mode", "SINGLE")
            // WEBVIEW：回调在进程内被 MSAL 自己的 AuthorizationActivity 截获，不经过
            // 系统浏览器 —— 于是不需要 browser_safelist（也就不会因为设备没装
            // Chrome/Firefox 而失败），也绕开 broker 的签名哈希校验。
            // 注意 `authorization_user_agent != WEBVIEW` 时 MSAL 会强制要求非空的
            // browser_safelist，所以这里改成 DEFAULT/BROWSER 会连带需要改配置。
            put("authorization_user_agent", "WEBVIEW")
            // 必须显式关闭 broker：MSAL 内置默认配置里 broker_redirect_uri_registered
            // = true，而配置是逐字段合并的，不写 false 就会继承 true，进而在真机上走
            // 要求签名哈希匹配的 broker 分支（个人账号本不需要）。
            put("use_broker", false)
            put("broker_redirect_uri_registered", false)
            put(
                "authorities",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "AAD")
                            // 个人账号专属 endpoint（login.microsoftonline.com/consumers）。
                            put("audience", buildJsonObject { put("type", "PersonalMicrosoftAccount") })
                            put("default", true)
                        }
                    )
                }
            )
        }.toString()

        val file = File(context.cacheDir, "msal/auth_config_single_account.json")
        file.parentFile?.mkdirs()
        if (!file.isFile || file.readText() != content) file.writeText(content)
        return file
    }
}
