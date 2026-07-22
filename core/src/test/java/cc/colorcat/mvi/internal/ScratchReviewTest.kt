package cc.colorcat.mvi.internal

import cc.colorcat.mvi.DispatchResult
import cc.colorcat.mvi.FatalErrorHandler
import cc.colorcat.mvi.GroupTagSelector
import cc.colorcat.mvi.HandleStrategy
import cc.colorcat.mvi.HybridStrategyConfig
import cc.colorcat.mvi.IntentHandler
import cc.colorcat.mvi.IntentQueueConfig
import cc.colorcat.mvi.IntentTransformer
import cc.colorcat.mvi.KMvi
import cc.colorcat.mvi.Logger
import cc.colorcat.mvi.Mvi
import cc.colorcat.mvi.TestLogger
import cc.colorcat.mvi.strategyTransformer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import java.util.concurrent.atomic.AtomicInteger

/**
 * Behavioral tests pinning down the pipeline's failure semantics so regressions stay visible.
 *
 * 1. [normallyCompletingTransformerDoesNotLeaveZombieContract] — a transformer that completes
 *    while the contract scope is still active is converted into a fatal [IllegalStateException]
 *    and the intent queue is closed, so [dispatch] returns [DispatchResult.Unavailable] instead
 *    of silently reporting `Submitted` forever (the "zombie contract" symptom).
 *
 * 2. [retriableHandlerFailureDoesNotCancelInFlightSiblings] — retry is scoped to a single intent:
 *    a retriable failure is retried inside that intent's own flow and never propagates to the
 *    strategy's `flatMapMerge`, so a concurrent in-flight sibling keeps running and the failed
 *    intent recovers on its own retry (no whole-pipeline restart, no silent loss of siblings).
 */
class ScratchReviewTest {

    @Rule
    @JvmField
    val testLog: TestRule = TestLogger()

    private data class S(val count: Int = 0) : Mvi.State
    private sealed interface E : Mvi.Event
    private sealed interface I : Mvi.Intent {
        data object A : I
        data object B : I
    }

    @Before
    fun setUp() {
        KMvi.configure { KMvi.Configuration(logger = Logger { _, _, _, _ -> }) }
    }

    @After
    fun tearDown() {
        KMvi.configure { KMvi.Configuration() }
    }

    // A normally-completing transformer must not leave a zombie contract.
    @Test
    fun normallyCompletingTransformerDoesNotLeaveZombieContract() = runBlocking {
        val fatal = CompletableDeferred<Throwable>()
        // SupervisorJob + swallowing handler keeps the scope alive after the sharing coroutine
        // rethrows, mirroring a real viewModelScope that survives the failure.
        val scope = CoroutineScope(SupervisorJob() + CoroutineExceptionHandler { _, _ -> })
        val contract = CoreReactiveContract<I, S, E>(
            scope = scope,
            initState = S(),
            intentQueueConfig = IntentQueueConfig(capacity = 64),
            errorHandler = FatalErrorHandler { error ->
                fatal.complete(error)
                throw error
            },
            transformer = IntentTransformer { emptyFlow() },
        )

        try {
            val error = withTimeout(1_000) { fatal.await() }
            assertTrue(error is IllegalStateException)
            assertTrue(error.message.orEmpty().contains("IntentTransformer completed"))
            // Scope is still active, yet the queue is closed: dispatch is rejected deterministically
            // instead of silently reporting Submitted (the zombie symptom).
            assertTrue(scope.isActive)
            assertEquals(DispatchResult.Unavailable, contract.dispatch(I.A))
        } finally {
            scope.cancel()
        }
    }

    // Retry is per-intent: a retriable failure in one handler must not tear down the CONCURRENT
    // merge, so an in-flight sibling survives and the failed intent recovers on its own retry.
    @Test
    fun retriableHandlerFailureDoesNotCancelInFlightSiblings() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val aStarted = CompletableDeferred<Unit>()
        val aCancelled = CompletableDeferred<Unit>()
        val bStartCount = AtomicInteger(0)
        val contract = CoreReactiveContract<I, S, E>(
            scope = scope,
            initState = S(),
            intentQueueConfig = IntentQueueConfig(capacity = 64),
            errorHandler = FatalErrorHandler.Rethrow,
            transformer = strategyTransformer(
                handleStrategy = HandleStrategy.CONCURRENT,
                hybridStrategyConfig = HybridStrategyConfig(),
                groupTagSelector = GroupTagSelector.byClass(),
                // Retry the first failure of each intent's own flow.
                retryPolicy = { _, attempt, _ -> attempt == 0L },
                handler = IntentHandler<I, S, E> { intent ->
                    when (intent) {
                        I.A -> flow {
                            aStarted.complete(Unit)
                            try {
                                awaitCancellation() // stays in-flight; only a merge teardown cancels it
                            } finally {
                                aCancelled.complete(Unit)
                            }
                        }

                        I.B -> flow {
                            if (bStartCount.getAndIncrement() == 0) {
                                throw RuntimeException("B fails once, then recovers")
                            }
                            emit(Mvi.PartialChange { it.updateState { copy(count = count + 1) } })
                        }
                    }
                },
            ),
        )

        try {
            contract.dispatch(I.A)
            withTimeout(1_000) { aStarted.await() } // A is genuinely in-flight
            contract.dispatch(I.B)

            // B's first attempt throws, retryWhen re-runs B's own flow, and it recovers.
            val state = withTimeout(1_000) { contract.stateFlow.first { it.count == 1 } }

            assertEquals(1, state.count)      // B recovered on retry
            assertEquals(2, bStartCount.get()) // B ran exactly twice (fail, then succeed)
            assertFalse(aCancelled.isCompleted) // the in-flight sibling A was never cancelled
            assertTrue(scope.isActive)
        } finally {
            scope.cancel()
        }
    }
}
