package com.txplugin.plugin.ui

import com.intellij.codeInsight.hints.*
import com.intellij.codeInsight.hints.presentation.InlayPresentation
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.*
import com.txplugin.plugin.store.TransactionStore

/**
 * Inlay hint provider for Java files.
 * Shows transaction stats above methods annotated with @Transactional.
 *
 * Example hint:
 *   ✓ 342ms | SQL:5 batch:2 | ↑3 ✎2 ↓1
 *   ✗ 89ms | NullPointerException
 */
@Suppress("UnstableApiUsage")
class JavaTransactionInlayHintsProvider : InlayHintsProvider<NoSettings> {

    override val key: SettingsKey<NoSettings> = SettingsKey("txplugin.java.hints")
    override val name: String = "Transaction Monitor"
    override val previewText: String = """
        @Transactional
        public void processOrder(Order order) { ... }
    """.trimIndent()

    override fun createSettings() = NoSettings()
    override fun getCollectorFor(
        file: PsiFile, editor: Editor, settings: NoSettings, sink: InlayHintsSink
    ): InlayHintsCollector = JavaTxHintsCollector(editor)

    override fun createConfigurable(settings: NoSettings) =
        object : ImmediateConfigurable { override fun createComponent(listener: ChangeListener) = javax.swing.JPanel() }
}

@Suppress("UnstableApiUsage")
private class JavaTxHintsCollector(private val editor: Editor) :
    FactoryInlayHintsCollector(editor) {

    private val store = TransactionStore.getInstance()

    override fun collect(element: PsiElement, editor: Editor, sink: InlayHintsSink): Boolean {
        if (element !is PsiMethod) return true

        val isTransactional = element.hasAnnotation("org.springframework.transaction.annotation.Transactional") ||
                              element.containingClass?.hasAnnotation("org.springframework.transaction.annotation.Transactional") == true
        if (!isTransactional) return true

        val className = element.containingClass?.qualifiedName ?: return true
        val methodName = element.name
        val methodKey = "$className#$methodName"
        val record = store.getLatestForMethod(methodKey) ?: return true

        val presentation = buildPresentation(record)
        val offset = element.textRange.startOffset
        sink.addBlockElement(
            offset = offset,
            relatesToPrecedingText = false,
            showAbove = true,
            priority = 0,
            presentation = presentation
        )
        return true
    }

    private fun buildPresentation(record: com.txplugin.plugin.model.TransactionRecord): InlayPresentation {
        // ✓/✗ prefix already conveys commit/rollback status visually
        val textPresentation = factory.smallText(record.inlayHintText)

        // Clicking the hint focuses the Tool Window
        return factory.referenceOnHover(textPresentation) { _, _ ->
            val project = editor.project ?: return@referenceOnHover
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Transaction Monitor")
            toolWindow?.show()
        }
    }
}
