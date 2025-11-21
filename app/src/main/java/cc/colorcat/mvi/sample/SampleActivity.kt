package cc.colorcat.mvi.sample

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import cc.colorcat.mvi.sample.databinding.ActivitySampleBinding

/**
 * Main Activity of the sample app, responsible for managing Fragment display and navigation.
 * Defaults to showing [NavigationFragment], navigates to other pages via [navigateToFragment] method.
 *
 * Author: ccolorcat
 * Date: 2025-11-15
 * GitHub: https://github.com/ccolorcat
 */
class SampleActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySampleBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivitySampleBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) {
            showNavigationFragment()
        }
    }

    /**
     * Show the navigation page.
     */
    private fun showNavigationFragment() {
        navigateToFragment(NavigationFragment())
    }

    /**
     * Navigate to the specified Fragment.
     *
     * @param fragment The Fragment to display
     * @param addToBackStack Whether to add to the back stack, defaults to true
     */
    fun navigateToFragment(fragment: Fragment, addToBackStack: Boolean = true) {
        supportFragmentManager.beginTransaction().apply {
            replace(R.id.fragment_container, fragment)
            if (addToBackStack) {
                addToBackStack(null)
            }
            commit()
        }
    }
}
