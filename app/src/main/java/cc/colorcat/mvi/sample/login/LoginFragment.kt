package cc.colorcat.mvi.sample.login

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import cc.colorcat.mvi.collectEvent
import cc.colorcat.mvi.collectState
import cc.colorcat.mvi.debounceFirst
import cc.colorcat.mvi.doOnClick
import cc.colorcat.mvi.sample.R
import cc.colorcat.mvi.sample.databinding.FragmentLoginBinding
import cc.colorcat.mvi.sample.login.LoginContract.Intent
import cc.colorcat.mvi.sample.login.LoginContract.State
import cc.colorcat.mvi.sample.util.doOnTextChanged
import cc.colorcat.mvi.sample.util.showToast
import cc.colorcat.mvi.sample.util.viewBinding
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach

class LoginFragment : Fragment() {
    private val binding by viewBinding<FragmentLoginBinding>()
    private val viewModel: LoginViewModel by viewModels()

    private val intents: Flow<Intent>
        get() = merge(
            inputIntents().debounceFirst(500L),
            authIntents().debounceFirst(600L),
        )

    private fun inputIntents(): Flow<Intent> = merge(
        binding.usernameInput.doOnTextChanged { trySend(LoginContract.ClearError) },
        binding.passwordInput.doOnTextChanged { trySend(LoginContract.ClearError) }
    )

    private fun authIntents(): Flow<Intent> = merge(
        binding.loginButton.doOnClick {
            val username = binding.usernameInput.text?.toString() ?: ""
            val password = binding.passwordInput.text?.toString() ?: ""
            trySend(Intent.Login(username, password))
        },
        binding.logoutButton.doOnClick {
            trySend(Intent.Logout)
        }
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_login, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupViewModel()
    }

    private fun setupViewModel() {
        viewModel.stateFlow.collectState(viewLifecycleOwner) {
            collectPartial(State::statusText, binding.statusText::setText)
            collectPartial(State::isLoading, binding.loadingBar::isVisible::set)
            collectPartial(State::isLoginEnabled, binding.loginButton::setEnabled)
            collectPartial(State::isLogoutEnabled, binding.logoutButton::setEnabled)
            collectPartial(State::errorMessage, binding.errorText::setText)
            collectPartial(State::hasError, binding.errorText::isVisible::set)
            collectPartial(State::shouldShowLoginForm, binding.loginCard::isVisible::set)
        }

        viewModel.eventFlow.collectEvent(viewLifecycleOwner) {
            collectParticular<LoginContract.ShowToast> { event ->
                requireContext().showToast(event.message)
            }
        }

        intents.onEach { viewModel.dispatch(it) }.launchIn(viewLifecycleOwner.lifecycleScope)
    }
}
