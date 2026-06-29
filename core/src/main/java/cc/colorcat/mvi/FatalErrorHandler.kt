package cc.colorcat.mvi

/**
 * Handles unrecoverable failures in the MVI processing pipeline.
 *
 * This hook is for developer errors and failures that the configured [RetryPolicy] gives up on.
 * It is not a recovery mechanism: implementations must not return normally.
 */
fun interface FatalErrorHandler {
    fun handle(error: Throwable): Nothing

    companion object {
        /**
         * Rethrows the error from the coroutine that observed the fatal failure.
         */
        val Rethrow: FatalErrorHandler = FatalErrorHandler { error ->
            throw error
        }
    }
}
