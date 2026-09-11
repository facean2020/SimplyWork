package com.yangz3720.simplywork.core.agent

import com.yangz3720.simplywork.core.agent.tool.AgentToolRegistry
import com.yangz3720.simplywork.core.agent.tool.RegisteredTool
import com.yangz3720.simplywork.infrastructure.model.model.ToolDefinition
import com.yangz3720.simplywork.infrastructure.systemops.model.DangerLevel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditGatePipelineTest {

    private fun tool(
        name: String,
        danger: DangerLevel,
        parseOk: Boolean = true,
        instanceDanger: ((Any?) -> DangerLevel)? = null
    ) = RegisteredTool(
        definition = ToolDefinition(name = name, description = "", parametersSchema = buildJsonObject { put("type", "object") }),
        dangerLevel = danger,
        // Requires field "x" — mirrors required-field validation in real parsers.
        parser = { raw ->
            raw["x"]?.let { if (parseOk) Result.success(it) else Result.failure(IllegalArgumentException("bad input")) }
                ?: Result.failure(IllegalArgumentException("missing required field x"))
        },
        runner = { _, _ -> buildJsonObject { put("ok", true) } },
        instanceDangerLevel = instanceDanger
    )

    private fun call(name: String, argsJson: String = """{"x":1}""") =
        com.yangz3720.simplywork.infrastructure.model.model.ToolCall(id = "c1", name = name, argumentsJson = argsJson)

    @Test
    fun `A1 unknown tool is denied`() = runTest {
        val gate = AuditGatePipeline(AgentToolRegistry())
        val decision = gate.preToolAudit(call("nope"))
        assertTrue(decision is AuditGatePipeline.AuditDecision.Denied)
        assertTrue((decision as AuditGatePipeline.AuditDecision.Denied).reason.contains("Unknown tool"))
    }

    @Test
    fun `A2 unparseable arguments are denied`() = runTest {
        val registry = AgentToolRegistry().apply { register(tool("t1", DangerLevel.SAFE)) }
        val gate = AuditGatePipeline(registry)

        val badJson = gate.preToolAudit(call("t1", "{not-json"))
        assertTrue(badJson is AuditGatePipeline.AuditDecision.Denied)

        val parserFailure = gate.preToolAudit(call("t1", """{"oops":true}"""))
        assertTrue(parserFailure is AuditGatePipeline.AuditDecision.Denied)
        assertTrue((parserFailure as AuditGatePipeline.AuditDecision.Denied).reason.contains("Invalid arguments"))
    }

    @Test
    fun `A3 safe and user-visible levels pass without approval`() = runTest {
        val registry = AgentToolRegistry().apply {
            register(tool("safe", DangerLevel.SAFE))
            register(tool("visible", DangerLevel.USER_VISIBLE))
        }
        val gate = AuditGatePipeline(registry)

        assertTrue(gate.preToolAudit(call("safe")) is AuditGatePipeline.AuditDecision.Approved)
        assertTrue(gate.preToolAudit(call("visible")) is AuditGatePipeline.AuditDecision.Approved)
    }

    @Test
    fun `A4 user-confirming consults the approval handler`() = runTest {
        val registry = AgentToolRegistry().apply { register(tool("confirm", DangerLevel.USER_CONFIRMING)) }

        val approved = AuditGatePipeline(registry, approvalHandler = { _, _, _ -> true })
        assertTrue(approved.preToolAudit(call("confirm")) is AuditGatePipeline.AuditDecision.Approved)

        val declined = AuditGatePipeline(registry, approvalHandler = AuditApprovalHandler { _, _, _ -> false })
        val decision = declined.preToolAudit(call("confirm"))
        assertTrue(decision is AuditGatePipeline.AuditDecision.Denied)
        assertTrue((decision as AuditGatePipeline.AuditDecision.Denied).reason.contains("declined"))
    }

    @Test
    fun `A5 instance danger refinement elevates calendar direct-write`() = runTest {
        val registry = AgentToolRegistry().apply {
            register(tool("calendar", DangerLevel.USER_VISIBLE, instanceDanger = { parsed ->
                if ((parsed as? kotlinx.serialization.json.JsonElement).toString().contains("DIRECT_WRITE")) {
                    DangerLevel.USER_CONFIRMING
                } else {
                    DangerLevel.USER_VISIBLE
                }
            }))
        }
        // A DECLINING handler: only the elevated (USER_CONFIRMING) call needs approval,
        // so a declining gate denies exactly the DIRECT_WRITE variant.
        val declining = AuditGatePipeline(registry, approvalHandler = AuditApprovalHandler { _, _, _ -> false })

        val plain = declining.preToolAudit(call("calendar", """{"x":"EDITOR_INTENT"}"""))
        assertTrue(plain is AuditGatePipeline.AuditDecision.Approved)

        val direct = declining.preToolAudit(call("calendar", """{"x":"DIRECT_WRITE"}"""))
        assertTrue(direct is AuditGatePipeline.AuditDecision.Denied)
    }

    @Test
    fun `post-tool gate sanitizes and truncates with explicit marker`() {
        val gate = AuditGatePipeline(
            AgentToolRegistry(),
            sanitizer = { it.uppercase() },
            maxToolResultChars = 10
        )
        assertEquals("""{"A":1}""", gate.postToolProcess(buildJsonObject { put("a", 1) }))
        val long = buildJsonObject { put("k", "y".repeat(50)) }
        val out = gate.postToolProcess(long)
        // prefix(10) + explicit truncation marker with the exact dropped count
        assertTrue(out.startsWith("""{"K":"YYYY"""))
        assertTrue(out.endsWith("[truncated 48 chars]"))
    }
}
