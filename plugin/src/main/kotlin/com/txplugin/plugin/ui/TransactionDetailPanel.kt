package com.txplugin.plugin.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.txplugin.plugin.model.TransactionRecord
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Font
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.*

/**
 * Panel displaying all details of a single [TransactionRecord].
 * Embedded in the Tool Window split pane and updated on selection change.
 *
 * Meta labels are pre-created once to avoid flickering on every selection change.
 */
class TransactionDetailPanel(private val project: Project) : JPanel(BorderLayout()) {

    // DateTimeFormatter is thread-safe
    private val dateFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

    // Top section
    private val titleLabel = JBLabel("Select a transaction").also {
        it.font = it.font.deriveFont(Font.BOLD, 13f)
        it.border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
    }

    // Pre-created value labels — updated in-place to avoid removeAll() flickering
    private val metaValues = linkedMapOf(
        "Method"      to JBLabel(),
        "Time"        to JBLabel(),
        "Duration"    to JBLabel(),
        "Thread"      to JBLabel(),
        "Propagation" to JBLabel(),
        "Isolation"   to JBLabel(),
        "ReadOnly"    to JBLabel(),
        "Timeout"     to JBLabel(),
        "Batch rows"  to JBLabel()
    )
    private val metaPanel = buildMetaPanel()

    // SQL list
    private val sqlArea = JBTextArea(8, 60).also {
        it.isEditable = false
        it.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        it.border = BorderFactory.createEmptyBorder(4, 6, 4, 6)
    }

    // Exception
    private val exceptionArea = JBTextArea(5, 60).also {
        it.isEditable = false
        it.font = Font(Font.MONOSPACED, Font.PLAIN, 11)
        it.foreground = JBColor.RED
        it.border = BorderFactory.createEmptyBorder(4, 6, 4, 6)
    }

    // Navigate button
    private val navigateButton = JButton("Navigate to source")

    private var currentRecord: TransactionRecord? = null

    init {
        buildLayout()
    }

    private fun buildMetaPanel(): JPanel {
        val panel = JPanel().also { it.layout = BoxLayout(it, BoxLayout.Y_AXIS) }
        metaValues.forEach { (label, valueLabel) ->
            val row = JPanel(FlowLayout(FlowLayout.LEFT, 4, 1))
            row.add(JBLabel("$label:").also { it.font = it.font.deriveFont(Font.BOLD) })
            row.add(valueLabel)
            panel.add(row)
        }
        return panel
    }

    private fun buildLayout() {
        background = UIManager.getColor("Panel.background")

        val topBar = JPanel(BorderLayout()).also {
            it.add(titleLabel, BorderLayout.WEST)
            val btnPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 2))
            btnPanel.add(navigateButton)
            it.add(btnPanel, BorderLayout.EAST)
        }

        val center = JPanel().also { p ->
            p.layout = BoxLayout(p, BoxLayout.Y_AXIS)

            p.add(sectionHeader("Transaction Info"))
            p.add(metaPanel)

            p.add(sectionHeader("SQL Queries"))
            p.add(JBScrollPane(sqlArea).also { it.preferredSize = java.awt.Dimension(0, 160) })

            p.add(sectionHeader("Exception / Rollback Cause"))
            p.add(JBScrollPane(exceptionArea).also { it.preferredSize = java.awt.Dimension(0, 120) })
        }

        add(topBar, BorderLayout.NORTH)
        add(JBScrollPane(center), BorderLayout.CENTER)

        navigateButton.addActionListener { navigateToSource() }
        navigateButton.isEnabled = false
    }

    fun showRecord(record: TransactionRecord) {
        currentRecord = record
        navigateButton.isEnabled = record.className.isNotEmpty()

        val statusColor = if (record.isCommitted) JBColor(Color(0, 130, 0), Color(100, 200, 100))
                          else JBColor.RED
        titleLabel.text = buildString {
            append(if (record.isCommitted) "✓ COMMITTED" else "✗ ROLLED BACK")
            append("  —  ${record.className.substringAfterLast('.')}.${record.methodName}()")
            append("  [${record.durationMs} ms]")
        }
        titleLabel.foreground = statusColor

        // Update pre-created labels in-place (no removeAll, no flickering)
        metaValues["Method"]!!.text      = "${record.className}.${record.methodName}()"
        metaValues["Time"]!!.text        = "${dateFormat.format(Instant.ofEpochMilli(record.startTimeMs))} → ${dateFormat.format(Instant.ofEpochMilli(record.endTimeMs))}"
        metaValues["Duration"]!!.text    = "${record.durationMs} ms"
        metaValues["Thread"]!!.text      = record.threadName
        metaValues["Propagation"]!!.text = record.propagation
        metaValues["Isolation"]!!.text   = record.isolationLevel
        metaValues["ReadOnly"]!!.text    = record.readOnly.toString()
        metaValues["Timeout"]!!.text     = if (record.timeout < 0) "none" else "${record.timeout}s"
        metaValues["Batch rows"]!!.text  = if (record.batchCount > 0) "${record.batchCount}" else "—"

        sqlArea.text = if (record.sqlQueries.isEmpty()) "(none captured)"
                       else record.sqlQueries.joinToString("\n\n")

        exceptionArea.text = when {
            record.exceptionType != null -> buildString {
                append("${record.exceptionType}: ${record.exceptionMessage ?: ""}\n\n")
                append(record.stackTrace ?: "")
            }
            else -> "(no exception)"
        }

        revalidate()
        repaint()
    }

    private fun sectionHeader(title: String): JComponent {
        val label = JBLabel(title)
        label.font = label.font.deriveFont(Font.BOLD, 11f)
        label.foreground = JBColor.GRAY
        label.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.LIGHT_GRAY),
            BorderFactory.createEmptyBorder(6, 8, 2, 8)
        )
        return label
    }

    /**
     * Navigates to the source of the current record using PSI.
     * Looks up the PsiClass and then the specific PsiMethod by name,
     * avoiding reliance on the (unreliable) lineNumber field.
     * Only searches project sources — does not fall back to library classes.
     */
    private fun navigateToSource() {
        val record = currentRecord ?: return

        val target: NavigatablePsiElement? = ReadAction.compute<NavigatablePsiElement?, Throwable> {
            val facade = JavaPsiFacade.getInstance(project)
            val projectScope = GlobalSearchScope.projectScope(project)

            val psiClass = facade.findClass(record.className, projectScope) ?: return@compute null

            // Find the specific overload matching the record's parameter types
            val targetMethod: PsiMethod? = psiClass.findMethodsByName(record.methodName, true)
                .firstOrNull { method ->
                    val paramTypes = method.parameterList.parameters.joinToString(",") { param ->
                        param.type.canonicalText.substringBefore('<').substringAfterLast('.')
                    }
                    paramTypes == record.parameterTypes
                }
                ?: psiClass.findMethodsByName(record.methodName, true).firstOrNull()

            targetMethod ?: psiClass
        }

        if (target == null) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("TransactionMonitor")
                .createNotification(
                    "Method not found in project sources: ${record.className}.${record.methodName}",
                    NotificationType.WARNING
                )
                .notify(project)
            return
        }
        target.navigate(true)
    }
}
