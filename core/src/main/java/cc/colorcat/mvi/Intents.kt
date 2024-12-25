package cc.colorcat.mvi

import kotlinx.coroutines.flow.Flow

/**
 * Author: ccolorcat
 * Date: 2024-12-24
 * GitHub: https://github.com/ccolorcat
 */
fun interface IntentHandler<I : MVI.Intent, S : MVI.State, E : MVI.Event> {
    suspend fun handle(intent: I): Flow<MVI.PartialChange<S, E>>
}


interface IntentHandlerRegistry<I : MVI.Intent, S : MVI.State, E : MVI.Event> {
    fun <T : I> register(intentType: Class<T>, handler: IntentHandler<T, S, E>)

    fun unregister(intentType: Class<out I>)
}


internal class IntentHandlerDelegate<I : MVI.Intent, S : MVI.State, E : MVI.Event>(
    private val defaultHandler: IntentHandler<I, S, E>
) : IntentHandlerRegistry<I, S, E>, IntentHandler<I, S, E> {
    private val handlers = mutableMapOf<Class<*>, IntentHandler<*, S, E>>()

    override fun <T : I> register(intentType: Class<T>, handler: IntentHandler<T, S, E>) {
        handlers[intentType] = handler
    }

    override fun unregister(intentType: Class<out I>) {
        handlers.remove(intentType)
    }

    override suspend fun handle(intent: I): Flow<MVI.PartialChange<S, E>> {
        @Suppress("UNCHECKED_CAST")
        val handler = handlers[intent.javaClass] as? IntentHandler<I, S, E> ?: defaultHandler
        return handler.handle(intent)
    }
}