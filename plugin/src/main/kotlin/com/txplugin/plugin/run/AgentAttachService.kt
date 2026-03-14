package com.txplugin.plugin.run

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.txplugin.plugin.store.TransactionStore
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Project-level service that manages Java Agent attachment to running processes.
 * Called by [SpringBootRunConfigurationExtension] when a Spring Boot process starts.
 */
@Service(Service.Level.PROJECT)
class AgentAttachService(private val project: Project) {

    private val log = thisLogger()

    /** PIDs that already have the agent attached */
    private val attachedPids = ConcurrentHashMap.newKeySet<Long>()

    /** Path to the unpacked agent JAR (extracted from plugin resources) */
    @Volatile private var agentJarPath: String? = null

    fun attachToProcess(pid: Long) {
        if (!attachedPids.add(pid)) return // already attached

        val jar = getOrExtractAgentJar() ?: run {
            log.warn("TransactionPlugin: agent JAR not found in plugin resources")
            return
        }
        val port = TransactionStore.getInstance().port

        try {
            attachViaApi(pid, jar, port)
            log.info("TransactionPlugin: agent attached to PID=$pid port=$port")
        } catch (e: Exception) {
            log.warn("TransactionPlugin: failed to attach agent to PID=$pid: ${e.message}")
            attachedPids.remove(pid)
        }
    }

    private fun attachViaApi(pid: Long, agentJar: String, port: Int) {
        // Load tools.jar / attach classes — available in JDK (not in JRE)
        // Use reflection to avoid compile-time dependency on com.sun.tools.attach
        val vmClass = loadVirtualMachineClass()
            ?: throw IllegalStateException("com.sun.tools.attach.VirtualMachine not available")

        val attachMethod = vmClass.getMethod("attach", String::class.java)
        val vm = attachMethod.invoke(null, pid.toString())
        try {
            val loadAgentMethod = vmClass.getMethod("loadAgent", String::class.java, String::class.java)
            loadAgentMethod.invoke(vm, agentJar, "port=$port")
        } finally {
            vmClass.getMethod("detach").invoke(vm)
        }
    }

    private fun loadVirtualMachineClass(): Class<*>? {
        // 1. Try standard attach API (JDK 9+)
        return try {
            Class.forName("com.sun.tools.attach.VirtualMachine")
        } catch (_: ClassNotFoundException) {
            // 2. Try loading from tools.jar (JDK 8)
            val javaHome = System.getProperty("java.home") ?: return null
            val toolsJar = sequenceOf(
                File(javaHome, "lib/tools.jar"),
                File(javaHome, "../lib/tools.jar")
            ).firstOrNull { it.exists() } ?: return null

            val cl = java.net.URLClassLoader(arrayOf(toolsJar.toURI().toURL()))
            try {
                cl.loadClass("com.sun.tools.attach.VirtualMachine")
            } catch (_: ClassNotFoundException) { null }
        }
    }

    private fun getOrExtractAgentJar(): String? {
        agentJarPath?.let { if (File(it).exists()) return it }

        val resource = AgentAttachService::class.java.getResourceAsStream("/agent/transaction-agent.jar")
            ?: return null

        val tmpFile = Files.createTempFile("transaction-agent-", ".jar").toFile()
        tmpFile.deleteOnExit()
        resource.use { input ->
            Files.copy(input, tmpFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        agentJarPath = tmpFile.absolutePath
        return agentJarPath
    }
}
