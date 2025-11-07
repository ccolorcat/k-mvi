package cc.colorcat.mvi.sample

import cc.colorcat.mvi.Mvi

/**
 * Author: ccolorcat
 * Date: 2024-05-10
 * GitHub: https://github.com/ccolorcat
 */
sealed interface IMain2 {
    data class State(
        val count: Int = 0
    ) : Mvi.State {
        val countText: CharSequence
            get() = count.toString()
    }

    sealed interface Event : Mvi.Event {
        data class ShowToast(val message: CharSequence) : Event
    }

    sealed interface Intent : Mvi.Intent


    sealed class PartialChange : Mvi.PartialChange<State, Event> {
        override fun apply(old: Mvi.Snapshot<State, Event>): Mvi.Snapshot<State, Event> {
            val oldCount = old.state.count
            return when (this) {
                is Increment -> {
                    if (oldCount >= 99) {
                        old.withEvent(Event.ShowToast("Already reached 99"))
                    } else {
                        old.updateState { copy(count = oldCount + 1) }
                    }
                }

                is Decrement -> {
                    if (oldCount <= 0) {
                        old.withEvent(Event.ShowToast("Already reached 0"))
                    } else {
                        old.updateState { copy(count = oldCount - 1) }
                    }
                }
            }
        }
    }

    data object Increment : PartialChange(), Intent

    data object Decrement : PartialChange(), Intent
}
