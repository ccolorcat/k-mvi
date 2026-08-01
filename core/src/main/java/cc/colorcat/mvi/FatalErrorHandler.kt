package cc.colorcat.mvi

/**
 * Handles terminal failures in the MVI processing pipeline.
 *
 * This hook is for developer errors and failures that the configured [RetryPolicy] gives up on.
 * K-MVI cancels the contract's intent queue before invoking it, so it cannot recover or restart
 * processing. Implementations choose how the terminal failure is exposed:
 * - Return normally to suppress exception propagation after observing or reporting the failure.
 * - Throw the received error or another exception to propagate it from the processing coroutine.
 *
 * In either case, the contract remains unavailable and later dispatches return
 * [DispatchResult.Unavailable].
 */
fun interface FatalErrorHandler {
    /**
     * Handles the terminal [error]. Returning suppresses exception propagation; throwing exposes
     * the original or a replacement exception. Neither choice resumes contract processing.
     */
    fun handle(error: Throwable)

    companion object {
        /**
         * Propagates the original error from the processing coroutine.
         */
        val Rethrow: FatalErrorHandler = FatalErrorHandler { error ->
            throw error
        }
    }
}
