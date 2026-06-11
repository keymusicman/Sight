package io.github.keymusicman.sight.plugin

import com.intellij.openapi.options.Configurable
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSlider
import javax.swing.event.ChangeListener

class PluginSettingsConfigurable : Configurable {

    private lateinit var formatCombo: JComboBox<OutputFormat>
    private lateinit var qualitySlider: JSlider
    private lateinit var qualityLabel: JLabel
    private lateinit var subprocessBox: JCheckBox

    override fun getDisplayName(): String = "Sight"

    override fun createComponent(): JComponent {
        formatCombo = JComboBox(OutputFormat.entries.toTypedArray())
        qualitySlider = JSlider(1, 100, 85)
        qualityLabel = JLabel("85")
        subprocessBox = JCheckBox("Render previews in isolated subprocess (experimental — avoids the Layoutlib native memory leak)")

        val qualityChangeListener = ChangeListener {
            qualityLabel.text = qualitySlider.value.toString()
        }
        qualitySlider.addChangeListener(qualityChangeListener)

        formatCombo.addActionListener { syncQualityEnabled() }

        val rows = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(8, 0, 8, 0)
            add(row("Output format:", formatCombo))
            add(row("JPEG quality:", qualitySlider, qualityLabel))
            add(row(subprocessBox))
        }

        reset()
        return JPanel(java.awt.BorderLayout()).apply { add(rows, java.awt.BorderLayout.NORTH) }
    }

    override fun isModified(): Boolean {
        val s = PluginSettingsService.getInstance().getState()
        return formatCombo.selectedItem != s.outputFormat ||
            qualitySlider.value != s.jpegQuality ||
            subprocessBox.isSelected != s.useSubprocessRenderer
    }

    override fun apply() {
        val s = PluginSettingsService.getInstance().getState()
        s.outputFormat = formatCombo.selectedItem as OutputFormat
        s.jpegQuality = qualitySlider.value
        s.useSubprocessRenderer = subprocessBox.isSelected
    }

    override fun reset() {
        val s = PluginSettingsService.getInstance().getState()
        formatCombo.selectedItem = s.outputFormat
        qualitySlider.value = s.jpegQuality
        qualityLabel.text = s.jpegQuality.toString()
        subprocessBox.isSelected = s.useSubprocessRenderer
        syncQualityEnabled()
    }

    private fun syncQualityEnabled() {
        val isJpeg = formatCombo.selectedItem == OutputFormat.JPEG
        qualitySlider.isEnabled = isJpeg
        qualityLabel.isEnabled = isJpeg
    }

    private fun row(vararg components: java.awt.Component): JPanel =
        JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply { components.forEach(::add) }

    private fun row(label: String, vararg components: java.awt.Component): JPanel =
        row(JLabel(label), *components)
}
