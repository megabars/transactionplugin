package com.txplugin.plugin.settings

import com.intellij.codeInsight.codeVision.CodeVisionHost
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.txplugin.plugin.ui.TransactionCodeVisionProvider
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

class TransactionSettingsConfigurable : Configurable {

    private var maxRecordsSpinner: JSpinner? = null
    private var portSpinner: JSpinner? = null
    private var showCodeVisionCheckBox: JBCheckBox? = null

    override fun getDisplayName(): String = "Transaction Monitor"

    override fun createComponent(): JComponent {
        val panel = JPanel(GridBagLayout())

        fun gbc(x: Int, y: Int, weightx: Double = 0.0, gridwidth: Int = 1) = GridBagConstraints().apply {
            gridx = x; gridy = y
            this.weightx = weightx
            this.gridwidth = gridwidth
            anchor = GridBagConstraints.WEST
            fill = if (weightx > 0) GridBagConstraints.HORIZONTAL else GridBagConstraints.NONE
            insets = Insets(4, 8, 4, 8)
        }

        // Max records
        panel.add(JBLabel("Max records in history:"), gbc(0, 0))
        maxRecordsSpinner = JSpinner(SpinnerNumberModel(1000, 100, 10_000, 100))
        panel.add(maxRecordsSpinner!!, gbc(1, 0, weightx = 0.3))

        // Port
        panel.add(JBLabel("TCP port:"), gbc(0, 1))
        portSpinner = JSpinner(SpinnerNumberModel(17321, 1024, 65535, 1))
        panel.add(portSpinner!!, gbc(1, 1, weightx = 0.3))
        panel.add(
            JBLabel("<html><i>Takes effect after IDE restart</i></html>"),
            gbc(2, 1, weightx = 1.0)
        )

        // Code Vision
        showCodeVisionCheckBox = JBCheckBox("Show Code Vision hints above @Transactional methods")
        panel.add(showCodeVisionCheckBox!!, gbc(0, 2, weightx = 1.0, gridwidth = 3))

        // Vertical filler
        panel.add(JPanel(), GridBagConstraints().apply {
            gridx = 0; gridy = 3; weighty = 1.0; fill = GridBagConstraints.VERTICAL
        })

        return panel
    }

    override fun isModified(): Boolean {
        val s = TransactionSettings.getInstance()
        return maxRecordsSpinner?.value != s.maxRecords ||
               portSpinner?.value != s.port ||
               showCodeVisionCheckBox?.isSelected != s.showCodeVision
    }

    override fun apply() {
        val s = TransactionSettings.getInstance()
        val codeVisionChanged = showCodeVisionCheckBox?.isSelected != s.showCodeVision

        (maxRecordsSpinner?.value as? Int)?.let { s.maxRecords = it }
        (portSpinner?.value as? Int)?.let { s.port = it }
        showCodeVisionCheckBox?.isSelected?.let { s.showCodeVision = it }

        if (codeVisionChanged) refreshCodeVision()
    }

    override fun reset() {
        val s = TransactionSettings.getInstance()
        maxRecordsSpinner?.value = s.maxRecords
        portSpinner?.value = s.port
        showCodeVisionCheckBox?.isSelected = s.showCodeVision
    }

    private fun refreshCodeVision() {
        ProjectManager.getInstance().openProjects.toList().forEach { project ->
            if (project.isDisposed) return@forEach
            val host = project.getService(CodeVisionHost::class.java) ?: return@forEach
            FileEditorManager.getInstance(project).allEditors.toList()
                .filterIsInstance<TextEditor>()
                .forEach { fileEditor ->
                    host.invalidateProvider(
                        CodeVisionHost.LensInvalidateSignal(
                            fileEditor.editor,
                            listOf(TransactionCodeVisionProvider.ID)
                        )
                    )
                }
        }
    }
}
