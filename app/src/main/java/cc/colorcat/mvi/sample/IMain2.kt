package cc.colorcat.mvi.sample

import cc.colorcat.mvi.MVI

/**
 * Author: ccolorcat
 * Date: 2024-05-10
 * GitHub: https://github.com/ccolorcat
 */
sealed interface IMain2 {
    data class State(
        val count: Int = 0
    ) : MVI.State {
        val countText: CharSequence
            get() = count.toString()
    }

    sealed interface Event : MVI.Event {
        data class ShowToast(val message: CharSequence) : Event
    }

    sealed interface Intent : MVI.Intent


    sealed class PartialChange : MVI.PartialChange<State, Event> {
        override fun apply(old: MVI.Snapshot<State, Event>): MVI.Snapshot<State, Event> {
            val oldCount = old.state.count
            return when (this) {
                is Increment -> {
                    if (oldCount >= 99) {
                        old.with(Event.ShowToast("Already reached 99"))
                    } else {
                        old.update { copy(count = oldCount + 1) }
                    }
                }

                is Decrement -> {
                    if (oldCount <= 0) {
                        old.with(Event.ShowToast("Already reached 0"))
                    } else {
                        old.update { copy(count = oldCount - 1) }
                    }
                }
            }
        }
    }

    data object Increment : PartialChange(), Intent

    data object Decrement : PartialChange(), Intent
}
