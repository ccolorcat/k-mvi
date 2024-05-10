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
fun <A : MVI.Action> View.doOnClick(block: ProducerScope<A>.() -> Unit): Flow<A> {
    return callbackFlow {
        setOnClickListener {
            this.block()
        }
        awaitClose { setOnClickListener(null) }
    }
}

fun <A : MVI.Action> CompoundButton.doOnCheckedChange(block: ProducerScope<A>.(Boolean) -> Unit): Flow<A> {
    return callbackFlow {
        setOnCheckedChangeListener { _, isChecked ->
            this.block(isChecked)
        }
        awaitClose { setOnCheckedChangeListener(null) }
    }
}

fun <A : MVI.Action> TextView.afterTextChanged(
    debounceTimeoutMillis: Long = 500L,
    block: ProducerScope<A>.(Editable?) -> Unit
): Flow<A> {
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
        flow.debounce(debounceTimeoutMillis)
    } else {
        flow
    }
}

fun <T> T.asFlow(): Flow<T> = flowOf(this)

@OptIn(FlowPreview::class)
fun <T> Flow<T>.debounce2(timeMillis: Long, responseFirst: Boolean = true): Flow<T> {
    return if (responseFirst) {
        flow {
            var time = -1L
            collect { value ->
                val lastTime = time
                time = System.currentTimeMillis()
                if (lastTime == -1L || time - lastTime > timeMillis) {
                    emit(value)
                }
            }
        }
    } else {
        debounce(timeMillis)
    }
}
