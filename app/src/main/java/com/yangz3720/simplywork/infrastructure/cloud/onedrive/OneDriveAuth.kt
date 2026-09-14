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

/** 尚未在 `gradle.properties` 填 `msalClientId`。UI 据此给"未配置"提示而不是空白报错。 */
class OneDriveNotConfiguredException :
    IllegalStateException("未配置 OneDrive：请在 gradle.properties 填入 msalClientId（见 docs/PlaningStages/onedrive-setup.md）")

/** 静默取令牌时发现本地没有已登录账号（MSAL `no_current_account`）。 */
class OneDriveNotSignedInException : IllegalStateException("尚未连接 OneDrive 账号")

/** 用户在授权页取消（MSAL 走 onCancel 回调，不是异常码）。 */
class OneDriveSignInCancelled : RuntimeException("已取消 OneDrive 登录")

/**
 * MSAL 单账号登录封装（v1.4）。只负责"登录 / 静默续期 / 登出 / 当前账号"，
 * 不碰 Graph（那是 [OneDriveGraphClient]），也不碰同步语义。
 *
 * 单账号（`account_mode: SINGLE`）是刻意的：产品语义是"这个 App 连一个个人 OneDrive"，
 * 多账号只会让"项目绑到谁的盘"变得含糊。
 *
 * 全部 MSAL 调用都离线主线程（`getCurrentAccount` / `acquireTokenSilent` / `create*`
 * 都是阻塞或重 IO 的），只有交互式 [signIn] 必须回主线程 —— MSAL 要从 Activity 拉起
 * 授权页。
 *
 * **静默取令牌只能用"不带回调"的那个重载。** `acquireTokenSilent(parameters)` 是同步版，
 * 其内部 `acquireTokenSilentInternal` 开头就是
 * `if (params.getCallback() != null) throw IllegalArgumentException("Do not provide callback for synchronous methods")`；
 * 要回调就得改用 `acquireTokenSilentAsync(parameters)`。两个重载互斥，传错必崩，
 * 而且崩在每一次取令牌上 —— 浏览、拉取、推送会全部失败。
 */
class OneDriveAuth(private val context: Context) {

    private val _account = MutableStateFlow<OneDriveAccount?>(null)
    val account: StateFlow<OneDriveAccount?> = _account.asStateFlow()

    private val createLock = Mutex()

    @Volatile
    private var app: ISingleAccountPublicClientApplication? = null

    fun isConfigured(): Boolean = OneDriveMsalConfig.isConfigured()

    /**
     * 冷启动恢复：把 MSAL 缓存里的账号投影到 [account]。没有已登录账号时清空状态
     * （而不是保留陈旧值，P2）。
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

    /** 交互式登录（必须在主线程：MSAL 要从 [activity] 拉起授权页）。 */
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

    /** 登出：清 MSAL 缓存中的账号，但**不动**任何项目的绑定与本地文件。 */
    suspend fun signOut(): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) { application().signOut() }
        _account.value = null
    }

    /**
     * 取 access token 给 Graph 用。[forceRefresh] = true 时绕过缓存（401 后重试用）。
     * 无账号 / 续期失败一律抛异常 —— 静默降级会让同步写出一半就断（P2）。
     */
    suspend fun accessToken(forceRefresh: Boolean = false): String = withContext(Dispatchers.IO) {
        val client = application()
        val current = client.currentAccount.currentAccount ?: throw OneDriveNotSignedInException()
        client.acquireTokenSilent(
            AcquireTokenSilentParameters.Builder()
                .withScopes(OneDriveMsalConfig.SCOPES)
                .forAccount(current)
                // forAccount 只设账号不设 authority，而 MSAL 校验要求 authority 非空。
                .fromAuthority(current.authority)
                .forceRefresh(forceRefresh)
                .build()
        ).accessToken
    }

    /** 惰性建单账号应用：MSAL 的 create* 是阻塞调用，只在后台线程做一次。 */
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
