package com.txplugin.plugin.run

import com.intellij.execution.Executor
import com.intellij.execution.configurations.JavaParameters
import com.intellij.execution.configurations.ModuleBasedConfiguration
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.runners.JavaProgramPatcher
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.JavaSdkVersion
import com.txplugin.plugin.store.TransactionStore
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class TransactionJavaProgramPatcher : JavaProgramPatcher() {

    private val log = thisLogger()

    override fun patchJavaParameters(executor: Executor, configuration: RunProfile, javaParameters: JavaParameters) {
        if (configuration !is ModuleBasedConfiguration<*, *>) return

        // Agent is compiled for Java 11 — loading it on an older JVM causes a fatal crash.
        // Skip injection silently when the run configuration uses JDK < 11.
        val jdk = javaParameters.jdk
        if (jdk != null) {
            val jdkVersion = JavaSdk.getInstance().getVersion(jdk)
            if (jdkVersion != null && !jdkVersion.isAtLeast(JavaSdkVersion.JDK_11)) {
                log.info("TransactionPlugin: skipping agent for '${configuration.name}' — requires JDK 11+ (detected ${jdkVersion.description})")
                return
            }
        }

        val agentJar = resolveAgentJar() ?: run {
            log.warn("TransactionPlugin: agent JAR not found — transaction hints disabled")
            return
        }

        val port = TransactionStore.getInstance().port
        val agentArg = "-javaagent:${agentJar.absolutePath}=port=$port"

        if (javaParameters.vmParametersList.parameters.none { it.startsWith("-javaagent:${agentJar.absolutePath}") }) {
            javaParameters.vmParametersList.add(agentArg)
            log.info("TransactionPlugin: injected agent for '${configuration.name}', port=$port")
        }
    }

    private fun resolveAgentJar(): File? {
        // 1. Try plugin distribution directory (agent/ subfolder — production install)
        val pluginDir = PluginManagerCore.getPlugin(PluginId.getId("com.txplugin"))?.pluginPath
        if (pluginDir != null) {
            val fromDir = pluginDir.resolve("agent/transaction-agent.jar").toFile()
            if (fromDir.exists()) return fromDir
        }

        // 2. Extract from plugin JAR resources (dev / sandbox mode) — cached across launches.
        // extractionAttempted flag ensures we don't retry on failure (avoids repeated temp file creation).
        if (extractionAttempted) return extractedAgentJar
        return synchronized(Companion) {
            if (!extractionAttempted) {
                extractedAgentJar = extractFromResources()
                extractionAttempted = true
            }
            extractedAgentJar
        }
    }

    companion object {
        /** Cached temp file so we don't re-extract on every Run Configuration launch */
        @Volatile private var extractedAgentJar: File? = null
        /** Set to true after first extraction attempt (success or failure) to avoid repeated temp files */
        @Volatile private var extractionAttempted: Boolean = false

        private fun extractFromResources(): File? {
            val log = thisLogger()
            val resource = TransactionJavaProgramPatcher::class.java
                .getResourceAsStream("/agent/transaction-agent.jar") ?: return null
            return try {
                val tmp = Files.createTempFile("transaction-agent-", ".jar").toFile()
                tmp.deleteOnExit()
                resource.use { Files.copy(it, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING) }
                log.info("TransactionPlugin: agent extracted to ${tmp.absolutePath}")
                tmp
            } catch (e: Exception) {
                log.warn("TransactionPlugin: failed to extract agent JAR: ${e.message}")
                null
            }
        }
    }
}
