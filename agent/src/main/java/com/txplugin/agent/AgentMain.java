package com.txplugin.agent;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.matcher.ElementMatchers;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.util.jar.JarFile;
import java.util.logging.Logger;

/**
 * Java Agent entry point.
 *
 * Added to JVM args by SpringBootRunConfigurationExtension:
 *   -javaagent:/path/to/transaction-agent.jar=port=PORT
 */
public class AgentMain {

    private static final Logger LOG = Logger.getLogger(AgentMain.class.getName());

    public static void premain(String agentArgs, Instrumentation instrumentation) {
        install(agentArgs, instrumentation);
    }

    public static void agentmain(String agentArgs, Instrumentation instrumentation) {
        install(agentArgs, instrumentation);
    }

    private static void install(String agentArgs, Instrumentation instrumentation) {
        int port = parsePort(agentArgs);
        LOG.fine("[TransactionAgent] Installing, reporting to plugin on port=" + port);

        // ── Inject agent classes into the system classloader ─────────────────
        // Spring Boot uses LaunchedURLClassLoader whose parent is the system CL.
        // Adding the agent JAR to the system CL makes our helper classes
        // (TransactionContext, SqlInterceptor, etc.) visible via parent delegation,
        // so Byte Buddy's inlined advice bytecode can call them.
        injectIntoSystemClassLoader(instrumentation);

        // ── Start reporter (connects to plugin's TCP server) ─────────────────
        SocketReporter.init(port);

        // ── Install Byte Buddy transformations ───────────────────────────────
        // Default AgentBuilder uses TypeStrategy.DECORATE (intercept at load time)
        // and RedefinitionStrategy.DISABLED — correct for -javaagent startup.
        new AgentBuilder.Default()
                .disableClassFormatChanges()
                // Don't instrument JDK / Byte Buddy / agent itself
                .ignore(ElementMatchers.nameStartsWith("java.")
                        .or(ElementMatchers.nameStartsWith("javax."))
                        .or(ElementMatchers.nameStartsWith("sun."))
                        .or(ElementMatchers.nameStartsWith("com.sun."))
                        .or(ElementMatchers.nameStartsWith("jdk."))
                        .or(ElementMatchers.nameStartsWith("net.bytebuddy."))
                        .or(ElementMatchers.nameStartsWith("com.txplugin.agent.")))

                // Match Spring TX aspect + Hibernate + JDBC implementations
                .type(
                        ElementMatchers.nameContains("TransactionAspectSupport")
                        .or(ElementMatchers.nameEndsWith("SessionFactoryImpl"))
                        .or(ElementMatchers.nameContains("PreparedStatement")
                                .and(ElementMatchers.not(ElementMatchers.nameStartsWith("java.")))
                                .and(ElementMatchers.not(ElementMatchers.nameStartsWith("javax."))))
                        .or(ElementMatchers.nameContains("Connection")
                                .and(ElementMatchers.not(ElementMatchers.nameStartsWith("java.")))
                                .and(ElementMatchers.not(ElementMatchers.nameStartsWith("javax.")))
                                .and(ElementMatchers.not(ElementMatchers.nameContains("Pool")))
                                .and(ElementMatchers.not(ElementMatchers.nameContains("Manager"))))
                        .or(ElementMatchers.nameContains("Statement")
                                .and(ElementMatchers.not(ElementMatchers.nameStartsWith("java.")))
                                .and(ElementMatchers.not(ElementMatchers.nameStartsWith("javax.")))
                                .and(ElementMatchers.not(ElementMatchers.nameContains("Prepared")))
                                .and(ElementMatchers.not(ElementMatchers.nameContains("Callable"))))
                )
                .transform(TransactionInstrumentation.buildTransformer())
                .installOn(instrumentation);

        LOG.fine("[TransactionAgent] Installed successfully");
    }

    /**
     * Adds the agent JAR to the system classloader search path.
     *
     * Spring Boot uses LaunchedURLClassLoader whose parent is the system classloader.
     * Through normal parent delegation, Spring's classes can therefore resolve our
     * helper classes (TransactionContext, SqlInterceptor, SocketReporter, etc.)
     * that are inlined into the instrumented bytecode by Byte Buddy.
     *
     * NOTE: We intentionally do NOT use appendToBootstrapClassLoaderSearch because
     * that would add Byte Buddy (already loaded by the system CL) a second time
     * under the bootstrap CL, causing a LinkageError due to duplicate class
     * definitions across two classloaders.
     */
    private static void injectIntoSystemClassLoader(Instrumentation instrumentation) {
        try {
            File agentJar = new File(
                    AgentMain.class.getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI()
            );
            if (agentJar.exists() && agentJar.getName().endsWith(".jar")) {
                try (JarFile jf = new JarFile(agentJar)) {
                    instrumentation.appendToSystemClassLoaderSearch(jf);
                }
                LOG.fine("[TransactionAgent] Added to system classloader: " + agentJar);
            }
        } catch (Exception e) {
            LOG.warning("[TransactionAgent] System classloader injection failed: " + e.getMessage());
        }
    }

    private static int parsePort(String args) {
        if (args == null || args.isEmpty()) return 17321;
        for (String part : args.split(",")) {
            part = part.trim();
            if (part.startsWith("port=")) {
                try { return Integer.parseInt(part.substring(5).trim()); }
                catch (NumberFormatException ignored) { }
            }
        }
        return 17321;
    }
}
