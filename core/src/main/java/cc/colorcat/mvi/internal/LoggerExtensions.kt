package cc.colorcat.mvi.internal

import cc.colorcat.mvi.KMvi
import cc.colorcat.mvi.Logger

/**
 * Internal logger extension functions and utilities.
 *
 * These functions provide convenient internal logging methods for the MVI framework.
 */

/**
 * Default log tag for the k-mvi framework.
 *
 * This tag is used for all internal logging within the MVI framework,
 * making it easy to filter framework logs in Logcat.
 */
internal const val TAG = "k-mvi"

private const val FALLBACK_LOG_CHUNK_SIZE = 1_000

/**
 * The global logger instance for the k-mvi framework.
 *
 * This property provides access to the Logger configured in [KMvi.logger].
 * All internal logging within the framework uses this logger instance,
 * allowing users to customize logging behavior by setting a custom logger
 * in [KMvi.logger].
 *
 * Example:
 * ```kotlin
 * // Configure custom logger via KMvi.configure
 * KMvi.configure { copy(logger = Logger(Logger.DEBUG)) }
 *
 * // All internal framework logs will use this logger
 * ```
 */
internal val logger: Logger
    get() = KMvi.logger

/**
 * Converts a Throwable to its stack trace string representation.
 *
 * Includes the full stack trace and all nested causes.
 *
 * @return A string containing the full stack trace with nested causes
 */
internal fun Throwable.getStackTraceString(): String = stackTraceToString()


/**
 * Logs a verbose message.
 *
 * @param tag The tag to identify the source of the log message
 * @param message A lambda that produces the log message (evaluated lazily)
 */
internal fun Logger.v(tag: String, message: () -> String) =
    log(Logger.VERBOSE, tag, null, message)

/**
 * Logs a debug message.
 *
 * @param tag The tag to identify the source of the log message
 * @param message A lambda that produces the log message (evaluated lazily)
 */
internal fun Logger.d(tag: String, message: () -> String) =
    log(Logger.DEBUG, tag, null, message)

/**
 * Logs an info message.
 *
 * @param tag The tag to identify the source of the log message
 * @param message A lambda that produces the log message (evaluated lazily)
 */
internal fun Logger.i(tag: String, message: () -> String) =
    log(Logger.INFO, tag, null, message)

/**
 * Logs a warning message with a throwable.
 *
 * @param tag The tag to identify the source of the log message
 * @param cause The optional throwable to be logged with stack trace and cause chain
 * @param message A lambda that produces the log message (evaluated lazily)
 */
internal fun Logger.w(tag: String, cause: Throwable? = null, message: () -> String) =
    log(Logger.WARN, tag, cause, message)

/**
 * Logs an error message with a throwable.
 *
 * @param tag The tag to identify the source of the log message
 * @param cause The optional throwable to be logged with stack trace and cause chain
 * @param message A lambda that produces the log message (evaluated lazily)
 */
internal fun Logger.e(tag: String, cause: Throwable? = null, message: () -> String) =
    log(Logger.ERROR, tag, cause, message)

/**
 * Logs an assert message for conditions that should never occur.
 *
 * @param tag The tag to identify the source of the log message
 * @param message A lambda that produces the log message (evaluated lazily)
 */
internal fun Logger.assert(tag: String, message: () -> String) =
    log(Logger.ASSERT, tag, null, message)

/** Builds the complete text passed to Android Log while preserving nested causes. */
internal fun formatLogText(message: String, cause: Throwable?): String {
    return if (cause == null) {
        message
    } else {
        buildString {
            appendLine(message)
            append(cause.getStackTraceString())
        }
    }
}

/**
 * Splits text for priorities that do not have a safe public Throwable overload.
 *
 * A conservative limit keeps each Modified UTF-8 payload below Logcat's entry limit for tags that
 * are valid on every supported Android version. Newline and surrogate-pair boundaries are retained.
 */
internal fun String.chunkForLogcat(maxChunkSize: Int = FALLBACK_LOG_CHUNK_SIZE): List<String> {
    require(maxChunkSize > 0) { "maxChunkSize must be greater than 0, but was $maxChunkSize." }
    if (isEmpty()) return listOf("")

    return buildList {
        var start = 0
        while (start < length) {
            var end = if (maxChunkSize >= length - start) length else start + maxChunkSize
            if (end < length) {
                val newline = lastIndexOf('\n', end - 1)
                if (newline >= start) {
                    end = newline + 1
                } else if (this@chunkForLogcat[end - 1].isHighSurrogate() &&
                    this@chunkForLogcat[end].isLowSurrogate()
                ) {
                    end = if (end - start == 1) end + 1 else end - 1
                }
            }
            add(substring(start, end))
            start = end
        }
    }
}
