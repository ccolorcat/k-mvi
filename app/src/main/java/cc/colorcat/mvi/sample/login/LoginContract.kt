package cc.colorcat.mvi.sample.login

import cc.colorcat.mvi.Mvi

/**
 * Contract defining the MVI architecture components for authentication (login) feature.
 *
 * **Design Pattern - Centralized PartialChange Implementation:**
 *
 * This contract demonstrates a **centralized approach** where all PartialChange implementations
 * are defined within the contract interface itself, rather than scattered in the ViewModel.
 *
 * **Advantages of this pattern:**
 * 1. **Easy Discovery**: All state transformations are in one place, making it easy to find
 *    how each PartialChange affects the state
 * 2. **Better Readability**: Readers can understand the complete state machine logic by
 *    reading just the contract file
 * 3. **Type Safety**: PartialChanges are strongly typed within the sealed interface
 * 4. **Separation of Concerns**: Business logic (ViewModel) is separated from state
 *    transformation logic (Contract)
 *
 * **Architecture Components:**
 * - [State]: Represents the authentication state and UI state
 * - [Event]: One-time events like toast messages
 * - [Intent]: User actions (login, logout, clear error)
 * - [PartialChange]: State transformation functions (centralized implementation)
 *
 * **Comparison with Counter Sample:**
 * - Counter: PartialChange implementations in ViewModel (inline PartialChange { ... })
 * - Login: PartialChange implementations in Contract (sealed types with apply())
 *
 * Author: ccolorcat
 * Date: 2025-11-20
 * GitHub: https://github.com/ccolorcat
 */
sealed interface LoginContract {
    /**
     * Represents the current authentication and UI state.
     *
     * @property isLoggedIn Whether the user is currently authenticated
     * @property username Current username (empty if not logged in)
     * @property isLoading Whether a login/logout operation is in progress
     * @property errorMessage Error message to display (null if no error)
     */
    data class State(
        val isLoggedIn: Boolean = false,
        val username: String = "",
        val isLoading: Boolean = false,
        val errorMessage: String = "",
    ) : Mvi.State {
        /**
         * UI state: whether the login button should be enabled.
         * Disabled when loading or already logged in.
         */
        val isLoginEnabled: Boolean
            get() = !isLoading && !isLoggedIn

        /**
         * UI state: whether the logout button should be enabled.
         * Disabled when loading or not logged in.
         */
        val isLogoutEnabled: Boolean
            get() = !isLoading && isLoggedIn

        /**
         * Display text for authentication status.
         */
        /**
         * Display text for authentication status.
         */
        val statusText: String
            get() = when {
                isLoading -> "Processing..."
                isLoggedIn -> "Logged in as: $username"
                else -> "Not logged in"
            }

        /**
         * UI state: whether error message should be visible.
         */
        val hasError: Boolean
            get() = errorMessage.isNotEmpty()

        /**
         * UI state: whether login form should be visible.
         * Hide the form when user is logged in.
         */
        val shouldShowLoginForm: Boolean
            get() = !isLoggedIn
    }

    /**
     * One-time events that should be consumed by the UI layer.
     */
    sealed interface Event : Mvi.Event {

    }

    /**
     * User intents/actions for the authentication feature.
     * Marked as [Mvi.Intent.Sequential] to ensure intents are processed sequentially.
     */
    sealed interface Intent : Mvi.Intent.Sequential {
        /**
         * Intent to perform login with username and password.
         *
         * @property username The username to log in with
         * @property password The password for authentication
         */
        data class Login(val username: String, val password: String) : Intent

        /**
         * Intent to perform logout.
         */
        data object Logout : Intent

        // Note: ClearError is defined at the bottom as it also implements PartialChange
    }

    /**
     * Sealed interface for state transformations.
     *
     * **Centralized Implementation Pattern:**
     * All PartialChange types are defined here with their state transformation logic
     * implemented in the [apply] method. This provides a single source of truth for
     * understanding how each operation affects the state.
     *
     * **Benefits:**
     * - All state transformations are visible in one place
     * - Easy to understand the complete state machine
     * - Type-safe exhaustive when expressions
     * - Clear separation: Contract defines "what changes", ViewModel defines "when to change"
     */
    sealed interface PartialChange : Mvi.PartialChange<State, Event> {

        override fun apply(old: Mvi.Snapshot<State, Event>): Mvi.Snapshot<State, Event> {
            return when (this) {
                // === Error Handling ===
                is SetErrorMessage -> old.updateState { copy(errorMessage = message) }
                is ClearError -> old.updateState { copy(errorMessage = "") }

                // === Loading State ===
                StartLoading -> old.updateState { copy(isLoading = true, errorMessage = "") }
                StopLoading -> old.updateState { copy(isLoading = false) }

                // === Login Operations ===
                is CompleteLogin -> old.updateWith(ShowToast("Welcome, $username!")) {
                    copy(isLoggedIn = true, username = username, errorMessage = "")
                }

                is FailLogin -> old.updateWith(ShowToast(message)) {
                    copy(errorMessage = message)
                }

                // === Logout Operations ===
                CompleteLogout -> old.updateWith(ShowToast("Logged out successfully")) {
                    copy(isLoggedIn = false, username = "", errorMessage = "")
                }

                is FailLogout -> old.withEvent(ShowToast(message))

                // === Events ===
                is Event -> old.withEvent(this)
            }
        }

        // ============================================================
        // Error Management
        // ============================================================

        /**
         * Set an error message to display to the user.
         *
         * @property message The error message
         */
        data class SetErrorMessage(val message: String) : PartialChange

        // Note: ClearError is defined at the bottom as it also implements Intent

        // ============================================================
        // Loading State Management
        // ============================================================

        /**
         * Start a loading operation.
         * Automatically clears any previous error messages.
         */
        data object StartLoading : PartialChange

        /**
         * Stop the loading operation.
         */
        data object StopLoading : PartialChange

        // ============================================================
        // Login Result Handling
        // ============================================================

        /**
         * Complete the login operation successfully.
         * Updates the logged-in state and username, clears errors, and shows a welcome toast.
         *
         * @property username The logged-in username
         */
        data class CompleteLogin(val username: String) : PartialChange

        /**
         * Handle login operation failure.
         * Sets the error message and shows a toast notification.
         *
         * @property message The error message describing why login failed
         */
        data class FailLogin(val message: String) : PartialChange

        // ============================================================
        // Logout Result Handling
        // ============================================================

        /**
         * Complete the logout operation successfully.
         * Resets the authentication state and shows a confirmation toast.
         */
        data object CompleteLogout : PartialChange

        /**
         * Handle logout operation failure.
         * Shows an error toast but maintains current state.
         *
         * @property message The error message describing why logout failed
         */
        data class FailLogout(val message: String) : PartialChange
    }

    // ============================================================
    // Events
    // ============================================================

    /**
     * Show a toast message to the user.
     *
     * This type implements both [Event] and [PartialChange], demonstrating a design pattern
     * for events that don't require state changes.
     *
     * **Design Pattern - Dual Implementation:**
     * - As an [Event]: Consumed by the UI layer to show toast messages
     * - As a [PartialChange]: Can be emitted directly without state updates
     *
     * **Benefits:**
     * - **Flexibility**: Can be used standalone or combined with state updates
     * - **Simplicity**: No need to wrap in a separate PartialChange
     * - **Reusability**: Can be used in multiple contexts (success, failure, info)
     *
     * **Usage patterns:**
     * ```kotlin
     * // Standalone (no state change):
     * emit(ShowToast("Operation completed"))
     *
     * // Combined with state update:
     * emit(CompleteLogin(username)) // This internally uses ShowToast
     * ```
     *
     * @property message The message to display
     */
    data class ShowToast(val message: String) : Event, PartialChange


    /**
     * Clear the error message.
     *
     * This type implements both [Intent] and [PartialChange], demonstrating a design pattern
     * for simple, synchronous operations that don't require additional business logic.
     *
     * **Design Pattern - Dual Implementation:**
     * - As an [Intent]: User can dispatch `ClearError` from the UI
     * - As a [PartialChange]: Can be directly used in the ViewModel without creating a separate handler
     *
     * **Benefits:**
     * - **Simplicity**: No need for a separate handler method in ViewModel
     * - **Efficiency**: Direct state transformation without intermediate steps
     * - **Clarity**: The intent directly maps to its state change
     *
     * **Usage in ViewModel:**
     * ```kotlin
     * // Can be used directly with asSingleFlow()
     * is Intent.ClearError -> PartialChange.ClearError.asSingleFlow()
     * ```
     *
     * **When to use this pattern:**
     * - For simple, synchronous operations
     * - When the intent directly maps to a single state change
     * - When no additional business logic is needed
     */
    data object ClearError : Intent, PartialChange
}