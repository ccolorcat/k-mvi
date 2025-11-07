package cc.colorcat.mvi

import android.util.Log
import cc.colorcat.mvi.internal.getStackTraceString

/**
 * A functional interface for logging.
 *
 * This interface provides priority-based filtering and lazy message evaluation
 * to improve performance by avoiding unnecessary string computations.
 *
 * The default implementation uses Android's Log system, but you can provide
 * your own custom implementation for different logging backends (e.g., file logging,
 * remote logging, etc.).
 *
 * Example usage:
 * ```
 * // Create a logger with default implementation and threshold (WARN)
 * val logger = Logger()
 * logger.log(Logger.INFO, "MyTag", null) { "Hello World" }
 *
 * // With exception
 * try {
 *     // some operation
 * } catch (e: Exception) {
 *     logger.log(Logger.ERROR, "MyTag", e) { "Error occurred" }
 * }
 *
 * // With custom threshold for debug builds
 * val debugLogger = Logger(threshold = Logger.DEBUG)
 *
 * // Custom implementation example
 * val customLogger = Logger { priority, tag, error, message ->
 *     // Your custom logging logic here
 *     println("[$tag] ${message()}")
 * }
 * ```
 *
 * Author: ccolorcat
 * Date: 2025-11-08
 * GitHub: https://github.com/ccolorcat
 */
fun interface Logger {
    /**
     * Logs a message with the specified priority level.
     *
     * @param priority The log priority level (VERBOSE, DEBUG, INFO, WARN, ERROR, ASSERT)
     * @param tag The tag to identify the source of the log message
     * @param error Optional throwable to be logged. In the default implementation, the full
     *              stack trace (including nested causes) will be included in the log output.
     * @param message A lambda that produces the log message (evaluated lazily)
     */
    fun log(priority: Int, tag: String, error: Throwable?, message: () -> String)

    companion object {
        /** Verbose log level - use for detailed debugging information */
        const val VERBOSE = Log.VERBOSE

        /** Debug log level - use for debugging messages */
        const val DEBUG = Log.DEBUG

        /** Info log level - use for informational messages */
        const val INFO = Log.INFO

        /** Warning log level - use for warning messages */
        const val WARN = Log.WARN

        /** Error log level - use for error messages */
        const val ERROR = Log.ERROR

        /** Assert log level - use for assertion failures that should never happen */
        const val ASSERT = Log.ASSERT


        /**
         * Creates a default Logger implementation with priority-based filtering.
         *
         * The returned Logger is thread-safe and stateless, so it can be safely
         * used concurrently from multiple threads without synchronization.
         *
         * @param threshold The minimum priority level to log (default: WARN).
         *                  Messages below this level will be ignored.
         * @return A Logger instance that logs to Android's Log system
         */
        operator fun invoke(threshold: Int = WARN): Logger {
            return Logger { priority, tag, error, message ->
                // Only log if priority meets or exceeds the threshold
                if (priority >= threshold) {
                    val msg = if (error == null) {
                        message()
                    } else {
                        buildString {
                            appendLine(message())
                            appendLine(error.getStackTraceString())
                        }
                    }
                    Log.println(priority, tag, msg)
                }
            }
        }
    }
}
