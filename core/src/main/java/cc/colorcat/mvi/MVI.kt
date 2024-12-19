package cc.colorcat.mvi

import kotlinx.coroutines.flow.Flow

/**
 * Author: ccolorcat
 * Date: 2024-05-10
 * GitHub: https://github.com/ccolorcat
 */
sealed interface MVI {
    interface Intent

    interface State

    interface Event


    fun interface PartialChange<S : State, E : Event> {
        fun apply(old: Snapshot<S, E>): Snapshot<S, E>
    }


    fun interface IntentHandler<I : Intent, S : State, E : Event> {
        suspend fun handle(intent: I): Flow<PartialChange<S, E>>
    }


    data class Snapshot<S : State, E : Event> internal constructor(val state: S, val event: E? = null) {
        fun update(update: S.() -> S): Snapshot<S, E> {
            val newState = this.state.update()
            return this.copy(state = newState, event = null)
        }

        fun with(event: E): Snapshot<S, E> = this.copy(event = event)

        fun pack(event: E, update: S.() -> S): Snapshot<S, E> {
            val newState = this.state.update()
            return this.copy(state = newState, event = event)
        }
    }
}
