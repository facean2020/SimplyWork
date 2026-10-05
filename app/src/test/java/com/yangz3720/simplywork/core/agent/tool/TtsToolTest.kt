package com.yangz3720.simplywork.core.agent.tool

import android.content.Context
import com.yangz3720.simplywork.core.session.ProjectStore
import com.yangz3720.simplywork.core.workspace.AgentWorkspace
import com.yangz3720.simplywork.infrastructure.systemops.model.DangerLevel
import com.yangz3720.simplywork.testsupport.InMemoryPrefs
import io.mockk.mockk
import java.io.File
import java.util.Locale
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `tts_tool`: argument validation, language parsing, and the sandbox boundary.
 *
 * Every case here stops **before** the Android TTS engine is touched -- real synthesis needs a device,
 * so what is pinned down is the part that decides whether to talk to the engine at all. The workspace,
 * on the other hand, is the real [AgentWorkspace] on a temp directory: its path-safety rules are
 * exactly what this tool depends on, so they should be exercised together rather than mocked away.
 */
class TtsToolTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // lazy: the @Rule folder only exists once JUnit has run the rule, i.e. after field initialization.
    private val workspace by lazy { AgentWorkspace(File(tmp.root, "ws")) }
    private val projects by lazy { ProjectStore(InMemoryPrefs()) }
    private val tool by lazy { TtsTool.registeredTool(mockk<Context>(relaxed = true), workspace, projects) }

    private fun context() = ToolExecutionContext(sessionId = "s1")

    private fun parse(raw: JsonObject) = tool.parse(raw).getOrThrow()

    private fun args(
        inputPath: String,
        language: String = "english",
        outputPath: String = "out/audio.wav",
        projectId: String? = null
    ): JsonObject = buildJsonObject {
        projectId?.let { put("projectId", it) }
        put("input_path", inputPath)
        put("output_path", outputPath)
        put("language", language)
    }

    /** Puts a text file in the current project's sandbox, the way fs_write would. */
    private fun textFile(path: String, content: String) =
        workspace.write(projects.currentId(), path, content)

    private fun reasonOf(result: JsonObject): String {
        assertEquals("error", result["status"]?.jsonPrimitive?.content)
        return result["reason"]?.jsonPrimitive?.content.orEmpty()
    }

    // ----- language parsing -----

    @Test
    fun `language spellings are recognised regardless of case and padding`() {
        listOf("Chinese", "chinese", "zh", "ZH-CN", " 中文 ", "汉语").forEach {
            assertEquals("'$it' should parse as Chinese", TtsLanguage.Chinese, TtsLanguage.parse(it))
        }
        listOf("English", "ENGLISH", "en", "en-US", " en-uk ").forEach {
            assertEquals("'$it' should parse as English", TtsLanguage.English, TtsLanguage.parse(it))
        }
    }

    @Test
    fun `an unknown language parses to NoLanguage rather than silently becoming the default`() {
        assertEquals(TtsLanguage.NoLanguage, TtsLanguage.parse("klingon"))
        assertEquals(TtsLanguage.NoLanguage, TtsLanguage.parse(""))
    }

    @Test
    fun `each language maps to the locale the engine expects`() {
        assertEquals(Locale.SIMPLIFIED_CHINESE, TtsLanguage.Chinese.toLocale())
        assertEquals(Locale.US, TtsLanguage.English.toLocale())
    }

    // ----- argument validation -----

    @Test
    fun `arguments that do not parse come back as an explicit invalid_arguments result`() = runTest {
        val result = tool.execute(parsed = null, context = context())

        assertEquals("invalid_arguments", result["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `an unsupported language is refused and lists the accepted spellings`() = runTest {
        textFile("script.txt", "hello")
        val result = tool.execute(parse(args("script.txt", language = "klingon")), context())

        val reason = reasonOf(result)
        assertTrue(reason, reason.contains("klingon"))
        assertTrue(reason, reason.contains("Chinese"))
        assertTrue(reason, reason.contains("English"))
    }

    @Test
    fun `a missing input file is refused instead of synthesizing nothing`() = runTest {
        val result = tool.execute(parse(args("nope.txt")), context())

        assertTrue(reasonOf(result).contains("does not exist"))
    }

    @Test
    fun `a file with no speakable text is refused`() = runTest {
        textFile("blank.txt", "   \n\t ")
        val result = tool.execute(parse(args("blank.txt")), context())

        assertTrue(reasonOf(result).contains("no speakable text"))
    }

    @Test
    fun `text over the per-call cap is rejected rather than truncated`() = runTest {
        val oversize = TtsTool.MAX_INPUT_CHARS + 1
        textFile("long.txt", "a".repeat(oversize))
        val result = tool.execute(parse(args("long.txt")), context())

        val reason = reasonOf(result)
        assertTrue(reason, reason.contains(oversize.toString()))
        assertTrue(reason, reason.contains(TtsTool.MAX_INPUT_CHARS.toString()))
    }

    // ----- the sandbox boundary (why this tool speaks relative paths at all) -----

    @Test
    fun `an absolute path is refused with a pointer at the relative convention`() = runTest {
        val result = tool.execute(parse(args("/data/data/notes/script.txt")), context())

        val reason = reasonOf(result)
        assertTrue(reason, reason.contains("relative"))
        assertTrue(reason, reason.contains("/data/data/notes/script.txt"))
    }

    @Test
    fun `an output path that escapes the workspace is refused`() = runTest {
        textFile("script.txt", "hello")
        val result = tool.execute(parse(args("script.txt", outputPath = "../escaped.wav")), context())

        val reason = reasonOf(result)
        assertTrue(reason, reason.contains("escapes"))
        assertTrue(File(tmp.root, "ws").listFiles().orEmpty().none { it.name == "escaped.wav" })
    }

    @Test
    fun `an unknown projectId is refused explicitly`() = runTest {
        val result = tool.execute(parse(args("script.txt", projectId = "no-such-project")), context())

        assertTrue(reasonOf(result).contains("unknown projectId"))
    }

    // ----- definition -----

    @Test
    fun `the tool is safe and advertises its languages and cap`() {
        val definition = tool.definition

        assertEquals(TtsTool.TOOL_NAME, definition.name)
        assertEquals(DangerLevel.SAFE, tool.dangerLevel)
        // the model only ever sees this text, so the cap and the languages have to be in it
        assertTrue(definition.description.contains(TtsTool.MAX_INPUT_CHARS.toString()))
        assertTrue(definition.description.contains("chinese"))
        assertTrue(definition.description.contains("english"))
    }
}
