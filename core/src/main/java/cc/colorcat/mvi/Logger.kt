package cc.colorcat.mvi

import android.util.Log

/**
 * Author: ccolorcat
 * Date: 2024-12-31
 * GitHub: https://github.com/ccolorcat
 */
fun interface Logger {
    fun println(priority: Int, tag: String, error: Throwable?, message: () -> String)

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
                        StringBuilder(message()).apply {
                            error.stackTrace.forEach {
                                append('\n').append(it.toString())
                            }
                        }.toString()
                    }
                    Log.println(priority, tag, msg)
                }
            }
        }
    }
}
