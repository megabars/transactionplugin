package com.txplugin.plugin.ui

import com.intellij.codeInsight.hints.*
import com.intellij.codeInsight.hints.presentation.InlayPresentation
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.*
import com.txplugin.plugin.store.TransactionStore

/**
 * Inlay hint provider for Kotlin files — mirrors [JavaTransactionInlayHintsProvider]
 * but uses Kotlin PSI (KtNamedFunction).
 */
@Suppress("UnstableApiUsage")
class KotlinTransactionInlayHintsProvider : InlayHintsProvider<NoSettings> {

    override val key: SettingsKey<NoSettings> = SettingsKey("txplugin.kotlin.hints")
    override val name: String = "Transaction Monitor (Kotlin)"
    override val previewText: String = """
        @Transactional
        fun processOrder(order: Order) { ... }
    """.trimIndent()

    override fun createSettings() = NoSettings()
    override fun getCollectorFor(
        file: PsiFile, editor: Editor, settings: NoSettings, sink: InlayHintsSink
    ): InlayHintsCollector = KotlinTxHintsCollector(editor)

    override fun createConfigurable(settings: NoSettings) =
        object : ImmediateConfigurable { override fun createComponent(listener: ChangeListener) = javax.swing.JPanel() }
}

@Suppress("UnstableApiUsage")
private class KotlinTxHintsCollector(private val editor: Editor) :
    FactoryInlayHintsCollector(editor) {

    private val store = TransactionStore.getInstance()

    private val TRANSACTIONAL_FQN = "org.springframework.transaction.annotation.Transactional"

    override fun collect(element: PsiElement, editor: Editor, sink: InlayHintsSink): Boolean {
        // Use PsiMethod — Kotlin functions exposed to Java implement PsiMethod
        if (element !is PsiMethod) return true

        val hasAnnotation = element.hasAnnotation(TRANSACTIONAL_FQN) ||
                            element.containingClass?.hasAnnotation(TRANSACTIONAL_FQN) == true
        if (!hasAnnotation) return true

        val className = element.containingClass?.qualifiedName ?: return true
        val methodKey = "$className#${element.name}"
        val record = store.getLatestForMethod(methodKey) ?: return true

        val textP = factory.smallText(record.inlayHintText)
        val presentation = factory.referenceOnHover(textP) { _, _ ->
            val project = editor.project ?: return@referenceOnHover
            ToolWindowManager.getInstance(project).getToolWindow("Transaction Monitor")?.show()
        }

        sink.addBlockElement(
            offset = element.textRange.startOffset,
            relatesToPrecedingText = false,
            showAbove = true,
            priority = 0,
            presentation = presentation
        )
        return true
    }
}
