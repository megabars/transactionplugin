package com.txplugin.plugin.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(
    name = "TransactionSettings",
    storages = [Storage("transactionSettings.xml")]
)
@Service(Service.Level.APP)
class TransactionSettings : PersistentStateComponent<TransactionSettings.State> {

    data class State(
        var maxRecords: Int = 1000,
        var port: Int = 17321,
        var showCodeVision: Boolean = true
    )

    @Volatile private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    var maxRecords: Int
        get() = state.maxRecords
        set(value) { state.maxRecords = value.coerceIn(100, 10_000) }

    var port: Int
        get() = state.port
        set(value) { state.port = value.coerceIn(1024, 65535) }

    var showCodeVision: Boolean
        get() = state.showCodeVision
        set(value) { state.showCodeVision = value }

    companion object {
        fun getInstance(): TransactionSettings =
            ApplicationManager.getApplication().getService(TransactionSettings::class.java)
    }
}
