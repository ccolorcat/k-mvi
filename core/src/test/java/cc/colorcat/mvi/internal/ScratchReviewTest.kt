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
import cc.colorcat.mvi.asSingleFlow
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import java.util.concurrent.atomic.AtomicInteger

/**
 * Characterization tests for the two P1 findings in
 * `docs/reactive-contract-snapshots-review-2026-07-20.md`.
 *
 * These are behavioral tests that pin down what the pipeline actually does for the two
 * failure modes raised in the review, so regressions become visible:
 *
 * 1. [normallyCompletingTransformerDoesNotLeaveZombieContract] — verifies the **fix**:
 *    a transformer that completes while the contract scope is still active is converted
 *    into a fatal [IllegalStateException] and the intent queue is closed, so [dispatch]
 *    returns [DispatchResult.Unavailable] instead of silently reporting `Submitted`
 *    forever (the "zombie contract" symptom).
 *
 * 2. [concurrentHandlerFailureCancelsInFlightSiblingsWithoutReplay] — documents the
 *    still-open behavior: under [HandleStrategy.CONCURRENT], one handler failing tears
 *    down the merged pipeline and cancels in-flight siblings. A pipeline-level retry
 *    restarts processing but does **not** replay the already-consumed intents; the
 *    contract nevertheless stays alive for subsequent intents.
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
        data object C : I
    }

    @Before
    fun setUp() {
        KMvi.configure { KMvi.Configuration(logger = Logger { _, _, _, _ -> }) }
    }

    @After
    fun tearDown() {
        KMvi.configure { KMvi.Configuration() }
    }

    // Review finding #1 (fixed): a normally-completing transformer must not leave a zombie contract.
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
            retryPolicy = { _, _ -> false },
            fatalErrorHandler = FatalErrorHandler { error ->
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

    // Review finding #2 (open): under CONCURRENT, one handler failing cancels in-flight siblings,
    // and a pipeline retry does NOT replay the already-consumed intents.
    @Test
    fun concurrentHandlerFailureCancelsInFlightSiblingsWithoutReplay() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val aStartCount = AtomicInteger(0)
        val aStarted = CompletableDeferred<Unit>()
        val aCancelled = CompletableDeferred<Unit>()
        val bStarted = CompletableDeferred<Unit>()
        val contract = CoreReactiveContract<I, S, E>(
            scope = scope,
            initState = S(),
            intentQueueConfig = IntentQueueConfig(capacity = 64),
            // Retry once so B's failure restarts the pipeline (recoverable, not fatal).
            retryPolicy = { attempt, _ -> attempt < 1 },
            fatalErrorHandler = FatalErrorHandler.Rethrow,
            transformer = strategyTransformer(
                handleStrategy = HandleStrategy.CONCURRENT,
                hybridStrategyConfig = HybridStrategyConfig(),
                groupTagSelector = GroupTagSelector.byClass(),
                handler = IntentHandler<I, S, E> { intent ->
                    when (intent) {
                        I.A -> flow<Mvi.PartialChange<S, E>> {
                            aStartCount.incrementAndGet()
                            aStarted.complete(Unit)
                            try {
                                awaitCancellation() // stays in-flight until a sibling failure tears it down
                            } finally {
                                aCancelled.complete(Unit)
                            }
                        }

                        I.B -> flow<Mvi.PartialChange<S, E>> {
                            bStarted.complete(Unit)
                            throw RuntimeException("B fails first")
                        }

                        I.C -> Mvi.PartialChange<S, E> { it.updateState { copy(count = count + 1) } }
                            .asSingleFlow()
                    }
                },
            ),
        )

        try {
            contract.dispatch(I.A)
            withTimeout(1_000) { aStarted.await() } // A is genuinely in-flight
            contract.dispatch(I.B)
            withTimeout(1_000) { bStarted.await() }
            // B's failure tears down the CONCURRENT merge, cancelling the in-flight sibling A.
            withTimeout(1_000) { aCancelled.await() }

            // The pipeline survives the retry: a newly dispatched intent is still processed.
            contract.dispatch(I.C)
            val state = withTimeout(1_000) { contract.stateFlow.first { it.count == 1 } }

            assertEquals(1, state.count) // only C applied; A/B produced no state change
            assertEquals(1, aStartCount.get()) // A ran once and was NOT replayed after the retry
        } finally {
            scope.cancel()
        }
    }
}
