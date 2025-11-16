package cc.colorcat.mvi.sample

import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * Author: ccolorcat
 * Date: 2025-11-14
 * GitHub: https://github.com/ccolorcat
 */
fun Context.showToast(text: CharSequence, duration: Int = Toast.LENGTH_SHORT) {
    Toast.makeText(this, text, duration).show()
}

suspend fun randomDelay() = delay(Random.nextLong(1000) + 500)
