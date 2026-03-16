package com.txplugin.plugin.ui

import com.txplugin.plugin.model.TransactionRecord
import com.txplugin.plugin.model.TransactionStatus
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TransactionTableModelTest {

    private lateinit var model: TransactionTableModel

    @BeforeEach
    fun setUp() {
        model = TransactionTableModel()
    }

    // ── Размеры ───────────────────────────────────────────────────────────────

    @Test
    fun rowCount_empty_isZero() {
        assertEquals(0, model.rowCount)
    }

    @Test
    fun rowCount_matchesRecordsSize() {
        model.setRecords(listOf(record("r1"), record("r2"), record("r3")))
        assertEquals(3, model.rowCount)
    }

    @Test
    fun columnCount_is8() {
        assertEquals(8, model.columnCount)
    }

    // ── Имена и типы колонок ──────────────────────────────────────────────────

    @Test
    fun getColumnName_allColumns_nonEmpty() {
        for (i in 0 until model.columnCount) {
            val name = model.getColumnName(i)
            assertNotNull(name)
            assertTrue(name.isNotEmpty(), "Имя колонки $i не должно быть пустым")
        }
    }

    @Test
    fun getColumnClass_durationColumn_isLong() {
        val idx = TransactionTableModel.Column.DURATION.ordinal
        assertEquals(Long::class.javaObjectType, model.getColumnClass(idx))
    }

    @Test
    fun getColumnClass_batchColumn_isInteger() {
        val idx = TransactionTableModel.Column.BATCH.ordinal
        assertEquals(Int::class.javaObjectType, model.getColumnClass(idx))
    }

    @Test
    fun getColumnClass_otherColumns_areString() {
        for (col in listOf(
            TransactionTableModel.Column.TIME,
            TransactionTableModel.Column.METHOD,
            TransactionTableModel.Column.STATUS,
            TransactionTableModel.Column.PROPAGATION,
            TransactionTableModel.Column.ISOLATION,
            TransactionTableModel.Column.READ_ONLY
        )) {
            assertEquals(String::class.java, model.getColumnClass(col.ordinal),
                "Колонка ${col.name} должна иметь тип String")
        }
    }

    // ── getValueAt — правильные значения ──────────────────────────────────────

    @Test
    fun getValueAt_methodColumn_includesSimpleClassNameAndParens() {
        // METHOD = "${className.substringAfterLast('.')}.${methodName}()"
        model.setRecords(listOf(record("r1",
            className = "com.example.OrderService",
            methodName = "processOrder"
        )))
        val value = model.getValueAt(0, TransactionTableModel.Column.METHOD.ordinal)
        assertEquals("OrderService.processOrder()", value)
    }

    @Test
    fun getValueAt_durationColumn_returnsLong() {
        model.setRecords(listOf(record("r1", durationMs = 342L)))
        val value = model.getValueAt(0, TransactionTableModel.Column.DURATION.ordinal)
        assertEquals(342L, value)
    }

    @Test
    fun getValueAt_statusColumn_usesDisplayName_committed() {
        model.setRecords(listOf(record("r1", status = TransactionStatus.COMMITTED)))
        val value = model.getValueAt(0, TransactionTableModel.Column.STATUS.ordinal)
        assertEquals("COMMITTED", value)
    }

    @Test
    fun getValueAt_statusColumn_usesDisplayName_rolledBack() {
        // "ROLLED BACK" с пробелом, не "ROLLED_BACK"
        model.setRecords(listOf(record("r1", status = TransactionStatus.ROLLED_BACK)))
        val value = model.getValueAt(0, TransactionTableModel.Column.STATUS.ordinal)
        assertEquals("ROLLED BACK", value)
        assertNotEquals("ROLLED_BACK", value)
    }

    @Test
    fun getValueAt_batchColumn_returnsInt() {
        model.setRecords(listOf(record("r1", batchCount = 5)))
        val value = model.getValueAt(0, TransactionTableModel.Column.BATCH.ordinal)
        assertEquals(5, value)
    }

    @Test
    fun getValueAt_propagationColumn() {
        model.setRecords(listOf(record("r1", propagation = "REQUIRES_NEW")))
        val value = model.getValueAt(0, TransactionTableModel.Column.PROPAGATION.ordinal)
        assertEquals("REQUIRES_NEW", value)
    }

    @Test
    fun getValueAt_readOnlyColumn_trueIsY() {
        // readOnly=true → "Y"
        model.setRecords(listOf(record("r1", readOnly = true)))
        val value = model.getValueAt(0, TransactionTableModel.Column.READ_ONLY.ordinal)
        assertEquals("Y", value)
    }

    @Test
    fun getValueAt_readOnlyColumn_falseIsEmpty() {
        // readOnly=false → "" (пустая строка)
        model.setRecords(listOf(record("r1", readOnly = false)))
        val value = model.getValueAt(0, TransactionTableModel.Column.READ_ONLY.ordinal)
        assertEquals("", value)
    }

    // ── Порядок отображения: newest first ─────────────────────────────────────

    @Test
    fun setRecords_storesInReversedOrder_newestFirst() {
        // setRecords вызывает asReversed() и сохраняет результат в rows
        val r1 = record("r1", className = "com.example.Svc", methodName = "method1")
        val r2 = record("r2", className = "com.example.Svc", methodName = "method2")
        model.setRecords(listOf(r1, r2))

        // row 0 — r2 (последний в списке = самый новый)
        assertEquals("Svc.method2()", model.getValueAt(0, TransactionTableModel.Column.METHOD.ordinal))
        assertEquals("Svc.method1()", model.getValueAt(1, TransactionTableModel.Column.METHOD.ordinal))
    }

    @Test
    fun setRecords_empty_zeroRows() {
        model.setRecords(listOf(record("r1")))
        model.setRecords(emptyList())
        assertEquals(0, model.rowCount)
    }

    @Test
    fun setRecords_replacesExistingRecords() {
        model.setRecords(listOf(record("r1"), record("r2")))
        model.setRecords(listOf(record("r3")))
        assertEquals(1, model.rowCount)
    }

    @Test
    fun recordAt_returnsCorrectRecord() {
        val r1 = record("r1", methodName = "first")
        val r2 = record("r2", methodName = "second")
        model.setRecords(listOf(r1, r2))
        // rows хранит reversed: [r2, r1]
        assertNotNull(model.recordAt(0))
        assertEquals("second", model.recordAt(0)?.methodName)
        assertEquals("first", model.recordAt(1)?.methodName)
    }

    @Test
    fun recordAt_outOfBounds_returnsNull() {
        model.setRecords(listOf(record("r1")))
        assertNull(model.recordAt(99))
    }

    // ── Вспомогательный метод ─────────────────────────────────────────────────

    private fun record(
        id: String,
        className: String = "com.example.TestService",
        methodName: String = "doWork",
        durationMs: Long = 100L,
        status: TransactionStatus = TransactionStatus.COMMITTED,
        batchCount: Int = 0,
        propagation: String = "REQUIRED",
        readOnly: Boolean = false
    ) = TransactionRecord(
        transactionId = id,
        className = className,
        methodName = methodName,
        startTimeMs = System.currentTimeMillis(),
        durationMs = durationMs,
        status = status,
        batchCount = batchCount,
        propagation = propagation,
        readOnly = readOnly
    )
}
