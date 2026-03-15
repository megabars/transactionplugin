package com.txplugin.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * Immutable snapshot of a completed Spring transaction.
 * Serialized as newline-delimited JSON and sent to the IntelliJ plugin.
 */
public class TransactionRecord {

    // ── Identity ─────────────────────────────────────────────────────────────
    public String transactionId;
    /** Fully qualified class name, e.g. "com.example.OrderService" */
    public String className;
    /** Simple method name, e.g. "processOrder" */
    public String methodName;
    /** Comma-separated simple parameter type names, e.g. "String,int,List" */
    public String parameterTypes = "";
    /** Best-effort source line of the @Transactional method */
    public int lineNumber;

    // ── Timing ───────────────────────────────────────────────────────────────
    public long startTimeMs;
    public long endTimeMs;
    public long durationMs;

    // ── Status ───────────────────────────────────────────────────────────────
    /** "COMMITTED" or "ROLLED_BACK" */
    public String status;

    // ── @Transactional parameters ────────────────────────────────────────────
    /** e.g. "REQUIRED", "REQUIRES_NEW", "NESTED" */
    public String propagation;
    /** e.g. "DEFAULT", "READ_COMMITTED", "SERIALIZABLE" */
    public String isolationLevel;
    public boolean readOnly;
    /** Timeout in seconds (-1 = none) */
    public int timeout;

    // ── SQL / JDBC ───────────────────────────────────────────────────────────
    public int sqlQueryCount;
    /** Number of executeBatch() calls */
    public int batchCount;
    /** Up to MAX_SQL_QUERIES captured SQL strings */
    public List<String> sqlQueries = new ArrayList<>();

    // ── Exception (populated when status == ROLLED_BACK) ────────────────────
    /** e.g. "java.lang.NullPointerException" */
    public String exceptionType;
    public String exceptionMessage;
    /** Top 10 stack frames as a single string */
    public String stackTrace;

    // ── Thread ───────────────────────────────────────────────────────────────
    public String threadName;

    public static final int MAX_SQL_QUERIES = 50;
}
