package com.vrivrixd.micmonitor

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

/**
 * Estado observavel da transmissao. O servico escreve, a interface le.
 */
object StreamState {

    data class Snapshot(
        val running: Boolean = false,
        val address: String? = null,
        val clientConnected: Boolean = false,
        val stereoRequested: Boolean = false,
        val stereoReal: Boolean = false,
        val stereoVerified: Boolean = false,
        val error: String? = null,
        val configRevision: Int = 0
    )

    private val _state = MutableLiveData(Snapshot())
    val state: LiveData<Snapshot> = _state

    val current: Snapshot get() = _state.value ?: Snapshot()

    fun update(block: (Snapshot) -> Snapshot) {
        _state.postValue(block(current))
    }

    fun reset(error: String? = null) {
        _state.postValue(Snapshot(error = error))
    }
}
