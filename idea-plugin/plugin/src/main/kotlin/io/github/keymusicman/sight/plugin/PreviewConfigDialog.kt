package io.github.keymusicman.sight.plugin

import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Frame
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JSeparator
import javax.swing.JTextField
import javax.swing.WindowConstants

class PreviewConfigDialog(
    parent: Frame?,
    initialConfig: PreviewRenderConfig,
    private val onApply: (PreviewRenderConfig) -> Unit,
) : JDialog(parent, "Preview Configuration", true) {

    private val useCustomCheckbox = JCheckBox("Use custom configuration", initialConfig.useCustomConfig)

    private val deviceCombo = JComboBox(PRESET_DEVICES.map { it.second }.toTypedArray()).apply {
        selectedIndex = PRESET_DEVICES.indexOfFirst { it.first == initialConfig.deviceId }.coerceAtLeast(0)
    }
    private val widthField  = JTextField("${initialConfig.customWidthDp}", 5)
    private val heightField = JTextField("${initialConfig.customHeightDp}", 5)

    private val lightRadio = JRadioButton("Light", initialConfig.uiMode == PreviewUiMode.LIGHT)
    private val darkRadio  = JRadioButton("Dark",  initialConfig.uiMode == PreviewUiMode.DARK)

    private val fontScaleValues = listOf(0.85f, 1.0f, 1.15f, 1.3f, 1.5f, 1.8f, 2.0f)
    private val fontScaleCombo = JComboBox(fontScaleValues.map { "${it}x" }.toTypedArray()).apply {
        selectedIndex = fontScaleValues.indexOfFirst { it == initialConfig.fontScale }.takeIf { it >= 0 } ?: 1
    }

    private val localeField      = JTextField(initialConfig.locale, 12)
    private val showSystemUiBox  = JCheckBox("Show system UI", initialConfig.showSystemUi)

    // Fields that are bulk-enabled/disabled with the main toggle (width/height managed separately)
    private val configFields: List<JComponent> = listOf(
        deviceCombo, lightRadio, darkRadio, fontScaleCombo, localeField, showSystemUiBox
    )

    init {
        defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        ButtonGroup().apply { add(lightRadio); add(darkRadio) }

        val mainPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(8, 12, 8, 12)

            add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { add(useCustomCheckbox) })
            add(JSeparator())

            add(row("Device:", deviceCombo,
                JLabel("  Width:"), widthField, JLabel("dp"),
                JLabel("  Height:"), heightField, JLabel("dp")))
            add(row("UI mode:", lightRadio, darkRadio))
            add(row("Font scale:", fontScaleCombo))
            add(row("Locale:", localeField, JLabel("  (empty = system default)")))
            add(row(showSystemUiBox))
        }

        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
            add(JButton("Cancel").apply { addActionListener { dispose() } })
            add(JButton("Apply").apply  { addActionListener { doApply() } })
        }

        layout = BorderLayout(0, 4)
        add(mainPanel, BorderLayout.CENTER)
        add(buttonPanel, BorderLayout.SOUTH)

        useCustomCheckbox.addActionListener { syncEnabled() }
        deviceCombo.addActionListener      { syncSizeFields() }

        syncEnabled()
        pack()
        setLocationRelativeTo(parent)
    }

    private fun syncEnabled() {
        val on = useCustomCheckbox.isSelected
        configFields.forEach { it.isEnabled = on }
        syncSizeFields()
    }

    private fun syncSizeFields() {
        val customIdx = PRESET_DEVICES.indexOfFirst { it.first == CUSTOM_DEVICE_ID }
        val isCustom  = deviceCombo.selectedIndex == customIdx
        val base      = useCustomCheckbox.isSelected
        widthField.isEnabled  = base && isCustom
        heightField.isEnabled = base && isCustom
    }

    private fun doApply() {
        val selectedDeviceId = PRESET_DEVICES[deviceCombo.selectedIndex].first
        onApply(PreviewRenderConfig(
            useCustomConfig = useCustomCheckbox.isSelected,
            deviceId        = selectedDeviceId,
            customWidthDp   = widthField.text.toIntOrNull() ?: 360,
            customHeightDp  = heightField.text.toIntOrNull() ?: 640,
            uiMode          = if (darkRadio.isSelected) PreviewUiMode.DARK else PreviewUiMode.LIGHT,
            fontScale       = fontScaleValues[fontScaleCombo.selectedIndex],
            locale          = localeField.text.trim(),
            showSystemUi    = showSystemUiBox.isSelected,
        ))
        dispose()
    }

    private fun row(vararg components: java.awt.Component): JPanel =
        JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply { components.forEach(::add) }

    private fun row(label: String, vararg components: java.awt.Component): JPanel =
        row(JLabel(label), *components)
}
