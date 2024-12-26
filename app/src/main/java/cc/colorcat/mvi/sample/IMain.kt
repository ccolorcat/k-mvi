package cc.colorcat.mvi.sample

import cc.colorcat.mvi.MVI

/**
 * Author: ccolorcat
 * Date: 2024-05-10
 * GitHub: https://github.com/ccolorcat
 */
sealed interface IMain {
    data class State(
        val count: Int = 0
    ) : MVI.State {
        val countText: CharSequence
            get() = count.toString()
    }

    sealed interface Event : MVI.Event {
        data class ShowToast(val message: CharSequence) : Event
    }

    sealed interface Intent : MVI.Intent {
        data object Increment : Intent, MVI.Intent.Concurrent, MVI.Intent.Sequential

        data object Decrement : Intent, MVI.Intent.Sequential

        data object Test : Intent, MVI.Intent.Sequential

        data object LoadTest : Intent, MVI.Intent.Concurrent
    }

    fun interface PartialChange : MVI.PartialChange<State, Event>
}
