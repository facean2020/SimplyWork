package com.yangz3720.simplywork.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageQueueManagerTest {

    @Test
    fun `drain preserves FIFO across message types`() {
        val queue = MessageQueueManager()
        queue.enqueue(IncomingMessage.UserPrompt("first"))
        queue.enqueue(IncomingMessage.Steer("steer!"))
        queue.enqueue(IncomingMessage.FollowUp("follow", "reason"))

        val drained = queue.drainAll()
        assertEquals(3, drained.size)
        assertTrue(drained[0] is IncomingMessage.UserPrompt)
        assertTrue(drained[1] is IncomingMessage.Steer)
        assertTrue(drained[2] is IncomingMessage.FollowUp)
        assertTrue(queue.isEmpty)
    }

    @Test
    fun `empty queue drains to empty list`() {
        val queue = MessageQueueManager()
        assertTrue(queue.isEmpty)
        assertTrue(queue.drainAll().isEmpty())
        assertTrue(queue.isEmpty)
    }

    @Test
    fun `clear drops everything`() {
        val queue = MessageQueueManager()
        queue.enqueue(IncomingMessage.UserPrompt("x"))
        queue.clear()
        assertTrue(queue.drainAll().isEmpty())
    }
}
