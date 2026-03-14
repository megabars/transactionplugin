package com.txplugin.plugin.run

import com.intellij.execution.configurations.JavaParameters
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.openapi.diagnostic.thisLogger
import com.txplugin.plugin.store.TransactionStore
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Adds the transaction agent as a -javaagent JVM argument before the process starts.
 * This is the standard, reliable instrumentation approach (same as JaCoCo, JRebel).
 *
 * updateJavaParameters() is called by IntelliJ for every run configuration that
 * implements CommonJavaRunConfigurationParameters (Spring Boot, plain Java, Kotlin, etc.).
 */
class SpringBootRunConfigurationExtension : com.intellij.execution.RunConfigurationExtension() {

    private val log = thisLogger()

    @Volatile private var agentJarPath: String? = null

    override fun isApplicableFor(configuration: RunConfigurationBase<*>): Boolean = true

    override fun <T : RunConfigurationBase<*>> updateJavaParameters(
        configuration: T,
        params: JavaParameters,
        runnerSettings: RunnerSettings?
    ) {
        val agentJar = getOrExtractAgentJar() ?: run {
            log.warn("TransactionPlugin: agent JAR not found in plugin resources — hints will not work")
            return
        }
        val port = TransactionStore.getInstance().port
        val agentArg = "-javaagent:$agentJar=port=$port"

        // Avoid adding twice (e.g. if configuration is reused)
        if (params.vmParametersList.parameters.none { it.startsWith("-javaagent:$agentJar") }) {
            params.vmParametersList.add(agentArg)
            log.info("TransactionPlugin: added $agentArg")
        }
    }

    private fun getOrExtractAgentJar(): String? {
        agentJarPath?.let { if (File(it).exists()) return it }

        val resource = javaClass.getResourceAsStream("/agent/transaction-agent.jar") ?: run {
            log.warn("TransactionPlugin: /agent/transaction-agent.jar not found in classpath")
            return null
        }
        return try {
            val tmp = Files.createTempFile("transaction-agent-", ".jar").toFile()
            tmp.deleteOnExit()
            resource.use { Files.copy(it, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            agentJarPath = tmp.absolutePath
            log.info("TransactionPlugin: agent extracted to ${tmp.absolutePath}")
            tmp.absolutePath
        } catch (e: Exception) {
            log.warn("TransactionPlugin: failed to extract agent JAR: ${e.message}")
            null
        }
    }
}
