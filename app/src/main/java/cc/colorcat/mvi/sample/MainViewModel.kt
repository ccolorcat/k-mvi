package cc.colorcat.mvi.sample

import android.util.Log
import androidx.lifecycle.ViewModel
import cc.colorcat.mvi.ReactiveContract
import cc.colorcat.mvi.asFlow
import cc.colorcat.mvi.contract
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow

/**
 * Author: ccolorcat
 * Date: 2024-05-10
 * GitHub: https://github.com/ccolorcat
 */
class MainViewModel : ViewModel() {
    private val contract: ReactiveContract<IMain.Intent, IMain.State, IMain.Event> by contract(
        initState = IMain.State(),
        defaultHandler = ::handleTest
    ) {
        register(IMain.Intent.Increment::class.java, ::handleIncrement)
        register(IMain.Intent.Decrement::class.java, ::handleDecrement)
//        register(IMain.Intent.Test::class.java, ::handleTest)
    }

    val stateFlow: StateFlow<IMain.State>
        get() = contract.stateFlow

    val eventFlow: Flow<IMain.Event>
        get() = contract.eventFlow

    fun dispatch(intent: IMain.Intent) = contract.dispatch(intent)

    private fun handleIncrement(intent: IMain.Intent.Increment): Flow<IMain.PartialChange> {
        return IMain.PartialChange {
            val oldCount = it.state.count
            if (oldCount >= 99) {
                it.with(IMain.Event.ShowToast("Already reached 99"))
            } else {
                it.reduce { copy(count = oldCount + 1) }
            }
        }.asFlow()
    }

    private fun handleDecrement(intent: IMain.Intent.Decrement): Flow<IMain.PartialChange> {
        return IMain.PartialChange {
            val oldCount = it.state.count
            if (oldCount <= 0) {
                it.with(IMain.Event.ShowToast("Already reached 0"))
            } else {
                it.reduce { copy(count = oldCount - 1) }
            }
        }.asFlow()
    }

    private var count = 0

    private fun handleTest(intent: IMain.Intent): Flow<IMain.PartialChange> {
        count++
        Log.d("MainActivity", "handleTest count = $count")
        return flow<IMain.PartialChange> {
            delay(500L)
            emit(IMain.PartialChange { it.with(IMain.Event.ShowToast("test start $count")) })
            delay(1000L)
            emit(IMain.PartialChange { it.with(IMain.Event.ShowToast("test succeed $count")) })
            delay(5000L)
            emit(IMain.PartialChange { it.with(IMain.Event.ShowToast("test completed $count")) })
        }
    }
}
