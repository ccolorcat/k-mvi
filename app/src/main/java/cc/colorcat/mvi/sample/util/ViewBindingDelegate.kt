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
 * Author: ccolorcat
 * Date: 2025-11-15
 * GitHub: https://github.com/ccolorcat
 */
class FragmentViewBindingDelegate<T : ViewBinding>(
    private val factory: (View) -> T
) : ReadOnlyProperty<Fragment, T> {

    private var binding: T? = null

    override fun getValue(thisRef: Fragment, property: KProperty<*>): T {
        ensureMainThread()
        val existing = binding
        if (existing != null) return existing

        val view = checkNotNull(thisRef.view) {
            "Should not attempt to get bindings when Fragment's view is null"
        }
        val vb = factory(view)
        thisRef.viewLifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                owner.lifecycle.removeObserver(this)
                binding = null
            }
        })

        binding = vb
        return vb
    }
}

fun <T : ViewBinding> Fragment.viewBinding(factory: (View) -> T): ReadOnlyProperty<Fragment, T> {
    return FragmentViewBindingDelegate(factory)
}

inline fun <reified T : ViewBinding> Fragment.viewBinding(): ReadOnlyProperty<Fragment, T> {
    return FragmentViewBindingDelegate { bind(it) }
}


inline fun <reified T : ViewBinding> bind(view: View): T {
    return T::class.java.getMethod("bind", View::class.java)
        .invoke(null, view) as T

}


class ActivityViewBindingDelegate<T : ViewBinding>(
    private val factory: (LayoutInflater) -> T
) : ReadOnlyProperty<Activity, T> {
    private var binding: T? = null

    override fun getValue(thisRef: Activity, property: KProperty<*>): T {
        ensureMainThread()
        return binding ?: factory(thisRef.layoutInflater).also { binding = it }
    }
}

inline fun <reified T : ViewBinding> Activity.viewBinding(): ReadOnlyProperty<Activity, T> {
    return ActivityViewBindingDelegate { inflate(it) }
}

inline fun <reified T : ViewBinding> inflate(inflater: LayoutInflater): T {
    return T::class.java.getMethod("inflate", LayoutInflater::class.java)
        .invoke(null, inflater) as T
}


private fun ensureMainThread() {
    if (Looper.myLooper() != Looper.getMainLooper()) {
        throw IllegalStateException("ViewBinding must be accessed from the main (UI) thread")
    }
}
