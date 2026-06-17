package cc.colorcat.mvi.sample.util

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
 * Shows a Toast with the given message.
 *
 * @param text The message to display
 * @param duration Toast duration, defaults to [android.widget.Toast.LENGTH_SHORT]
 */
fun Context.showToast(text: CharSequence, duration: Int = Toast.LENGTH_SHORT) {
    Toast.makeText(this, text, duration).show()
}

/**
 * Suspends for a random duration between [min] and [max] milliseconds.
 *
 * Uses [kotlin.random.Random] for the delay duration. Useful for simulating
 * network latency or async processing in sample/demo code.
 *
 * @param min Minimum delay in milliseconds (default: 1000)
 * @param max Maximum delay in milliseconds (default: 3000)
 */
suspend fun randomDelay(min: Long = 1000, max: Long = 3000) =
    delay(Random.nextLong(max - min) + min)


/**
 * Converts text changes into a Flow of intents.
 *
 * This Flow must be collected on the main thread because it registers and
 * removes Android View listeners. The sample collects it through lifecycle-aware
 * Fragment helpers, which use `LifecycleOwner.lifecycleScope` and satisfy this requirement.
 */
fun <I : Mvi.Intent> TextView.doOnTextChanged(
    block: ProducerScope<I>.(text: CharSequence?) -> Unit,
): Flow<I> = callbackFlow {
    val watcher = object : TextWatcher {
        override fun beforeTextChanged(
            s: CharSequence?,
            start: Int,
            count: Int,
            after: Int,
        ) {
        }

        override fun onTextChanged(
            s: CharSequence?,
            start: Int,
            before: Int,
            count: Int,
        ) {
            this@callbackFlow.block(s)
        }

        override fun afterTextChanged(s: Editable?) {
        }
    }

    this@doOnTextChanged.addTextChangedListener(watcher)
    awaitClose { this@doOnTextChanged.removeTextChangedListener(watcher) }
}
