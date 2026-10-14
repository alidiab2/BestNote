/**
 * :core:common — tiny cross-cutting utilities with no Android dependency.
 *
 * Currently: an injectable [DispatcherProvider] so that code doing disk, database or CPU work
 * never hard-codes `Dispatchers.*` and can be driven by a test dispatcher in unit tests.
 */
package app.bestnote.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject

/** Source of coroutine dispatchers; inject this instead of touching [Dispatchers] directly. */
interface DispatcherProvider {
    /** UI thread. */
    val main: CoroutineDispatcher

    /** Blocking file / database / network I/O. */
    val io: CoroutineDispatcher

    /** CPU-bound work: geometry, recognition, encoding. */
    val default: CoroutineDispatcher
}

/** Production dispatchers. */
class DefaultDispatcherProvider @Inject constructor() : DispatcherProvider {
    override val main: CoroutineDispatcher get() = Dispatchers.Main
    override val io: CoroutineDispatcher get() = Dispatchers.IO
    override val default: CoroutineDispatcher get() = Dispatchers.Default
}

/** Routes every dispatcher to one [dispatcher]; meant for tests. */
class SingleDispatcherProvider(dispatcher: CoroutineDispatcher) : DispatcherProvider {
    override val main = dispatcher
    override val io = dispatcher
    override val default = dispatcher
}
