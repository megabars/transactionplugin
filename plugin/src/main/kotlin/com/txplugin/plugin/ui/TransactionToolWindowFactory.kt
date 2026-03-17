package com.txplugin.plugin.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.txplugin.plugin.model.TransactionStatus
import com.txplugin.plugin.store.TransactionStore
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import javax.swing.*

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

    // Fix #5: используем prepareRenderer на уровне JBTable вместо setDefaultRenderer,
    // чтобы цвет строки выставлялся один раз на строку, а не через отдельный объект-рендерер.
    private val committedBg = JBColor(Color(230, 255, 230), Color(30, 60, 30))
    private val rolledBackBg = JBColor(Color(255, 230, 230), Color(70, 20, 20))
    private val table = object : JBTable(tableModel) {
        override fun prepareRenderer(renderer: javax.swing.table.TableCellRenderer, row: Int, column: Int): Component {
            val comp = super.prepareRenderer(renderer, row, column)
            if (!isRowSelected(row)) {
                val modelRow = convertRowIndexToModel(row)
                comp.background = when (tableModel.recordAt(modelRow)?.status) {
                    TransactionStatus.COMMITTED   -> committedBg
                    TransactionStatus.ROLLED_BACK -> rolledBackBg
                    else -> background
                }
            }
            return comp
        }
    }
    private val detailPanel = TransactionDetailPanel(project)

    // Filter state
    private var statusFilter: TransactionStatus? = null // null = All

    // Keep reference so we can remove the exact same lambda instance on dispose
    private val storeListener: () -> Unit = { refreshTable() }

    init {
        add(buildToolbar(), BorderLayout.NORTH)

        // Configure table
        // Fix #3: проверяем valueIsAdjusting — не вызываем showRecord во время drag/keyboard-навигации
        // Fix #4: убираем mouseListener с double-click — selection listener уже обрабатывает выбор строки
        table.apply {
            autoCreateRowSorter = true
            selectionModel.addListSelectionListener { e ->
                if (!e.valueIsAdjusting) onRowSelected()
            }
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

    // Fix #2: сохраняем выбранную строку по transactionId и восстанавливаем после fireTableDataChanged()
    private fun refreshTable() {
        val selectedId = table.selectedRow
            .takeIf { it >= 0 }
            ?.let { table.convertRowIndexToModel(it) }
            ?.let { tableModel.recordAt(it)?.transactionId }

        val all = store.getRecords()
        val filtered = if (statusFilter == null) all
                       else all.filter { it.status == statusFilter }
        tableModel.setRecords(filtered)

        if (selectedId != null) {
            val modelRow = (0 until tableModel.rowCount)
                .firstOrNull { tableModel.recordAt(it)?.transactionId == selectedId }
            if (modelRow != null) {
                val viewRow = table.convertRowIndexToView(modelRow)
                if (viewRow >= 0) {
                    table.setRowSelectionInterval(viewRow, viewRow)
                    table.scrollRectToVisible(table.getCellRect(viewRow, 0, true))
                }
            }
        }
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

