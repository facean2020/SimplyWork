package com.yangz3720.simplywork.infrastructure.cloud.onedrive

import android.app.Activity
import android.content.Context
import com.microsoft.identity.client.AcquireTokenSilentParameters
import com.microsoft.identity.client.AuthenticationCallback
import com.microsoft.identity.client.IAuthenticationResult
import com.microsoft.identity.client.ISingleAccountPublicClientApplication
import com.microsoft.identity.client.PublicClientApplication
import com.microsoft.identity.client.SignInParameters
import com.microsoft.identity.client.exception.MsalException
import com.yangz3720.simplywork.core.cloud.OneDriveAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** `msalClientId` has not been filled in `gradle.properties` yet. The UI uses this to show a "not configured" hint instead of a blank error. */
class OneDriveNotConfiguredException :
    IllegalStateException("未配置 OneDrive：请在 gradle.properties 填入 msalClientId（见 docs/PlaningStages/onedrive-setup.md）")

/** Silent token acquisition found no signed-in account locally (MSAL `no_current_account`). */
class OneDriveNotSignedInException : IllegalStateException("尚未连接 OneDrive 账号")

/** The user cancelled on the authorisation page (MSAL goes through the onCancel callback, not an error code). */
class OneDriveSignInCancelled : RuntimeException("已取消 OneDrive 登录")

/**
 * MSAL single-account sign-in wrapper (v1.4). It only handles "sign in / silent renewal / sign out / current account",
 * touching neither Graph (that is [OneDriveGraphClient]) nor sync semantics.
 *
 * Single account (`account_mode: SINGLE`) is deliberate: the product semantics are "this app connects to one personal OneDrive",
 * and multiple accounts would only make "whose drive the project is bound to" ambiguous.
 *
 * Every MSAL call stays off the main thread (`getCurrentAccount` / `acquireTokenSilent` / `create*`
 * are blocking or heavy IO); only the interactive [signIn] must go back to the main thread — MSAL has to launch the
 * authorisation page from an Activity.
 *
 * **Silent token acquisition may only use the overload without a callback.** `acquireTokenSilent(parameters)` is the synchronous variant,
 * and its internal `acquireTokenSilentInternal` starts with
 * `if (params.getCallback() != null) throw IllegalArgumentException("Do not provide callback for synchronous methods")`;
 * passing a callback means switching to `acquireTokenSilentAsync(parameters)`. The two overloads are mutually exclusive, and passing the wrong one always crashes,
 * and it crashes on every single token acquisition — browsing, pulling and pushing would all fail.
 */
class OneDriveAuth(private val context: Context) {

    private val _account = MutableStateFlow<OneDriveAccount?>(null)
    val account: StateFlow<OneDriveAccount?> = _account.asStateFlow()

    private val createLock = Mutex()

    @Volatile
    private var app: ISingleAccountPublicClientApplication? = null

    fun isConfigured(): Boolean = OneDriveMsalConfig.isConfigured()

    /**
     * Cold-start restore: project the account from the MSAL cache into [account]. When there is no signed-in account, clear the state
     * (rather than keeping a stale value, P2).
     */
    suspend fun restore(): OneDriveAccount? {
        if (!isConfigured()) {
            _account.value = null
            return null
        }
        val restored = runCatching {
            withContext(Dispatchers.IO) { application().currentAccount.currentAccount }
        }.getOrNull()?.let { OneDriveAccount(it.id, it.username.orEmpty()) }
        _account.value = restored
        return restored
    }

    /** Interactive sign-in (must be on the main thread: MSAL has to launch the authorisation page from [activity]). */
    suspend fun signIn(activity: Activity): Result<OneDriveAccount> = withContext(Dispatchers.Main) {
        if (!isConfigured()) return@withContext Result.failure(OneDriveNotConfiguredException())
        val client = runCatching { application() }
            .getOrElse { return@withContext Result.failure(it) }
        suspendCancellableCoroutine { continuation ->
            client.signIn(
                SignInParameters.builder()
                    .withActivity(activity)
                    .withScopes(OneDriveMsalConfig.SCOPES)
                    .withCallback(
                        object : AuthenticationCallback {
                            override fun onSuccess(result: IAuthenticationResult) {
                                val signed = OneDriveAccount(result.account.id, result.account.username.orEmpty())
                                _account.value = signed
                                if (continuation.isActive) continuation.resume(Result.success(signed))
                            }

                            override fun onError(exception: MsalException) {
                                if (continuation.isActive) continuation.resume(Result.failure(exception))
                            }

                            override fun onCancel() {
                                if (continuation.isActive) continuation.resume(Result.failure(OneDriveSignInCancelled()))
                            }
                        }
                    )
                    .build()
            )
        }
    }

    /** Sign out: clear the account in the MSAL cache, but **leave untouched** every project binding and local file. */
    suspend fun signOut(): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) { application().signOut() }
        _account.value = null
    }

    /**
     * Get an access token for Graph. [forceRefresh] = true bypasses the cache (used for the retry after a 401).
     * No account / renewal failure always throws — a silent degradation would let a sync write half of it and then break (P2).
     */
    suspend fun accessToken(forceRefresh: Boolean = false): String = withContext(Dispatchers.IO) {
        val client = application()
        val current = client.currentAccount.currentAccount ?: throw OneDriveNotSignedInException()
        client.acquireTokenSilent(
            AcquireTokenSilentParameters.Builder()
                .withScopes(OneDriveMsalConfig.SCOPES)
                .forAccount(current)
                // forAccount sets only the account and not the authority, while MSAL validation requires a non-null authority.
                .fromAuthority(current.authority)
                .forceRefresh(forceRefresh)
                .build()
        ).accessToken
    }

    /** Lazily create the single-account application: MSAL's create* is a blocking call, done once on a background thread only. */
    private suspend fun application(): ISingleAccountPublicClientApplication {
        app?.let { return it }
        return createLock.withLock {
            app?.let { return it }
            val created = withContext(Dispatchers.IO) {
                PublicClientApplication.createSingleAccountPublicClientApplication(
                    context,
                    OneDriveMsalConfig.writeConfigFile(context)
                )
            }
            app = created
            created
        }
    }
}
