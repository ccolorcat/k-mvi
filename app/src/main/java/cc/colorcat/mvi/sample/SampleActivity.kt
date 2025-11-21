package cc.colorcat.mvi.sample

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.NavController
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
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
    private val binding: ActivitySampleBinding by lazy(LazyThreadSafetyMode.NONE) {
        ActivitySampleBinding.inflate(layoutInflater)
    }
    private val navController: NavController by lazy(LazyThreadSafetyMode.NONE) {
        findNavController(R.id.fragment_container)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        setupActionBarWithNavController(
            navController,
            AppBarConfiguration(setOf(R.id.navigationFragment)),
        )
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp() || super.onSupportNavigateUp()
    }
}
