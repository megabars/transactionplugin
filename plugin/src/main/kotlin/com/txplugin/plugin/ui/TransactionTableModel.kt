package com.txplugin.plugin.ui

import com.txplugin.plugin.model.TransactionRecord
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.table.AbstractTableModel

class TransactionTableModel : AbstractTableModel() {

    enum class Column(val title: String, val width: Int) {
        TIME("Time", 80),
        METHOD("Method", 260),
        DURATION("ms", 60),
        STATUS("Status", 90),
        SQL("SQL", 50),
        BATCH("Batch", 50),
        INSERT("↑Ins", 50),
        UPDATE("✎Upd", 50),
        DELETE("↓Del", 50),
        PROPAGATION("Propagation", 110),
        ISOLATION("Isolation", 130),
        READ_ONLY("R/O", 40),
    }

    // DateTimeFormatter is thread-safe (unlike SimpleDateFormat)
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
    private var rows: List<TransactionRecord> = emptyList()

    fun setRecords(records: List<TransactionRecord>) {
        rows = records.asReversed() // newest first
        fireTableDataChanged()
    }

    fun recordAt(row: Int): TransactionRecord? = rows.getOrNull(row)

    override fun getRowCount() = rows.size
    override fun getColumnCount() = Column.entries.size
    override fun getColumnName(col: Int) = Column.entries[col].title

    override fun getValueAt(row: Int, col: Int): Any {
        val r = rows[row]
        return when (Column.entries[col]) {
            Column.TIME       -> timeFormat.format(Instant.ofEpochMilli(r.startTimeMs))
            Column.METHOD     -> "${r.className.substringAfterLast('.')}.${r.methodName}()"
            Column.DURATION   -> r.durationMs
            Column.STATUS     -> r.status.name
            Column.SQL        -> r.sqlQueryCount
            Column.BATCH      -> r.batchCount
            Column.INSERT     -> r.insertCount
            Column.UPDATE     -> r.updateCount
            Column.DELETE     -> r.deleteCount
            Column.PROPAGATION -> r.propagation
            Column.ISOLATION  -> r.isolationLevel
            Column.READ_ONLY  -> if (r.readOnly) "Y" else ""
        }
    }

    override fun getColumnClass(col: Int): Class<*> = when (Column.entries[col]) {
        Column.DURATION, Column.SQL, Column.BATCH -> Long::class.java
        Column.INSERT, Column.UPDATE, Column.DELETE -> Long::class.java
        else -> String::class.java
    }
}
