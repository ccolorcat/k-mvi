package cc.colorcat.mvi

import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.CompoundButton
import android.widget.TextView
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

/**
 * Author: ccolorcat
 * Date: 2024-05-10
 * GitHub: https://github.com/ccolorcat
 */
fun <T> T.asSingleFlow(): Flow<T> = flowOf(this)

fun <T> Flow<T>.debounce2(timeMillis: Long, responseFirst: Boolean = true): Flow<T> {
    return if (responseFirst) {
        flow {
            var time = -1L
            collect { value ->
                val lastTime = time
                time = System.currentTimeMillis()
                if (time - lastTime > timeMillis) {
                    emit(value)
                }
            }
        }
    } else {
        @OptIn(FlowPreview::class)
        debounce(timeMillis)
    }
}


fun <I : MVI.Intent> View.doOnClick(block: ProducerScope<I>.() -> Unit): Flow<I> = callbackFlow {
    setOnClickListener {
        this.block()
    }
    awaitClose { setOnClickListener(null) }
}

fun <I : MVI.Intent> CompoundButton.doOnCheckedChange(
    block: ProducerScope<I>.(isChecked: Boolean) -> Unit
): Flow<I> = callbackFlow {
    setOnCheckedChangeListener { _, isChecked ->
        this.block(isChecked)
    }
    awaitClose { setOnCheckedChangeListener(null) }
}

fun <I : MVI.Intent> TextView.afterTextChanged(
    debounceTimeoutMillis: Long = 500L,
    block: ProducerScope<I>.(Editable?) -> Unit
): Flow<I> {
    val flow = callbackFlow {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                this@callbackFlow.block(s)
            }
        }
        addTextChangedListener(watcher)
        awaitClose { removeTextChangedListener(watcher) }
    }
    return if (debounceTimeoutMillis > 0L) {
        @OptIn(FlowPreview::class)
        flow.debounce(debounceTimeoutMillis)
    } else {
        flow
    }
}
