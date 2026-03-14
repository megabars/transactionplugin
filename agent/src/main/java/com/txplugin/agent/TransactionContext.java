package com.txplugin.agent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/**
 * Per-thread stack of in-progress transactions.
 * A stack (not a single value) handles nested @Transactional calls correctly:
 * SQL queries are always attributed to the innermost active transaction.
 */
public class TransactionContext {

    public final String transactionId = UUID.randomUUID().toString();
    public String className;
    public String methodName;
    public int lineNumber;

    public final long startTimeMs = System.currentTimeMillis();

    // @Transactional metadata
    public String propagation    = "REQUIRED";
    public String isolationLevel = "DEFAULT";
    public boolean readOnly      = false;
    public int timeout           = -1;

    // SQL tracking
    public int sqlQueryCount = 0;
    public int batchCount    = 0;
    public final java.util.List<String> sqlQueries = new java.util.ArrayList<>();

    // Hibernate entity counts snapshotted at transaction start
    public long insertCountBefore = 0;
    public long updateCountBefore = 0;
    public long deleteCountBefore = 0;

    // Exception set on rollback
    public Throwable exception;

    public final String threadName = Thread.currentThread().getName();

    // ── Stack-based ThreadLocal ───────────────────────────────────────────────

    private static final ThreadLocal<Deque<TransactionContext>> STACK =
            ThreadLocal.withInitial(ArrayDeque::new);

    /** Push a new context for an entering @Transactional method. */
    public static TransactionContext push() {
        TransactionContext ctx = new TransactionContext();
        STACK.get().push(ctx);
        return ctx;
    }

    /** The innermost active context (used by SQL interceptor). */
    public static TransactionContext current() {
        Deque<TransactionContext> stack = STACK.get();
        return stack.isEmpty() ? null : stack.peek();
    }

    /** Pop and return the innermost context when its method exits. */
    public static TransactionContext pop() {
        Deque<TransactionContext> stack = STACK.get();
        TransactionContext ctx = stack.poll();
        if (stack.isEmpty()) STACK.remove(); // avoid memory leak
        return ctx;
    }

    // ── Record builder ────────────────────────────────────────────────────────

    public TransactionRecord toRecord(String status,
                                       long insertAfter,
                                       long updateAfter,
                                       long deleteAfter) {
        TransactionRecord r   = new TransactionRecord();
        r.transactionId       = transactionId;
        r.className           = className;
        r.methodName          = methodName;
        r.lineNumber          = lineNumber;
        r.startTimeMs         = startTimeMs;
        r.endTimeMs           = System.currentTimeMillis();
        r.durationMs          = r.endTimeMs - startTimeMs;
        r.status              = status;
        r.propagation         = propagation;
        r.isolationLevel      = isolationLevel;
        r.readOnly            = readOnly;
        r.timeout             = timeout;
        r.sqlQueryCount       = sqlQueryCount;
        r.batchCount          = batchCount;
        r.sqlQueries          = new java.util.ArrayList<>(sqlQueries);
        r.insertCount         = Math.max(0, insertAfter - insertCountBefore);
        r.updateCount         = Math.max(0, updateAfter - updateCountBefore);
        r.deleteCount         = Math.max(0, deleteAfter - deleteCountBefore);
        r.threadName          = threadName;
        if (exception != null) {
            r.exceptionType    = exception.getClass().getName();
            r.exceptionMessage = exception.getMessage();
            r.stackTrace       = buildStackTrace(exception);
        }
        return r;
    }

    private static String buildStackTrace(Throwable t) {
        StackTraceElement[] frames = t.getStackTrace();
        StringBuilder sb = new StringBuilder(t.toString()).append('\n');
        int limit = Math.min(frames.length, 10);
        for (int i = 0; i < limit; i++) sb.append("  at ").append(frames[i]).append('\n');
        if (frames.length > 10) sb.append("  ... ").append(frames.length - 10).append(" more");
        return sb.toString();
    }
}
