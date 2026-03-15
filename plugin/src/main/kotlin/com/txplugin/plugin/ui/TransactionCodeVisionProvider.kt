package com.txplugin.plugin.ui

import com.intellij.codeInsight.codeVision.*
import com.intellij.codeInsight.codeVision.ui.model.ClickableTextCodeVisionEntry
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.txplugin.plugin.model.TransactionRecord
import com.txplugin.plugin.store.TransactionStore
import java.awt.event.MouseEvent
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Shows the last transaction result as a code vision lens above each
 * @Transactional method. Uses the modern CodeVisionProvider API (same as
 * method-timer plugin).
 *
 * Refresh is triggered via CodeVisionHost.invalidateProvider() from
 * TransactionStore whenever new transaction data arrives over the socket.
 */
class TransactionCodeVisionProvider : CodeVisionProvider<Unit> {

    companion object {
        const val ID = "com.txplugin.transactions"
        private val DATE_FORMAT = SimpleDateFormat("HH:mm:ss")
        private const val TRANSACTIONAL_FQN =
            "org.springframework.transaction.annotation.Transactional"
    }

    override val id: String = ID
    override val name: String = "Transaction Monitor"
    override val defaultAnchor: CodeVisionAnchorKind = CodeVisionAnchorKind.Top
    override val relativeOrderings: List<CodeVisionRelativeOrdering> =
        listOf(CodeVisionRelativeOrdering.CodeVisionRelativeOrderingAfter("java.inheritors"))
    override val groupId: String = id

    override fun precomputeOnUiThread(editor: Editor) {}

    override fun computeCodeVision(editor: Editor, uiData: Unit): CodeVisionState {
        val project = editor.project ?: return CodeVisionState.READY_EMPTY
        val store = TransactionStore.getInstance()

        val lenses = ReadAction.compute<List<Pair<TextRange, CodeVisionEntry>>, Throwable> {
            val psiFile = PsiDocumentManager.getInstance(project)
                .getPsiFile(editor.document) ?: return@compute emptyList()

            val result = mutableListOf<Pair<TextRange, CodeVisionEntry>>()
            PsiTreeUtil.findChildrenOfType(psiFile, PsiMethod::class.java).forEach { method ->
                if (!isTransactional(method)) return@forEach

                val className = method.containingClass?.qualifiedName ?: return@forEach
                val record = store.getLatestForMethod("$className#${method.name}") ?: return@forEach

                val textRange = method.nameIdentifier?.textRange ?: return@forEach
                val label = record.inlayHintText

                val entry = ClickableTextCodeVisionEntry(
                    label, id,
                    { event, sourceEditor -> handleClick(event, sourceEditor, record) },
                    null, label, ""
                )
                result.add(textRange to entry)
            }
            result
        }

        return CodeVisionState.Ready(lenses)
    }

    private fun isTransactional(method: PsiMethod): Boolean =
        method.hasAnnotation(TRANSACTIONAL_FQN) ||
        method.containingClass?.hasAnnotation(TRANSACTIONAL_FQN) == true

    private fun handleClick(event: MouseEvent?, editor: Editor, record: TransactionRecord) {
        val time = DATE_FORMAT.format(Date(record.startTimeMs))
        val message = buildString {
            appendLine("Method:     ${record.className}.${record.methodName}()")
            appendLine("Status:     ${record.status}")
            appendLine("Duration:   ${record.durationMs} ms")
            appendLine("Time:       $time")
            appendLine("Propagation: ${record.propagation}")
            appendLine("Isolation:   ${record.isolationLevel}")
            appendLine("ReadOnly:    ${record.readOnly}")
            if (record.sqlQueryCount > 0) {
                appendLine("SQL:        ${record.sqlQueryCount} queries, ${record.batchCount} batches")
            }
            val entityOps = buildString {
                if (record.insertCount > 0) append("↑${record.insertCount} ")
                if (record.updateCount > 0) append("✎${record.updateCount} ")
                if (record.deleteCount > 0) append("↓${record.deleteCount}")
            }.trim()
            if (entityOps.isNotEmpty()) appendLine("Entities:   $entityOps")
            if (record.exceptionType != null) {
                appendLine("Exception:  ${record.exceptionType}")
                if (record.exceptionMessage != null) appendLine("Message:    ${record.exceptionMessage}")
            }
        }.trimEnd()

        // Show detail popup AND bring up the Tool Window
        val popup = JBPopupFactory.getInstance().createMessage(message)
        if (event != null) {
            popup.showInScreenCoordinates(editor.contentComponent, event.locationOnScreen)
        } else {
            popup.showInBestPositionFor(editor)
        }

        editor.project?.let { project ->
            ToolWindowManager.getInstance(project).getToolWindow("Transaction Monitor")?.show()
        }
    }
}
