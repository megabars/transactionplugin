package com.txplugin.plugin.ui

import com.github.vertical_blank.sqlformatter.SqlFormatter
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.txplugin.plugin.model.TransactionRecord
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
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

    // SQL list — виртуализированный JBList: рендерит только видимые ячейки независимо
    // от числа запросов, в отличие от JTextArea с одним гигантским документом.
    private val sqlListModel = DefaultListModel<String>()
    private val sqlList = JBList<String>(sqlListModel).also {
        it.cellRenderer = SqlEntryRenderer()
        it.selectionMode = ListSelectionModel.SINGLE_SELECTION
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

    // SQL formatting toggle
    private var isFormatted = false
    private val formatButton = JButton("Format SQL").also { it.isEnabled = false }

    private var currentRecord: TransactionRecord? = null

    init {
        buildLayout()
    }

    private fun buildMetaPanel(): JPanel {
        val panel = JPanel(GridBagLayout())

        fun lbl(text: String, col: Int, row: Int) {
            panel.add(
                JBLabel("$text:").also { it.font = it.font.deriveFont(Font.BOLD) },
                GridBagConstraints().also {
                    it.gridx = col; it.gridy = row
                    it.anchor = GridBagConstraints.NORTHWEST
                    it.insets = Insets(1, 8, 1, 4)
                    it.weightx = 0.0; it.fill = GridBagConstraints.NONE
                }
            )
        }

        fun value(label: JBLabel, col: Int, row: Int, colspan: Int = 1) {
            panel.add(
                label,
                GridBagConstraints().also {
                    it.gridx = col; it.gridy = row; it.gridwidth = colspan
                    it.anchor = GridBagConstraints.NORTHWEST
                    it.insets = Insets(1, 0, 1, 8)
                    it.weightx = 1.0; it.fill = GridBagConstraints.HORIZONTAL
                }
            )
        }

        // Row 0: Method spans all 4 columns
        lbl("Method", 0, 0);  value(metaValues["Method"]!!,      1, 0, colspan = 3)
        // Row 1: Time | Duration
        lbl("Time",   0, 1);  value(metaValues["Time"]!!,        1, 1)
        lbl("Duration",   2, 1);  value(metaValues["Duration"]!!,    3, 1)
        // Row 2: Thread | Propagation
        lbl("Thread", 0, 2);  value(metaValues["Thread"]!!,      1, 2)
        lbl("Propagation", 2, 2); value(metaValues["Propagation"]!!, 3, 2)
        // Row 3: Isolation | ReadOnly
        lbl("Isolation", 0, 3); value(metaValues["Isolation"]!!, 1, 3)
        lbl("ReadOnly",  2, 3); value(metaValues["ReadOnly"]!!,  3, 3)
        // Row 4: Timeout | Batch rows
        lbl("Timeout",    0, 4); value(metaValues["Timeout"]!!,    1, 4)
        lbl("Batch rows", 2, 4); value(metaValues["Batch rows"]!!, 3, 4)

        return panel
    }

    private fun buildLayout() {
        background = UIManager.getColor("Panel.background")

        val topBar = JPanel(BorderLayout()).also {
            it.add(titleLabel, BorderLayout.CENTER)
            val btnPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 2))
            btnPanel.add(navigateButton)
            it.add(btnPanel, BorderLayout.EAST)
        }

        // Fixed-height block: title + Transaction Info fields
        val northPanel = JPanel(BorderLayout()).also {
            it.add(topBar, BorderLayout.NORTH)
            it.add(sectionHeader("Transaction Info"), BorderLayout.CENTER)
            it.add(metaPanel, BorderLayout.SOUTH)
        }

        // SQL and Exception panels split vertically — both resizable by drag
        val sqlHeaderPanel = JPanel(BorderLayout()).also { hp ->
            hp.add(sectionHeader("SQL Queries"), BorderLayout.CENTER)
            hp.add(formatButton, BorderLayout.EAST)
        }
        val sqlPanel = JPanel(BorderLayout()).also {
            it.add(sqlHeaderPanel, BorderLayout.NORTH)
            it.add(JBScrollPane(sqlList).also { sp ->
                sp.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
            }, BorderLayout.CENTER)
        }
        val exceptionPanel = JPanel(BorderLayout()).also {
            it.add(sectionHeader("Exception / Rollback Cause"), BorderLayout.NORTH)
            it.add(JBScrollPane(exceptionArea), BorderLayout.CENTER)
        }
        val splitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT, sqlPanel, exceptionPanel).also {
            it.resizeWeight = 0.75   // SQL gets 75% when panel is resized
            it.dividerSize = 5
            it.isContinuousLayout = true
        }

        add(northPanel, BorderLayout.NORTH)
        add(splitPane, BorderLayout.CENTER)

        navigateButton.addActionListener { navigateToSource() }
        navigateButton.isEnabled = false

        formatButton.addActionListener {
            val record = currentRecord ?: return@addActionListener
            isFormatted = !isFormatted
            formatButton.text = if (isFormatted) "Raw SQL" else "Format SQL"
            refreshSqlArea(record)
        }
    }

    fun clear() {
        currentRecord = null
        navigateButton.isEnabled = false
        isFormatted = false
        formatButton.text = "Format SQL"
        formatButton.isEnabled = false
        titleLabel.text = "Select a transaction"
        titleLabel.foreground = UIManager.getColor("Label.foreground")
        metaValues.values.forEach { it.text = "" }
        sqlListModel.clear()
        exceptionArea.text = ""
        revalidate()
        repaint()
    }

    fun showRecord(record: TransactionRecord) {
        val sameRecord = currentRecord?.transactionId == record.transactionId
        currentRecord = record
        navigateButton.isEnabled = record.className.isNotEmpty()
        if (sameRecord) return
        isFormatted = false
        formatButton.text = "Format SQL"
        formatButton.isEnabled = record.sqlQueries.isNotEmpty()

        val statusColor = if (record.isCommitted) JBColor(Color(0, 130, 0), Color(100, 200, 100))
                          else JBColor.RED
        titleLabel.text = buildString {
            append(if (record.isCommitted) "✓ COMMITTED" else "✗ ROLLED BACK")
            append("  —  ${record.className.substringAfterLast('.')}.${record.methodName}()")
            append("  [${record.durationMs} ms]")
        }
        titleLabel.foreground = statusColor

        // Update pre-created labels in-place (no removeAll, no flickering)
        metaValues["Method"]?.text      = "${record.className}.${record.methodName}()"
        metaValues["Time"]?.text        = "${dateFormat.format(Instant.ofEpochMilli(record.startTimeMs))} → ${dateFormat.format(Instant.ofEpochMilli(record.endTimeMs))}"
        metaValues["Duration"]?.text    = "${record.durationMs} ms"
        metaValues["Thread"]?.text      = record.threadName
        metaValues["Propagation"]?.text = record.propagation
        metaValues["Isolation"]?.text   = record.isolationLevel
        metaValues["ReadOnly"]?.text    = record.readOnly.toString()
        metaValues["Timeout"]?.text     = if (record.timeout < 0) "none" else "${record.timeout}s"
        metaValues["Batch rows"]?.text  = if (record.batchCount > 0) "${record.batchCount}" else "—"

        refreshSqlArea(record)

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

    private fun refreshSqlArea(record: TransactionRecord) {
        sqlListModel.clear()
        if (record.sqlQueries.isEmpty()) {
            sqlListModel.addElement("(none captured)")
        } else if (isFormatted) {
            record.sqlQueries.forEach { sqlListModel.addElement(formatSqlEntry(it)) }
        } else {
            record.sqlQueries.forEach { sqlListModel.addElement(it) }
        }
        // Прокрутка в начало списка
        if (sqlListModel.size() > 0) sqlList.ensureIndexIsVisible(0)
    }

    private fun formatSqlEntry(entry: String): String {
        val lines = entry.lines()
        val sqlLine = lines.first()
        val paramLines = lines.drop(1)

        val batchIdx = sqlLine.indexOf("  [batch:")
        val (sqlPart, batchSuffix) = if (batchIdx >= 0)
            sqlLine.substring(0, batchIdx) to sqlLine.substring(batchIdx)
        else
            sqlLine to ""

        val formatted = try {
            SqlFormatter.format(sqlPart)
        } catch (_: Exception) {
            sqlPart
        }

        val formattedLines = formatted.lines()
        val sqlLines = if (batchSuffix.isNotEmpty())
            formattedLines.dropLast(1) + (formattedLines.last() + batchSuffix)
        else
            formattedLines

        return (sqlLines + paramLines).joinToString("\n")
    }

    private fun sectionHeader(title: String): JComponent {
        val label = JBLabel(title)
        label.font = label.font.deriveFont(Font.BOLD, 11f)
        label.foreground = JBColor.GRAY
        label.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.LIGHT_GRAY),
            BorderFactory.createEmptyBorder(4, 8, 2, 8)
        )
        return label
    }

    /**
     * Navigates to the source of the current record using PSI.
     * PSI index lookup runs on a background thread to avoid blocking EDT.
     * Only searches project sources — does not fall back to library classes.
     */
    private fun navigateToSource() {
        val record = currentRecord ?: return

        ReadAction.nonBlocking<NavigatablePsiElement?> {
            val facade = JavaPsiFacade.getInstance(project)
            val projectScope = GlobalSearchScope.projectScope(project)

            val psiClass = facade.findClass(record.className, projectScope) ?: return@nonBlocking null

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
        }.finishOnUiThread(ModalityState.defaultModalityState()) { target ->
            if (target == null) {
                NotificationGroupManager.getInstance()
                    .getNotificationGroup("TransactionMonitor")
                    .createNotification(
                        "Method not found in project sources: ${record.className}.${record.methodName}",
                        NotificationType.WARNING
                    )
                    .notify(project)
            } else {
                target.navigate(true)
            }
        }.submit(AppExecutorUtil.getAppExecutorService())
    }
}

/**
 * Рендерер ячейки JBList для одного SQL-запроса.
 *
 * Один экземпляр JTextArea переиспользуется для всех ячеек (стандартный Swing-паттерн).
 * JList виртуализирован: вызывает рендерер только для видимых ячеек, поэтому прокрутка
 * остаётся O(visible_rows), а не O(total_lines) как было у JBTextArea с одним документом.
 */
private class SqlEntryRenderer : ListCellRenderer<String> {

    private val textArea = JTextArea().apply {
        isEditable = false
        lineWrap = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.LIGHT_GRAY),
            BorderFactory.createEmptyBorder(4, 6, 6, 6)
        )
    }

    override fun getListCellRendererComponent(
        list: JList<out String>, value: String, index: Int,
        isSelected: Boolean, cellHasFocus: Boolean
    ): Component {
        textArea.text = value
        textArea.background = if (isSelected) list.selectionBackground else list.background
        textArea.foreground = if (isSelected) list.selectionForeground else list.foreground
        return textArea
    }
}
