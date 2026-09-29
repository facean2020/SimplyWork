package com.yangz3720.simplywork.core.agent

import com.yangz3720.simplywork.core.agent.tool.AgentToolRegistry
import com.yangz3720.simplywork.core.agent.tool.RegisteredTool
import com.yangz3720.simplywork.infrastructure.model.model.ToolDefinition
import com.yangz3720.simplywork.infrastructure.systemops.model.DangerLevel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditGatePipelineTest {

    private fun tool(
        name: String,
        danger: DangerLevel,
        parseOk: Boolean = true,
        instanceDanger: ((Any?) -> DangerLevel)? = null,
        destructive: Boolean = false
    ) = RegisteredTool(
        definition = ToolDefinition(name = name, description = "", parametersSchema = buildJsonObject { put("type", "object") }),
        dangerLevel = danger,
        // Requires field "x" — mirrors required-field validation in real parsers.
        parser = { raw ->
            raw["x"]?.let { if (parseOk) Result.success(it) else Result.failure(IllegalArgumentException("bad input")) }
                ?: Result.failure(IllegalArgumentException("missing required field x"))
        },
        runner = { _, _ -> buildJsonObject { put("ok", true) } },
        instanceDangerLevel = instanceDanger,
        destructive = destructive
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

    // ----- auto-approve (v1.1): only irreversible calls are left to a human -----

    @Test
    fun `A6 auto-approve passes non-destructive confirmations without asking`() = runTest {
        val registry = AgentToolRegistry().apply {
            register(tool("location", DangerLevel.USER_CONFIRMING))
            register(tool("open_app", DangerLevel.USER_CONFIRMING))
        }
        var asked = 0
        val gate = AuditGatePipeline(
            registry,
            approvalHandler = AuditApprovalHandler { _, _, _ -> asked++; true },
            autoApproveNonDestructive = true
        )

        assertTrue(gate.preToolAudit(call("location")) is AuditGatePipeline.AuditDecision.Approved)
        assertTrue(gate.preToolAudit(call("open_app")) is AuditGatePipeline.AuditDecision.Approved)
        // the point: not "answered yes automatically" but **never asked at all** -- else the dialog would still flash
        assertEquals(0, asked)
    }

    @Test
    fun `A7 auto-approve still consults the handler for destructive tools`() = runTest {
        val registry = AgentToolRegistry().apply {
            register(tool("fs_delete", DangerLevel.USER_CONFIRMING, destructive = true))
        }
        var asked = 0
        val gate = AuditGatePipeline(
            registry,
            approvalHandler = AuditApprovalHandler { _, _, _ -> asked++; false },
            autoApproveNonDestructive = true
        )

        val decision = gate.preToolAudit(call("fs_delete"))
        // a delete confirmation a toggle can switch off is no confirmation at all: destructive has nothing to do with autoApprove.
        assertEquals(1, asked)
        assertTrue(decision is AuditGatePipeline.AuditDecision.Denied)
    }

    @Test
    fun `A8 auto-approve off asks for every confirmation including destructive ones`() = runTest {
        val registry = AgentToolRegistry().apply {
            register(tool("location", DangerLevel.USER_CONFIRMING))
            register(tool("fs_delete", DangerLevel.USER_CONFIRMING, destructive = true))
        }
        var asked = 0
        val gate = AuditGatePipeline(
            registry,
            approvalHandler = AuditApprovalHandler { _, _, _ -> asked++; true },
            autoApproveNonDestructive = false
        )

        assertTrue(gate.preToolAudit(call("location")) is AuditGatePipeline.AuditDecision.Approved)
        assertTrue(gate.preToolAudit(call("fs_delete")) is AuditGatePipeline.AuditDecision.Approved)
        assertEquals(2, asked)
    }

    @Test
    fun `A9 auto-approve keeps instance refinement and safe levels intact`() = runTest {
        val registry = AgentToolRegistry().apply {
            register(tool("safe", DangerLevel.SAFE))
            register(
                tool("calendar", DangerLevel.USER_VISIBLE, instanceDanger = { parsed ->
                    if ((parsed as? kotlinx.serialization.json.JsonElement).toString().contains("DIRECT_WRITE")) {
                        DangerLevel.USER_CONFIRMING
                    } else {
                        DangerLevel.USER_VISIBLE
                    }
                })
            )
        }
        var asked = 0
        val gate = AuditGatePipeline(
            registry,
            approvalHandler = AuditApprovalHandler { _, _, _ -> asked++; true },
            autoApproveNonDestructive = true
        )

        assertTrue(gate.preToolAudit(call("safe")) is AuditGatePipeline.AuditDecision.Approved)
        assertTrue(
            gate.preToolAudit(call("calendar", """{"x":"EDITOR_INTENT"}"""))
                is AuditGatePipeline.AuditDecision.Approved
        )
        // elevated to USER_CONFIRMING but irreversible -> auto-approved, still without asking
        assertTrue(
            gate.preToolAudit(call("calendar", """{"x":"DIRECT_WRITE"}"""))
                is AuditGatePipeline.AuditDecision.Approved
        )
        assertEquals(0, asked)
    }

    @Test
    fun `post-tool gate keeps oversized results valid JSON and declares the cut`() {
        val gate = AuditGatePipeline(
            AgentToolRegistry(),
            sanitizer = { it.uppercase() },
            maxToolResultChars = 10
        )
        assertEquals("""{"A":1}""", gate.postToolProcess(buildJsonObject { put("a", 1) }))

        // S6: a byte-cut would hand the model invalid JSON. The JSON-aware path must
        // produce something that still parses, with the cut declared inside the payload.
        val long = buildJsonObject { put("k", "y".repeat(50)) }
        val out = gate.postToolProcess(long)
        assertTrue(out.length <= 10)
        val element = Json.parseToJsonElement(out)
        assertTrue(element.jsonPrimitive.content.endsWith("…[cut]"))

        // An oversized nested array keeps a leading prefix plus a note item instead of
        // losing parseability — the shape a busy sys_calendar_query result has.
        val big = buildJsonObject {
            put("count", 40)
            put("events", buildJsonArray {
                repeat(40) { n -> add(buildJsonObject { put("i", n); put("t", "event $n") }) }
            })
        }
        val arrayGate = AuditGatePipeline(AgentToolRegistry(), maxToolResultChars = 300)
        val arrayOut = arrayGate.postToolProcess(big)
        val parsed = Json.parseToJsonElement(arrayOut).jsonObject
        val events = parsed["events"]!!.jsonArray
        assertTrue(events.size < 40)
        assertTrue(events.last().jsonObject.containsKey("_truncated"))
    }

    @Test
    fun `post-tool gate falls back to byte-cut when sanitizer breaks the JSON`() {
        val mangler = AuditGatePipeline(
            AgentToolRegistry(),
            sanitizer = { it.repeat(3) },
            maxToolResultChars = 20
        )
        val out = mangler.postToolProcess(buildJsonObject { put("a", 1) })
        assertTrue(out.endsWith("[truncated 1 chars]"))
    }
}
