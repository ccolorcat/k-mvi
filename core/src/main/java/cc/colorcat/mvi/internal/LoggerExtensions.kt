package cc.colorcat.mvi.internal

import android.util.Log
import cc.colorcat.mvi.KMvi
import cc.colorcat.mvi.Logger

/**
 * Internal logger extension functions and utilities.
 *
 * These functions provide convenient internal logging methods for the MVI framework.
 *
 * Author: ccolorcat
 * Date: 2025-11-08
 * GitHub: https://github.com/ccolorcat
 */

/**
 * Default log tag for the k-mvi framework.
 *
 * This tag is used for all internal logging within the MVI framework,
 * making it easy to filter framework logs in Logcat.
 */
internal const val TAG = "k-mvi"

/**
 * The global logger instance for the k-mvi framework.
 *
 * This property provides access to the Logger configured in [KMvi.logger].
 * All internal logging within the framework uses this logger instance,
 * allowing users to customize logging behavior by setting a custom logger
 * in [KMvi.logger].
 *
 * Example:
 * ```
 * // Configure custom logger
 * KMvi.logger = Logger(threshold = Logger.DEBUG)
 *
 * // All internal framework logs will use this logger
 * ```
 */
internal val logger: Logger
    get() = KMvi.logger

/**
 * Converts a Throwable to its stack trace string representation.
 *
 * Uses Android's Log.getStackTraceString() which includes the full stack trace
 * and all nested causes.
 *
 * @return A string containing the full stack trace with nested causes
 */
internal fun Throwable.getStackTraceString(): String = Log.getStackTraceString(this)


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
 * Logs a warning message.
 *
 * @param tag The tag to identify the source of the log message
 * @param message A lambda that produces the log message (evaluated lazily)
 */
internal fun Logger.w(tag: String, message: () -> String) =
    log(Logger.WARN, tag, null, message)

/**
 * Logs an error message.
 *
 * @param tag The tag to identify the source of the log message
 * @param message A lambda that produces the log message (evaluated lazily)
 */
internal fun Logger.e(tag: String, message: () -> String) =
    log(Logger.ERROR, tag, null, message)

/**
 * Logs an error message with a throwable.
 *
 * @param tag The tag to identify the source of the log message
 * @param error The throwable to be logged with stack trace and cause chain
 * @param message A lambda that produces the log message (evaluated lazily)
 */
internal fun Logger.e(tag: String, error: Throwable, message: () -> String) =
    log(Logger.ERROR, tag, error, message)

/**
 * Logs an assert message for conditions that should never occur.
 *
 * @param tag The tag to identify the source of the log message
 * @param message A lambda that produces the log message (evaluated lazily)
 */
internal fun Logger.assert(tag: String, message: () -> String) =
    log(Logger.ASSERT, tag, null, message)
