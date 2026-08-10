package com.speedread.rsvp.ui

import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Collects [flow] tied to the fragment's VIEW lifecycle, suspending while the view is below STARTED
 * and cancelling cleanly on onDestroyView. Use this for every flow collector that touches `binding`.
 */
inline fun <T> Fragment.collectOnView(
    flow: Flow<T>,
    crossinline onEach: suspend (T) -> Unit
): Job = viewLifecycleOwner.lifecycleScope.launch {
    viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
        flow.collect { value -> onEach(value) }
    }
}

/**
 * Launches a one-shot coroutine tied to the view lifecycle. Cancels on onDestroyView so any
 * post-suspension `binding` access is safe. Use for one-shot async work triggered by UI events.
 */
inline fun Fragment.launchOnView(
    crossinline block: suspend CoroutineScope.() -> Unit
): Job = viewLifecycleOwner.lifecycleScope.launch { block() }
