package com.yangz3720.simplywork.infrastructure.cloud.onedrive

import com.yangz3720.simplywork.BuildConfig
import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MSAL **Android** 的回调地址格式（v1.4）。这层必须有测试，因为它错了只在真机登录时
 * 才暴露、且报的是服务器侧的 `AADSTS50011`，看起来完全不像本地 bug。
 *
 * 具体踩过的坑：本项目最初写成 `msal<clientId>://auth`（MSAL.NET 的形式），
 * MSAL Android 用的是 `msauth://<包名>/<URL 编码的签名哈希>`。
 *
 * 断言刻意与**本机 keystore 无关**（哈希随机器变），只锁死"结构 + 编码"这两件
 * 会写错的事。
 */
class OneDriveMsalConfigTest {

    @Test
    fun `redirect uri uses the MSAL Android msauth form, not the MSAL net msal form`() {
        val uri = OneDriveMsalConfig.redirectUri()

        assertTrue(
            "MSAL Android 的回调必须是 msauth://<包名>/…，实际为 $uri",
            uri.startsWith("msauth://${BuildConfig.APPLICATION_ID}/")
        )
        assertFalse("msal<clientId>://auth 是 MSAL.NET 的形式，Android 上属 legacy", uri.startsWith("msal"))
    }

    @Test
    fun `redirect uri carries the signature hash URL encoded`() {
        val uri = OneDriveMsalConfig.redirectUri()
        val encoded = uri.removePrefix("msauth://${BuildConfig.APPLICATION_ID}/")

        // base64 里的 `/` `+` `=` 必须转义成 %2F %2B %3D，否则会被当成 URI 结构字符
        // （hash 以 `/` 开头时会凭空多出一段路径）。
        assertFalse("签名哈希必须 URL 编码，实际为 $encoded", encoded.contains('/'))
        assertFalse(encoded.contains('+'))
        assertFalse(encoded.contains('='))

        // 解码回来必须与原哈希逐字符相同 —— 这条同时覆盖了"以 / 开头"的 keystore。
        assertEquals(BuildConfig.MSAL_SIGNATURE_HASH, URLDecoder.decode(encoded, "UTF-8"))
    }

    @Test
    fun `placeholder client id is reported as not configured`() {
        // 本仓库已填真实 client id；这条断言的是哨兵前缀的语义不被改坏。
        assertEquals(
            !BuildConfig.MSAL_CLIENT_ID.startsWith("REPLACE"),
            OneDriveMsalConfig.isConfigured()
        )
    }

    @Test
    fun `scopes never include the reserved identity scopes`() {
        // openid/profile/offline_access 由 MSAL 自行补齐，显式传入会与它冲突。
        assertEquals(listOf("User.Read", "Files.ReadWrite"), OneDriveMsalConfig.SCOPES)
    }
}
