package com.vyro.core.common

enum class EngineErrorCode(val numericCode: Int) {
    OK(0),
    INVALID_STATE(1001),
    INVALID_TIMELINE_EDIT(1002),
    CORRUPT_PROJECT(1003),
    STORAGE_FAILURE(1004),
    UNSUPPORTED_CAPABILITY(1005),
    NATIVE_BRIDGE_FAILURE(1006),
    MISSING_MEDIA(1007),
    PERMISSION_REVOKED(1008),
    UNSUPPORTED_MEDIA(1009);

    companion object {
        fun fromCode(code: Int): EngineErrorCode =
            entries.firstOrNull { it.numericCode == code } ?: NATIVE_BRIDGE_FAILURE
    }
}

data class EngineError(
    val code: EngineErrorCode,
    val sourceModule: String,
    val userMessage: String,
    val diagnosticDetail: String,
    val recoverable: Boolean = true,
)

sealed interface EngineResult<out T> {
    data class Success<out T>(val value: T) : EngineResult<T>
    data class Failure(val error: EngineError) : EngineResult<Nothing>

    val isSuccess: Boolean get() = this is Success

    fun getOrNull(): T? = (this as? Success)?.value

    fun errorOrNull(): EngineError? = (this as? Failure)?.error
}

inline fun <T, R> EngineResult<T>.map(transform: (T) -> R): EngineResult<R> = when (this) {
    is EngineResult.Success -> EngineResult.Success(transform(value))
    is EngineResult.Failure -> this
}
