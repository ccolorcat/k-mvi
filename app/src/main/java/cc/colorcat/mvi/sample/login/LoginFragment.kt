package cc.colorcat.mvi.sample.login

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import cc.colorcat.mvi.collectEvent
import cc.colorcat.mvi.collectState
import cc.colorcat.mvi.debounceFirst
import cc.colorcat.mvi.doOnClick
import cc.colorcat.mvi.sample.databinding.FragmentLoginBinding
import cc.colorcat.mvi.sample.login.LoginContract.Intent
import cc.colorcat.mvi.sample.login.LoginContract.State
import cc.colorcat.mvi.sample.showToast
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach

/**
 * Fragment demonstrating MVI pattern for authentication (login) feature.
 *
 * This sample demonstrates:
 * - Form input handling with reactive state updates
 * - Button state management based on loading/authentication state
 * - Error message display and clearing
 * - Event handling (toast messages, navigation)
 * - Lifecycle-aware state and event collection
 *
 * Author: ccolorcat
 * Date: 2025-11-20
 * GitHub: https://github.com/ccolorcat
 */
class LoginFragment : Fragment() {
    private val viewModel: LoginViewModel by viewModels()
    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!

    /**
     * Merged flow of all user intents.
     * Includes login, logout, and error clearing intents.
     */
    private val intents: Flow<Intent>
        get() = merge(
            binding.loginButton.doOnClick {
                trySend(
                    Intent.Login(
                        username = binding.usernameInput.text?.toString() ?: "",
                        password = binding.passwordInput.text?.toString() ?: ""
                    )
                )
            }.debounceFirst(600L),
            binding.logoutButton.doOnClick {
                trySend(Intent.Logout)
            }.debounceFirst(600L),
        )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLoginBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupViews()
        setupViewModel()
    }

    /**
     * Setup view listeners for error clearing.
     */
    private fun setupViews() {
        // Clear error when user starts typing
        binding.usernameInput.doOnTextChanged { _, _, _, _ ->
            viewModel.dispatch(LoginContract.ClearError)
        }
        binding.passwordInput.doOnTextChanged { _, _, _, _ ->
            viewModel.dispatch(LoginContract.ClearError)
        }
    }

    /**
     * Setup ViewModel connection by observing state and events.
     *
     * **Pattern: Efficient Partial State Collection**
     * Uses collectPartial to observe only specific state properties, updating UI
     * only when those particular properties change, not on every state emission.
     */
    private fun setupViewModel() {
        // Observe state changes
        viewModel.stateFlow.collectState(viewLifecycleOwner) {
            // Status text display
            collectPartial(State::statusText, binding.statusText::setText)

            // Loading indicator
            collectPartial(State::isLoading, binding.loadingBar::isVisible::set)

            // Button states (enabled/disabled)
            collectPartial(State::isLoginEnabled, binding.loginButton::setEnabled)
            collectPartial(State::isLogoutEnabled, binding.logoutButton::setEnabled)

            // Error message display
            collectPartial(State::errorMessage, binding.errorText::setText)
            collectPartial(State::hasError, binding.errorText::isVisible::set)

            // Form visibility (hide when logged in)
            collectPartial(State::shouldShowLoginForm, binding.loginCard::isVisible::set)
        }

        // Handle events (toast messages)
        viewModel.eventFlow.collectEvent(this) {
            collectParticular<LoginContract.ShowToast> { event ->
                context?.showToast(event.message)
            }
        }

        // Dispatch intents to ViewModel
        intents.onEach { viewModel.dispatch(it) }.launchIn(lifecycleScope)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

