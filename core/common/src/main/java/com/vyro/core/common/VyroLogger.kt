package com.vyro.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

enum class LogSeverity {
    DEBUG,
    INFO,
    WARN,
    ERROR,
}

data class StructuredLogEntry(
    val timestampIso: String,
    val severity: LogSeverity,
    val module: String,
    val correlationId: String,
    val message: String,
    val errorCode: EngineErrorCode? = null,
)

object VyroLogger {
    private const val MAX_BUFFERED_LOGS = 200
    private val entries = CopyOnWriteArrayList<StructuredLogEntry>()

    fun log(
        severity: LogSeverity,
        module: String,
        message: String,
        correlationId: String = "global",
        errorCode: EngineErrorCode? = null,
    ) {
        val entry = StructuredLogEntry(
            timestampIso = Instant.now().toString(),
            severity = severity,
            module = module,
            correlationId = correlationId,
            message = message,
            errorCode = errorCode,
        )
        entries.add(entry)
        while (entries.size > MAX_BUFFERED_LOGS) {
            entries.removeAt(0)
        }
    }

    fun recentEntries(): List<StructuredLogEntry> = entries.toList()

    fun clear() {
        entries.clear()
    }
}

interface DispatchersProvider {
    val main: CoroutineDispatcher
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher
}

object DefaultDispatchersProvider : DispatchersProvider {
    override val main: CoroutineDispatcher = Dispatchers.Main
    override val io: CoroutineDispatcher = Dispatchers.IO
    override val default: CoroutineDispatcher = Dispatchers.Default
}
