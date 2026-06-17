package cc.colorcat.mvi.sample.util

import android.app.Activity
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.viewbinding.ViewBinding
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty


/**
 * Delegates for type-safe [ViewBinding] access in [Fragment] and [Activity].
 *
 * Thread-safety: all public accessors call [ensureMainThread] to enforce
 * main-thread access, consistent with Android ViewBinding's own threading
 * contract. Fragment delegates automatically null the binding reference when
 * the fragment's view is destroyed.
 */

/**
 * [ReadOnlyProperty] delegate that lazily binds a [ViewBinding] for a [Fragment].
 *
 * The binding is created once from the fragment's root view, cached,
 * and automatically cleared when the fragment's view is destroyed (via
 * [DefaultLifecycleObserver.onDestroy]).
 *
 * @param T The ViewBinding type
 * @param factory Creates the binding from a [View]
 */
class FragmentViewBindingDelegate<T : ViewBinding>(
    private val factory: (View) -> T,
) : ReadOnlyProperty<Fragment, T> {

    private var binding: T? = null

    /**
     * Returns the cached binding, or creates and caches a new one.
     *
     * Registers a lifecycle observer on first creation so the binding reference
     * is cleared when the fragment's view is destroyed, preventing leaks.
     *
     * @throws IllegalStateException if the fragment's view is null
     * @throws IllegalStateException if called off the main thread
     */
    override fun getValue(thisRef: Fragment, property: KProperty<*>): T {
        ensureMainThread()
        val existing = binding
        if (existing != null) return existing

        val view = checkNotNull(thisRef.view) {
            "Should not attempt to get bindings when Fragment's view is null"
        }
        val vb = factory(view)
        thisRef.viewLifecycleOwner.lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    owner.lifecycle.removeObserver(this)
                    binding = null
                }
            },
        )

        binding = vb
        return vb
    }
}

/** Creates a [FragmentViewBindingDelegate] with an explicit [factory] function. */
fun <T : ViewBinding> Fragment.viewBinding(factory: (View) -> T): ReadOnlyProperty<Fragment, T> {
    return FragmentViewBindingDelegate(factory)
}

/** Creates a [FragmentViewBindingDelegate] using the reified type's `bind` method. */
inline fun <reified T : ViewBinding> Fragment.viewBinding(): ReadOnlyProperty<Fragment, T> {
    return FragmentViewBindingDelegate { bind(it) }
}


/**
 * Invokes the `bind(View)` static method on a view binding class via reflection.
 *
 * @param view The root view to bind
 * @return A new instance of [T]
 */
inline fun <reified T : ViewBinding> bind(view: View): T {
    return T::class.java.getMethod("bind", View::class.java)
        .invoke(null, view) as T

}


/**
 * [ReadOnlyProperty] delegate that lazily binds a [ViewBinding] for an [Activity].
 *
 * Unlike [FragmentViewBindingDelegate], the binding is created once and never
 * cleared (Activity views are not recreated).
 *
 * @param T The ViewBinding type
 * @param factory Creates the binding from a [LayoutInflater]
 */
class ActivityViewBindingDelegate<T : ViewBinding>(
    private val factory: (LayoutInflater) -> T,
) : ReadOnlyProperty<Activity, T> {
    private var binding: T? = null

    /**
     * Returns the cached binding, creating it on first access if needed.
     *
     * @throws IllegalStateException if called off the main thread
     */
    override fun getValue(thisRef: Activity, property: KProperty<*>): T {
        ensureMainThread()
        return binding ?: factory(thisRef.layoutInflater).also { binding = it }
    }
}

/** Creates an [ActivityViewBindingDelegate] using the reified type's `inflate` method. */
inline fun <reified T : ViewBinding> Activity.viewBinding(): ReadOnlyProperty<Activity, T> {
    return ActivityViewBindingDelegate { inflate(it) }
}

/** Invokes the `inflate(LayoutInflater)` static method on a view binding class via reflection. */
inline fun <reified T : ViewBinding> inflate(inflater: LayoutInflater): T {
    return T::class.java.getMethod("inflate", LayoutInflater::class.java)
        .invoke(null, inflater) as T
}


/**
 * Ensures the calling thread is the main thread.
 *
 * ViewBinding access is only valid on the main thread. This guard prevents
 * subtle crashes from off-thread access.
 *
 * @throws IllegalStateException if called from any other thread
 */
private fun ensureMainThread() {
    if (Looper.myLooper() != Looper.getMainLooper()) {
        throw IllegalStateException("ViewBinding must be accessed from the main (UI) thread")
    }
}
