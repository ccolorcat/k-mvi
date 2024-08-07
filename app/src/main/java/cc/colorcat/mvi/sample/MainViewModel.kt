package cc.colorcat.mvi.sample

import androidx.lifecycle.ViewModel
import cc.colorcat.mvi.ReactiveContract
import cc.colorcat.mvi.asFlow
import cc.colorcat.mvi.contract
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Author: ccolorcat
 * Date: 2024-05-10
 * GitHub: https://github.com/ccolorcat
 */
class MainViewModel : ViewModel() {
    private val contract: ReactiveContract<IMain.Intent, IMain.State, IMain.Event> by contract(IMain.State()) {
        register(IMain.Intent.Increment::class.java, ::handleIncrement)
        register(IMain.Intent.Decrement::class.java, ::handleDecrement)
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
}
