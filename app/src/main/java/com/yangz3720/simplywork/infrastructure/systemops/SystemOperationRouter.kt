package com.yangz3720.simplywork.infrastructure.systemops

import com.yangz3720.simplywork.infrastructure.error.InfrastructureException
import com.yangz3720.simplywork.infrastructure.systemops.intent.SystemIntentExecutor
import com.yangz3720.simplywork.infrastructure.systemops.model.AppOpenSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.CalendarInsertSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.ClipboardReadSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.ClipboardWriteSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.DeepLinkSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.NotificationPostSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.OperationSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.ShareTextSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.SystemOperationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeout
import kotlin.time.TimeSource

/**
 * The ONLY facade the (future) tool-core talks to: "select path → execute → normalize result".
 *
 * v1.1: the fallback matrix collapsed to the single Intent path, so the router's remaining
 * responsibilities are unified timing (P5), exception normalization (§4.3: path impls may
 * throw, but tool-core only ever sees [SystemOperationResult]) and probe pass-through.
 *
 * Timeout semantics: the caller is guaranteed a result within [execute]'s timeout. A binder
 * call already in flight cannot be aborted mid-call; if it completes after the timeout its
 * result is discarded.
 */
interface SystemOperationRouter {

    suspend fun execute(
        spec: OperationSpec,
        timeoutMs: Long = DEFAULT_OP_TIMEOUT_MS
    ): SystemOperationResult

    fun capabilities(): SystemCapabilities

    fun observeCapabilities(): Flow<SystemCapabilities>

    companion object { const val DEFAULT_OP_TIMEOUT_MS = 10_000L }
}

class SystemOperationRouterImpl(
    private val executor: SystemIntentExecutor,
    private val probe: SystemCapabilityProbe
) : SystemOperationRouter {

    override suspend fun execute(
        spec: OperationSpec,
        timeoutMs: Long
    ): SystemOperationResult {
        val started = TimeSource.Monotonic.markNow()
        return try {
            val result = withTimeout(timeoutMs) { dispatch(spec) }
            // Stamp centralized wall time (P5 observability).
            if (result is SystemOperationResult.Success) {
                result.copy(executionTimeMs = started.elapsedNow().inWholeMilliseconds)
            } else {
                result
            }
        } catch (e: TimeoutCancellationException) {
            SystemOperationResult.Timeout(timeoutMs, started.elapsedNow().inWholeMilliseconds)
        } catch (e: CancellationException) {
            throw e // outer cancellation must propagate untouched
        } catch (e: InfrastructureException.SystemOperation) {
            SystemOperationResult.Failed(e.message, e.cause?.message)
        } catch (e: Exception) {
            SystemOperationResult.Failed(
                "Unhandled failure in ${spec::class.simpleName ?: "OperationSpec"}: ${e.message}",
                e::class.simpleName
            )
        }
    }

    private suspend fun dispatch(spec: OperationSpec): SystemOperationResult = when (spec) {
        is DeepLinkSpec -> executor.launchDeepLink(spec.uri, spec.launchContext)
        is AppOpenSpec -> executor.openApp(spec.packageName)
        is ClipboardReadSpec -> executor.readClipboard()
        is ClipboardWriteSpec -> executor.writeClipboard(spec.label, spec.text)
        is CalendarInsertSpec -> executor.insertCalendarEvent(spec)
        is NotificationPostSpec -> executor.postNotification(spec)
        is ShareTextSpec -> executor.shareText(spec.title, spec.content)
    }

    override fun capabilities(): SystemCapabilities = probe.snapshot()

    override fun observeCapabilities(): Flow<SystemCapabilities> = probe.observe()
}
