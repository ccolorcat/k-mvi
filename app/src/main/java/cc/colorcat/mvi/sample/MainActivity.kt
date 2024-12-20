package cc.colorcat.mvi.sample

import android.os.Bundle
import android.util.Log
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
    private val intents: Flow<IMain.Intent>
        get() = userIntents()
//            .debounce2(500L)


    private val viewModel2: MainViewModel2 by viewModels()
    private val intents2: Flow<IMain2.Intent>
        get() = userIntents2().debounce2(500L)

    private val viewModel3: MainViewModel3 by viewModels()


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupViewModel()
//        setupViewModel2()
//        setupViewModel3()
    }

    private fun setupViewModel() {
        viewModel.stateFlow.collectState(this) {
            collectPartial(IMain.State::countText, binding.count::setText)
        }
        viewModel.eventFlow.collectEvent(this, Lifecycle.State.CREATED) {
            collectParticular<IMain.Event.ShowToast> {
//                Toast.makeText(this@MainActivity, it.message, Toast.LENGTH_SHORT).show()
                Log.i("MainActivity", "received message: ${it.message}")
            }
        }
        intents.onEach { viewModel.dispatch(it) }.launchIn(lifecycleScope)
    }

    private fun userIntents(): Flow<IMain.Intent> = merge(
        binding.increment.doOnClick { trySend(IMain.Intent.Increment) },
        binding.decrement.doOnClick { trySend(IMain.Intent.Decrement) },
        binding.test.doOnClick {
            Log.v("MainActivity", "test clicked")
            trySend(IMain.Intent.Test)
        },
        binding.loadTest.doOnClick {
            Log.v("MainActivity", "load test clicked")
            trySend(IMain.Intent.LoadTest)
        },
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
        intents2.onEach { viewModel2.dispatch(it) }.launchIn(lifecycleScope)
    }

    private fun setupViewModel3() {
        viewModel3.stateFlow.collectState(this) {
            collectPartial(IMain.State::countText, binding.count::setText)
        }
        viewModel3.eventFlow.collectEvent(this, Lifecycle.State.CREATED) {
            collectParticular<IMain.Event.ShowToast> {
                Toast.makeText(this@MainActivity, it.message, Toast.LENGTH_SHORT).show()
            }
        }
        intents.onEach { viewModel3.dispatch(it) }.launchIn(lifecycleScope)
    }

    private fun userIntents2(): Flow<IMain2.Intent> = merge(
        binding.increment.doOnClick { trySend(IMain2.Increment) },
        binding.decrement.doOnClick { trySend(IMain2.Decrement) },
    )
}
