package cc.colorcat.mvi

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Author: ccolorcat
 * Date: 2024-08-01
 * GitHub: https://github.com/ccolorcat
 */
open class MVIViewModel<I : MVI.Intent, S : MVI.State, E : MVI.Event>(initState: S) : ViewModel() {
    protected val contract: ReactiveContract<I, S, E> by contract(initState, ::handle, ::setupContract)

    open val stateFlow: StateFlow<S>
        get() = contract.stateFlow

    open val eventFlow: Flow<E>
        get() = contract.eventFlow

    fun dispatch(intent: I) {
        contract.dispatch(intent)
    }


    protected open fun setupContract(contract: ReactiveContract<I, S, E>) {

    }

    protected open suspend fun handle(intent: I): Flow<MVI.PartialChange<S, E>> {
        return emptyFlow()
    }
}


open class MVIAndroidViewModel<I : MVI.Intent, S : MVI.State, E : MVI.Event>(
    initState: S,
    application: Application,
) : AndroidViewModel(application) {
    protected val context: Application
        get() = getApplication()

    protected val contract: ReactiveContract<I, S, E> by contract(initState, ::handle, ::setupContract)

    open val stateFlow: StateFlow<S>
        get() = contract.stateFlow

    open val eventFlow: Flow<E>
        get() = contract.eventFlow

    fun dispatch(intent: I) {
        contract.dispatch(intent)
    }

    protected open fun setupContract(contract: ReactiveContract<I, S, E>) {

    }

    protected open suspend fun handle(intent: I): Flow<MVI.PartialChange<S, E>> {
        return emptyFlow()
    }
}
