package com.txplugin.plugin.ui

import com.intellij.codeInsight.codeVision.*
import com.intellij.codeInsight.codeVision.ui.model.ClickableTextCodeVisionEntry
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.TextRange
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.txplugin.plugin.model.TransactionRecord
import com.txplugin.plugin.settings.TransactionSettings
import com.txplugin.plugin.store.TransactionStore
import java.awt.Dimension
import java.awt.Font
import java.awt.event.MouseEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.BorderFactory
import javax.swing.UIManager

/**
 * Shows the last transaction result as a code vision lens above each
 * @Transactional method. Uses the modern CodeVisionProvider API.
 *
 * Refresh is triggered via CodeVisionHost.invalidateProvider() from
 * TransactionStore whenever new transaction data arrives over the socket.
 */
class TransactionCodeVisionProvider : CodeVisionProvider<Unit> {

    companion object {
        const val ID = "com.txplugin.transactions"

        // DateTimeFormatter is thread-safe (unlike SimpleDateFormat)
        private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

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
        if (!TransactionSettings.getInstance().showCodeVision) return CodeVisionState.READY_EMPTY
        val project = editor.project ?: return CodeVisionState.READY_EMPTY
        val store = TransactionStore.getInstance()

        val lenses = ReadAction.compute<List<Pair<TextRange, CodeVisionEntry>>, Throwable> {
            val psiFile = PsiDocumentManager.getInstance(project)
                .getPsiFile(editor.document) ?: return@compute emptyList()

            val result = mutableListOf<Pair<TextRange, CodeVisionEntry>>()
            PsiTreeUtil.findChildrenOfType(psiFile, PsiMethod::class.java).forEach { method ->
                if (!isTransactional(method)) return@forEach

                val className = method.containingClass?.qualifiedName ?: return@forEach
                val methodKey = buildMethodKey(className, method)
                val record = store.getLatestForMethod(methodKey) ?: return@forEach

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

    /**
     * Builds the methodKey matching the agent's format: "className#methodName(ParamType1,ParamType2)".
     * Uses raw (erased) simple type names to match what Class.getSimpleName() returns in the agent.
     */
    private fun buildMethodKey(className: String, method: PsiMethod): String {
        val paramTypes = method.parameterList.parameters.joinToString(",") { param ->
            // Strip generics and take simple name to match agent's Class.getSimpleName()
            param.type.canonicalText.substringBefore('<').substringAfterLast('.')
        }
        return "$className#${method.name}($paramTypes)"
    }

    private fun handleClick(event: MouseEvent?, editor: Editor, record: TransactionRecord) {
        val time = TIME_FORMAT.format(Instant.ofEpochMilli(record.startTimeMs))
        val message = buildString {
            appendLine("Method:     ${record.className}.${record.methodName}()")
            appendLine("Status:     ${record.status}")
            appendLine("Duration:   ${record.durationMs} ms")
            appendLine("Time:       $time")
            appendLine("Propagation: ${record.propagation}")
            appendLine("Isolation:   ${record.isolationLevel}")
            appendLine("ReadOnly:    ${record.readOnly}")
            if (record.batchCount > 0) {
                appendLine("Batch rows: ${record.batchCount}")
            }
            if (record.exceptionType != null) {
                appendLine("Exception:  ${record.exceptionType}")
                if (record.exceptionMessage != null) appendLine("Message:    ${record.exceptionMessage}")
            }
        }.trimEnd()

        val textArea = JBTextArea(message).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = Font(Font.MONOSPACED, Font.PLAIN, 12)
            background = UIManager.getColor("Panel.background")
            border = BorderFactory.createEmptyBorder(8, 10, 8, 10)
        }
        val scrollPane = JBScrollPane(textArea).apply {
            preferredSize = Dimension(480, 180)
            border = null
        }
        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(scrollPane, textArea)
            .setResizable(true)
            .setMovable(true)
            .setRequestFocus(true)
            .createPopup()
        if (event != null) {
            popup.showInScreenCoordinates(editor.contentComponent, event.locationOnScreen)
        } else {
            popup.showInBestPositionFor(editor)
        }
    }
}
