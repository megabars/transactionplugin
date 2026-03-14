package com.txplugin.agent;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatchers;

/**
 * Byte Buddy advice classes.
 *
 * Key design decisions:
 * 1. Transaction lifecycle is tracked via invokeWithinTransaction enter/exit
 *    (no need to hook into doCommit/doRollback which are abstract methods).
 * 2. SQL is tracked via PreparedStatement/Statement interception.
 * 3. Hibernate entity counts via SessionFactory advice.
 */
public class TransactionInstrumentation {

    // =========================================================================
    // 1. TransactionAspectSupport.invokeWithinTransaction
    //    Signature: invokeWithinTransaction(Method method, Class<?> targetClass,
    //                                        InvocationCallback invocation)
    //    arg0 = Method, arg1 = Class<?>, arg2 = InvocationCallback
    // =========================================================================

    public static class InvokeWithinTransactionEnterAdvice {

        @Advice.OnMethodEnter
        public static void onEnter(
                @Advice.Argument(value = 0, typing = Assigner.Typing.DYNAMIC) Object method,
                @Advice.Argument(value = 1, typing = Assigner.Typing.DYNAMIC) Object targetClass) {
            try {
                TransactionContext ctx = TransactionContext.push();

                if (method instanceof java.lang.reflect.Method) {
                    java.lang.reflect.Method m = (java.lang.reflect.Method) method;
                    ctx.methodName = m.getName();

                    if (targetClass instanceof Class) {
                        ctx.className = ((Class<?>) targetClass).getName();
                    } else {
                        ctx.className = m.getDeclaringClass().getName();
                    }

                    // Read @Transactional metadata
                    try {
                        org.springframework.transaction.annotation.Transactional tx =
                                m.getAnnotation(org.springframework.transaction.annotation.Transactional.class);
                        if (tx == null && targetClass instanceof Class) {
                            tx = ((Class<?>) targetClass).getAnnotation(
                                    org.springframework.transaction.annotation.Transactional.class);
                        }
                        if (tx != null) {
                            ctx.propagation    = tx.propagation().name();
                            ctx.isolationLevel = tx.isolation().name();
                            ctx.readOnly       = tx.readOnly();
                            ctx.timeout        = tx.timeout();
                        }
                    } catch (Throwable ignored) { }
                }

                // Snapshot Hibernate counters at transaction start
                ctx.insertCountBefore = HibernateStatsCollector.getInsertCount();
                ctx.updateCountBefore = HibernateStatsCollector.getUpdateCount();
                ctx.deleteCountBefore = HibernateStatsCollector.getDeleteCount();

            } catch (Throwable ignored) { }
        }
    }

    public static class InvokeWithinTransactionExitAdvice {

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void onExit(@Advice.Thrown Throwable thrown) {
            try {
                TransactionContext ctx = TransactionContext.pop();
                if (ctx == null) return;

                if (thrown != null) ctx.exception = thrown;

                String status = (thrown == null) ? "COMMITTED" : "ROLLED_BACK";

                TransactionRecord record = ctx.toRecord(
                        status,
                        HibernateStatsCollector.getInsertCount(),
                        HibernateStatsCollector.getUpdateCount(),
                        HibernateStatsCollector.getDeleteCount()
                );
                SocketReporter.send(record);
            } catch (Throwable ignored) { }
        }
    }

    // =========================================================================
    // 2. Connection.prepareStatement — capture SQL for PreparedStatement
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
    // 3. PreparedStatement.execute / executeUpdate / executeQuery (no-arg)
    // =========================================================================

    public static class PreparedExecuteAdvice {

        @Advice.OnMethodEnter
        public static void onEnter() {
            try {
                SqlInterceptor.onPreparedExecute(SqlInterceptor.getPreparedSql());
            } catch (Throwable ignored) { }
        }
    }

    // =========================================================================
    // 4. Statement.execute(String) / executeUpdate(String) / executeQuery(String)
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
    // 5. executeBatch
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
    // 6. SessionFactory creation — register Hibernate stats
    // =========================================================================

    public static class SessionFactoryAdvice {

        @Advice.OnMethodExit
        public static void onExit(@Advice.Return(typing = Assigner.Typing.DYNAMIC) Object result) {
            try {
                if (result != null) HibernateStatsCollector.setSessionFactory(result);
            } catch (Throwable ignored) { }
        }
    }

    // =========================================================================
    // Transformer — wires advice to target classes/methods
    // =========================================================================

    public static net.bytebuddy.agent.builder.AgentBuilder.Transformer buildTransformer() {
        return (builder, typeDescription, classLoader, module, domain) ->
                applyAdvice(builder, typeDescription);
    }

    private static DynamicType.Builder<?> applyAdvice(
            DynamicType.Builder<?> builder,
            TypeDescription type) {

        String name = type.getName();

        // Spring TX aspect — invokeWithinTransaction uses two separate advice classes
        // because Byte Buddy requires split enter/exit when both are present
        if (name.equals("org.springframework.transaction.interceptor.TransactionAspectSupport")) {
            builder = builder
                    .visit(Advice.to(InvokeWithinTransactionEnterAdvice.class)
                            .on(ElementMatchers.named("invokeWithinTransaction")))
                    .visit(Advice.to(InvokeWithinTransactionExitAdvice.class)
                            .on(ElementMatchers.named("invokeWithinTransaction")));
        }

        // Hibernate SessionFactory (detect SessionFactoryImpl construction)
        if (name.endsWith("SessionFactoryImpl") || name.endsWith("JdbcServicesImpl")) {
            builder = builder.visit(Advice.to(SessionFactoryAdvice.class)
                    .on(ElementMatchers.isConstructor()));
        }

        // JDBC — Connection implementations (for prepareStatement SQL capture)
        if (!name.startsWith("java.") && !name.startsWith("javax.")
                && name.contains("Connection")
                && !name.contains("ConnectionPool")
                && !name.contains("ConnectionManager")
                && !name.contains("AbstractConnection")) {
            builder = builder.visit(Advice.to(PrepareStatementAdvice.class)
                    .on(ElementMatchers.named("prepareStatement")
                            .and(ElementMatchers.takesArgument(0, String.class))));
        }

        // JDBC — PreparedStatement implementations
        if (!name.startsWith("java.") && !name.startsWith("javax.")
                && name.contains("PreparedStatement")) {
            builder = builder
                    .visit(Advice.to(PreparedExecuteAdvice.class)
                            .on(ElementMatchers.namedOneOf("execute", "executeUpdate", "executeQuery")
                                    .and(ElementMatchers.takesNoArguments())))
                    .visit(Advice.to(BatchExecuteAdvice.class)
                            .on(ElementMatchers.named("executeBatch")));
        }

        // JDBC — plain Statement implementations
        if (!name.startsWith("java.") && !name.startsWith("javax.")
                && name.contains("Statement")
                && !name.contains("PreparedStatement")
                && !name.contains("CallableStatement")) {
            builder = builder.visit(Advice.to(StatementExecuteAdvice.class)
                    .on(ElementMatchers.namedOneOf("execute", "executeUpdate", "executeQuery")
                            .and(ElementMatchers.takesArgument(0, String.class))));
        }

        return builder;
    }
}
