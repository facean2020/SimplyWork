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
 * Generates MSAL's single-account config file (v1.4).
 *
 * Why **generate at runtime** instead of shipping it in `res/raw`: both the client id and the
 * signature hash take part in the assembly, and Android resources do no string interpolation.
 * Funnelling every value through `BuildConfig` (sourced from `gradle.properties`'
 * `msalClientId` + the signature hash) means we never get the silent mismatch of
 * "edited the JSON, forgot to edit the manifest". MSAL exposes a
 * `createSingleAccountPublicClientApplication(context, File)` overload, so taking the config
 * from an arbitrary file is an officially supported path.
 */
object OneDriveMsalConfig {

    /**
     * Delegated permissions (personal accounts only support Delegated). Deliberately without
     * `openid`/`profile`/`offline_access`: those three are reserved scopes that MSAL fills in
     * automatically and uses to issue the refresh token, so passing them explicitly is
     * redundant. `Files.ReadWrite` also covers "files shared with me" on personal accounts.
     */
    val SCOPES = listOf("User.Read", "Files.ReadWrite")

    /** The unset sentinel prefix (kept consistent with `app/build.gradle.kts`). */
    private const val PLACEHOLDER_PREFIX = "REPLACE"

    fun isConfigured(): Boolean = !BuildConfig.MSAL_CLIENT_ID.startsWith(PLACEHOLDER_PREFIX)

    /**
     * MSAL **Android**'s redirect URI format is `msauth://<package name>/<URL-encoded signature hash>`.
     * Note it is not `msal<clientId>://auth` -- that is MSAL.NET's form, legacy in this library
     * (`PublicClientApplicationConfiguration` logs a legacy warning for it), and it disagrees with
     * this library's signature-check / self-check tooling (`showExpectedMsalRedirectUriInfo`).
     *
     * This string has to match the one registered in the Entra app registration **character for
     * character**, otherwise the server replies with
     * `AADSTS50011: The reply URL specified in the request does not match...`.
     * The hash is encoded with `URLEncoder`: the `/` `+` `=` in base64 become `%2F` `%2B` `%3D`.
     */
    fun redirectUri(): String =
        "msauth://${BuildConfig.APPLICATION_ID}/${URLEncoder.encode(BuildConfig.MSAL_SIGNATURE_HASH, "UTF-8")}"

    /** Writes into `cacheDir/msal/` and returns the file; skips the rewrite when the content is unchanged (avoids pointless I/O). */
    fun writeConfigFile(context: Context): File {
        val content = buildJsonObject {
            put("client_id", BuildConfig.MSAL_CLIENT_ID)
            put("redirect_uri", redirectUri())
            put("account_mode", "SINGLE")
            // WEBVIEW: the callback is intercepted in-process by MSAL's own
            // AuthorizationActivity instead of going through the system browser -- so
            // browser_safelist is not needed (and it cannot fail just because the device has
            // no Chrome/Firefox), and it sidesteps the broker's signature hash check too.
            // Note that when `authorization_user_agent != WEBVIEW` MSAL insists on a non-empty
            // browser_safelist, so switching this to DEFAULT/BROWSER drags a config change with it.
            put("authorization_user_agent", "WEBVIEW")
            // The broker must be explicitly switched off: MSAL's built-in default config has
            // broker_redirect_uri_registered = true, and the config is merged field by field,
            // so not writing false inherits true and sends on-device auth down the broker
            // branch that demands a matching signature hash (which personal accounts never needed).
            put("use_broker", false)
            put("broker_redirect_uri_registered", false)
            put(
                "authorities",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "AAD")
                            // The personal-account-only endpoint (login.microsoftonline.com/consumers).
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
