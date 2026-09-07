package io.github.zvensmoluya.tavernplayer.conversation.mvu

import com.dokar.quickjs.QuickJs
import io.github.zvensmoluya.tavernplayer.conversation.ConversationRuntimeState
import io.github.zvensmoluya.tavernplayer.conversation.MvuStateSnapshot
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray

@Serializable
data class MvuDiagnostic(val level: String, val text: String)

data class MvuMessageResult(
    val sourceText: String,
    val processedText: String,
    val state: MvuStateSnapshot,
) {
    fun applyTo(previous: ConversationRuntimeState): ConversationRuntimeState = previous.copy(mvuState = state)
}

data class MvuEvaluation(
    val messages: List<MvuMessageResult>,
    val diagnostics: List<MvuDiagnostic>,
    val events: List<String>,
)

/**
 * QuickJS execution for an explicitly selected card program and a Player-supplied MVU bundle.
 * Does not fetch code, activate imported cards, expose Android objects, or own a second timeline.
 * Calls serialize on one worker; each update starts from the caller's durable checkpoint.
 */
class QuickJsMvuRuntime private constructor(
    private val bundleSha256: String,
    private val programSha256: String,
    private val dispatcher: kotlinx.coroutines.ExecutorCoroutineDispatcher,
    private val engine: QuickJs,
    private val evaluationTimeoutMillis: Long,
) {
    private val mutex = Mutex()
    private var closed = false
    private var failed = false

    suspend fun initialize(greetings: List<String>? = null): MvuEvaluation {
        val argument = greetings?.let { JsonArray(it.map(::JsonPrimitive)).toString() }.orEmpty()
        require(argument.length <= MAX_INPUT_CHARS) { "MVU greetings exceed input limit" }
        return evaluate("session.initialize($argument)")
    }

    suspend fun update(sourceText: String, previous: ConversationRuntimeState): MvuEvaluation {
        require(sourceText.length <= MAX_INPUT_CHARS) { "MVU message exceeds input limit" }
        val snapshot = requireNotNull(previous.mvuState) { "Missing MVU checkpoint" }
        require(snapshot.bundleSha256 == bundleSha256 && snapshot.programSha256 == programSha256) {
            "MVU checkpoint belongs to a different bundle or card program"
        }
        val data = snapshot.data.toString()
        require(data.length <= MAX_INPUT_CHARS) { "MVU checkpoint exceeds input limit" }
        // JSON strings are passed as data, never interpolated as executable message/schema syntax.
        return evaluate("session.update(JSON.parse(${JsonPrimitive(data)}), ${JsonPrimitive(sourceText)})")
    }

    suspend fun memoryUsedBytes(): Long = access { engine.memoryUsage.memoryUsedSize }

    suspend fun close() = mutex.withLock {
        if (!closed) {
            withContext(NonCancellable + dispatcher) {
                if (!engine.isClosed) engine.close()
            }
            closed = true
            dispatcher.close()
        }
    }

    private suspend fun evaluate(expression: String): MvuEvaluation = access {
        try {
            val output = withTimeout(evaluationTimeoutMillis) {
                engine.evaluate<String>("JSON.stringify(await $expression)", filename = "mvu-call.js")
            }
            require(output.length <= MAX_INPUT_CHARS) { "MVU result exceeds output limit" }
            val wire = Json.decodeFromString<WireEvaluation>(output)
            val messages = wire.messages.map { message ->
                require(message.data["stat_data"] is JsonObject && "schema" in message.data) {
                    "MVU returned an incomplete checkpoint"
                }
                MvuMessageResult(message.sourceText, message.processedText,
                    MvuStateSnapshot(bundleSha256, programSha256, message.data))
            }
            MvuEvaluation(messages, wire.diagnostics, wire.events)
        } catch (error: Throwable) {
            // A failed/cancelled script may have mutated JS state. Never reuse it for another turn.
            failed = true
            engine.close()
            throw error
        }
    }

    private suspend fun <T> access(block: suspend () -> T): T = mutex.withLock {
        check(!closed && !failed) { "MVU runtime is closed or failed; reopen from the saved checkpoint" }
        withContext(dispatcher) { block() }
    }

    companion object {
        private const val MAX_INPUT_CHARS = 2 * 1024 * 1024

        suspend fun create(
            bundle: String,
            program: JsonObject,
            evaluationTimeoutMillis: Long = 2_000,
        ): QuickJsMvuRuntime {
            require(evaluationTimeoutMillis in 1..10_000)
            require(bundle.length <= 8 * 1024 * 1024) { "MVU bundle exceeds limit" }
            val programText = program.toString()
            require(programText.length <= MAX_INPUT_CHARS) { "MVU program exceeds limit" }
            val dispatcher = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "mvu-quickjs").apply { isDaemon = true }
            }.asCoroutineDispatcher()
            var engine: QuickJs? = null
            try {
                return withContext(dispatcher) {
                    val js = QuickJs.create(dispatcher).also { engine = it }
                    js.memoryLimit = 96L * 1024 * 1024
                    js.maxStackSize = 1024L * 1024
                    js.evaluationTimeoutMillis = 10_000
                    js.evaluate<Any?>(bundle, filename = "mvu-runtime.js")
                    js.evaluationTimeoutMillis = evaluationTimeoutMillis
                    js.evaluate<Any?>("globalThis.session = PlayerMvu.createSession(JSON.parse(${JsonPrimitive(programText)})); void 0;",
                        filename = "mvu-program.js")
                    QuickJsMvuRuntime(sha256(bundle), sha256(programText), dispatcher, js, evaluationTimeoutMillis)
                }
            } catch (error: Throwable) {
                withContext(NonCancellable + dispatcher) { engine?.let { if (!it.isClosed) it.close() } }
                dispatcher.close()
                throw error
            }
        }

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

@Serializable
private data class WireEvaluation(
    val messages: List<WireMessage>,
    val diagnostics: List<MvuDiagnostic>,
    val events: List<String>,
)

@Serializable
private data class WireMessage(val sourceText: String, val processedText: String, val data: JsonObject)
