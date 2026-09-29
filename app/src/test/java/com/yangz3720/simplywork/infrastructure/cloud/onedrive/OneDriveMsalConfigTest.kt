package com.yangz3720.simplywork.infrastructure.cloud.onedrive

import com.yangz3720.simplywork.BuildConfig
import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MSAL **Android**'s redirect URI format (v1.4). This layer has to be tested, because getting it
 * wrong only shows up on an on-device sign-in, and what it reports is a server-side `AADSTS50011`
 * that looks nothing like a local bug.
 *
 * The pothole actually hit: this project originally wrote `msal<clientId>://auth` (the MSAL.NET
 * form), whereas MSAL Android uses `msauth://<package name>/<URL-encoded signature hash>`.
 *
 * The assertions are deliberately **independent of this machine's keystore** (the hash varies by
 * machine) and lock down only the two things that get written wrong: structure + encoding.
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

        // the `/` `+` `=` of base64 must be escaped to %2F %2B %3D, else they are taken for URI structure characters
        // (a hash starting with `/` would conjure an extra path segment out of nowhere).
        assertFalse("签名哈希必须 URL 编码，实际为 $encoded", encoded.contains('/'))
        assertFalse(encoded.contains('+'))
        assertFalse(encoded.contains('='))

        // decoding must give back the original hash character for character -- this also covers the keystore whose hash "starts with /".
        assertEquals(BuildConfig.MSAL_SIGNATURE_HASH, URLDecoder.decode(encoded, "UTF-8"))
    }

    @Test
    fun `placeholder client id is reported as not configured`() {
        // this repo already has a real client id filled in; the assertion pins the sentinel prefix's semantics so they cannot be broken.
        assertEquals(
            !BuildConfig.MSAL_CLIENT_ID.startsWith("REPLACE"),
            OneDriveMsalConfig.isConfigured()
        )
    }

    @Test
    fun `scopes never include the reserved identity scopes`() {
        // openid/profile/offline_access are filled in by MSAL itself; passing them explicitly conflicts with it.
        assertEquals(listOf("User.Read", "Files.ReadWrite"), OneDriveMsalConfig.SCOPES)
    }
}
