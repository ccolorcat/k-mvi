package cc.colorcat.mvi

import android.util.Log

/**
 * Author: ccolorcat
 * Date: 2024-12-31
 * GitHub: https://github.com/ccolorcat
 */
fun interface Logger {
    fun log(priority: Int, tag: String, error: Throwable?, message: () -> String)

    companion object {
        const val DEBUG = Log.DEBUG
        const val INFO = Log.INFO
        const val WARN = Log.WARN
        const val ERROR = Log.ERROR

        operator fun invoke(threshold: Int = WARN): Logger {
            return Logger { priority, tag, error, message ->
                if (priority >= threshold) {
                    val msg = if (error == null) {
                        message()
                    } else {
                        buildString {
                            append(message())
                            append("\n").append(error.javaClass.name).append(": ").append(error.message)
                            error.stackTrace.joinTo(this, separator = "\n\t", prefix = "\n\t") { it.toString() }
                        }
                    }
                    Log.println(priority, tag, msg)
                }
            }
        }
    }
}
