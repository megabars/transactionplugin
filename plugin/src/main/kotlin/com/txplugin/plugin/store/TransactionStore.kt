package com.txplugin.plugin.store

import com.google.gson.Gson
import com.intellij.codeInsight.codeVision.CodeVisionHost
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.ProjectManager
import com.txplugin.plugin.model.TransactionRecord
import com.txplugin.plugin.ui.TransactionCodeVisionProvider
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Application-level service that:
 *  1. Owns the in-memory ring buffer of completed transactions (max 1000).
 *  2. Persists the last transaction per method across IDE restarts.
 *  3. Runs a TCP server that accepts connections from the Java Agent.
 *  4. Notifies UI listeners when new transactions arrive.
 */
@State(
    name = "TransactionStore",
    storages = [Storage("transactionStore.xml")]
)
@Service(Service.Level.APP)
class TransactionStore : PersistentStateComponent<TransactionStore.State> {

    /** Persistent state: last transaction JSON per methodKey */
    data class State(
        var lastByMethod: MutableMap<String, String> = mutableMapOf()
    )

    companion object {
        const val MAX_RECORDS = 1000
        const val DEFAULT_PORT = 17321

        fun getInstance(): TransactionStore =
            ApplicationManager.getApplication().getService(TransactionStore::class.java)
    }

    private val gson = Gson()
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "tx-store-io").also { it.isDaemon = true }
    }

    /** Ring buffer — oldest entries are dropped when full */
    private val records = ArrayDeque<TransactionRecord>(MAX_RECORDS)
    private val lock = Any()

    /** Listeners notified on EDT whenever new records arrive */
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** Port actually bound (may differ from DEFAULT_PORT if in use) */
    @Volatile var port: Int = DEFAULT_PORT
        private set

    @Volatile private var serverSocket: ServerSocket? = null

    init {
        startServer()
    }

    // ── PersistentStateComponent ──────────────────────────────────────────────

    override fun getState(): State {
        val map = mutableMapOf<String, String>()
        synchronized(lock) {
            // Store only the last record per method
            records.forEach { r -> map[r.methodKey] = gson.toJson(r) }
        }
        return State(map)
    }

    override fun loadState(state: State) {
        synchronized(lock) {
            records.clear()
            state.lastByMethod.values.forEach { json ->
                try {
                    val r = gson.fromJson(json, TransactionRecord::class.java)
                    if (r != null) records.addLast(r)
                } catch (_: Exception) { }
            }
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun getRecords(): List<TransactionRecord> = synchronized(lock) { records.toList() }

    /** Returns last known transaction for the given className#methodName key */
    fun getLatestForMethod(methodKey: String): TransactionRecord? =
        synchronized(lock) { records.lastOrNull { it.methodKey == methodKey } }

    fun addListener(listener: () -> Unit) = listeners.add(listener)
    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    // ── Server ────────────────────────────────────────────────────────────────

    private fun startServer() {
        executor.submit {
            try {
                val ss = bindServerSocket()
                serverSocket = ss
                port = ss.localPort

                while (!ss.isClosed) {
                    try {
                        val client = ss.accept()
                        executor.submit { handleClient(client) }
                    } catch (e: Exception) {
                        if (!ss.isClosed) continue
                        break
                    }
                }
            } catch (_: Exception) {
                // Server failed to start — plugin still usable, just no live data
            }
        }
    }

    private fun bindServerSocket(): ServerSocket {
        return try {
            ServerSocket(DEFAULT_PORT)
        } catch (_: Exception) {
            ServerSocket(0)
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.use { s ->
                BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val record = parseLine(line!!) ?: continue
                        addRecord(record)
                    }
                }
            }
        } catch (_: Exception) { }
    }

    private fun parseLine(line: String): TransactionRecord? {
        return try {
            gson.fromJson(line.trim(), TransactionRecord::class.java)
        } catch (_: Exception) {
            null
        }
    }

    private fun addRecord(record: TransactionRecord) {
        synchronized(lock) {
            if (records.size >= MAX_RECORDS) records.removeFirst()
            records.addLast(record)
        }
        notifyListeners()
    }

    private fun notifyListeners() {
        ApplicationManager.getApplication().invokeLater {
            // 1. Notify Tool Window and other UI listeners
            listeners.forEach { it() }

            // 2. Force CodeVision refresh via CodeVisionHost (immediate, no daemon delay)
            ProjectManager.getInstance().openProjects.forEach { project ->
                if (project.isDisposed) return@forEach
                project.getService(CodeVisionHost::class.java)?.invalidateProvider(
                    CodeVisionHost.LensInvalidateSignal(null, listOf(TransactionCodeVisionProvider.ID))
                )
            }
        }
    }

    fun dispose() {
        serverSocket?.close()
        executor.shutdownNow()
    }
}
