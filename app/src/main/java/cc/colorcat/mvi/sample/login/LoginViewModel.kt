package cc.colorcat.mvi.sample.login

import androidx.lifecycle.ViewModel
import cc.colorcat.mvi.HybridConfig
import cc.colorcat.mvi.asSingleFlow
import cc.colorcat.mvi.contract
import cc.colorcat.mvi.sample.login.LoginContract.Event
import cc.colorcat.mvi.sample.login.LoginContract.Intent
import cc.colorcat.mvi.sample.login.LoginContract.PartialChange
import cc.colorcat.mvi.sample.login.LoginContract.State
import cc.colorcat.mvi.sample.util.randomDelay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlin.random.Random

/**
 * ViewModel for authentication (login) feature demonstrating async operations in MVI pattern.
 *
 * **Design Pattern - Centralized Intent Handling:**
 *
 * This ViewModel demonstrates using a **centralized defaultHandler** to process all intents
 * in one place, rather than registering separate handlers for each intent type.
 *
 * **Advantages of centralized handling:**
 * 1. **Single Entry Point**: All intent processing logic is in one method ([dispatchIntent])
 * 2. **Easy Navigation**: Developers can quickly find where each intent is handled
 * 3. **Exhaustive Checking**: Kotlin's when expression ensures all intent types are handled
 * 4. **Clear Flow**: Intent → Handler → PartialChange flow is obvious
 *
 * **Comparison with Counter Sample:**
 * - Counter: Uses `register(::handleIncrement)` for each intent type (distributed)
 * - Login: Uses `defaultHandler = ::dispatchIntent` with when expression (centralized)
 *
 * **When to use each pattern:**
 * - Use **distributed** (Counter style) when:
 *   - Each intent has complex, independent logic
 *   - You want to test handlers in isolation
 *   - Handlers might be reused or composed
 *
 * - Use **centralized** (Login style) when:
 *   - Intent handling logic is straightforward
 *   - You want a clear overview of all intents
 *   - The contract defines most transformation logic
 *
 * **This sample showcases:**
 * - Centralized intent dispatching with defaultHandler
 * - PartialChange implementations in Contract (not ViewModel)
 * - Async operations with loading states
 * - Error handling with try-catch-finally
 * - Input validation with early returns
 *
 * Author: ccolorcat
 * Date: 2025-11-20
 * GitHub: https://github.com/ccolorcat
 */
class LoginViewModel : ViewModel() {
    private val contract by contract(
        initState = State(),
        config = HybridConfig(
            groupTagSelector = ::getIntentTag
        ),
        defaultHandler = ::dispatchIntent
    )

    private fun getIntentTag(intent: Intent): String = when (intent) {
        // Group authentication-related intents under a clear, semantic tag
        is Intent.Login, is Intent.Logout -> "auth"
        // Fallback: derive a stable, normalized tag from the intent class name
        else -> intent::class.simpleName ?: "fallback_tag"
    }

    val stateFlow: StateFlow<State> = contract.stateFlow
    val eventFlow: Flow<Event> = contract.eventFlow

    fun dispatch(intent: Intent) = contract.dispatch(intent)

    /**
     * Centralized intent dispatcher.
     *
     * All intents are routed through this single method, which delegates to
     * specific handlers or returns PartialChange flows directly.
     *
     * **Benefits:**
     * - Single source of truth for intent routing
     * - Exhaustive when expression ensures all intents are handled
     * - Easy to see all supported intents at a glance
     *
     * @param intent The intent to process
     * @return Flow of PartialChanges representing state transformations
     */
    private fun dispatchIntent(intent: Intent): Flow<PartialChange> {
        return when (intent) {
            is Intent.Login -> handleLogin(intent)
            is Intent.Logout -> handleLogout()
            is PartialChange -> intent.asSingleFlow()
        }
    }

    /**
     * Handles login intent with async operation.
     *
     * **Implementation Pattern:**
     * 1. **Input Validation**: Check for empty fields before starting async work
     * 2. **Loading State**: Emit StartLoading (also clears previous errors)
     * 3. **Async Authentication**: Simulate network call with randomDelay()
     * 4. **Business Validation**: Check password requirements
     * 5. **Result Handling**: Emit CompleteLogin or FailLogin
     * 6. **Cleanup**: Always stop loading in finally block
     *
     * **Note on PartialChange usage:**
     * All PartialChange types are defined in LoginContract, making it easy to
     * see what state transformations are possible without looking at ViewModel code.
     *
     * @param intent The login intent containing username and password
     * @return Flow emitting PartialChanges for each step of the login process
     */
    private fun handleLogin(intent: Intent.Login): Flow<PartialChange> = flow {
        // Validation: Check for empty inputs (early return pattern)
        if (intent.username.isBlank() || intent.password.isBlank()) {
            emit(PartialChange.SetErrorMessage("Username and password cannot be empty"))
            return@flow
        }

        try {
            // Start loading and clear previous errors
            emit(PartialChange.StartLoading)

            // Simulate async authentication (e.g., network call)
            randomDelay()

            // Business rule validation
            if (intent.password.length < 6) {
                throw IllegalArgumentException("Password must be at least 6 characters")
            }

            // Success: emit successful login with username
            emit(PartialChange.CompleteLogin(intent.username))

        } catch (e: Exception) {
            // Error: emit failure with error message
            emit(PartialChange.FailLogin("Login failed: ${e.message}"))
        } finally {
            // Cleanup: always stop loading
            emit(PartialChange.StopLoading)
        }
    }

    /**
     * Handles logout intent with async operation.
     *
     * **Implementation Pattern:**
     * 1. **Loading State**: Emit StartLoading to show progress
     * 2. **Async Operation**: Simulate clearing server session
     * 3. **Error Simulation**: 5% chance of failure to demonstrate error handling
     * 4. **Result Handling**: Emit CompleteLogout or FailLogout
     * 5. **Cleanup**: Always stop loading in finally block
     *
     * **Note:**
     * CompleteLogout automatically resets all authentication state (isLoggedIn, username)
     * as defined in the Contract's PartialChange implementation.
     *
     * @return Flow emitting PartialChanges for each step of the logout process
     */
    private fun handleLogout(): Flow<PartialChange> = flow {
        try {
            // Start loading and clear previous errors
            emit(PartialChange.StartLoading)

            // Simulate async logout operation (e.g., invalidating server session token)
            randomDelay()

            // Simulate occasional failure (5% chance) for demonstration
            if (Random.nextInt(101) > 95) {
                throw IllegalStateException("Session invalidation failed")
            }

            // Success: emit successful logout (clears all auth state)
            emit(PartialChange.CompleteLogout)

        } catch (e: Exception) {
            // Error: show toast but maintain current login state
            emit(PartialChange.FailLogout("Logout failed: ${e.message}"))
        } finally {
            // Cleanup: always stop loading
            emit(PartialChange.StopLoading)
        }
    }
}
