package com.txplugin.plugin.model

import com.google.gson.Gson
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TransactionRecordModelTest {

    // ── methodKey ─────────────────────────────────────────────────────────────

    @Test
    fun methodKey_noParams() {
        val r = record(className = "com.example.OrderService", methodName = "processOrder", parameterTypes = "")
        assertEquals("com.example.OrderService#processOrder()", r.methodKey)
    }

    @Test
    fun methodKey_withParams() {
        val r = record(className = "com.example.Svc", methodName = "doWork", parameterTypes = "String,int")
        assertEquals("com.example.Svc#doWork(String,int)", r.methodKey)
    }

    @Test
    fun methodKey_withGenericParam_erasured() {
        val r = record(className = "com.example.Svc", methodName = "save", parameterTypes = "List")
        assertEquals("com.example.Svc#save(List)", r.methodKey)
    }

    // ── inlayHintText ─────────────────────────────────────────────────────────

    @Test
    fun inlayHintText_committed_noBatch_withPropagation() {
        val r = record(status = TransactionStatus.COMMITTED, durationMs = 342, propagation = "REQUIRED")
        val hint = r.inlayHintText
        assertTrue(hint.startsWith("✓ COMMITTED"), "Должен начинаться с ✓ COMMITTED, получили: $hint")
        assertTrue(hint.contains("342ms"), "Должен содержать длительность")
        assertTrue(hint.contains("REQUIRED"), "Должен содержать propagation")
        assertFalse(hint.contains("batch"), "Не должен содержать batch при batchCount=0")
    }

    @Test
    fun inlayHintText_rolledBack_withException() {
        val r = record(
            status = TransactionStatus.ROLLED_BACK,
            durationMs = 89,
            propagation = "REQUIRED",
            exceptionType = "java.lang.NullPointerException"
        )
        val hint = r.inlayHintText
        assertTrue(hint.startsWith("✗ ROLLED BACK"), "Должен начинаться с ✗ ROLLED BACK, получили: $hint")
        assertTrue(hint.contains("89ms"))
        assertTrue(hint.contains("REQUIRED"))
        assertTrue(hint.contains("NullPointerException"), "Должен содержать короткое имя исключения")
        assertFalse(hint.contains("java.lang"), "Не должен содержать полный путь пакета")
    }

    @Test
    fun inlayHintText_withBatchCount() {
        val r = record(status = TransactionStatus.COMMITTED, batchCount = 3, propagation = "REQUIRED")
        assertTrue(r.inlayHintText.contains("batch:3"))
    }

    @Test
    fun inlayHintText_zeroBatch_noBatchSection() {
        val r = record(status = TransactionStatus.COMMITTED, batchCount = 0, propagation = "REQUIRED")
        assertFalse(r.inlayHintText.contains("batch"))
    }

    @Test
    fun inlayHintText_committed_noExceptionSection_evenIfExceptionTypeSet() {
        // При COMMITTED исключение не выводится, даже если поле заполнено (noRollbackFor сценарий)
        val r = record(
            status = TransactionStatus.COMMITTED,
            propagation = "REQUIRED",
            exceptionType = "java.lang.IllegalArgumentException"
        )
        assertFalse(r.inlayHintText.contains("IllegalArgumentException"))
    }

    @Test
    fun inlayHintText_isComputedFromCurrentFields() {
        // Проверяем что get() вычисляется при каждом вызове, а не хранится
        val r = record(status = TransactionStatus.COMMITTED, durationMs = 100, propagation = "REQUIRED")
        val hint1 = r.inlayHintText
        // data class копия с другим значением
        val r2 = r.copy(durationMs = 999)
        val hint2 = r2.inlayHintText
        assertTrue(hint1.contains("100ms"))
        assertTrue(hint2.contains("999ms"))
        assertFalse(hint2.contains("100ms"))
    }

    // ── TransactionStatus.displayName ─────────────────────────────────────────

    @Test
    fun status_displayName_committed() {
        assertEquals("COMMITTED", TransactionStatus.COMMITTED.displayName)
    }

    @Test
    fun status_displayName_rolledBack_hasSpace() {
        // Важно: "ROLLED BACK" с пробелом, не "ROLLED_BACK"
        assertEquals("ROLLED BACK", TransactionStatus.ROLLED_BACK.displayName)
    }

    // ── isCommitted / isRolledBack ────────────────────────────────────────────

    @Test
    fun isCommitted_true_forCommitted() {
        assertTrue(record(status = TransactionStatus.COMMITTED).isCommitted)
        assertFalse(record(status = TransactionStatus.COMMITTED).isRolledBack)
    }

    @Test
    fun isRolledBack_true_forRolledBack() {
        assertTrue(record(status = TransactionStatus.ROLLED_BACK).isRolledBack)
        assertFalse(record(status = TransactionStatus.ROLLED_BACK).isCommitted)
    }

    // ── Gson десериализация ───────────────────────────────────────────────────

    @Test
    fun gsonDeserialize_fromAgentJson_allScalarFieldsPopulated() {
        val json = """
            {
              "transactionId": "abc-123",
              "className": "com.example.OrderService",
              "methodName": "placeOrder",
              "parameterTypes": "String,int",
              "lineNumber": 42,
              "startTimeMs": 1700000000000,
              "endTimeMs": 1700000000342,
              "durationMs": 342,
              "status": "COMMITTED",
              "propagation": "REQUIRED",
              "isolationLevel": "DEFAULT",
              "readOnly": false,
              "timeout": -1,
              "sqlQueryCount": 2,
              "batchCount": 0,
              "sqlQueries": ["SELECT 1", "SELECT 2"],
              "threadName": "http-nio-1",
              "exceptionType": null,
              "exceptionMessage": null,
              "stackTrace": null
            }
        """.trimIndent()

        val r = Gson().fromJson(json, TransactionRecord::class.java)

        assertEquals("abc-123", r.transactionId)
        assertEquals("com.example.OrderService", r.className)
        assertEquals("placeOrder", r.methodName)
        assertEquals("String,int", r.parameterTypes)
        assertEquals(342L, r.durationMs)
        assertEquals(TransactionStatus.COMMITTED, r.status)
        assertEquals("REQUIRED", r.propagation)
        assertEquals("DEFAULT", r.isolationLevel)
        assertFalse(r.readOnly)
        assertEquals(-1, r.timeout)
        assertEquals(2, r.sqlQueryCount)
        assertEquals(listOf("SELECT 1", "SELECT 2"), r.sqlQueries)
        assertEquals("http-nio-1", r.threadName)
        assertNull(r.exceptionType)
        assertNull(r.exceptionMessage)
        assertNull(r.stackTrace)
    }

    @Test
    fun gsonDeserialize_parameterTypes_asJsonString() {
        val json = """{"parameterTypes":"String,int","status":"COMMITTED"}"""
        val r = Gson().fromJson(json, TransactionRecord::class.java)
        assertEquals("String,int", r.parameterTypes)
    }

    @Test
    fun gsonDeserialize_rolledBackStatus() {
        val json = """{"status":"ROLLED_BACK","exceptionType":"java.lang.NullPointerException","status":"ROLLED_BACK"}"""
        val r = Gson().fromJson(json, TransactionRecord::class.java)
        assertEquals(TransactionStatus.ROLLED_BACK, r.status)
    }

    @Test
    fun gsonDeserialize_nullableFields_remainNull() {
        val json = """{"status":"COMMITTED","exceptionType":null,"exceptionMessage":null,"stackTrace":null}"""
        val r = Gson().fromJson(json, TransactionRecord::class.java)
        assertNull(r.exceptionType)
        assertNull(r.exceptionMessage)
        assertNull(r.stackTrace)
    }

    @Test
    fun gsonDeserialize_missingFields_useDefaults() {
        // Только status задан, остальные поля — дефолты Kotlin
        val json = """{"status":"COMMITTED"}"""
        val r = Gson().fromJson(json, TransactionRecord::class.java)
        assertFalse(r.readOnly)
        assertEquals(-1, r.timeout)
        assertEquals("", r.parameterTypes)
        assertEquals(emptyList<String>(), r.sqlQueries)
    }

    @Test
    fun gsonDeserialize_methodKey_correctAfterDeserialization() {
        val json = """
            {"className":"com.example.Svc","methodName":"process","parameterTypes":"String","status":"COMMITTED"}
        """.trimIndent()
        val r = Gson().fromJson(json, TransactionRecord::class.java)
        assertEquals("com.example.Svc#process(String)", r.methodKey)
    }

    // ── Вспомогательный метод ─────────────────────────────────────────────────

    private fun record(
        transactionId: String = "test-id",
        className: String = "com.example.TestService",
        methodName: String = "doWork",
        parameterTypes: String = "",
        status: TransactionStatus = TransactionStatus.COMMITTED,
        durationMs: Long = 0,
        propagation: String = "",
        batchCount: Int = 0,
        exceptionType: String? = null
    ) = TransactionRecord(
        transactionId = transactionId,
        className = className,
        methodName = methodName,
        parameterTypes = parameterTypes,
        status = status,
        durationMs = durationMs,
        propagation = propagation,
        batchCount = batchCount,
        exceptionType = exceptionType
    )
}
