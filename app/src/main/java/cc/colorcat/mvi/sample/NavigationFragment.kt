package cc.colorcat.mvi.sample

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import cc.colorcat.mvi.sample.count.CounterFragment
import cc.colorcat.mvi.sample.databinding.FragmentNavigationBinding

/**
 * Navigation page that displays navigation buttons to jump to different example pages.
 *
 * Author: ccolorcat
 * Date: 2025-11-20
 * GitHub: https://github.com/ccolorcat
 */
class NavigationFragment : Fragment() {

    private var _binding: FragmentNavigationBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNavigationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupClickListeners()
    }

    private fun setupClickListeners() {
        binding.btnLogin.setOnClickListener {
            navigateToLogin()
        }
        binding.btnCount.setOnClickListener {
            navigateToCount()
        }
        // Can add more navigation buttons
    }

    private fun navigateToLogin() {
        (activity as? SampleActivity)?.navigateToFragment(cc.colorcat.mvi.sample.login.LoginFragment())
    }

    private fun navigateToCount() {
        (activity as? SampleActivity)?.navigateToFragment(CounterFragment())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}