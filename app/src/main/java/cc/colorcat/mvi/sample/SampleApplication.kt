package cc.colorcat.mvi.sample

import android.app.Application
import cc.colorcat.mvi.KMvi
import cc.colorcat.mvi.Logger

/**
 * Application entry point for the K-MVI sample app.
 *
 * Configures [KMvi] with DEBUG-level logging so all internal framework log messages
 * (intent dispatch, handler routing, retry decisions) are visible during development.
 * This is the recommended pattern — call [KMvi.configure] once in [Application.onCreate].
 */
class SampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        KMvi.configure {
            copy(logger = Logger(Logger.DEBUG))
        }
    }
}
