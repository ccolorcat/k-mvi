package cc.colorcat.mvi

import kotlinx.coroutines.flow.Flow

/**
 * Author: ccolorcat
 * Date: 2024-05-10
 * GitHub: https://github.com/ccolorcat
 */
sealed interface MVI {
    interface Action

    interface State

    interface Event


    fun interface PartialChange<S : State, E : Event> {
        fun reduce(old: Frame<S, E>): Frame<S, E>
    }


    fun interface ActionHandler<A : Action, S : State, E : Event> {
        suspend fun handle(action: A): Flow<PartialChange<S, E>>
    }


    data class Frame<S : State, E : Event> internal constructor(val state: S, val event: E? = null) {
        fun reduce(reduce: S.() -> S): Frame<S, E> {
            val newState = this.state.reduce()
            return this.copy(state = newState, event = null)
        }

        fun with(event: E): Frame<S, E> = this.copy(event = event)

        fun pack(event: E, reduce: S.() -> S): Frame<S, E> {
            val newState = this.state.reduce()
            return this.copy(state = newState, event = event)
        }
    }
}
