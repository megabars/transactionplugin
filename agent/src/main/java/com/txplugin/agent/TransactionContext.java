package com.txplugin.agent;

import java.util.UUID;

/**
 * Per-thread mutable state for a transaction in progress.
 * Populated incrementally as the transaction executes, then snapshotted
 * into a {@link TransactionRecord} on commit/rollback.
 */
public class TransactionContext {

    public final String transactionId = UUID.randomUUID().toString();
    public String className;
    public String methodName;
    public int lineNumber;

    public long startTimeMs = System.currentTimeMillis();

    // @Transactional metadata — filled by TransactionAspectSupport interceptor
    public String propagation = "REQUIRED";
    public String isolationLevel = "DEFAULT";
    public boolean readOnly = false;
    public int timeout = -1;

    // SQL tracking — incremented by SqlInterceptor
    public int sqlQueryCount = 0;
    public int batchCount = 0;
    public final java.util.List<String> sqlQueries = new java.util.ArrayList<>();

    // Hibernate entity counts — snapshotted before/after by HibernateStatsCollector
    public long insertCountBefore = 0;
    public long updateCountBefore = 0;
    public long deleteCountBefore = 0;

    // Exception (set on rollback)
    public Throwable exception;

    public final String threadName = Thread.currentThread().getName();

    /** Thread-local singleton — one context per transaction per thread */
    private static final ThreadLocal<TransactionContext> CURRENT = new ThreadLocal<>();

    public static TransactionContext current() {
        return CURRENT.get();
    }

    public static void set(TransactionContext ctx) {
        CURRENT.set(ctx);
    }

    public static void clear() {
        CURRENT.remove();
    }

    /**
     * Build the final immutable record when the transaction completes.
     *
     * @param status        "COMMITTED" or "ROLLED_BACK"
     * @param insertAfter   current Hibernate insert counter
     * @param updateAfter   current Hibernate update counter
     * @param deleteAfter   current Hibernate delete counter
     */
    public TransactionRecord toRecord(String status,
                                       long insertAfter,
                                       long updateAfter,
                                       long deleteAfter) {
        TransactionRecord r = new TransactionRecord();
        r.transactionId = transactionId;
        r.className = className;
        r.methodName = methodName;
        r.lineNumber = lineNumber;

        r.startTimeMs = startTimeMs;
        r.endTimeMs = System.currentTimeMillis();
        r.durationMs = r.endTimeMs - startTimeMs;

        r.status = status;
        r.propagation = propagation;
        r.isolationLevel = isolationLevel;
        r.readOnly = readOnly;
        r.timeout = timeout;

        r.sqlQueryCount = sqlQueryCount;
        r.batchCount = batchCount;
        r.sqlQueries = new java.util.ArrayList<>(sqlQueries);

        r.insertCount = Math.max(0, insertAfter - insertCountBefore);
        r.updateCount = Math.max(0, updateAfter - updateCountBefore);
        r.deleteCount = Math.max(0, deleteAfter - deleteCountBefore);

        r.threadName = threadName;

        if (exception != null) {
            r.exceptionType = exception.getClass().getName();
            r.exceptionMessage = exception.getMessage();
            r.stackTrace = buildStackTrace(exception);
        }

        return r;
    }

    private static String buildStackTrace(Throwable t) {
        StackTraceElement[] frames = t.getStackTrace();
        StringBuilder sb = new StringBuilder(t.toString()).append('\n');
        int limit = Math.min(frames.length, 10);
        for (int i = 0; i < limit; i++) {
            sb.append("  at ").append(frames[i]).append('\n');
        }
        if (frames.length > 10) {
            sb.append("  ... ").append(frames.length - 10).append(" more");
        }
        return sb.toString();
    }
}
