package com.txplugin.agent;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatchers;

import java.util.logging.Logger;

/**
 * Byte Buddy advice classes.
 *
 * Key design decisions:
 * 1. Transaction lifecycle is tracked via invokeWithinTransaction enter/exit
 *    (no need to hook into doCommit/doRollback which are abstract methods).
 * 2. SQL is tracked via PreparedStatement/Statement interception.
 */
public class TransactionInstrumentation {

    static final Logger LOG = Logger.getLogger(TransactionInstrumentation.class.getName());

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

                    // Build comma-separated simple type names for overload disambiguation
                    Class<?>[] pts = m.getParameterTypes();
                    if (pts.length > 0) {
                        StringBuilder ptSb = new StringBuilder();
                        for (int i = 0; i < pts.length; i++) {
                            if (i > 0) ptSb.append(',');
                            ptSb.append(pts[i].getSimpleName());
                        }
                        ctx.parameterTypes = ptSb.toString();
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
                    } catch (Throwable t) {
                        TransactionInstrumentation.LOG.fine("[TX] failed to read @Transactional metadata: " + t);
                    }
                }

            } catch (Throwable t) {
                TransactionInstrumentation.LOG.fine("[TX] InvokeWithinTransaction enter advice failed: " + t);
            }
        }
    }

    public static class InvokeWithinTransactionExitAdvice {

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void onExit(@Advice.Thrown Throwable thrown) {
            try {
                TransactionContext ctx = TransactionContext.pop();
                if (ctx == null) return;

                if (thrown != null) ctx.exception = thrown;

                // Check if this is a nested method participating in an outer transaction.
                // REQUIRED/SUPPORTS/MANDATORY join an existing TX — no separate DB transaction.
                // REQUIRES_NEW and NESTED always start a genuinely new TX → report separately.
                TransactionContext parent = TransactionContext.current();
                boolean isNewTransaction = "REQUIRES_NEW".equals(ctx.propagation)
                        || "NESTED".equals(ctx.propagation);

                if (parent != null && !isNewTransaction) {
                    // Merge inner SQL into parent. The actual DB work (Hibernate flush, batch)
                    // happens during the outer's commit phase, so the outer record will capture
                    // flush-SQL correctly at its own exit.
                    parent.sqlQueryCount += ctx.sqlQueryCount;
                    parent.batchCount    += ctx.batchCount;
                    for (String sql : ctx.sqlQueries) {
                        if (parent.sqlQueries.size() < TransactionRecord.MAX_SQL_QUERIES) {
                            parent.sqlQueries.add(sql);
                        }
                    }
                    return; // No separate record — this method participates in the outer TX
                }

                String status = (thrown == null) ? "COMMITTED" : "ROLLED_BACK";
                SocketReporter.send(ctx.toRecord(status));
            } catch (Throwable t) {
                TransactionInstrumentation.LOG.fine("[TX] InvokeWithinTransaction exit advice failed: " + t);
            }
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
            } catch (Throwable t) {
                TransactionInstrumentation.LOG.fine("[TX] prepareStatement advice failed: " + t);
            }
        }
    }

    // =========================================================================
    // 3. PreparedStatement.execute / executeUpdate / executeQuery (no-arg)
    // =========================================================================

    public static class PreparedExecuteAdvice {

        @Advice.OnMethodEnter
        public static void onEnter() {
            try {
                SqlInterceptor.onPreparedExecute(
                        SqlInterceptor.getPreparedSql(),
                        SqlInterceptor.getPreparedParams());
            } catch (Throwable t) {
                TransactionInstrumentation.LOG.fine("[TX] PreparedStatement execute advice failed: " + t);
            }
        }
    }

    // =========================================================================
    // 3a. PreparedStatement.setXxx(int parameterIndex, value)
    //     Intercepts all standard JDBC parameter binding calls.
    // =========================================================================

    public static class SetParameterAdvice {

        @Advice.OnMethodEnter
        public static void onEnter(
                @Advice.Argument(0) int index,
                @Advice.Argument(value = 1, typing = Assigner.Typing.DYNAMIC) Object value) {
            try {
                SqlInterceptor.onSetParameter(index, value);
            } catch (Throwable t) {
                // Must not throw from advice
            }
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
            } catch (Throwable t) {
                TransactionInstrumentation.LOG.fine("[TX] Statement execute advice failed: " + t);
            }
        }
    }

    // =========================================================================
    // 5. PreparedStatement.addBatch() — capture SQL+params for each batch row
    // =========================================================================

    public static class AddBatchAdvice {

        @Advice.OnMethodEnter
        public static void onEnter() {
            try {
                SqlInterceptor.onAddBatch();
            } catch (Throwable t) {
                // Must not throw from advice
            }
        }
    }

    // =========================================================================
    // 6. executeBatch — enter/exit pair to deduplicate proxy double-calls
    // =========================================================================

    public static class BatchExecuteEnterAdvice {

        @Advice.OnMethodEnter
        public static void onEnter() {
            try {
                SqlInterceptor.onBatchExecuteEnter();
            } catch (Throwable t) {
                TransactionInstrumentation.LOG.fine("[TX] executeBatch enter advice failed: " + t);
            }
        }
    }

    public static class BatchExecuteExitAdvice {

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void onExit() {
            try {
                SqlInterceptor.onBatchExecuteExit();
            } catch (Throwable t) {
                // Must not throw
            }
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
                    .visit(Advice.to(AddBatchAdvice.class)
                            .on(ElementMatchers.named("addBatch")
                                    .and(ElementMatchers.takesNoArguments())))
                    .visit(Advice.to(BatchExecuteEnterAdvice.class)
                            .on(ElementMatchers.named("executeBatch")))
                    .visit(Advice.to(BatchExecuteExitAdvice.class)
                            .on(ElementMatchers.named("executeBatch")))
                    // Capture parameter bindings: setString/setInt/setLong/setObject/etc.
                    // Selector: name starts with "set", exactly 2 args, first arg is int (parameterIndex).
                    // Excludes setNull(int,int) which carries sqlType, not a user value.
                    .visit(Advice.to(SetParameterAdvice.class)
                            .on(ElementMatchers.nameStartsWith("set")
                                    .and(ElementMatchers.not(ElementMatchers.named("setNull")))
                                    .and(ElementMatchers.takesArguments(2))
                                    .and(ElementMatchers.takesArgument(0, int.class))));
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
