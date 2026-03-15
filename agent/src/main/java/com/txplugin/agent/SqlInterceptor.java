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
     */
    public static void onPreparedExecute(String sql) {
        TransactionContext ctx = TransactionContext.current();
        if (ctx == null) return;
        ctx.sqlQueryCount++;
        if (sql != null && ctx.sqlQueries.size() < TransactionRecord.MAX_SQL_QUERIES) {
            ctx.sqlQueries.add(sql);
        }
    }

    /**
     * Called before PreparedStatement.executeBatch() / Statement.executeBatch().
     */
    public static void onBatchExecute() {
        TransactionContext ctx = TransactionContext.current();
        if (ctx == null) return;
        ctx.batchCount++;
    }

    // ── PreparedStatement SQL tracking ───────────────────────────────────────
    // When a PreparedStatement is created we store its SQL string so we can
    // report it when execute() is called (no sql arg available then).

    private static final ThreadLocal<String> PREPARED_SQL = new ThreadLocal<>();

    public static void onPrepareStatement(String sql) {
        PREPARED_SQL.set(sql);
    }

    public static String getPreparedSql() {
        return PREPARED_SQL.get();
    }


}
