package cc.colorcat.mvi.sample

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.widget.TextView
import android.widget.Toast
import cc.colorcat.mvi.Mvi
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.random.Random

/**
 * Author: ccolorcat
 * Date: 2025-11-14
 * GitHub: https://github.com/ccolorcat
 */
fun Context.showToast(text: CharSequence, duration: Int = Toast.LENGTH_SHORT) {
    Toast.makeText(this, text, duration).show()
}

suspend fun randomDelay(min: Long = 1000, max: Long = 3000) =
    delay(Random.nextLong(max - min) + min)


fun <I : Mvi.Intent> TextView.doOnTextChanged(
    block: ProducerScope<I>.(text: CharSequence?) -> Unit
): Flow<I> = callbackFlow {
    val watcher = object : TextWatcher {
        override fun beforeTextChanged(
            s: CharSequence?,
            start: Int,
            count: Int,
            after: Int
        ) {
        }

        override fun onTextChanged(
            s: CharSequence?,
            start: Int,
            before: Int,
            count: Int
        ) {
            this@callbackFlow.block(s)
        }

        override fun afterTextChanged(s: Editable?) {
        }
    }

    this@doOnTextChanged.addTextChangedListener(watcher)
    awaitClose { this@doOnTextChanged.removeTextChangedListener(watcher) }
}
