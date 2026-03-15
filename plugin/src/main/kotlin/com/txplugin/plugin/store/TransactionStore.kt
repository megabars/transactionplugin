package com.txplugin.plugin.store

import com.google.gson.Gson
import com.intellij.codeInsight.codeVision.CodeVisionHost
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.ProjectManager
import com.txplugin.plugin.model.TransactionRecord
import com.txplugin.plugin.ui.TransactionCodeVisionProvider
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
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
class TransactionStore : PersistentStateComponent<TransactionStore.State>, com.intellij.openapi.Disposable {

    /** Persistent state: last transaction JSON per methodKey */
    data class State(
        var lastByMethod: MutableMap<String, String> = mutableMapOf()
    )

    companion object {
        const val MAX_RECORDS = 1000
        const val DEFAULT_PORT = 17321
        /** Max JSON line length accepted from agent (1 MiB); guards against OOM on malformed input */
        private const val MAX_LINE_BYTES = 1_048_576

        fun getInstance(): TransactionStore =
            ApplicationManager.getApplication().getService(TransactionStore::class.java)
    }

    private val log = thisLogger()
    private val gson = Gson()
    private val ioThreadIndex = java.util.concurrent.atomic.AtomicInteger()
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "tx-store-io-${ioThreadIndex.incrementAndGet()}").also { it.isDaemon = true }
    }

    /** Ring buffer — oldest entries are dropped when full */
    private val records = ArrayDeque<TransactionRecord>(MAX_RECORDS)

    /** O(1) lookup: methodKey → last record for that method.
     *  LinkedHashMap preserves insertion order so we can evict the oldest entry
     *  when the map exceeds MAX_RECORDS (prevents unbounded growth). */
    private val latestByMethod = LinkedHashMap<String, TransactionRecord>()

    private val lock = Any()

    /** Listeners notified on EDT whenever new records arrive */
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** Port actually bound (may differ from DEFAULT_PORT if in use) */
    @Volatile var port: Int = DEFAULT_PORT
        private set

    @Volatile private var serverSocket: ServerSocket? = null

    init {
        // Bind socket synchronously so that `port` is set before any Run Configuration reads it.
        // Only the accept loop runs in the background.
        val ss = try {
            bindServerSocket()
        } catch (e: Exception) {
            log.warn("TransactionStore: TCP server failed to bind — live transaction data unavailable", e)
            null
        }
        if (ss != null) {
            serverSocket = ss
            port = ss.localPort
            executor.submit { acceptLoop(ss) }
        }
    }

    // ── PersistentStateComponent ──────────────────────────────────────────────

    override fun getState(): State {
        val map = mutableMapOf<String, String>()
        synchronized(lock) {
            latestByMethod.forEach { (key, r) -> map[key] = gson.toJson(r) }
        }
        return State(map)
    }

    override fun loadState(state: State) {
        synchronized(lock) {
            records.clear()
            latestByMethod.clear()
            state.lastByMethod.values.forEach { json ->
                try {
                    val r = gson.fromJson(json, TransactionRecord::class.java)
                    if (r != null) {
                        if (records.size < MAX_RECORDS) records.addLast(r)
                        latestByMethod[r.methodKey] = r
                    }
                } catch (e: Exception) {
                    log.warn("TransactionStore: failed to deserialize persisted record", e)
                }
            }
            // Evict oldest entries if persistence had more than MAX_RECORDS unique methods
            if (latestByMethod.size > MAX_RECORDS) {
                val iter = latestByMethod.iterator()
                repeat(latestByMethod.size - MAX_RECORDS) { if (iter.hasNext()) { iter.next(); iter.remove() } }
            }
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun getRecords(): List<TransactionRecord> = synchronized(lock) { records.toList() }

    /** Returns last known transaction for the given methodKey — O(1) */
    fun getLatestForMethod(methodKey: String): TransactionRecord? =
        synchronized(lock) { latestByMethod[methodKey] }

    fun addListener(listener: () -> Unit) = listeners.add(listener)
    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    fun clear() {
        synchronized(lock) {
            records.clear()
            latestByMethod.clear()
        }
        notifyListeners()
    }

    // ── Disposable ────────────────────────────────────────────────────────────

    override fun dispose() {
        serverSocket?.close()
        executor.shutdownNow()
    }

    // ── Server ────────────────────────────────────────────────────────────────

    private fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            try {
                val client = ss.accept()
                executor.submit { handleClient(client) }
            } catch (e: Exception) {
                if (ss.isClosed) break
                log.warn("TransactionStore: accept() error — retrying: ${e.message}")
            }
        }
    }

    private fun bindServerSocket(): ServerSocket {
        val loopback = InetAddress.getLoopbackAddress()
        return try {
            ServerSocket(DEFAULT_PORT, 50, loopback)
        } catch (_: Exception) {
            ServerSocket(0, 50, loopback)
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.use { s ->
                s.soTimeout = 30_000
                BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8)).use { reader ->
                    reader.lineSequence().forEach { line ->
                        val lineBytes = line.toByteArray(Charsets.UTF_8).size
                        if (lineBytes > MAX_LINE_BYTES) {
                            log.warn("TransactionStore: oversized line ($lineBytes bytes) — skipping")
                            return@forEach
                        }
                        val record = parseLine(line) ?: return@forEach
                        addRecord(record)
                    }
                }
            }
        } catch (e: Exception) {
            log.debug("TransactionStore: agent connection closed: ${e.message}")
        }
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
            latestByMethod[record.methodKey] = record
            // Evict oldest entries to prevent unbounded growth (e.g. many generated proxy methods)
            if (latestByMethod.size > MAX_RECORDS) {
                val iter = latestByMethod.iterator()
                repeat(latestByMethod.size - MAX_RECORDS) { if (iter.hasNext()) { iter.next(); iter.remove() } }
            }
        }
        notifyListeners()
    }

    private fun notifyListeners() {
        ApplicationManager.getApplication().invokeLater {
            // 1. Notify Tool Window and other UI listeners
            listeners.forEach { it() }

            // 2. Force CodeVision refresh via CodeVisionHost (immediate, no daemon delay).
            // Передаём конкретный Editor для каждого открытого файла, так как
            // LensInvalidateSignal(null, ...) ненадёжно тригерит перевалидацию в IJ 2023.3.
            ProjectManager.getInstance().openProjects.toList().forEach { project ->
                if (project.isDisposed) return@forEach
                val codeVisionHost = project.getService(CodeVisionHost::class.java) ?: return@forEach
                FileEditorManager.getInstance(project).allEditors.toList()
                    .filterIsInstance<TextEditor>()
                    .forEach { fileEditor ->
                        codeVisionHost.invalidateProvider(
                            CodeVisionHost.LensInvalidateSignal(
                                fileEditor.editor,
                                listOf(TransactionCodeVisionProvider.ID)
                            )
                        )
                    }
            }
        }
    }
}
