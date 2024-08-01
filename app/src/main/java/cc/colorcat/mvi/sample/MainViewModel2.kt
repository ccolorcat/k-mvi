package cc.colorcat.mvi.sample

import androidx.lifecycle.ViewModel
import cc.colorcat.mvi.asFlow
import cc.colorcat.mvi.contract
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Author: ccolorcat
 * Date: 2024-05-10
 * GitHub: https://github.com/ccolorcat
 */
class MainViewModel2 : ViewModel() {
    private val contract by contract(IMain2.State(), ::handle)

    val stateFlow: StateFlow<IMain2.State>
        get() = contract.stateFlow

    val eventFlow: Flow<IMain2.Event>
        get() = contract.eventFlow

    fun dispatch(intent: IMain2.Intent) = contract.dispatch(intent)

    private suspend fun handle(intent: IMain2.Intent): Flow<IMain2.PartialChange> {
        return when (intent) {
            is IMain2.PartialChange -> intent.asFlow()
        }
    }
}
