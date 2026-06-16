package cc.colorcat.mvi.sample

import android.app.Application
import cc.colorcat.mvi.KMvi
import cc.colorcat.mvi.Logger

/**
 * Author: ccolorcat
 * Date: 2024-12-26
 * GitHub: https://github.com/ccolorcat
 */
class SampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        KMvi.configure {
            copy(logger = Logger(Logger.DEBUG))
        }
    }
}
