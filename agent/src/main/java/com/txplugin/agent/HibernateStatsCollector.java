package com.txplugin.agent;

import java.lang.reflect.Method;
import java.util.logging.Logger;

/**
 * Reads Hibernate Statistics counters (insert/update/delete entity counts)
 * via reflection to avoid a compile-time dependency on Hibernate.
 *
 * Requires {@code hibernate.generate_statistics=true} in the app config.
 * The agent enables this automatically by intercepting SessionFactory creation.
 */
public class HibernateStatsCollector {

    private static final Logger LOG = Logger.getLogger(HibernateStatsCollector.class.getName());

    /** Cached reflection references — resolved lazily on first use */
    private static volatile Object sessionFactory;
    private static volatile Method getStatistics;
    private static volatile Method getEntityInsertCount;
    private static volatile Method getEntityUpdateCount;
    private static volatile Method getEntityDeleteCount;
    private static volatile boolean unavailable = false;

    /** Called by TransactionInstrumentation after SessionFactory is created */
    public static void setSessionFactory(Object sf) {
        if (sf == null || unavailable) return;
        try {
            Class<?> sfClass = sf.getClass();
            Method stats = findMethod(sfClass, "getStatistics");
            if (stats == null) { unavailable = true; return; }
            Object statsObj = stats.invoke(sf);
            if (statsObj == null) { unavailable = true; return; }

            // Try to enable statistics if not already enabled
            tryEnableStatistics(statsObj);

            Class<?> statsClass = statsObj.getClass();
            sessionFactory = sf;
            getStatistics = stats;
            getEntityInsertCount = findMethod(statsClass, "getEntityInsertCount");
            getEntityUpdateCount = findMethod(statsClass, "getEntityUpdateCount");
            getEntityDeleteCount = findMethod(statsClass, "getEntityDeleteCount");
        } catch (Exception e) {
            LOG.fine("Hibernate statistics not available: " + e.getMessage());
            unavailable = true;
        }
    }

    private static void tryEnableStatistics(Object statsObj) {
        try {
            Method setEnabled = findMethod(statsObj.getClass(), "setStatisticsEnabled", boolean.class);
            if (setEnabled != null) setEnabled.invoke(statsObj, true);
        } catch (Exception ignored) { }
    }

    public static long getInsertCount() { return readLong(getEntityInsertCount); }
    public static long getUpdateCount() { return readLong(getEntityUpdateCount); }
    public static long getDeleteCount() { return readLong(getEntityDeleteCount); }

    private static long readLong(Method method) {
        if (unavailable || sessionFactory == null || method == null) return 0L;
        try {
            Object sf = sessionFactory;
            Object stats = getStatistics.invoke(sf);
            Object val = method.invoke(stats);
            return val instanceof Number ? ((Number) val).longValue() : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    private static Method findMethod(Class<?> clazz, String name, Class<?>... params) {
        try {
            Method m = clazz.getMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException e) {
            // Try interfaces
            for (Class<?> iface : clazz.getInterfaces()) {
                Method m = findMethod(iface, name, params);
                if (m != null) return m;
            }
            return null;
        }
    }
}
