package cc.colorcat.mvi.sample

import cc.colorcat.mvi.MVIViewModel
import cc.colorcat.mvi.ReactiveContract
import cc.colorcat.mvi.asFlow
import kotlinx.coroutines.flow.Flow

/**
 * Author: ccolorcat
 * Date: 2024-08-01
 * GitHub: https://github.com/ccolorcat
 */
class MainViewModel3 : MVIViewModel<IMain.Intent, IMain.State, IMain.Event>(IMain.State()) {
    override fun setupContract(contract: ReactiveContract<IMain.Intent, IMain.State, IMain.Event>) {
        contract.register(IMain.Intent.Increment::class.java, ::handleIncrement)
        contract.register(IMain.Intent.Decrement::class.java, ::handleDecrement)
    }

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
