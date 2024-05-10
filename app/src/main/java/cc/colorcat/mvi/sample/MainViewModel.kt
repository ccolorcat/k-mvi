package cc.colorcat.mvi.sample

import androidx.lifecycle.ViewModel
import cc.colorcat.mvi.asFlow
import cc.colorcat.mvi.containers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Author: ccolorcat
 * Date: 2024-05-10
 * GitHub: https://github.com/ccolorcat
 */
class MainViewModel : ViewModel() {
    private val container by containers<IMain.Action, IMain.State, IMain.Event>(IMain.State()) {
        register(IMain.Action.Increment::class.java, ::handleIncrement)
        register(IMain.Action.Decrement::class.java, ::handleDecrement)
    }

    val stateFlow: StateFlow<IMain.State>
        get() = container.stateFlow

    val eventFlow: Flow<IMain.Event>
        get() = container.eventFlow

    fun dispatch(action: IMain.Action) = container.dispatch(action)

    private fun handleIncrement(action: IMain.Action.Increment): Flow<IMain.PartialChange> {
        return IMain.PartialChange {
            val oldCount = it.state.count
            if (oldCount >= 99) {
                it.with(IMain.Event.ShowToast("Already reached 99"))
            } else {
                it.reduce { copy(count = oldCount + 1) }
            }
        }.asFlow()
    }

    private fun handleDecrement(action: IMain.Action.Decrement): Flow<IMain.PartialChange> {
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
