package com.yangz3720.simplywork.core.agent

import com.yangz3720.simplywork.infrastructure.model.model.Attachment
import kotlinx.coroutines.channels.Channel

/**
 * An inbound message for the agent loop (agent-core-design.md §6 step ②).
 * Each variant maps to one persisted node type when drained:
 * - [UserPrompt] → UserPrompt node
 * - [Steer]      → Steer node
 * - [FollowUp]   → SystemPrompt(kind=follow_up) node
 */
sealed interface IncomingMessage {

    /** New user prompt (initial prompt of a run or a queued follow-up prompt from the user). */
    data class UserPrompt(val text: String, val attachments: List<Attachment> = emptyList()) : IncomingMessage

    /** Mid-run steering: the user overrides/refocuses the agent while it is working. */
    data class Steer(val text: String, val atLoop: Int = -1) : IncomingMessage

    /** System-generated re-injection (completion guard follow-up). */
    data class FollowUp(val text: String, val reason: String) : IncomingMessage
}

/**
 * Concurrent message queue feeding the loop: user prompts, mid-flight steering and
 * system follow-ups all enter here; the loop drains everything at the top of each
 * iteration (architecture.md "MessageQueueManager", user flow-chart node B).
 *
 * Channel.UNLIMITED + trySend: enqueue never blocks or drops; drain is non-suspending
 * so the loop can decide queue-empty transitions synchronously.
 */
class MessageQueueManager {

    private val channel = Channel<IncomingMessage>(Channel.UNLIMITED)

    fun enqueue(message: IncomingMessage) {
        channel.trySend(message)
    }

    /** Drains all currently queued messages in FIFO order. */
    fun drainAll(): List<IncomingMessage> {
        val drained = ArrayList<IncomingMessage>()
        while (true) {
            val result = channel.tryReceive()
            if (result.isSuccess) drained.add(result.getOrThrow()) else break
        }
        return drained
    }

    val isEmpty: Boolean get() = channel.isEmpty

    /** Testing/backpressure helper: drops everything queued. */
    fun clear() {
        drainAll()
    }
}
