package com.txplugin.agent;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.instrument.Instrumentation;
import java.util.logging.Logger;

/**
 * Java Agent entry point.
 *
 * Loaded by the IntelliJ plugin via Attach API:
 *   VirtualMachine.attach(pid).loadAgent(agentJarPath, "port=PORT")
 *
 * Or added to JVM args: -javaagent:/path/to/transaction-agent.jar=port=PORT
 *
 * Agent arguments format: "port=12345"
 */
public class AgentMain {

    private static final Logger LOG = Logger.getLogger(AgentMain.class.getName());

    /** Called when agent is specified via -javaagent flag at JVM startup */
    public static void premain(String agentArgs, Instrumentation instrumentation) {
        install(agentArgs, instrumentation);
    }

    /** Called when agent is dynamically attached to a running JVM */
    public static void agentmain(String agentArgs, Instrumentation instrumentation) {
        install(agentArgs, instrumentation);
    }

    private static void install(String agentArgs, Instrumentation instrumentation) {
        int port = parsePort(agentArgs);
        LOG.info("[TransactionAgent] Installing, plugin port=" + port);

        // Start the reporter (connects to the plugin's TCP server)
        SocketReporter.init(port);

        // Install Byte Buddy transformations
        new AgentBuilder.Default()
                // Don't instrument JDK internals or the agent itself
                .ignore(ElementMatchers.nameStartsWith("java.")
                        .or(ElementMatchers.nameStartsWith("javax."))
                        .or(ElementMatchers.nameStartsWith("sun."))
                        .or(ElementMatchers.nameStartsWith("com.sun."))
                        .or(ElementMatchers.nameStartsWith("net.bytebuddy."))
                        .or(ElementMatchers.nameStartsWith("com.txplugin.agent.")))

                // Target: Spring TX classes + JDBC implementations
                .type(ElementMatchers.nameContains("TransactionAspectSupport")
                        .or(ElementMatchers.nameContains("AbstractPlatformTransactionManager"))
                        .or(ElementMatchers.nameContains("SessionFactoryImpl"))
                        .or(ElementMatchers.nameContains("JdbcServicesImpl"))
                        // JDBC connection/statement implementations from common pools/drivers
                        .or(ElementMatchers.nameContains("PreparedStatement")
                                .and(ElementMatchers.not(ElementMatchers.nameStartsWith("java."))))
                        .or(ElementMatchers.nameContains("Connection")
                                .and(ElementMatchers.not(ElementMatchers.nameStartsWith("java.")))
                                .and(ElementMatchers.not(ElementMatchers.nameStartsWith("javax."))))
                )
                .transform(TransactionInstrumentation.buildTransformer())
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                .installOn(instrumentation);

        LOG.info("[TransactionAgent] Installed successfully");
    }

    private static int parsePort(String args) {
        if (args == null || args.isEmpty()) return 17321; // default port
        for (String part : args.split(",")) {
            part = part.trim();
            if (part.startsWith("port=")) {
                try {
                    return Integer.parseInt(part.substring(5).trim());
                } catch (NumberFormatException ignored) { }
            }
        }
        return 17321;
    }
}
