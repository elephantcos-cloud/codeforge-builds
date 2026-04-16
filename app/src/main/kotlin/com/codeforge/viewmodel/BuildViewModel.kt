package com.codeforge.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.codeforge.api.BuildEngine

class BuildViewModel(app: Application) : AndroidViewModel(app) {

    sealed class State {
        object Idle : State()
        data class Running(val message: String, val progress: Int) : State()
        data class Done(val url: String) : State()
        data class Failed(val reason: String) : State()
    }

    private val _state = MutableLiveData<State>(State.Idle)
    val state: LiveData<State> = _state

    private val engine = BuildEngine()

    fun build(appName: String, pkg: String, code: String, lang: BuildEngine.Lang) {
        _state.value = State.Running("Starting...", 0)
        engine.build(appName, pkg, code, lang, object : BuildEngine.Listener {
            override fun onStep(message: String, progress: Int) {
                _state.postValue(State.Running(message, progress))
            }
            override fun onDone(downloadUrl: String) {
                _state.postValue(State.Done(downloadUrl))
            }
            override fun onFail(reason: String) {
                _state.postValue(State.Failed(reason))
            }
        })
    }

    fun reset() { _state.value = State.Idle }

    override fun onCleared() { super.onCleared(); engine.cancel() }
}
