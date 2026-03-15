package com.txplugin.plugin.model

/**
 * Mirror of the agent's TransactionRecord.
 * Deserialized from newline-delimited JSON received via TCP socket.
 */
data class TransactionRecord(
    val transactionId: String = "",
    val className: String = "",
    val methodName: String = "",
    /** Comma-separated simple type names, e.g. "String,int,List". Empty for no-arg methods. */
    val parameterTypes: String = "",
    val lineNumber: Int = 0,

    val startTimeMs: Long = 0,
    val endTimeMs: Long = 0,
    val durationMs: Long = 0,

    /** "COMMITTED" or "ROLLED_BACK" */
    val status: String = "",

    val propagation: String = "",
    val isolationLevel: String = "",
    val readOnly: Boolean = false,
    val timeout: Int = -1,

    val sqlQueryCount: Int = 0,
    val batchCount: Int = 0,
    val sqlQueries: List<String> = emptyList(),

    val insertCount: Long = 0,
    val updateCount: Long = 0,
    val deleteCount: Long = 0,

    val exceptionType: String? = null,
    val exceptionMessage: String? = null,
    val stackTrace: String? = null,

    val threadName: String = ""
) {
    val isCommitted: Boolean get() = status == "COMMITTED"
    val isRolledBack: Boolean get() = status == "ROLLED_BACK"

    /** Short key used to match records to source methods, includes parameter types for overload disambiguation */
    val methodKey: String get() = "$className#$methodName($parameterTypes)"

    /** One-line summary for inlay hint */
    val inlayHintText: String get() = buildString {
        if (isCommitted) append("✓ COMMITTED") else append("✗ ROLLED BACK")
        append("  ${durationMs}ms")
        if (sqlQueryCount > 0) {
            append(" | SQL:$sqlQueryCount")
            if (batchCount > 0) append(" batch:$batchCount")
        }
        val entityOps = buildString {
            if (insertCount > 0) append("↑$insertCount ")
            if (updateCount > 0) append("✎$updateCount ")
            if (deleteCount > 0) append("↓$deleteCount")
        }.trim()
        if (entityOps.isNotEmpty()) append(" | $entityOps")
        if (propagation.isNotEmpty()) append(" | $propagation")
        if (isRolledBack && exceptionType != null) {
            val shortType = exceptionType.substringAfterLast('.')
            append(" | $shortType")
        }
    }
}
