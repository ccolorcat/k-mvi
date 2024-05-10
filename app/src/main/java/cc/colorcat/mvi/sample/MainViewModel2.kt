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
class MainViewModel2 : ViewModel() {
    private val container by containers(IMain2.State(), ::handle)

    val stateFlow: StateFlow<IMain2.State>
        get() = container.stateFlow

    val eventFlow: Flow<IMain2.Event>
        get() = container.eventFlow

    fun dispatch(action: IMain2.Action) = container.dispatch(action)

    private suspend fun handle(action: IMain2.Action): Flow<IMain2.PartialChange> {
        return when (action) {
            is IMain2.PartialChange -> action.asFlow()
        }
    }
}
