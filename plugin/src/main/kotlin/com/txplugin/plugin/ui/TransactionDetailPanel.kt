package com.txplugin.plugin.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.JavaPsiFacade
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
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.*

/**
 * Panel displaying all details of a single [TransactionRecord].
 * Embedded in the Tool Window split pane and updated on selection change.
 */
class TransactionDetailPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS")

    // Top section
    private val titleLabel = JBLabel("Select a transaction").also {
        it.font = it.font.deriveFont(Font.BOLD, 13f)
        it.border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
    }

    // Meta section (status, timing, tx params)
    private val metaPanel = JPanel().also { it.layout = BoxLayout(it, BoxLayout.Y_AXIS) }

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
        navigateButton.isEnabled = record.lineNumber > 0 || record.className.isNotEmpty()

        val statusColor = if (record.isCommitted) JBColor(Color(0, 130, 0), Color(100, 200, 100))
                          else JBColor.RED
        titleLabel.text = buildString {
            append(if (record.isCommitted) "✓ COMMITTED" else "✗ ROLLED BACK")
            append("  —  ${record.className.substringAfterLast('.')}.${record.methodName}()")
            append("  [${record.durationMs} ms]")
        }
        titleLabel.foreground = statusColor

        // Meta rows
        metaPanel.removeAll()
        metaRow("Method",      "${record.className}.${record.methodName}()")
        metaRow("Time",        "${dateFormat.format(Date(record.startTimeMs))} → ${dateFormat.format(Date(record.endTimeMs))}")
        metaRow("Duration",    "${record.durationMs} ms")
        metaRow("Thread",      record.threadName)
        metaRow("Propagation", record.propagation)
        metaRow("Isolation",   record.isolationLevel)
        metaRow("ReadOnly",    record.readOnly.toString())
        metaRow("Timeout",     if (record.timeout < 0) "none" else "${record.timeout}s")
        metaRow("SQL queries", "${record.sqlQueryCount}  |  batches: ${record.batchCount}")
        metaRow("Entities",    "↑ inserted: ${record.insertCount}   ✎ updated: ${record.updateCount}   ↓ deleted: ${record.deleteCount}")
        metaPanel.revalidate()

        // SQL
        sqlArea.text = if (record.sqlQueries.isEmpty()) "(none captured)"
                       else record.sqlQueries.joinToString("\n\n")

        // Exception
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

    private fun metaRow(label: String, value: String) {
        val row = JPanel(FlowLayout(FlowLayout.LEFT, 4, 1))
        row.add(JBLabel("$label:").also { it.font = it.font.deriveFont(Font.BOLD) })
        row.add(JBLabel(value))
        metaPanel.add(row)
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

    private fun navigateToSource() {
        val record = currentRecord ?: return
        val facade = JavaPsiFacade.getInstance(project)
        val psiClass = facade.findClass(record.className, GlobalSearchScope.allScope(project))
            ?: return
        val vFile: VirtualFile = psiClass.containingFile?.virtualFile ?: return
        val line = if (record.lineNumber > 0) record.lineNumber - 1 else 0
        OpenFileDescriptor(project, vFile, line, 0).navigate(true)
    }
}
