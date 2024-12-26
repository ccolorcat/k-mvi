package cc.colorcat.mvi.sample

import android.app.Application
import cc.colorcat.mvi.MVIKit

/**
 * Author: ccolorcat
 * Date: 2024-12-26
 * GitHub: https://github.com/ccolorcat
 */
class SampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MVIKit.setup {
            copy(loggable = true)
        }
    }
}
