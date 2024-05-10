package cc.colorcat.mvi.sample

import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import cc.colorcat.mvi.collectEvent
import cc.colorcat.mvi.collectState
import cc.colorcat.mvi.debounce2
import cc.colorcat.mvi.doOnClick
import cc.colorcat.mvi.sample.databinding.ActivityMainBinding
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach

class MainActivity : AppCompatActivity() {
    private val binding: ActivityMainBinding by lazy(LazyThreadSafetyMode.NONE) {
        ActivityMainBinding.inflate(layoutInflater)
    }

    private val viewModel: MainViewModel by viewModels()
    private val actions: Flow<IMain.Action>
        get() = userActions().debounce2(500L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        setupViewModel()
    }

    private fun setupViewModel() {
        viewModel.stateFlow.collectState(this) {
            collectPartial(IMain.State::countText, binding.count::setText)
        }
        viewModel.eventFlow.collectEvent(this, Lifecycle.State.CREATED) {
            collectParticular<IMain.Event.ShowToast> {
                Toast.makeText(this@MainActivity, it.message, Toast.LENGTH_SHORT).show()
            }
        }
        actions.onEach { viewModel.dispatch(it) }.launchIn(lifecycleScope)
    }

    private fun userActions(): Flow<IMain.Action> = merge(
        binding.increment.doOnClick { trySend(IMain.Action.Increment) },
        binding.decrement.doOnClick { trySend(IMain.Action.Decrement) },
    )
}
