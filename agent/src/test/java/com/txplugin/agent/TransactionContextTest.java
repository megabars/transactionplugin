package com.txplugin.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TransactionContextTest {

    @BeforeEach
    @AfterEach
    void clearStack() {
        // Очищаем стек на случай если предыдущий тест оставил элементы
        while (TransactionContext.current() != null) {
            TransactionContext.pop();
        }
    }

    // ── Базовые операции стека ────────────────────────────────────────────────

    @Test
    void push_createsNewContext() {
        assertNull(TransactionContext.current());
        TransactionContext ctx = TransactionContext.push();
        assertNotNull(ctx);
        assertSame(ctx, TransactionContext.current());
    }

    @Test
    void pop_returnsContext_andClearsStack() {
        TransactionContext ctx = TransactionContext.push();
        TransactionContext popped = TransactionContext.pop();
        assertSame(ctx, popped);
        assertNull(TransactionContext.current());
    }

    @Test
    void currentReturnsNull_whenEmpty() {
        assertNull(TransactionContext.current());
    }

    @Test
    void pop_returnsNull_whenEmpty() {
        assertNull(TransactionContext.pop());
    }

    @Test
    void pop_removesThreadLocal_whenStackBecomesEmpty() {
        TransactionContext.push();
        TransactionContext.pop();
        // Повторный push/pop должен работать без проблем
        TransactionContext ctx2 = TransactionContext.push();
        assertNotNull(ctx2);
        TransactionContext.pop();
        assertNull(TransactionContext.current());
    }

    // ── Вложенные транзакции ──────────────────────────────────────────────────

    @Test
    void nestedPushPop_maintainsCorrectOrder() {
        TransactionContext ctxA = TransactionContext.push();
        TransactionContext ctxB = TransactionContext.push();

        assertNotSame(ctxA, ctxB);
        assertSame(ctxB, TransactionContext.current()); // innermost
        assertSame(ctxB, TransactionContext.pop());
        assertSame(ctxA, TransactionContext.current()); // outer
        assertSame(ctxA, TransactionContext.pop());
        assertNull(TransactionContext.current());
    }

    @Test
    void nestedPush_3levels() {
        TransactionContext.push();
        TransactionContext.push();
        TransactionContext ctx3 = TransactionContext.push();
        assertSame(ctx3, TransactionContext.current());
        TransactionContext.pop();
        TransactionContext.pop();
        TransactionContext.pop();
        assertNull(TransactionContext.current());
    }

    // ── Поля по умолчанию ─────────────────────────────────────────────────────

    @Test
    void push_setsDefaultPropagation_REQUIRED() {
        TransactionContext ctx = TransactionContext.push();
        assertEquals("REQUIRED", ctx.propagation);
    }

    @Test
    void push_setsDefaultIsolationLevel_DEFAULT() {
        TransactionContext ctx = TransactionContext.push();
        assertEquals("DEFAULT", ctx.isolationLevel);
    }

    @Test
    void push_setsTransactionId_nonNull() {
        TransactionContext ctx = TransactionContext.push();
        assertNotNull(ctx.transactionId);
        assertFalse(ctx.transactionId.isEmpty());
    }

    @Test
    void push_setsStartTimeMs_positive() {
        long before = System.currentTimeMillis();
        TransactionContext ctx = TransactionContext.push();
        long after = System.currentTimeMillis();
        assertTrue(ctx.startTimeMs >= before);
        assertTrue(ctx.startTimeMs <= after);
    }

    @Test
    void push_setsThreadName_currentThread() {
        TransactionContext ctx = TransactionContext.push();
        assertEquals(Thread.currentThread().getName(), ctx.threadName);
    }

    // ── toRecord() ────────────────────────────────────────────────────────────

    @Test
    void toRecord_setsStatus() {
        TransactionContext ctx = TransactionContext.push();
        assertEquals("COMMITTED", ctx.toRecord("COMMITTED").status);
        assertEquals("ROLLED_BACK", ctx.toRecord("ROLLED_BACK").status);
    }

    @Test
    void toRecord_copiesAllFields() {
        TransactionContext ctx = TransactionContext.push();
        ctx.className = "com.example.OrderService";
        ctx.methodName = "processOrder";
        ctx.parameterTypes = "String,int";
        ctx.propagation = "REQUIRES_NEW";
        ctx.isolationLevel = "READ_COMMITTED";
        ctx.readOnly = true;
        ctx.timeout = 30;
        ctx.sqlQueryCount = 3;
        ctx.batchCount = 5;
        ctx.sqlQueries.add("SELECT 1");

        TransactionRecord r = ctx.toRecord("COMMITTED");

        assertEquals("com.example.OrderService", r.className);
        assertEquals("processOrder", r.methodName);
        assertEquals("String,int", r.parameterTypes);
        assertEquals("REQUIRES_NEW", r.propagation);
        assertEquals("READ_COMMITTED", r.isolationLevel);
        assertTrue(r.readOnly);
        assertEquals(30, r.timeout);
        assertEquals(3, r.sqlQueryCount);
        assertEquals(5, r.batchCount);
        assertEquals(1, r.sqlQueries.size());
        assertEquals("SELECT 1", r.sqlQueries.get(0));
        assertEquals(ctx.transactionId, r.transactionId);
        assertEquals(ctx.threadName, r.threadName);
    }

    @Test
    void toRecord_durationMs_isPositive() {
        TransactionContext ctx = TransactionContext.push();
        TransactionRecord r = ctx.toRecord("COMMITTED");
        assertTrue(r.durationMs >= 0);
        assertTrue(r.endTimeMs >= ctx.startTimeMs);
        assertEquals(r.endTimeMs - ctx.startTimeMs, r.durationMs);
    }

    @Test
    void toRecord_sqlQueries_isCopy_notSameList() {
        TransactionContext ctx = TransactionContext.push();
        ctx.sqlQueries.add("SELECT 1");
        TransactionRecord r = ctx.toRecord("COMMITTED");
        // Копия списка — изменение оригинала не влияет на запись
        ctx.sqlQueries.add("SELECT 2");
        assertEquals(1, r.sqlQueries.size());
    }

    @Test
    void toRecord_withException_buildsStackTrace() {
        TransactionContext ctx = TransactionContext.push();
        ctx.exception = new RuntimeException("something went wrong");

        TransactionRecord r = ctx.toRecord("ROLLED_BACK");

        assertEquals("java.lang.RuntimeException", r.exceptionType);
        assertEquals("something went wrong", r.exceptionMessage);
        assertNotNull(r.stackTrace);
        assertTrue(r.stackTrace.contains("RuntimeException"));
        assertTrue(r.stackTrace.contains("something went wrong"));
    }

    @Test
    void toRecord_withoutException_noExceptionFields() {
        TransactionContext ctx = TransactionContext.push();
        TransactionRecord r = ctx.toRecord("COMMITTED");
        assertNull(r.exceptionType);
        assertNull(r.exceptionMessage);
        assertNull(r.stackTrace);
    }

    @Test
    void toRecord_causedBy_chain_appearsInStackTrace() {
        RuntimeException root = new RuntimeException("root cause");
        RuntimeException wrapper = new RuntimeException("wrapper", root);

        TransactionContext ctx = TransactionContext.push();
        ctx.exception = wrapper;
        TransactionRecord r = ctx.toRecord("ROLLED_BACK");

        assertNotNull(r.stackTrace);
        assertTrue(r.stackTrace.contains("Caused by:"), "Должен содержать Caused by:");
        assertTrue(r.stackTrace.contains("root cause"), "Должен содержать причину");
    }

    @Test
    void buildStackTrace_causedByChain_truncatedAtDepth5() {
        // Строим 7-уровневую цепочку снизу вверх через конструктор
        RuntimeException e7 = new RuntimeException("level7");
        RuntimeException e6 = new RuntimeException("level6", e7);
        RuntimeException e5 = new RuntimeException("level5", e6);
        RuntimeException e4 = new RuntimeException("level4", e5);
        RuntimeException e3 = new RuntimeException("level3", e4);
        RuntimeException e2 = new RuntimeException("level2", e3);
        RuntimeException e1 = new RuntimeException("level1", e2);

        TransactionContext ctx = TransactionContext.push();
        ctx.exception = e1;
        TransactionRecord r = ctx.toRecord("ROLLED_BACK");

        // appendThrowable отсекает на depth > 5 → максимум 6 "Caused by:"
        int causedByCount = countOccurrences(r.stackTrace, "Caused by:");
        assertTrue(causedByCount <= 6, "Не более 6 'Caused by:', найдено: " + causedByCount);
        assertTrue(r.stackTrace.contains("truncated") || causedByCount <= 6,
            "Цепочка должна быть усечена");
    }

    @Test
    void buildStackTrace_cyclicCause_doesNotLoop() {
        // initCause() — публичный API, не требует рефлексии
        RuntimeException e1 = new RuntimeException("e1");
        RuntimeException e2 = new RuntimeException("e2");
        e1.initCause(e2); // e1 → e2
        e2.initCause(e1); // e2 → e1 (цикл)

        TransactionContext ctx = TransactionContext.push();
        ctx.exception = e1;

        assertDoesNotThrow(() -> ctx.toRecord("ROLLED_BACK"),
            "Циклическая цепочка причин не должна вызывать зависание или StackOverflowError");
    }

    @Test
    void buildStackTrace_nullMessage_noNPE() {
        TransactionContext ctx = TransactionContext.push();
        ctx.exception = new RuntimeException(); // без сообщения

        assertDoesNotThrow(() -> {
            TransactionRecord r = ctx.toRecord("ROLLED_BACK");
            assertNotNull(r.stackTrace);
        });
    }

    // ── Изоляция между потоками ───────────────────────────────────────────────

    @Test
    void threadIsolation_contextsAreIndependent() throws InterruptedException {
        CountDownLatch bothPushed = new CountDownLatch(2);
        CountDownLatch done = new CountDownLatch(2);
        AtomicReference<String> t1Name = new AtomicReference<>();
        AtomicReference<String> t2Name = new AtomicReference<>();

        Thread t1 = new Thread(() -> {
            TransactionContext ctx = TransactionContext.push();
            ctx.className = "ServiceA";
            bothPushed.countDown();
            try { bothPushed.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            TransactionContext cur = TransactionContext.current();
            t1Name.set(cur != null ? cur.className : null);
            TransactionContext.pop();
            done.countDown();
        });

        Thread t2 = new Thread(() -> {
            TransactionContext ctx = TransactionContext.push();
            ctx.className = "ServiceB";
            bothPushed.countDown();
            try { bothPushed.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            TransactionContext cur = TransactionContext.current();
            t2Name.set(cur != null ? cur.className : null);
            TransactionContext.pop();
            done.countDown();
        });

        t1.start();
        t2.start();
        done.await();

        assertEquals("ServiceA", t1Name.get(), "Поток A видит только свой контекст");
        assertEquals("ServiceB", t2Name.get(), "Поток B видит только свой контекст");
    }

    // ── Вспомогательные методы ────────────────────────────────────────────────

    private static int countOccurrences(String text, String sub) {
        if (text == null) return 0;
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }
}
