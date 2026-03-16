package com.txplugin.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SqlInterceptorTest {

    private TransactionContext ctx;

    @BeforeEach
    void setUp() {
        ctx = TransactionContext.push();
    }

    @AfterEach
    void tearDown() {
        while (TransactionContext.current() != null) {
            TransactionContext.pop();
        }
        // Сбрасываем любое оставшееся ThreadLocal-состояние SqlInterceptor
        SqlInterceptor.getPreparedSql();
        SqlInterceptor.getPreparedParams();
    }

    // ── PreparedStatement flow ────────────────────────────────────────────────

    @Test
    void preparedExecute_addsQueryToContext() {
        SqlInterceptor.onPreparedExecute("SELECT 1", List.of());

        assertEquals(1, ctx.sqlQueryCount);
        assertEquals(1, ctx.sqlQueries.size());
        assertEquals("SELECT 1", ctx.sqlQueries.get(0));
    }

    @Test
    void preparedExecute_withParams_formatsOnNextLine() {
        SqlInterceptor.onPreparedExecute(
            "SELECT * FROM users WHERE id = ?",
            List.of("1='42'")
        );

        assertEquals(1, ctx.sqlQueries.size());
        String query = ctx.sqlQueries.get(0);
        assertTrue(query.contains("SELECT * FROM users WHERE id = ?"));
        assertTrue(query.contains("[1='42']"));
    }

    @Test
    void preparedExecute_multipleParams() {
        SqlInterceptor.onPreparedExecute(
            "INSERT INTO orders (user_id, amount) VALUES (?,?)",
            List.of("1='42'", "2='99.90'")
        );

        String query = ctx.sqlQueries.get(0);
        assertTrue(query.contains("[1='42', 2='99.90']"));
    }

    @Test
    void preparedExecute_noContext_doesNotThrow() {
        TransactionContext.pop();
        assertDoesNotThrow(() -> SqlInterceptor.onPreparedExecute("SELECT 1", List.of()));
    }

    @Test
    void preparedExecute_nullSql_countsQueryButNoSqlAdded() {
        // sqlQueryCount инкрементируется всегда, но запись в sqlQueries не добавляется
        SqlInterceptor.onPreparedExecute(null, List.of());
        assertEquals(1, ctx.sqlQueryCount);
        assertEquals(0, ctx.sqlQueries.size());
    }

    // ── onPrepareStatement + onSetParameter + getPreparedSql/Params ──────────

    @Test
    void onPrepareStatement_storesSql() {
        SqlInterceptor.onPrepareStatement("SELECT ?");
        assertEquals("SELECT ?", SqlInterceptor.getPreparedSql());
    }

    @Test
    void getPreparedSql_consumesValue() {
        SqlInterceptor.onPrepareStatement("SELECT 1");
        SqlInterceptor.getPreparedSql(); // первый вызов — потребляет
        assertNull(SqlInterceptor.getPreparedSql()); // второй — уже null
    }

    @Test
    void onSetParameter_formatsParamString() {
        SqlInterceptor.onPrepareStatement("SELECT ?");
        SqlInterceptor.onSetParameter(1, 42);
        List<String> params = SqlInterceptor.getPreparedParams();
        assertEquals(1, params.size());
        assertEquals("1='42'", params.get(0));
    }

    @Test
    void onSetParameter_deduplicatesByIndex_linkedHashMap() {
        SqlInterceptor.onPrepareStatement("SELECT ?");
        SqlInterceptor.onSetParameter(1, "first");
        SqlInterceptor.onSetParameter(1, "second"); // перезаписывает тот же индекс
        List<String> params = SqlInterceptor.getPreparedParams();
        assertEquals(1, params.size());
        assertEquals("1='second'", params.get(0));
    }

    @Test
    void onSetParameter_ignoresNullParams_whenNoPrepare() {
        // Нет активного prepare — PREPARED_PARAMS == null
        assertDoesNotThrow(() -> SqlInterceptor.onSetParameter(1, "value"));
    }

    @Test
    void fullPreparedFlow_prepareSetExecute() {
        SqlInterceptor.onPrepareStatement("SELECT * FROM users WHERE id = ?");
        SqlInterceptor.onSetParameter(1, 42);
        String sql = SqlInterceptor.getPreparedSql();
        List<String> params = SqlInterceptor.getPreparedParams();
        SqlInterceptor.onPreparedExecute(sql, params);

        assertEquals(1, ctx.sqlQueryCount);
        assertTrue(ctx.sqlQueries.get(0).contains("1='42'"));
    }

    // ── Batch flow ────────────────────────────────────────────────────────────

    @Test
    void batch_countsRowsCorrectly() {
        SqlInterceptor.onPrepareStatement("INSERT INTO users (name) VALUES (?)");
        SqlInterceptor.onSetParameter(1, "Alice");
        SqlInterceptor.onAddBatch();
        SqlInterceptor.onSetParameter(1, "Bob");
        SqlInterceptor.onAddBatch();
        SqlInterceptor.onSetParameter(1, "Charlie");
        SqlInterceptor.onAddBatch();
        SqlInterceptor.onBatchExecuteEnter();
        SqlInterceptor.onBatchExecuteExit();

        assertEquals(3, ctx.batchCount);
        assertEquals(1, ctx.sqlQueryCount);
    }

    @Test
    void batch_capturesParamsForEachRow() {
        SqlInterceptor.onPrepareStatement("INSERT INTO users (name) VALUES (?)");
        SqlInterceptor.onSetParameter(1, "Alice");
        SqlInterceptor.onAddBatch();
        SqlInterceptor.onSetParameter(1, "Bob");
        SqlInterceptor.onAddBatch();
        SqlInterceptor.onBatchExecuteEnter();
        SqlInterceptor.onBatchExecuteExit();

        assertEquals(1, ctx.sqlQueries.size());
        String entry = ctx.sqlQueries.get(0);
        assertTrue(entry.contains("[batch: 2 rows]"), "Заголовок должен содержать число строк");
        assertTrue(entry.contains("1='Alice'"), "Должны быть параметры первой строки");
        assertTrue(entry.contains("1='Bob'"), "Должны быть параметры второй строки");
    }

    @Test
    void batch_addBatch_deduplicatesProxyDoubleCalls() {
        // После первого onAddBatch флаг = TRUE → второй onAddBatch игнорируется
        SqlInterceptor.onPrepareStatement("INSERT INTO t (v) VALUES (?)");
        SqlInterceptor.onSetParameter(1, "x");
        SqlInterceptor.onAddBatch(); // захватывает, batchCount=1
        SqlInterceptor.onAddBatch(); // пропускается (флаг TRUE)
        SqlInterceptor.onBatchExecuteEnter();
        SqlInterceptor.onBatchExecuteExit();

        assertEquals(1, ctx.batchCount);
    }

    @Test
    void batch_setParam_resetsFlagAllowsNextRow() {
        // onSetParameter сбрасывает BATCH_ROW_CAPTURED → следующий onAddBatch захватывает
        SqlInterceptor.onPrepareStatement("INSERT INTO t (v) VALUES (?)");
        SqlInterceptor.onSetParameter(1, "x");
        SqlInterceptor.onAddBatch(); // batchCount=1, флаг=TRUE
        SqlInterceptor.onSetParameter(1, "y"); // флаг=FALSE
        SqlInterceptor.onAddBatch(); // batchCount=2
        SqlInterceptor.onBatchExecuteEnter();
        SqlInterceptor.onBatchExecuteExit();

        assertEquals(2, ctx.batchCount);
    }

    @Test
    void batch_execDepth_deduplicatesProxyDoubleCalls() {
        // Только первый вызов onBatchExecuteEnter (depth=0) добавляет запись
        SqlInterceptor.onPrepareStatement("INSERT INTO t (v) VALUES (?)");
        SqlInterceptor.onSetParameter(1, "a");
        SqlInterceptor.onAddBatch();
        SqlInterceptor.onBatchExecuteEnter(); // depth=0 → обрабатывает, depth становится 1
        SqlInterceptor.onBatchExecuteEnter(); // depth=1 → пропускает, depth становится 2
        SqlInterceptor.onBatchExecuteExit();  // depth=1
        SqlInterceptor.onBatchExecuteExit();  // depth=0 → освобождает

        assertEquals(1, ctx.sqlQueryCount, "Двойной enter → только один SQL");
    }

    @Test
    void batch_execDepth_cleanedAfterExit() {
        // После полного цикла enter/exit второй batch работает корректно
        SqlInterceptor.onPrepareStatement("SELECT 1");
        SqlInterceptor.onAddBatch();
        SqlInterceptor.onBatchExecuteEnter();
        SqlInterceptor.onBatchExecuteExit();

        SqlInterceptor.onPrepareStatement("SELECT 2");
        SqlInterceptor.onAddBatch();
        SqlInterceptor.onBatchExecuteEnter();
        SqlInterceptor.onBatchExecuteExit();

        assertEquals(2, ctx.sqlQueryCount, "Два независимых batch — два SQL");
    }

    @Test
    void batch_truncatesParamListAt1000Rows() {
        SqlInterceptor.onPrepareStatement("INSERT INTO t (v) VALUES (?)");
        for (int i = 1; i <= 1001; i++) {
            SqlInterceptor.onSetParameter(1, "val" + i);
            SqlInterceptor.onAddBatch();
        }
        SqlInterceptor.onBatchExecuteEnter();
        SqlInterceptor.onBatchExecuteExit();

        assertEquals(1001, ctx.batchCount);
        String entry = ctx.sqlQueries.get(0);
        // Строка должна содержать информацию об усечении
        assertTrue(entry.contains("1001"), "Должен показать реальное число строк");
        // Параметры усечены — не должно быть 1001-й строки
        assertTrue(entry.contains("params for first"), "Должна быть пометка об усечении");
    }

    // ── plain Statement ───────────────────────────────────────────────────────

    @Test
    void statementExecute_addsRawSql() {
        SqlInterceptor.onStatementExecute("SELECT now()");

        assertEquals(1, ctx.sqlQueryCount);
        assertEquals("SELECT now()", ctx.sqlQueries.get(0));
    }

    @Test
    void statementExecute_ignoresNullSql() {
        SqlInterceptor.onStatementExecute(null);
        assertEquals(0, ctx.sqlQueryCount);
    }

    @Test
    void statementExecute_ignoresWhenNoContext() {
        TransactionContext.pop();
        assertDoesNotThrow(() -> SqlInterceptor.onStatementExecute("SELECT 1"));
    }

    // ── Изоляция ThreadLocal между потоками ──────────────────────────────────

    @Test
    void threadLocal_sqlNoLeakBetweenThreads() throws InterruptedException {
        // Поток 1 готовит SQL, но не выполняет — поток 2 не должен его получить
        CountDownLatch prepDone = new CountDownLatch(1);
        CountDownLatch execDone = new CountDownLatch(1);
        AtomicInteger thread2QueryCount = new AtomicInteger(-1);

        Thread t1 = new Thread(() -> {
            SqlInterceptor.onPrepareStatement("SELECT secret FROM t1");
            prepDone.countDown();
            try { execDone.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            SqlInterceptor.getPreparedSql(); // чистим за собой
        });

        Thread t2 = new Thread(() -> {
            try { prepDone.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            TransactionContext t2ctx = TransactionContext.push();
            // Выполняем execute без предварительного prepare в этом потоке
            String sql = SqlInterceptor.getPreparedSql(); // должен быть null
            if (sql != null) {
                SqlInterceptor.onPreparedExecute(sql, List.of());
            }
            thread2QueryCount.set(t2ctx.sqlQueryCount);
            TransactionContext.pop();
            execDone.countDown();
        });

        t1.start();
        t2.start();
        t1.join();
        t2.join();

        assertEquals(0, thread2QueryCount.get(), "Поток 2 не должен видеть SQL потока 1");
    }
}
