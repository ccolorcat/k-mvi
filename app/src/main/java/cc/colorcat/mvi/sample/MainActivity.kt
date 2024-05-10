package cc.colorcat.mvi.sample

import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
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


    private val viewModel2: MainViewModel2 by viewModels()
    private val actions2: Flow<IMain2.Action>
        get() = userActions2().debounce2(500L)


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupViewModel()
//        setupViewModel2()
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


    private fun setupViewModel2() {
        viewModel2.stateFlow.collectState(this) {
            collectPartial(IMain2.State::countText, binding.count::setText)
        }
        viewModel2.eventFlow.collectEvent(this, Lifecycle.State.CREATED) {
            collectParticular<IMain2.Event.ShowToast> {
                Toast.makeText(this@MainActivity, it.message, Toast.LENGTH_SHORT).show()
            }
        }
        actions2.onEach { viewModel2.dispatch(it) }.launchIn(lifecycleScope)
    }

    private fun userActions2(): Flow<IMain2.Action> = merge(
        binding.increment.doOnClick { trySend(IMain2.Increment) },
        binding.decrement.doOnClick { trySend(IMain2.Decrement) },
    )
}
