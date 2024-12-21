package cc.colorcat.mvi

import android.util.Log


/**
 * Author: ccolorcat
 * Date: 2024-12-21
 * GitHub: https://github.com/ccolorcat
 */

internal val MVI.Intent.isConcurrent: Boolean
    get() = this is MVI.Intent.Concurrent && this !is MVI.Intent.Sequential

internal val MVI.Intent.isSequential: Boolean
    get() = this is MVI.Intent.Sequential && this !is MVI.Intent.Concurrent

/**
 * Checks if the Intent has conflicting types or doesn't fall into either Concurrent or Sequential categories.
 * Logs a warning if an Intent is marked both Concurrent and Sequential, as it may cause unpredictable behavior.
 */
internal val MVI.Intent.isFallback: Boolean
    get() {
        val isConflictingIntent = this is MVI.Intent.Concurrent && this is MVI.Intent.Sequential
        if (isConflictingIntent) {
            Log.w(
                "MVI_Intents",
                "${javaClass.name} implements both Concurrent and Sequential, which may lead to unpredictable behavior."
            )
        }
        return isConflictingIntent || (this !is MVI.Intent.Concurrent && this !is MVI.Intent.Sequential)
    }