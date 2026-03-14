package com.txplugin.plugin.run

import com.intellij.execution.JavaTestConfigurationBase
import com.intellij.execution.configurations.JavaParameters
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.diagnostic.thisLogger

/**
 * Hooks into every Run Configuration. When a process starts, we wait for its
 * PID (available after the process is up) and attach the Java Agent.
 *
 * We detect "Spring Boot applications" heuristically: any Java/Kotlin run
 * config that has spring-core on the classpath. If we accidentally attach to a
 * non-Spring process the agent's Byte Buddy matchers simply find nothing to
 * instrument and the overhead is negligible.
 */
class SpringBootRunConfigurationExtension :
    com.intellij.execution.RunConfigurationExtension() {

    private val log = thisLogger()

    // Required abstract method — no JVM args modification needed (agent attached post-start)
    override fun <T : RunConfigurationBase<*>> updateJavaParameters(
        configuration: T, params: JavaParameters, runnerSettings: RunnerSettings?
    ) {}

    override fun isApplicableFor(configuration: RunConfigurationBase<*>): Boolean {
        // Apply to all Java-based configurations
        val configClass = configuration.javaClass.name
        return configClass.contains("Application") ||
               configClass.contains("JavaRunConfiguration") ||
               configClass.contains("SpringBoot") ||
               isJavaConfiguration(configuration)
    }

    private fun isJavaConfiguration(config: RunConfigurationBase<*>): Boolean {
        return try {
            config.javaClass.interfaces.any { iface ->
                iface.name.contains("CommonJavaRunConfigurationParameters") ||
                iface.name.contains("JavaRunConfiguration")
            }
        } catch (_: Exception) { false }
    }

    override fun attachToProcess(
        configuration: RunConfigurationBase<*>,
        handler: ProcessHandler,
        runnerSettings: RunnerSettings?
    ) {
        handler.addProcessListener(object : ProcessListener {
            override fun startNotified(event: ProcessEvent) {
                scheduleAttach(configuration, handler)
            }
            override fun processTerminated(event: ProcessEvent) {}
            override fun onTextAvailable(event: ProcessEvent, outputType: com.intellij.openapi.util.Key<*>) {}
        })
    }

    private fun scheduleAttach(configuration: RunConfigurationBase<*>, handler: ProcessHandler) {
        val project = configuration.project

        Thread({
            // Wait up to 30s for the process to be available and obtain its PID
            var attempts = 0
            while (attempts < 60) {
                val pid = tryGetPid(handler)
                if (pid != null) {
                    val attachService = project.getService(AgentAttachService::class.java)
                    attachService.attachToProcess(pid)
                    return@Thread
                }
                Thread.sleep(500)
                attempts++
            }
            log.warn("TransactionPlugin: could not obtain PID for ${configuration.name}")
        }, "tx-attach-${configuration.name}").also { it.isDaemon = true }.start()
    }

    private fun tryGetPid(handler: ProcessHandler): Long? {
        return try {
            // ProcessHandler.getProcess() returns java.lang.Process (JDK 9+)
            val processField = handler.javaClass.declaredFields
                .firstOrNull { it.type == Process::class.java || it.name == "myProcess" }
                ?: return null
            processField.isAccessible = true
            val process = processField.get(handler) as? Process ?: return null
            process.pid()
        } catch (_: Exception) { null }
    }
}
