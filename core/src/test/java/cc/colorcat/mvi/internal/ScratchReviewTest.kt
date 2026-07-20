package cc.colorcat.mvi.internal

import cc.colorcat.mvi.DispatchResult
import cc.colorcat.mvi.FatalErrorHandler
import cc.colorcat.mvi.GroupTagSelector
import cc.colorcat.mvi.HandleStrategy
import cc.colorcat.mvi.HybridStrategyConfig
import cc.colorcat.mvi.IntentHandler
import cc.colorcat.mvi.IntentQueueConfig
import cc.colorcat.mvi.IntentTransformer
import cc.colorcat.mvi.Mvi
import cc.colorcat.mvi.strategyTransformer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test

class ScratchReviewTest {
    private data class S(val count: Int = 0) : Mvi.State
    private sealed interface E : Mvi.Event
    private sealed interface I : Mvi.Intent {
        data object A : I
        data object B : I
        data object C : I
    }

    // Contested claim #1: a normally-completing transformer leaves a zombie contract.
    @Test
    fun zombieOnNormalCompletion() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val contract = CoreReactiveContract<I, S, E>(
            scope = scope,
            initState = S(),
            intentQueueConfig = IntentQueueConfig(capacity = 64),
            retryPolicy = { _, _ -> false },
            fatalErrorHandler = FatalErrorHandler.Rethrow,
            transformer = IntentTransformer { emptyFlow() },
        )
        try {
            delay(150) // allow eager sharing to start and upstream to complete
            val r = contract.dispatch(I.A)
            println("SCRATCH#1 dispatch=$r scopeActive=${scope.isActive}")
        } finally {
            scope.cancel()
        }
    }

    // Contested claim #2: under CONCURRENT, one handler failing cancels in-flight
    // siblings, and pipeline retry does NOT replay them.
    @Test
    fun concurrentSiblingLossOnRetry() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val siblingStarted = CompletableDeferred<Unit>()
        val siblingEmitted = CompletableDeferred<Unit>()
        val contract = CoreReactiveContract<I, S, E>(
            scope = scope,
            initState = S(),
            intentQueueConfig = IntentQueueConfig(capacity = 64),
            retryPolicy = { attempt, _ -> attempt < 1 }, // retry once => pipeline restarts, not fatal
            fatalErrorHandler = FatalErrorHandler.Rethrow,
            transformer = strategyTransformer(
                handleStrategy = HandleStrategy.CONCURRENT,
                hybridStrategyConfig = HybridStrategyConfig(),
                groupTagSelector = GroupTagSelector.byClass(),
                handler = IntentHandler<I, S, E> { intent ->
                    when (intent) {
                        I.A -> kotlinx.coroutines.flow.flow {
                            siblingStarted.complete(Unit)
                            delay(300) // long-running sibling
                            siblingEmitted.complete(Unit)
                            emit(Mvi.PartialChange { it.updateState { copy(count = count + 1) } })
                        }
                        I.B -> kotlinx.coroutines.flow.flow {
                            delay(50)
                            throw RuntimeException("B fails first")
                        }
                        else -> emptyFlow()
                    }
                },
            ),
        )
        try {
            contract.dispatch(I.A)
            withTimeout(1000) { siblingStarted.await() } // A is genuinely in-flight
            contract.dispatch(I.B)                        // B will fail at t=50, before A emits at t=300
            delay(600)                                    // past A's would-be emit time
            val siblingDidEmit = siblingEmitted.isCompleted
            val count = contract.stateFlow.value.count
            // Prove pipeline still alive after retry: C must be processed.
            val cResult = contract.dispatch(I.C)
            println("SCRATCH#2 siblingDidEmit=$siblingDidEmit count=$count afterRetryDispatch=$cResult scopeActive=${scope.isActive}")
        } finally {
            scope.cancel()
        }
    }
}
