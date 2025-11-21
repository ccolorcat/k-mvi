package cc.colorcat.mvi.sample

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import cc.colorcat.mvi.sample.databinding.FragmentNavigationBinding

/**
 * Navigation page that displays navigation buttons to jump to different example pages.
 */
class NavigationFragment : Fragment() {
    private var _binding: FragmentNavigationBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentNavigationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupClickListeners()
    }

    private fun setupClickListeners() {
        binding.run {
            login.setOnClickListener { navigate(R.id.loginFragment) }
            counter.setOnClickListener { navigate(R.id.counterFragment) }
        }
    }

    private fun navigate(resId: Int) {
        findNavController().navigate(resId)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
