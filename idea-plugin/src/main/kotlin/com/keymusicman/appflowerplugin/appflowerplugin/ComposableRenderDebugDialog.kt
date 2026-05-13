package com.keymusicman.appflowerplugin.appflowerplugin

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.concurrency.AppExecutorUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTextField
import javax.swing.JTextArea
import javax.swing.SwingUtilities

class ComposableRenderDebugDialog(private val project: Project) :
    JDialog(WindowManager.getInstance().getFrame(project), "Composable Renderer Debug", false) {

    private val fqnField = JTextField(50)
    private val renderButton = JButton("Render")
    private val simpleLayoutCheckbox = JCheckBox("Simple layout (TextView)", false)
    private val useCustomConfigCheckbox = JCheckBox("Custom config", false)
    private val debugDeviceCombo = JComboBox<String>(PRESET_DEVICES.map { it.second }.toTypedArray())
    private val logArea = JTextArea().apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, 11)
        isEditable = false
    }
    private val imagePanel = ImagePreviewPanel()

    init {
        layout = BorderLayout(0, 4)

        val topBar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 6)).apply {
            add(JLabel("Composable FQN:"))
            add(fqnField)
            add(renderButton)
            add(simpleLayoutCheckbox)
            add(useCustomConfigCheckbox)
            add(debugDeviceCombo)
        }
        add(topBar, BorderLayout.NORTH)

        val splitPane = JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            JScrollPane(logArea),
            JScrollPane(imagePanel),
        ).apply {
            dividerLocation = 450
            resizeWeight = 0.45
        }
        add(splitPane, BorderLayout.CENTER)

        renderButton.addActionListener { startRender() }
        fqnField.addActionListener { startRender() }

        setSize(1100, 750)
        setLocationRelativeTo(parent)
    }

    private fun appendLog(msg: String) {
        SwingUtilities.invokeLater {
            logArea.append("$msg\n")
            logArea.caretPosition = logArea.document.length
        }
    }

    private fun startRender() {
        val fqn = fqnField.text.trim()
        if (fqn.isEmpty()) return

        SwingUtilities.invokeLater {
            logArea.text = ""
            imagePanel.setImage(null)
            renderButton.isEnabled = false
        }

        AppExecutorUtil.getAppExecutorService().submit {
            try {
                val modulePath = resolveModulePath(fqn)
                if (modulePath == null) {
                    appendLog("[ERROR] Could not resolve a module path for: $fqn")
                    return@submit
                }

                val debugConfig = if (useCustomConfigCheckbox.isSelected) {
                    PreviewRenderConfig(
                        useCustomConfig = true,
                        deviceId = PRESET_DEVICES[debugDeviceCombo.selectedIndex].first,
                    )
                } else {
                    PreviewRenderConfig(useCustomConfig = false)
                }
                val imagePath = ComposableRenderer.render(
                    project = project,
                    modulePath = modulePath,
                    composableFqn = fqn,
                    onLog = ::appendLog,
                    useSimpleLayout = simpleLayoutCheckbox.isSelected,
                    previewConfig = debugConfig,
                )

                if (imagePath != null) {
                    val image = runCatching { ImageIO.read(File(imagePath)) }.getOrNull()
                    if (image != null) {
                        var nonWhite = 0
                        for (y in 0 until image.height) {
                            for (x in 0 until image.width) {
                                val rgb = image.getRGB(x, y)
                                if ((rgb and 0xFFFFFF) != 0xFFFFFF && (rgb and 0xFFFFFF) != 0xFAFAFA) nonWhite++
                            }
                        }
                        appendLog("[INFO] Image: ${image.width}×${image.height}  non-white pixels: $nonWhite / ${image.width * image.height}")
                    }
                    SwingUtilities.invokeLater { imagePanel.setImage(image) }
                }
            } finally {
                SwingUtilities.invokeLater { renderButton.isEnabled = true }
            }
        }
    }

    private fun resolveModulePath(fqn: String): String? {
        val packageName = fqn.substringBeforeLast('.', missingDelimiterValue = "")

        // Step 1: PSI class lookup — works for class-based composables.
        val psiClass: PsiClass? = try {
            ReadAction.compute<PsiClass?, Throwable> {
                val scope = GlobalSearchScope.allScope(project)
                JavaPsiFacade.getInstance(project).findClass(fqn, scope)
                    ?: JavaPsiFacade.getInstance(project).findClass("${fqn}Kt", scope)
            }
        } catch (_: Exception) { null }

        val ijModule: Module? = psiClass?.let {
            try { ReadAction.compute<Module?, Throwable> { ModuleUtilCore.findModuleForPsiElement(it) } }
            catch (_: Exception) { null }
        } ?: run {
            // Step 2: Package directory lookup — works for top-level Kotlin functions.
            if (packageName.isEmpty()) return@run null
            try {
                ReadAction.compute<Module?, Throwable> {
                    JavaPsiFacade.getInstance(project).findPackage(packageName)
                        ?.directories
                        ?.firstNotNullOfOrNull { dir -> ModuleUtilCore.findModuleForPsiElement(dir) }
                }
            } catch (_: Exception) { null }
        }

        if (ijModule != null) {
            val ijRoot = ModuleRootManager.getInstance(ijModule).contentRoots.firstOrNull()?.path
            appendLog("[INFO] PSI/package resolved IntelliJ module: ${ijModule.name}  root=$ijRoot")

            if (ijRoot != null) {
                val scannerModules = ModuleScanner.findModulesWithExportGraph(project)
                // Find the longest-matching ancestor — i.e. the closest app/exportGraph module.
                val best = scannerModules
                    .filter { it.modulePath == ijRoot || ijRoot.startsWith(it.modulePath + "/") }
                    .maxByOrNull { it.modulePath.length }
                if (best != null) {
                    appendLog("[INFO] Matched ModuleScanner module: ${best.name} → ${best.modulePath}")
                    return best.modulePath
                }
                // No ModuleScanner ancestor — use the IJ module root directly.
                appendLog("[WARN] No ModuleScanner match; using IJ module root directly: $ijRoot")
                return ijRoot
            }
        }

        appendLog("[WARN] All PSI resolution failed for '$fqn', falling back to first ModuleScanner result")
        val modules = ModuleScanner.findModulesWithExportGraph(project)
        return modules.firstOrNull()?.modulePath?.also { appendLog("[INFO] ModuleScanner fallback: $it") }
    }
}

private class ImagePreviewPanel : JPanel() {
    private var image: BufferedImage? = null

    fun setImage(img: BufferedImage?) {
        image = img
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val img = image ?: return
        val scale = minOf(width.toDouble() / img.width, height.toDouble() / img.height)
        val w = (img.width * scale).toInt()
        val h = (img.height * scale).toInt()
        val x = (width - w) / 2
        val y = (height - h) / 2
        g.drawImage(img, x, y, w, h, null)
    }

    override fun getPreferredSize() = Dimension(500, 650)
}
