package com.txplugin.plugin.store

import com.google.gson.Gson
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.psi.PsiManager
import com.txplugin.plugin.model.TransactionRecord
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Application-level service that:
 *  1. Owns the in-memory ring buffer of completed transactions (max 1000).
 *  2. Runs a TCP server that accepts connections from the Java Agent.
 *  3. Notifies UI listeners when new transactions arrive.
 */
@Service(Service.Level.APP)
class TransactionStore {

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
            } catch (e: Exception) {
                // Server failed to start — plugin still usable, just no live data
            }
        }
    }

    private fun bindServerSocket(): ServerSocket {
        // Try DEFAULT_PORT first, then let the OS pick a free port
        return try {
            ServerSocket(DEFAULT_PORT)
        } catch (e: Exception) {
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

            // 2. Force inlay hints refresh — restart daemon for open Java/Kotlin files
            ProjectManager.getInstance().openProjects.forEach { project ->
                if (project.isDisposed) return@forEach
                val daemon = DaemonCodeAnalyzer.getInstance(project)
                val psiManager = PsiManager.getInstance(project)
                FileEditorManager.getInstance(project).openFiles.forEach { vFile ->
                    if (vFile.extension == "java" || vFile.extension == "kt") {
                        val psiFile = psiManager.findFile(vFile) ?: return@forEach
                        daemon.restart(psiFile)
                    }
                }
            }
        }
    }

    fun dispose() {
        serverSocket?.close()
        executor.shutdownNow()
    }
}
