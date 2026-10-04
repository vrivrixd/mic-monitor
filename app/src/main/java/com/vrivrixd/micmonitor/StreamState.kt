package com.vrivrixd.micmonitor

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

/**
 * Observable state of the stream. The service writes it, the screens read it.
 *
 * The writes arrive from several threads: the service on the main one, and the
 * connection threads when a listener joins or leaves. So the current state lives in
 * a field of its own, guarded by a lock, instead of being read back from the live
 * data. A posted value only reaches the live data when the main thread gets around
 * to it, and until then it cannot be read, which let a late write from a dying
 * connection build on a state that had already been replaced. Stopping the stream
 * while someone was listening could end up showing the stream as running.
 */
object StreamState {

    data class Snapshot(
        val running: Boolean = false,
        val address: String? = null,
        val clientCount: Int = 0,
        val paused: Boolean = false,
        val error: String? = null,
        val configRevision: Int = 0
    )

    private val _state = MutableLiveData(Snapshot())
    val state: LiveData<Snapshot> = _state

    private val lock = Any()
    private var latest = Snapshot()

    val current: Snapshot get() = synchronized(lock) { latest }

    fun update(block: (Snapshot) -> Snapshot) {
        // The value travels inside the lock, so the screens see the changes in the
        // same order in which they happened.
        synchronized(lock) {
            latest = block(latest)
            _state.postValue(latest)
        }
    }

    fun reset(error: String? = null) {
        update { Snapshot(error = error) }
    }
}
