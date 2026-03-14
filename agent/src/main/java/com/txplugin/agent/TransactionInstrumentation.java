package com.txplugin.agent;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.utility.JavaModule;

import java.security.ProtectionDomain;

/**
 * Byte Buddy advice classes for intercepting Spring TX and JDBC.
 *
 * Each inner class is a stateless advice — Byte Buddy inlines the
 * {@code @Advice.OnMethodEnter} / {@code @Advice.OnMethodExit} code directly
 * into the target method's bytecode.
 */
public class TransactionInstrumentation {

    // =========================================================================
    // 1. TransactionAspectSupport.invokeWithinTransaction — captures method info
    // =========================================================================

    public static class InvokeWithinTransactionAdvice {

        @Advice.OnMethodEnter
        public static void onEnter(
                @Advice.Argument(value = 1, typing = Assigner.Typing.DYNAMIC) Object method,
                @Advice.Argument(value = 2, typing = Assigner.Typing.DYNAMIC) Object targetClass) {

            try {
                if (method instanceof java.lang.reflect.Method) {
                    java.lang.reflect.Method m = (java.lang.reflect.Method) method;
                    TransactionContext ctx = new TransactionContext();
                    if (targetClass instanceof Class) {
                        ctx.className = ((Class<?>) targetClass).getName();
                    } else {
                        ctx.className = m.getDeclaringClass().getName();
                    }
                    ctx.methodName = m.getName();

                    // Attempt to read @Transactional annotation metadata
                    org.springframework.transaction.annotation.Transactional tx =
                            m.getAnnotation(org.springframework.transaction.annotation.Transactional.class);
                    if (tx == null) {
                        Class<?> declaring = m.getDeclaringClass();
                        tx = declaring.getAnnotation(org.springframework.transaction.annotation.Transactional.class);
                    }
                    if (tx != null) {
                        ctx.propagation = tx.propagation().name();
                        ctx.isolationLevel = tx.isolation().name();
                        ctx.readOnly = tx.readOnly();
                        ctx.timeout = tx.timeout();
                    }

                    // Snapshot Hibernate counters before the transaction body runs
                    ctx.insertCountBefore = HibernateStatsCollector.getInsertCount();
                    ctx.updateCountBefore = HibernateStatsCollector.getUpdateCount();
                    ctx.deleteCountBefore = HibernateStatsCollector.getDeleteCount();

                    TransactionContext.set(ctx);
                }
            } catch (Throwable ignored) { }
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void onExit(@Advice.Thrown Throwable thrown) {
            try {
                TransactionContext ctx = TransactionContext.current();
                if (ctx != null && thrown != null) {
                    ctx.exception = thrown;
                }
                // Actual record creation happens in doCommit / doRollback advice
            } catch (Throwable ignored) { }
        }
    }

    // =========================================================================
    // 2. AbstractPlatformTransactionManager.doCommit
    // =========================================================================

    public static class DoCommitAdvice {

        @Advice.OnMethodExit
        public static void onExit() {
            try {
                TransactionContext ctx = TransactionContext.current();
                if (ctx != null) {
                    TransactionRecord record = ctx.toRecord(
                            "COMMITTED",
                            HibernateStatsCollector.getInsertCount(),
                            HibernateStatsCollector.getUpdateCount(),
                            HibernateStatsCollector.getDeleteCount()
                    );
                    SocketReporter.send(record);
                    TransactionContext.clear();
                }
            } catch (Throwable ignored) { }
        }
    }

    // =========================================================================
    // 3. AbstractPlatformTransactionManager.doRollback
    // =========================================================================

    public static class DoRollbackAdvice {

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void onExit() {
            try {
                TransactionContext ctx = TransactionContext.current();
                if (ctx != null) {
                    TransactionRecord record = ctx.toRecord(
                            "ROLLED_BACK",
                            HibernateStatsCollector.getInsertCount(),
                            HibernateStatsCollector.getUpdateCount(),
                            HibernateStatsCollector.getDeleteCount()
                    );
                    SocketReporter.send(record);
                    TransactionContext.clear();
                }
            } catch (Throwable ignored) { }
        }
    }

    // =========================================================================
    // 4. Connection.prepareStatement — capture SQL for PreparedStatement
    // =========================================================================

    public static class PrepareStatementAdvice {

        @Advice.OnMethodEnter
        public static void onEnter(@Advice.Argument(0) String sql) {
            try {
                SqlInterceptor.onPrepareStatement(sql);
            } catch (Throwable ignored) { }
        }
    }

    // =========================================================================
    // 5. PreparedStatement.execute / executeUpdate / executeQuery
    // =========================================================================

    public static class PreparedExecuteAdvice {

        @Advice.OnMethodEnter
        public static void onEnter() {
            try {
                String sql = SqlInterceptor.getPreparedSql();
                SqlInterceptor.onPreparedExecute(sql);
            } catch (Throwable ignored) { }
        }
    }

    // =========================================================================
    // 6. Statement.execute(String) / executeUpdate(String) / executeQuery(String)
    // =========================================================================

    public static class StatementExecuteAdvice {

        @Advice.OnMethodEnter
        public static void onEnter(@Advice.Argument(0) String sql) {
            try {
                SqlInterceptor.onStatementExecute(sql);
            } catch (Throwable ignored) { }
        }
    }

    // =========================================================================
    // 7. Statement.executeBatch
    // =========================================================================

    public static class BatchExecuteAdvice {

        @Advice.OnMethodEnter
        public static void onEnter() {
            try {
                SqlInterceptor.onBatchExecute();
            } catch (Throwable ignored) { }
        }
    }

    // =========================================================================
    // 8. SessionFactory creation — register Hibernate stats collector
    // =========================================================================

    public static class SessionFactoryAdvice {

        @Advice.OnMethodExit
        public static void onExit(@Advice.Return(typing = Assigner.Typing.DYNAMIC) Object result) {
            try {
                if (result != null) {
                    HibernateStatsCollector.setSessionFactory(result);
                }
            } catch (Throwable ignored) { }
        }
    }

    // =========================================================================
    // Byte Buddy transformer — wires advice to target classes
    // =========================================================================

    public static net.bytebuddy.agent.builder.AgentBuilder.Transformer buildTransformer() {
        return (builder, typeDescription, classLoader, module, domain) ->
                applyAdvice(builder, typeDescription);
    }

    @SuppressWarnings("deprecation")
    private static DynamicType.Builder<?> applyAdvice(
            DynamicType.Builder<?> builder,
            TypeDescription type) {

        String name = type.getName();

        if (name.equals("org.springframework.transaction.interceptor.TransactionAspectSupport")) {
            builder = builder.visit(Advice.to(InvokeWithinTransactionAdvice.class)
                    .on(ElementMatchers.named("invokeWithinTransaction")));
        }

        if (name.equals("org.springframework.transaction.support.AbstractPlatformTransactionManager")) {
            builder = builder
                    .visit(Advice.to(DoCommitAdvice.class).on(ElementMatchers.named("doCommit")))
                    .visit(Advice.to(DoRollbackAdvice.class).on(ElementMatchers.named("doRollback")));
        }

        if (name.equals("org.hibernate.engine.jdbc.internal.JdbcServicesImpl")
                || name.endsWith("SessionFactoryImpl")) {
            builder = builder.visit(Advice.to(SessionFactoryAdvice.class)
                    .on(ElementMatchers.isConstructor()));
        }

        // JDBC interception — target java.sql.* implementations
        if (implementsJdbcConnection(name)) {
            builder = builder.visit(Advice.to(PrepareStatementAdvice.class)
                    .on(ElementMatchers.named("prepareStatement")
                            .and(ElementMatchers.takesArgument(0, String.class))));
        }

        if (implementsJdbcPreparedStatement(name)) {
            builder = builder
                    .visit(Advice.to(PreparedExecuteAdvice.class)
                            .on(ElementMatchers.namedOneOf("execute", "executeUpdate", "executeQuery")
                                    .and(ElementMatchers.takesNoArguments())))
                    .visit(Advice.to(BatchExecuteAdvice.class)
                            .on(ElementMatchers.named("executeBatch")));
        }

        if (implementsJdbcStatement(name)) {
            builder = builder
                    .visit(Advice.to(StatementExecuteAdvice.class)
                            .on(ElementMatchers.namedOneOf("execute", "executeUpdate", "executeQuery")
                                    .and(ElementMatchers.takesArgument(0, String.class))));
        }

        return builder;
    }

    private static boolean implementsJdbcConnection(String name) {
        return name.contains("Connection") && !name.startsWith("java.");
    }

    private static boolean implementsJdbcPreparedStatement(String name) {
        return name.contains("PreparedStatement") && !name.startsWith("java.");
    }

    private static boolean implementsJdbcStatement(String name) {
        return (name.contains("Statement") && !name.contains("Prepared"))
                && !name.startsWith("java.");
    }
}
