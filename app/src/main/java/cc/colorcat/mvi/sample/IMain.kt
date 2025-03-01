package cc.colorcat.mvi.sample

import cc.colorcat.mvi.Mvi

/**
 * Author: ccolorcat
 * Date: 2024-05-10
 * GitHub: https://github.com/ccolorcat
 */
sealed interface IMain {
    data class State(
        val count: Int = 0
    ) : Mvi.State {
        val countText: CharSequence
            get() = count.toString()
    }

    sealed interface Event : Mvi.Event {
        data class ShowToast(val message: CharSequence) : Event
    }

    sealed interface Intent : Mvi.Intent {
        data object Increment : Intent, Mvi.Intent.Concurrent

        data object Decrement : Intent, Mvi.Intent.Sequential

        data object Test : Intent, Mvi.Intent.Sequential

        data object LoadTest : Intent, Mvi.Intent.Concurrent
    }

    fun interface PartialChange : Mvi.PartialChange<State, Event>
}
