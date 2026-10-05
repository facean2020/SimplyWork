package com.yangz3720.simplywork.core.agent.tool

import android.content.Context
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.yangz3720.simplywork.core.session.ProjectStore
import com.yangz3720.simplywork.core.workspace.AgentWorkspace
import com.yangz3720.simplywork.core.workspace.WorkspaceException
import com.yangz3720.simplywork.infrastructure.model.model.ToolDefinition
import com.yangz3720.simplywork.infrastructure.systemops.model.DangerLevel
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

/**
 * TTS tool (v1.6): synthesize a text file into an audio file with the device's text-to-speech engine.
 *
 * Shape of the family: a single `tts_tool` call takes `input_path` / `output_path` / `language`.
 * The two paths are **workspace-relative**, exactly like the `fs_*` family -- the agent works inside
 * its project sandbox and has no business knowing (or typing) an absolute `/data/...` path, and
 * [AgentWorkspace] is the only component allowed to turn a relative path into a real file. An earlier
 * draft took absolute paths, which made the tool unusable from inside the sandbox: the agent knows
 * relative paths (that is what `fs_list` / `fs_write` speak) and nothing ever told it the sandbox root.
 *
 * It is a **synchronous** call -- the loop waits for the engine to report completion, because a tool
 * whose "result" arrives later has no way to say whether the file on disk is complete. [TtsManager]
 * owns the asynchronous engine and bridges its callbacks onto the coroutine this tool runs on.
 *
 * Danger level is SAFE, matching `fs_write`: it only creates/overwrites the audio file at the
 * workspace path the caller named; it cannot delete anything, and cannot leave the sandbox.
 */
object TtsTool {

    const val TOOL_NAME = "tts_tool"

    /**
     * Characters accepted by one synthesis call. Mirrors the framework constant behind
     * `TextToSpeech.getMaxSpeechInputLength()` (4000); declared here so argument validation can be
     * exercised off-device. Longer input is **rejected** rather than silently truncated -- a half-spoken
     * audio file that looks successful is worse than an explicit "split it up" answer.
     */
    const val MAX_INPUT_CHARS = 4_000

    @Serializable
    data class Convert_To_Speech(
        /** Project whose workspace the paths are relative to; defaults to the CURRENT project (same rule as `fs_*`). */
        val projectId: String? = null,
        val input_path: String,
        val output_path: String,
        val language: String
    )

    @Serializable
    data class Tts_Engine(val package_name: String, val label: String)

    private val json = Json { ignoreUnknownKeys = true }

    fun definition(): ToolDefinition = ToolDefinition(
        name = TOOL_NAME,
        description = "A TTS utility that reads a text file from the project workspace and generates a " +
            "speech audio file next to it, using the device's text-to-speech engine. At most " +
            "$MAX_INPUT_CHARS characters per call; longer text has to be split across files/calls. " +
            "Supported languages: english (en, en-us, en-uk) and chinese (zh, zh-cn, 中文, 汉语), " +
            "parsed case-insensitively; the engine must have a voice for the language installed. " +
            "This call waits for synthesis to finish, so expect a few seconds of latency.",
        parametersSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("projectId", buildJsonObject {
                    put("type", "string")
                    put("description", "Project whose workspace the paths are relative to; defaults to the current project")
                })
                put("input_path", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Path of the UTF-8 text file to speak, relative to the project workspace root " +
                            "(the same path fs_list/fs_write use, e.g. 'notes/script.txt'). The file " +
                            "must exist and hold at most $MAX_INPUT_CHARS characters."
                    )
                })
                put("output_path", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Path of the audio file to write, relative to the project workspace root; " +
                            "missing parent directories are created and an existing file there is " +
                            "overwritten. The container format is chosen by the TTS engine (16-bit PCM " +
                            "WAV on most devices), not by the extension -- prefer a .wav name."
                    )
                })
                put("language", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Language of the input text, used to pick the engine voice and phoneme rules " +
                            "(e.g., 'english', 'chinese', 'en-us', 'zh-cn'). Unknown values are rejected."
                    )
                })
            })
            put("required", JsonArray(listOf(
                JsonPrimitive("input_path"),
                JsonPrimitive("output_path"),
                JsonPrimitive("language")
            )))
        },
        isStrict = false
    )

    /**
     * Registers `tts_tool`. It needs a [Context] (the engine is an Android service) and the workspace
     * seam, so the composition root injects both through the same `selfOps` channel as the other
     * context-bound tool families (see `AgentRuntime.createWithStores`).
     */
    fun registeredTool(
        context: Context,
        workspace: AgentWorkspace,
        projects: ProjectStore
    ): RegisteredTool = RegisteredTool(
        definition = definition(),
        dangerLevel = DangerLevel.SAFE,
        parser = { raw -> runCatching { json.decodeFromJsonElement(Convert_To_Speech.serializer(), raw) } },
        runner = { parsed, _ ->
            val input = parsed as? Convert_To_Speech
                ?: return@RegisteredTool invalidArguments("$TOOL_NAME arguments did not parse")
            convertToSpeech(context, workspace, projects, input)
        }
    )

    private suspend fun convertToSpeech(
        context: Context,
        workspace: AgentWorkspace,
        projects: ProjectStore,
        input: Convert_To_Speech
    ): JsonObject {
        val language = TtsLanguage.parse(input.language)
        if (language == TtsLanguage.NoLanguage) {
            return error("unsupported language '${input.language}' (accepted: ${TtsLanguage.ACCEPTED_HINT})")
        }

        return guarded {
            val projectId = resolveProjectId(projects, input.projectId)

            // The single mistake this tool is most likely to attract is an absolute path (its earlier
            // draft demanded one), so name it instead of letting the workspace's "not a regular file"
            // fall out of readOrNull below.
            val absolute = listOf(input.input_path, input.output_path).firstOrNull { it.startsWith("/") }
            if (absolute != null) {
                return@guarded error(
                    "paths must be relative to the project workspace, not absolute (got '$absolute'); " +
                        "use the same relative paths as fs_list/fs_write"
                )
            }

            // readOrNull rather than read: a missing file is an argument mistake worth naming, not an
            // exception to leak outward (read() would surface it as a bare "not a regular file").
            val source = workspace.readOrNull(projectId, input.input_path)
                ?: return@guarded error("input file does not exist or is not readable: ${input.input_path}")
            if (source.truncated) {
                return@guarded error(
                    "input file is larger than the ${workspace.maxFileBytes}-byte workspace read limit: ${input.input_path}"
                )
            }
            val text = source.content.trim()
            if (text.isEmpty()) return@guarded error("input file has no speakable text: ${input.input_path}")
            if (text.length > MAX_INPUT_CHARS) {
                return@guarded error(
                    "input is ${text.length} characters, over the $MAX_INPUT_CHARS-character limit of one " +
                        "synthesis call; split it into smaller files and call $TOOL_NAME per file"
                )
            }

            val output = workspace.fileForWrite(projectId, input.output_path)

            val manager = TtsManager(context)
            try {
                manager.init(language).getOrElse {
                    return@guarded error(it.message ?: "TTS engine initialization failed")
                }
                manager.synthesizeToFile(text, output).fold(
                    onSuccess = {
                        buildJsonObject {
                            put("status", "success")
                            put("projectId", projectId)
                            put("input_path", source.path)
                            put("output_path", input.output_path)
                            put("bytes_written", output.length())
                            put("characters", text.length)
                            put("language", language.name)
                            // Reported so the model can tell the user which voice actually spoke; a null
                            // answer (no engine could be described) simply omits the field.
                            manager.engineInfo?.let {
                                put("engine", json.encodeToJsonElement(Tts_Engine.serializer(), it))
                            }
                        }
                    },
                    onFailure = { error(it.message ?: "audio synthesis failed") }
                )
            } finally {
                manager.release()
            }
        }
    }

    /** projectId defaults to the current project; when given explicitly it must exist (P2 explicit failure). */
    private fun resolveProjectId(projects: ProjectStore, requested: String?): String {
        val id = requested ?: projects.currentId()
        if (projects.byId(id) == null) {
            throw WorkspaceException("unknown projectId '$id' — call self_config_read for the project list")
        }
        return id
    }

    private fun error(message: String): JsonObject = buildJsonObject {
        put("status", "error")
        put("reason", message)
    }

    private fun invalidArguments(message: String): JsonObject = buildJsonObject {
        put("status", "invalid_arguments")
        put("reason", message)
    }

    /**
     * Sandbox refusals (path escape, absolute path, unknown project) become JSON the model can act on,
     * instead of raw exceptions thrown into the loop (P2). Same shape as [SelfOpsTools]'.
     */
    private suspend inline fun guarded(crossinline block: suspend () -> JsonObject): JsonObject = try {
        block()
    } catch (e: WorkspaceException) {
        error(e.message ?: "workspace operation failed")
    } catch (e: IllegalArgumentException) {
        error(e.message ?: "invalid argument")
    }
}

/**
 * Typed TTS failure. [TtsManager] reports these through a `Result` failure instead of throwing raw
 * exceptions, so the tool can hand the model a readable reason to self-correct from (P2: no raw
 * exception is thrown into the loop).
 */
interface TtsError {
    val message: String

    /** `TextToSpeech` reported a non-SUCCESS init status: no engine installed/enabled, or a broken service. */
    data class InitFailed(val status: Int) : TtsError {
        override val message: String
            get() = "TTS engine initialization failed (status=$status), please make sure the device has TTS engine installed and enabled"

    }

    /** The engine never called back within the init window -- bound, but unresponsive. */
    data class InitTimedOut(val timeoutMs: Long) : TtsError {
        override val message: String
            get() = "the TTS engine did not finish initializing within ${timeoutMs}ms"
    }

    /** The engine has no voice for the requested language (or its voice data is not installed). */
    data class LanguageUnavailable(val language: TtsLanguage, val availability: Int) : TtsError {
        override val message: String
            get() = buildString {
                append("the TTS engine cannot speak ")
                append(language.name)
                append(" (availability=")
                append(availability)
                append(")")
                if (availability == TextToSpeech.LANG_MISSING_DATA) {
                    append("; install the voice data for this language in the device's TTS settings")
                }
            }
    }

    /** The engine accepted the request but reported an error while synthesizing. */
    data class SynthesisFailed(val utteranceId: String, val errorCode: Int?) : TtsError {
        override val message: String
            get() = "the TTS engine failed to synthesize audio (utteranceId=$utteranceId" +
                (errorCode?.let { ", errorCode=$it" } ?: "") + ")"
    }

    /** The engine accepted the request but never reported completion within the cap. */
    data class SynthesisTimedOut(val timeoutMs: Long) : TtsError {
        override val message: String
            get() = "audio synthesis did not finish within ${timeoutMs}ms"
    }

    /** `synthesizeToFile` itself refused the call. */
    data class RequestRejected(val resultCode: Int) : TtsError {
        override val message: String
            get() = "the TTS engine rejected the synthesis request (synthesizeToFile returned $resultCode)"
    }

    /** The output file could not be opened for writing. */
    data class OutputUnwritable(val path: String, val reason: String?) : TtsError {
        override val message: String
            get() = "cannot open '$path' for writing" + (reason?.let { ": $it" } ?: "")
    }

    /** Defensive: [TtsManager] was used before a successful [TtsManager.init]. */
    data object NotInitialized : TtsError {
        override val message: String
            get() = "the TTS manager was used before a successful init()"
    }
}

/** Carries a typed [TtsError] through Kotlin's `Result` failure channel. */
class TtsToolException(val error: TtsError) : Exception(error.message)

/**
 * Language selection for synthesis. [NoLanguage] means "the caller asked for something we do not
 * know" -- the tool turns it into an explicit argument error, so it never reaches the engine.
 */
enum class TtsLanguage {
    Chinese,
    English,
    NoLanguage;

    /** The locale handed to the engine; [NoLanguage] keeps the engine's own default. */
    fun toLocale(): Locale = when (this) {
        Chinese -> Locale.SIMPLIFIED_CHINESE
        English -> Locale.US
        NoLanguage -> Locale.getDefault()
    }

    companion object {
        val ChineseLanL: List<String> = listOf("Chinese", "zh", "zh-cn", "中文", "汉语")
        val EnglishLanL: List<String> = listOf("English", "en", "en-us", "en-uk")

        /** The accepted spellings, for error messages; the model cannot see the implementation. */
        val ACCEPTED_HINT: String = (ChineseLanL + EnglishLanL).joinToString(", ")

        /** Case-folded lookup built once: [parse] runs on every tool call and must not reallocate. */
        private val lookup: Map<String, TtsLanguage> =
            (ChineseLanL.map { it.lowercase() to Chinese } + EnglishLanL.map { it.lowercase() to English }).toMap()

        /**
         * Parses the model-supplied language, case- and whitespace-insensitively ("ZH-CN", " english ").
         * Anything unrecognised becomes [NoLanguage], which the tool rejects explicitly.
         */
        fun parse(input: String): TtsLanguage = lookup[input.trim().lowercase()] ?: NoLanguage
    }
}

/**
 * Owns one [TextToSpeech] connection and bridges its asynchronous callbacks onto coroutines.
 *
 * Everything here runs on the main thread: `TextToSpeech` creates a `Handler` bound to the thread that
 * constructs it, so building it anywhere else throws ("Can't create handler inside thread that has not
 * called Looper.prepare()"). The awaiting caller is suspended, not blocked -- the loop keeps running
 * while this manager waits for the engine.
 *
 * Lifecycle: [init] on the way in, [release] on the way out; a manager is cheap, so the tool creates one
 * per call and releases it in a `finally`.
 */
class TtsManager(context: Context) {
    private val appContext = context.applicationContext
    private var engine: TextToSpeech? = null

    /**
     * The engine that served the last successful [init]. Deliberately survives [release], so the result
     * JSON can still name the voice that produced the file after the connection is gone.
     */
    var engineInfo: TtsTool.Tts_Engine? = null
        private set

    companion object {
        private const val INIT_TIMEOUT_MS = 5_000L
        private const val SYNTH_TIMEOUT_MS = 120_000L
        private const val UTTERANCE_ID = "tts_tool_utterance"
    }

    /**
     * Connects to the engine and selects [language]. Fails when no engine answers within
     * [INIT_TIMEOUT_MS], when the engine reports an error, or when it cannot speak the language.
     */
    suspend fun init(language: TtsLanguage): Result<Unit> = withContext(Dispatchers.Main.immediate) {
        release()

        val ready = CompletableDeferred<Int>()
        val created = TextToSpeech(appContext) { status -> ready.complete(status) }

        val status = withTimeoutOrNull(INIT_TIMEOUT_MS) { ready.await() }
        if (status == null) {
            created.shutdown()
            return@withContext failure(TtsError.InitTimedOut(INIT_TIMEOUT_MS))
        }
        if (status != TextToSpeech.SUCCESS) {
            created.shutdown()
            return@withContext failure(TtsError.InitFailed(status))
        }

        // setLanguage both applies and reports: a negative result means the engine has no usable voice
        // for the locale (LANG_MISSING_DATA = voice data not downloaded).
        val applied = created.setLanguage(language.toLocale())
        if (applied < TextToSpeech.LANG_AVAILABLE) {
            created.shutdown()
            return@withContext failure(TtsError.LanguageUnavailable(language, applied))
        }

        engine = created
        engineInfo = describe(created)
        Result.success(Unit)
    }

    /**
     * Synthesizes [text] into [output]. Fails when the engine refuses the request, reports an error, or
     * stays silent past [SYNTH_TIMEOUT_MS].
     *
     * The file is opened with `MODE_TRUNCATE`, so a previous file at that path can never bleed into the
     * new audio, and a failed run leaves an empty file rather than a plausible-looking stale one.
     */
    suspend fun synthesizeToFile(text: String, output: File): Result<Unit> = withContext(Dispatchers.Main.immediate) {
        val tts = engine ?: return@withContext failure(TtsError.NotInitialized)

        // The progress listener is the only completion channel `synthesizeToFile` has: it returns as
        // soon as the engine accepts the job, so waiting for onDone/onError is what makes the call
        // synchronous for the loop. `Result` (never null) keeps OnDone distinguishable from a timeout.
        val completion = CompletableDeferred<Result<Unit>>()
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit

            override fun onDone(utteranceId: String) {
                completion.complete(Result.success(Unit))
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) {
                completion.complete(failure(TtsError.SynthesisFailed(utteranceId, null)))
            }

            override fun onError(utteranceId: String, errorCode: Int) {
                completion.complete(failure(TtsError.SynthesisFailed(utteranceId, errorCode)))
            }
        })

        val descriptor = runCatching {
            ParcelFileDescriptor.open(
                output,
                ParcelFileDescriptor.MODE_CREATE or
                    ParcelFileDescriptor.MODE_READ_WRITE or
                    ParcelFileDescriptor.MODE_TRUNCATE
            )
        }.getOrElse { return@withContext failure(TtsError.OutputUnwritable(output.absolutePath, it.message)) }

        try {
            val accepted = tts.synthesizeToFile(text, Bundle(), descriptor, UTTERANCE_ID)
            if (accepted != TextToSpeech.SUCCESS) {
                return@withContext failure(TtsError.RequestRejected(accepted))
            }
            withTimeoutOrNull(SYNTH_TIMEOUT_MS) { completion.await() }
                ?: run {
                    // Abandon the job rather than leave the engine writing into a file nobody will read.
                    tts.stop()
                    failure(TtsError.SynthesisTimedOut(SYNTH_TIMEOUT_MS))
                }
        } finally {
            tts.setOnUtteranceProgressListener(null)
            descriptor.close()
        }
    }

    /** Disconnects from the engine. Safe to call repeatedly, and called after every tool invocation. */
    fun release() {
        engine?.shutdown()
        engine = null
    }

    /** Best-effort description of the bound engine; it is only a label, so a missing answer is fine. */
    private fun describe(tts: TextToSpeech): TtsTool.Tts_Engine? = runCatching {
        val packageName = tts.defaultEngine ?: return@runCatching null
        val label = tts.engines.firstOrNull { it.name == packageName }?.label ?: packageName
        TtsTool.Tts_Engine(package_name = packageName, label = label)
    }.getOrNull()

    private fun failure(error: TtsError): Result<Unit> = Result.failure(TtsToolException(error))
}
