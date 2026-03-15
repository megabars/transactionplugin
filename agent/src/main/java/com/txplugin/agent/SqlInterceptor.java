package com.txplugin.agent;

/**
 * Static helper methods invoked by Byte Buddy advice injected into
 * {@link java.sql.PreparedStatement} and {@link java.sql.Statement}.
 *
 * These methods are called from the instrumented JVM, so they must be
 * accessible (public static) and must not throw.
 */
public class SqlInterceptor {

    /**
     * Called before Statement.execute(String sql) / executeUpdate(String sql) /
     * executeQuery(String sql).
     */
    public static void onStatementExecute(String sql) {
        TransactionContext ctx = TransactionContext.current();
        if (ctx == null || sql == null) return;
        ctx.sqlQueryCount++;
        if (ctx.sqlQueries.size() < TransactionRecord.MAX_SQL_QUERIES) {
            ctx.sqlQueries.add(sql);
        }
    }

    /**
     * Called before PreparedStatement.execute() / executeUpdate() / executeQuery().
     * The SQL is captured at prepareStatement() time and stored in a ThreadLocal.
     * Parameters collected via setXxx() are appended after the SQL on a separate line.
     */
    public static void onPreparedExecute(String sql, java.util.List<String> params) {
        TransactionContext ctx = TransactionContext.current();
        if (ctx == null) return;
        ctx.sqlQueryCount++;
        if (sql != null && ctx.sqlQueries.size() < TransactionRecord.MAX_SQL_QUERIES) {
            ctx.sqlQueries.add(buildEntry(sql, params));
        }
    }

    /**
     * Called before PreparedStatement.addBatch() (no-arg).
     * Batch flow: prepareStatement → (setXxx × N → addBatch) × M → executeBatch.
     * SQL is NOT removed here so it remains available for subsequent addBatch() calls.
     * Params are cleared after capture so the next row starts fresh.
     */
    public static void onAddBatch() {
        // Skip if this is a proxy double-call for the same row
        if (Boolean.TRUE.equals(BATCH_ROW_CAPTURED.get())) return;
        BATCH_ROW_CAPTURED.set(Boolean.TRUE);

        TransactionContext ctx = TransactionContext.current();
        if (ctx == null) return;
        ctx.sqlQueryCount++;
        ctx.batchCount++; // count rows, not executeBatch() calls
        String sql = PREPARED_SQL.get(); // peek — keep for subsequent addBatch() calls
        java.util.LinkedHashMap<Integer, Object> params = PREPARED_PARAMS.get();
        if (sql != null && ctx.sqlQueries.size() < TransactionRecord.MAX_SQL_QUERIES) {
            ctx.sqlQueries.add(buildEntry(sql, formatParams(params)));
        }
        // Clear params for the next row; SQL stays until executeBatch() cleans up
        if (params != null) params.clear();
    }

    /**
     * Called on entry to executeBatch(). Only the outermost call (depth==0) is counted —
     * proxy wrappers re-invoke the real executeBatch() from within, which would double the
     * count without this guard.
     */
    public static void onBatchExecuteEnter() {
        int[] depth = BATCH_EXEC_DEPTH.get();
        if (depth[0] == 0) {
            PREPARED_SQL.remove();
            PREPARED_PARAMS.remove();
            BATCH_ROW_CAPTURED.remove();
        }
        depth[0]++;
    }

    /** Called on exit from executeBatch() (including exceptional exit). */
    public static void onBatchExecuteExit() {
        int[] depth = BATCH_EXEC_DEPTH.get();
        if (depth[0] > 0) depth[0]--;
    }

    // ── PreparedStatement SQL tracking ───────────────────────────────────────
    // When a PreparedStatement is created we store its SQL string so we can
    // report it when execute() is called (no sql arg available then).

    private static final ThreadLocal<String> PREPARED_SQL = new ThreadLocal<>();
    // LinkedHashMap: key = parameterIndex, value = bound value.
    // Using a map (not a list) deduplicates proxy double-calls: if a JDBC proxy wraps
    // the real PreparedStatement, setXxx fires twice for the same index — put() overwrites
    // instead of appending, so we never get duplicate entries like [1='x', 1='x'].
    private static final ThreadLocal<java.util.LinkedHashMap<Integer, Object>> PREPARED_PARAMS = new ThreadLocal<>();
    // Deduplicates addBatch() proxy double-calls: proxy calls addBatch() → we capture and
    // set flag; real PS calls addBatch() again → we skip. Flag resets when setXxx is called
    // (next batch row is being prepared).
    private static final ThreadLocal<Boolean> BATCH_ROW_CAPTURED = new ThreadLocal<>();
    // Depth counter for executeBatch() proxy double-calls.
    // int[1] instead of Integer so we can mutate without re-boxing.
    private static final ThreadLocal<int[]> BATCH_EXEC_DEPTH = ThreadLocal.withInitial(() -> new int[]{0});

    public static void onPrepareStatement(String sql) {
        PREPARED_SQL.set(sql);
        PREPARED_PARAMS.set(new java.util.LinkedHashMap<>());
        BATCH_ROW_CAPTURED.remove();
    }

    /**
     * Called from SetParameterAdvice for every setXxx(int parameterIndex, value) call.
     * Overwrites any previous value for the same index (handles proxy double-calls).
     * Also resets the batch-row-captured flag so the next addBatch() is treated as a new row.
     */
    public static void onSetParameter(int index, Object value) {
        java.util.LinkedHashMap<Integer, Object> params = PREPARED_PARAMS.get();
        if (params == null) return;
        // Guard against runaway params (e.g. huge batch re-bindings before addBatch)
        if (!params.containsKey(index) && params.size() >= 200) return;
        params.put(index, value);
        BATCH_ROW_CAPTURED.set(Boolean.FALSE); // new row is being prepared
    }

    public static String getPreparedSql() {
        String sql = PREPARED_SQL.get();
        PREPARED_SQL.remove(); // prevent stale SQL leaking into reused thread-pool threads
        return sql;
    }

    public static java.util.List<String> getPreparedParams() {
        java.util.LinkedHashMap<Integer, Object> params = PREPARED_PARAMS.get();
        PREPARED_PARAMS.remove();
        return formatParams(params);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static java.util.List<String> formatParams(java.util.LinkedHashMap<Integer, Object> params) {
        if (params == null || params.isEmpty()) return java.util.Collections.emptyList();
        java.util.List<String> result = new java.util.ArrayList<>(params.size());
        for (java.util.Map.Entry<Integer, Object> e : params.entrySet()) {
            Object v = e.getValue();
            String repr;
            if (v == null) {
                repr = "null";
            } else {
                String str = v.toString();
                repr = "'" + (str.length() > 100 ? str.substring(0, 100) + "…" : str) + "'";
            }
            result.add(e.getKey() + "=" + repr);
        }
        return result;
    }

    private static String buildEntry(String sql, java.util.List<String> params) {
        return (params == null || params.isEmpty())
                ? sql
                : sql + "\n  [" + String.join(", ", params) + "]";
    }
}
