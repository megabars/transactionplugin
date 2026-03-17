package com.txplugin.plugin.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.txplugin.plugin.model.TransactionRecord
import com.txplugin.plugin.model.TransactionStatus
import com.txplugin.plugin.store.TransactionStore
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import javax.swing.*
import javax.swing.table.DefaultTableCellRenderer

class TransactionToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = TransactionPanel(project)
        val content = toolWindow.contentManager.factory.createContent(panel, "Transactions", false)
        Disposer.register(content, panel)
        toolWindow.contentManager.addContent(content)
    }
}

/**
 * Main panel: toolbar + split pane (table | detail).
 */
class TransactionPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val store = TransactionStore.getInstance()
    private val tableModel = TransactionTableModel()
    private val table = JBTable(tableModel)
    private val detailPanel = TransactionDetailPanel(project)

    // Filter state
    private var statusFilter: TransactionStatus? = null // null = All

    // Keep reference so we can remove the exact same lambda instance on dispose
    private val storeListener: () -> Unit = { refreshTable() }

    init {
        add(buildToolbar(), BorderLayout.NORTH)

        // Configure table
        table.apply {
            autoCreateRowSorter = true
            setDefaultRenderer(Any::class.java, StatusColorRenderer())
            selectionModel.addListSelectionListener { onRowSelected() }
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    if (e.clickCount == 2) onRowSelected()
                }
            })
        }
        applyColumnWidths()

        val split = JSplitPane(
            JSplitPane.VERTICAL_SPLIT,
            JBScrollPane(table),
            detailPanel
        ).also { it.resizeWeight = 0.55 }

        add(split, BorderLayout.CENTER)

        // Listen for new records — removed in dispose() to prevent listener leak
        store.addListener(storeListener)
        refreshTable()
    }

    override fun dispose() {
        store.removeListener(storeListener)
    }

    private fun buildToolbar(): JPanel {
        val bar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4))

        val clearBtn = JButton("Clear").also {
            it.addActionListener {
                store.clear()
                detailPanel.clear()
            }
        }

        val filterCombo = JComboBox(arrayOf("All", "Committed", "Rolled Back")).also {
            it.addActionListener { _ ->
                statusFilter = when (it.selectedIndex) {
                    1 -> TransactionStatus.COMMITTED
                    2 -> TransactionStatus.ROLLED_BACK
                    else -> null
                }
                refreshTable()
            }
        }

        val statusLabel = if (store.isListening) {
            JLabel("Listening on port ${store.port}").also {
                it.foreground = JBColor(Color(0, 130, 0), Color(100, 200, 100))
            }
        } else {
            JLabel("Not listening (port unavailable)").also {
                it.foreground = JBColor.RED
            }
        }

        bar.add(JLabel("Filter:"))
        bar.add(filterCombo)
        bar.add(clearBtn)
        bar.add(Box.createHorizontalStrut(20))
        bar.add(statusLabel)
        return bar
    }

    private fun refreshTable() {
        val all = store.getRecords()
        val filtered = if (statusFilter == null) all
                       else all.filter { it.status == statusFilter }
        tableModel.setRecords(filtered)
    }

    private fun onRowSelected() {
        val row = table.selectedRow.takeIf { it >= 0 } ?: return
        val modelRow = table.convertRowIndexToModel(row)
        val record = tableModel.recordAt(modelRow) ?: return
        detailPanel.showRecord(record)
    }

    private fun applyColumnWidths() {
        val cm = table.columnModel
        TransactionTableModel.Column.entries.forEachIndexed { i, col ->
            cm.getColumn(i).preferredWidth = col.width
        }
    }
}

/**
 * Colors table rows: green for COMMITTED, red for ROLLED_BACK.
 */
private class StatusColorRenderer : DefaultTableCellRenderer() {

    private val committedBg = JBColor(Color(230, 255, 230), Color(30, 60, 30))
    private val rolledBackBg = JBColor(Color(255, 230, 230), Color(70, 20, 20))

    override fun getTableCellRendererComponent(
        table: JTable, value: Any?, isSelected: Boolean,
        hasFocus: Boolean, row: Int, column: Int
    ): Component {
        val comp = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
        if (!isSelected) {
            val modelRow = table.convertRowIndexToModel(row)
            val model = table.model as? TransactionTableModel ?: return comp
            val record: TransactionRecord = model.recordAt(modelRow) ?: return comp
            comp.background = when {
                record.isCommitted  -> committedBg
                record.isRolledBack -> rolledBackBg
                else                -> table.background
            }
        }
        return comp
    }
}
