package com.lioncph.plugin.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import com.lioncph.plugin.models.CphSettings
import com.lioncph.plugin.models.CphStorage
import com.lioncph.plugin.models.ProblemData
import com.lioncph.plugin.models.TestCase
import com.lioncph.plugin.models.TestResult
import com.lioncph.plugin.services.CMakeSyncService
import com.lioncph.plugin.services.CodeforcesSubmitService
import com.lioncph.plugin.services.CompanionServerService
import com.lioncph.plugin.services.JudgeService
import com.lioncph.plugin.services.PendingSubmit
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.GridLayout
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.filechooser.FileNameExtensionFilter

class LionCPHToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = LionCPHPanel(project)
        Disposer.register(project, panel)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
        panels[project] = panel
    }

    companion object {
        val panels = ConcurrentHashMap<Project, LionCPHPanel>()
    }
}

class RunAllAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("LionCPH")
        toolWindow?.show {
            LionCPHToolWindowFactory.panels[project]?.runAllTests()
        }
    }
}

private object CphColors {
    val BG = Color(0x1E, 0x1E, 0x1E)
    val SURFACE = Color(0x25, 0x25, 0x26)
    val INPUT_BG = Color(0x1B, 0x1B, 0x1C)
    val BORDER = Color(0x33, 0x33, 0x37)
    val TEXT = Color(0xCC, 0xCC, 0xCC)
    val MUTED = Color(0x8B, 0x8B, 0x8B)
    val TITLE = Color(0xFF, 0xFF, 0xFF)
    val TC_BLUE = Color(0x4E, 0xA1, 0xF3)
    val GREEN = Color(0x52, 0x8B, 0x28)
    val GREEN_TEXT = Color(0x9C, 0xE2, 0x2E)
    val RED = Color(0xB5, 0x20, 0x2E)
    val RED_TEXT = Color(0xF1, 0x4C, 0x4C)
    val BLUE = Color(0x11, 0x77, 0xBB)
    val AMBER = Color(0xC7, 0x8B, 0x18)
    val BTN_DARK = Color(0x2D, 0x2D, 0x30)
    val BTN_DARK_BORDER = Color(0x3E, 0x3E, 0x42)
    val CE_BG = Color(0x3A, 0x16, 0x19)
    val CE_BORDER = Color(0x8A, 0x23, 0x2B)
}

private class FlatCphButton(
    text: String = "",
    var baseColor: Color,
    private val borderColor: Color? = null,
    icon: Icon? = null
) : JButton(text, icon) {
    private var hovered = false

    init {
        isContentAreaFilled = false
        isOpaque = false
        isBorderPainted = false
        isFocusPainted = false
        foreground = Color.WHITE
        font = Font("Segoe UI", Font.BOLD, 12)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        iconTextGap = 6
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { hovered = true; repaint() }
            override fun mouseExited(e: MouseEvent) { hovered = false; repaint() }
        })
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val fill = if (model.isPressed) baseColor.darker() else if (hovered) baseColor.brighter() else baseColor
        g2.color = fill
        g2.fillRoundRect(0, 0, width, height, 6, 6)
        if (borderColor != null) {
            g2.color = borderColor
            g2.drawRoundRect(0, 0, width - 1, height - 1, 6, 6)
        }
        g2.dispose()
        super.paintComponent(g)
    }
}

private class PlayTriangleIcon(private val size: Int = 11) : Icon {
    override fun getIconWidth() = size
    override fun getIconHeight() = size
    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = Color.WHITE
        val xs = intArrayOf(x + 2, x + size - 1, x + 2)
        val ys = intArrayOf(y + 1, y + size / 2, y + size - 1)
        g2.drawPolygon(xs, ys, 3)
        g2.dispose()
    }
}

private class SolidPlayIcon(private val size: Int = 10) : Icon {
    override fun getIconWidth() = size
    override fun getIconHeight() = size
    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = Color.WHITE
        val xs = intArrayOf(x + 1, x + size - 1, x + 1)
        val ys = intArrayOf(y + 1, y + size / 2, y + size - 1)
        g2.fillPolygon(xs, ys, 3)
        g2.dispose()
    }
}

private class TrashCanIcon(private val size: Int = 12) : Icon {
    override fun getIconWidth() = size
    override fun getIconHeight() = size
    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = Color.WHITE
        g2.stroke = BasicStroke(1.3f)
        g2.drawLine(x + 2, y + 3, x + size - 2, y + 3)
        g2.drawLine(x + 4, y + 1, x + size - 4, y + 1)
        g2.drawRect(x + 3, y + 3, size - 6, size - 5)
        g2.drawLine(x + 5, y + 5, x + 5, y + size - 3)
        g2.drawLine(x + size - 5, y + 5, x + size - 5, y + size - 3)
        g2.dispose()
    }
}

private class ChevronIcon(private val down: Boolean, private val color: Color = Color.WHITE, private val size: Int = 10) : Icon {
    override fun getIconWidth() = size
    override fun getIconHeight() = size
    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = color
        g2.stroke = BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        if (down) {
            g2.drawLine(x + 1, y + 3, x + size / 2, y + size - 3)
            g2.drawLine(x + size / 2, y + size - 3, x + size - 1, y + 3)
        } else {
            g2.drawLine(x + 3, y + 1, x + size - 3, y + size / 2)
            g2.drawLine(x + size - 3, y + size / 2, x + 3, y + size - 1)
        }
        g2.dispose()
    }
}

private class PlusIcon(private val size: Int = 12) : Icon {
    override fun getIconWidth() = size
    override fun getIconHeight() = size
    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = Color.WHITE
        g2.stroke = BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        val mid = size / 2
        g2.drawLine(x + mid, y + 1, x + mid, y + size - 1)
        g2.drawLine(x + 1, y + mid, x + size - 1, y + mid)
        g2.dispose()
    }
}

private class RoundedBadgeLabel(text: String) : JLabel(text) {
    var badgeBg: Color = Color(0x33, 0x33, 0x37)
    var badgeBorder: Color? = null
    var arc: Int = 8
    init {
        isOpaque = false
        border = JBUI.Borders.empty(3, 9)
    }
    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = badgeBg
        g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
        badgeBorder?.let {
            g2.color = it
            g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
        }
        g2.dispose()
        super.paintComponent(g)
    }
}

private class PillPanel : JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)) {
    init {
        isOpaque = false
        border = JBUI.Borders.empty(2, 8, 2, 10)
    }
    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = Color(0x2B, 0x2F, 0x33)
        g2.fillRoundRect(0, 0, width, height, 18, 18)
        g2.dispose()
        super.paintComponent(g)
    }
}

private class SlidersIcon(private val size: Int = 13) : Icon {
    override fun getIconWidth() = size
    override fun getIconHeight() = size
    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = Color.WHITE
        g2.stroke = BasicStroke(1.3f)
        val ys = intArrayOf(y + 3, y + 7, y + 11)
        val knobs = intArrayOf(x + 4, x + 9, x + 5)
        for (i in 0..2) {
            g2.drawLine(x + 1, ys[i], x + size - 1, ys[i])
            g2.fillRect(knobs[i] - 1, ys[i] - 2, 3, 5)
        }
        g2.dispose()
    }
}

class LionCPHPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private var activeCppFile: Path? = null
    private var hasProblem: Boolean = false
    private var currentProblem: ProblemData = ProblemData()
    private lateinit var bottomBar: JPanel
    private val currentResults: MutableMap<String, TestResult> = ConcurrentHashMap()
    private var compileError: String = ""
    private var settings: CphSettings = CphSettings()
    private val collapsedMap: MutableMap<String, Boolean> = ConcurrentHashMap()
    private var checkerExpanded = false

    @Volatile private var isRunning = false
    private val cancelFlag = AtomicBoolean(false)
    private val activeProcesses: MutableSet<Process> = ConcurrentHashMap.newKeySet()
    private lateinit var runAllBtn: FlatCphButton

    private val saveDebounceTimer = Timer(350) { saveActiveProblem() }.apply { isRepeats = false }

    private val subtitleLabel = JLabel("CPH Judge: Results").apply {
        foreground = CphColors.MUTED
        font = Font("Segoe UI", Font.PLAIN, 11)
    }
    private val problemTitleLabel = JLabel("No C++ File Open").apply {
        foreground = CphColors.TITLE
        font = Font("Segoe UI", Font.BOLD, 15)
    }
    private val passedBadgeLabel = RoundedBadgeLabel("0 / 0 passed").apply {
        foreground = Color(0xD4, 0xD4, 0xD4)
        font = Font("Segoe UI", Font.PLAIN, 11)
    }

    private val contentStack = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = CphColors.BG
        border = JBUI.Borders.empty(10, 10, 14, 10)
    }

    private val scrollWrapper = JPanel(BorderLayout()).apply {
        background = CphColors.BG
        add(contentStack, BorderLayout.NORTH)
    }

    private val scrollPane = JBScrollPane(scrollWrapper).apply {
        border = null
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBar.unitIncrement = 16
        viewport.background = CphColors.BG
    }

    init {
        background = CphColors.BG
        project.basePath?.let { settings = CphStorage.loadSettings(Path.of(it)) }

        add(scrollPane, BorderLayout.CENTER)
        bottomBar = buildBottomBar()
        add(bottomBar, BorderLayout.SOUTH)

        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    val file = event.newFile
                    if (file == null) {
                        flushPendingSave()
                        activeCppFile = null
                        hasProblem = false
                        currentResults.clear()
                        collapsedMap.clear()
                        compileError = ""
                        renderUi()
                        return
                    }
                    if (isCppFile(file.extension)) {
                        flushPendingSave()
                        loadCppFile(Path.of(file.path))
                    }
                }
            }
        )

        startCompanionListener(settings.companionPort)
        syncActiveEditorFile()
    }

    private fun isCppFile(ext: String?): Boolean {
        return ext.equals("cpp", true) || ext.equals("cc", true) || ext.equals("cxx", true)
    }

    fun rebindCompanionCallbacks() {
        CompanionServerService.rebind(
            rootProvider = { project.basePath?.let { Path.of(it) } }
        ) { cppFile, _ ->
            ApplicationManager.getApplication().invokeLater {
                project.basePath?.let { root ->
                    CMakeSyncService.triggerCMakeReload(Path.of(root), project)
                }
                val vFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(cppFile)
                if (vFile != null) {
                    FileEditorManager.getInstance(project).openFile(vFile, true)
                }
                loadCppFile(cppFile)
            }
        }
    }

    private fun startCompanionListener(port: Int) {
        CompanionServerService.start(
            primaryPort = port,
            rootProvider = { project.basePath?.let { Path.of(it) } }
        ) { cppFile, _ ->
            ApplicationManager.getApplication().invokeLater {
                project.basePath?.let { root ->
                    CMakeSyncService.triggerCMakeReload(Path.of(root), project)
                }
                val vFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(cppFile)
                if (vFile != null) {
                    FileEditorManager.getInstance(project).openFile(vFile, true)
                }
                loadCppFile(cppFile)
            }
        }
    }

    private fun syncActiveEditorFile() {
        ApplicationManager.getApplication().invokeLater {
            val file = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
            if (file != null && isCppFile(file.extension)) {
                loadCppFile(Path.of(file.path))
            } else {
                renderUi()
            }
        }
    }

    private fun loadCppFile(cppFile: Path) {
        val root = project.basePath?.let { Path.of(it) } ?: return
        activeCppFile = cppFile
        val loaded = CphStorage.loadProblemOrNull(root, cppFile)
        if (loaded != null) {
            hasProblem = true
            currentProblem = loaded
        } else {
            hasProblem = false
            currentProblem = ProblemData()
        }
        currentResults.clear()
        collapsedMap.clear()
        compileError = ""
        renderUi()
    }

    private fun scheduleSave() {
        saveDebounceTimer.restart()
    }

    private fun flushPendingSave() {
        if (saveDebounceTimer.isRunning) {
            saveDebounceTimer.stop()
            saveActiveProblem()
        }
    }

    private fun saveActiveProblem() {
        if (!hasProblem) return
        val root = project.basePath?.let { Path.of(it) } ?: return
        activeCppFile?.let { CphStorage.saveProblem(root, it, currentProblem) }
    }

    private fun setRunningState(running: Boolean) {
        isRunning = running
        if (running) {
            runAllBtn.text = "Stop"
            runAllBtn.baseColor = CphColors.RED
            runAllBtn.icon = null
        } else {
            runAllBtn.text = "Run All"
            runAllBtn.baseColor = CphColors.BLUE
            runAllBtn.icon = PlayTriangleIcon()
        }
        runAllBtn.repaint()
    }

    private fun stopRunningTests() {
        cancelFlag.set(true)
        activeProcesses.forEach { proc ->
            runCatching {
                proc.descendants().forEach { it.destroyForcibly() }
                proc.destroyForcibly()
            }
        }
        activeProcesses.clear()
        setRunningState(false)
        renderUi()
    }

    private fun renderUi(scrollToBottom: Boolean = false) {
        val savedScroll = scrollPane.verticalScrollBar.value
        contentStack.removeAll()

        val headerWrapper = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = CphColors.BG
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 50)
        }
        subtitleLabel.alignmentX = Component.LEFT_ALIGNMENT
        headerWrapper.add(subtitleLabel)
        headerWrapper.add(Box.createVerticalStrut(4))

        val titleRow = JPanel(BorderLayout(8, 0)).apply {
            background = CphColors.BG
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 28)
        }
        val tests = currentProblem.tests
        val passedCount = tests.count { currentResults[it.id]?.status == "AC" }
        problemTitleLabel.text = if (activeCppFile == null) {
            "No C++ File Open"
        } else {
            currentProblem.name.ifBlank { "Local: ${activeCppFile!!.fileName}" }
        }
        passedBadgeLabel.text = if (activeCppFile == null) "0 / 0 passed" else "$passedCount / ${tests.size} passed"
        if (tests.isNotEmpty() && passedCount == tests.size) {
            passedBadgeLabel.foreground = Color(0x7E, 0xD9, 0x87)
            passedBadgeLabel.badgeBg = Color(0x25, 0x33, 0x2A)
            passedBadgeLabel.badgeBorder = Color(0x52, 0xA8, 0x5E)
        } else {
            passedBadgeLabel.foreground = Color(0xD4, 0xD4, 0xD4)
            passedBadgeLabel.badgeBg = CphColors.BORDER
            passedBadgeLabel.badgeBorder = null
        }

        titleRow.add(problemTitleLabel, BorderLayout.CENTER)
        titleRow.add(passedBadgeLabel, BorderLayout.EAST)
        headerWrapper.add(titleRow)
        contentStack.add(headerWrapper)
        contentStack.add(Box.createVerticalStrut(10))

        if (activeCppFile == null || !hasProblem) {
            bottomBar.isVisible = false
            // Replace header with clean subtitle-only CPH empty screen
            contentStack.removeAll()
            subtitleLabel.alignmentX = Component.LEFT_ALIGNMENT
            contentStack.add(subtitleLabel)
            contentStack.add(Box.createVerticalStrut(24))

            val msgText = if (activeCppFile == null) {
                "<html><div style='text-align:center;color:#cccccc;width:240px;'>" +
                    "Open a <b>.cpp</b> file in CLion or import a problem with<br/><b>Competitive Companion</b>." +
                    "</div></html>"
            } else {
                "<html><div style='text-align:center;color:#cccccc;width:240px;'>" +
                    "This document does not have a CPH problem<br/>associated with it." +
                    "</div></html>"
            }
            val msgWrapper = JPanel(FlowLayout(FlowLayout.CENTER, 0, 0)).apply {
                background = CphColors.BG
                alignmentX = Component.LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, 54)
                add(JLabel(msgText))
            }
            contentStack.add(msgWrapper)
            contentStack.add(Box.createVerticalStrut(16))

            if (activeCppFile != null) {
                contentStack.add(fullWidthButton("+ Create Problem", CphColors.BLUE) {
                    val root = project.basePath?.let { Path.of(it) } ?: return@fullWidthButton
                    val cpp = activeCppFile ?: return@fullWidthButton
                    currentProblem = CphStorage.createLocalProblem(root, cpp)
                    hasProblem = true
                    currentResults.clear()
                    compileError = ""
                    renderUi()
                })
                contentStack.add(Box.createVerticalStrut(6))
            }

            contentStack.add(fullWidthButton("? How to use this extension", CphColors.GREEN) {
                JOptionPane.showMessageDialog(
                    this,
                    "LionCPH Quick Start:\n\n" +
                        "1. Click '+ Create Problem' to add manual testcases for any .cpp file.\n" +
                        "2. Or click the green (+) Competitive Companion icon in Chrome on Codeforces/AtCoder.\n" +
                        "3. Press Ctrl + Alt + B or 'Run All' to compile and judge all testcases.\n" +
                        "4. Click 'Submit' to send your solution to Codeforces.",
                    "How to use LionCPH",
                    JOptionPane.INFORMATION_MESSAGE
                )
            })

            refreshStack(0, false)
            return
        }
        bottomBar.isVisible = true

        if (compileError.isNotBlank()) {
            val cePanel = JPanel(BorderLayout(4, 4)).apply {
                background = CphColors.CE_BG
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(CphColors.CE_BORDER),
                    JBUI.Borders.empty(8)
                )
                alignmentX = Component.LEFT_ALIGNMENT
            }
            val ceTitle = JLabel("Compilation Error").apply {
                foreground = Color(0xFF, 0x6B, 0x6B)
                font = Font("Segoe UI", Font.BOLD, 12)
            }
            val ceArea = JTextArea(compileError).apply {
                isEditable = false
                background = CphColors.CE_BG
                foreground = Color(0xF4, 0xB8, 0xBC)
                font = Font("JetBrains Mono", Font.PLAIN, 11)
                lineWrap = true
                wrapStyleWord = true
            }
            cePanel.add(ceTitle, BorderLayout.NORTH)
            cePanel.add(ceArea, BorderLayout.CENTER)
            contentStack.add(cePanel)
            contentStack.add(Box.createVerticalStrut(8))
        }

        tests.forEachIndexed { idx, tc ->
            contentStack.add(buildTestCard(idx, tc))
            contentStack.add(Box.createVerticalStrut(8))
        }

        if (currentProblem.url.isNotBlank()) {
            val topActionRow = JPanel(GridLayout(1, 2, 6, 0)).apply {
                background = CphColors.BG
                alignmentX = Component.LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, 32)
                preferredSize = Dimension(200, 32)
            }
            topActionRow.add(FlatCphButton("New Testcase", CphColors.GREEN, icon = PlusIcon()).apply {
                addActionListener {
                    currentProblem.tests.add(TestCase(input = "", output = ""))
                    saveActiveProblem()
                    renderUi(scrollToBottom = true)
                }
            })
            topActionRow.add(FlatCphButton("Submit", CphColors.BLUE, icon = AllIcons.Actions.Upload).apply {
                addActionListener { handleSmartSubmit() }
            })
            contentStack.add(topActionRow)
        } else {
            contentStack.add(fullWidthButton("New Testcase", CphColors.GREEN, PlusIcon()) {
                currentProblem.tests.add(TestCase(input = "", output = ""))
                saveActiveProblem()
                renderUi(scrollToBottom = true)
            })
        }
        contentStack.add(Box.createVerticalStrut(6))

        val checkerBtnText = if (currentProblem.useCustomChecker) "Custom Checker (Enabled)" else "Custom Checker"
        contentStack.add(fullWidthButton(checkerBtnText, CphColors.BLUE, ChevronIcon(!checkerExpanded, Color.WHITE)) {
            checkerExpanded = !checkerExpanded
            renderUi()
        })

        if (checkerExpanded) {
            contentStack.add(Box.createVerticalStrut(4))
            contentStack.add(buildCheckerBox())
        }
        contentStack.add(Box.createVerticalStrut(6))

        val grid = JPanel(GridLayout(2, 3, 5, 5)).apply {
            background = CphColors.BG
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 60)
        }
        grid.add(darkGridButton("Settings", AllIcons.General.Settings) { openSettingsDialog() })
        grid.add(darkGridButton("Import", AllIcons.Actions.Download) { openImportDialog() })
        grid.add(darkGridButton("Problem", AllIcons.General.Web) { handleProblemButton() })
        grid.add(darkGridButton("About", AllIcons.General.Information) { showAboutDialog() })
        grid.add(darkGridButton("Shortcuts", AllIcons.Actions.Help) {
            JOptionPane.showMessageDialog(
                this,
                "Ctrl + Alt + B : Run All Testcases\n\nYou can customize this shortcut in:\nSettings > Keymap > Plugins > LionCPH",
                "LionCPH Shortcuts",
                JOptionPane.INFORMATION_MESSAGE
            )
        })
        grid.add(darkGridButton("Ports", AllIcons.Nodes.Plugin) { showPortsStatusDialog() })
        contentStack.add(grid)
        contentStack.add(Box.createVerticalStrut(10))

        val ojPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            background = CphColors.BG
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 32)
        }
        val ojPill = PillPanel()
        val ojCheck = JCheckBox("Set ONLINE_JUDGE", settings.setOnlineJudge).apply {
            isOpaque = false
            foreground = Color(0xB8, 0xBF, 0xC6)
            font = Font("Segoe UI", Font.PLAIN, 12)
            isFocusPainted = false
            toolTipText = if (settings.setOnlineJudge) {
                "ON: Compiles with -DONLINE_JUDGE (matches Codeforces; skips #ifndef ONLINE_JUDGE debug/freopen)"
            } else {
                "OFF: Compiles with -DLOCAL (runs #ifndef ONLINE_JUDGE debug code and prints cerr to Stderr)"
            }
            addActionListener {
                settings.setOnlineJudge = isSelected
                JudgeService.clearCompileCache()
                project.basePath?.let { root ->
                    val rootPath = Path.of(root)
                    CphStorage.saveSettings(rootPath, settings)
                    CMakeSyncService.syncOnlineJudgeMacro(rootPath, isSelected, project)
                }
                renderUi()
            }
        }
        ojPill.add(ojCheck)
        ojPanel.add(ojPill)
        contentStack.add(ojPanel)

        refreshStack(savedScroll, scrollToBottom)
    }

    private fun buildTestCard(idx: Int, tc: TestCase): JPanel {
        val res = currentResults[tc.id]
        val status = res?.status ?: "IDLE"
        val isCollapsed = collapsedMap[tc.id] ?: false

        val accentColor = when (status) {
            "AC" -> Color(0x14, 0xB8, 0x14)
            "WA", "RE", "CE" -> CphColors.RED_TEXT
            "TLE" -> CphColors.AMBER
            else -> CphColors.BORDER
        }

        val card = JPanel(BorderLayout()).apply {
            background = CphColors.SURFACE
            border = BorderFactory.createMatteBorder(0, 4, 0, 0, accentColor)
            alignmentX = Component.LEFT_ALIGNMENT
        }

        val header = JPanel(BorderLayout()).apply {
            background = CphColors.SURFACE
            border = JBUI.Borders.empty(6, 8)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }

        val leftInfo = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            background = CphColors.SURFACE
        }
        leftInfo.add(JLabel(ChevronIcon(!isCollapsed, CphColors.TC_BLUE)))
        leftInfo.add(JLabel("TC ${idx + 1}").apply {
            foreground = CphColors.TC_BLUE
            font = Font("Segoe UI", Font.BOLD, 13)
        })

        val statusLabelText = when (status) {
            "AC" -> "Passed"
            "WA" -> "Failed"
            "TLE" -> "TLE"
            "RE" -> "Runtime Error"
            "CE" -> "Compile Error"
            "RUNNING" -> "Running..."
            else -> ""
        }
        if (statusLabelText.isNotEmpty()) {
            val stColor = when (status) {
                "AC" -> CphColors.GREEN_TEXT
                "TLE" -> Color(0xE5, 0xA9, 0x3C)
                "RUNNING" -> Color(0x75, 0xBE, 0xFF)
                else -> CphColors.RED_TEXT
            }
            leftInfo.add(JLabel(statusLabelText).apply {
                foreground = stColor
                font = Font("Segoe UI", Font.BOLD, 12)
            })
        }
        if (res != null && status != "IDLE" && status != "RUNNING" && status != "CE") {
            leftInfo.add(RoundedBadgeLabel("${res.timeMs}ms").apply {
                foreground = Color(0xD8, 0xD8, 0xD8)
                badgeBg = Color(0x3E, 0x3D, 0x32)
                arc = 6
                font = Font("Segoe UI", Font.PLAIN, 10)
                border = JBUI.Borders.empty(2, 6)
            })
        }

        val actions = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            background = CphColors.SURFACE
        }
        actions.add(smallIconButton(PlayTriangleIcon(11), CphColors.GREEN) { runSingleTest(tc.id) })
        actions.add(smallIconButton(TrashCanIcon(), CphColors.RED) {
            currentProblem.tests.removeAll { it.id == tc.id }
            currentResults.remove(tc.id)
            saveActiveProblem()
            renderUi()
        })

        header.add(leftInfo, BorderLayout.CENTER)
        header.add(actions, BorderLayout.EAST)
        header.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                collapsedMap[tc.id] = !isCollapsed
                renderUi()
            }
        })
        card.add(header, BorderLayout.NORTH)

        if (!isCollapsed) {
            val body = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                background = CphColors.SURFACE
                border = JBUI.Borders.empty(2, 8, 10, 8)
            }

            body.add(ioHeaderRow("Input:", "Copy") { lbl ->
                CopyPasteManager.getInstance().setContents(StringSelection(tc.input))
                flashCopied(lbl)
            })
            body.add(ioTextArea(tc.input, editable = true) { newText ->
                tc.input = newText
                scheduleSave()
            })

            if (!currentProblem.useCustomChecker) {
                body.add(Box.createVerticalStrut(6))
                body.add(ioHeaderRow("Expected Output:", "Copy") { lbl ->
                    CopyPasteManager.getInstance().setContents(StringSelection(tc.output))
                    flashCopied(lbl)
                })
                body.add(ioTextArea(tc.output, editable = true) { newText ->
                    tc.output = newText
                    scheduleSave()
                })
            }

            if (res != null && status != "IDLE" && status != "RUNNING") {
                body.add(Box.createVerticalStrut(6))
                val recHeader = JPanel(BorderLayout()).apply {
                    background = CphColors.SURFACE
                    alignmentX = Component.LEFT_ALIGNMENT
                    maximumSize = Dimension(Int.MAX_VALUE, 20)
                }
                recHeader.add(JLabel("Received Output:").apply {
                    foreground = Color(0xBB, 0xBB, 0xBB)
                    font = Font("Segoe UI", Font.PLAIN, 12)
                }, BorderLayout.WEST)

                val rightLinks = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
                    background = CphColors.SURFACE
                }
                if (!currentProblem.useCustomChecker) {
                    rightLinks.add(linkLabel("Set") {
                        tc.output = res.stdout
                        saveActiveProblem()
                        runSingleTest(tc.id)
                    })
                }
                rightLinks.add(linkLabel("Copy") { lbl ->
                    CopyPasteManager.getInstance().setContents(StringSelection(res.stdout))
                    flashCopied(lbl)
                })
                recHeader.add(rightLinks, BorderLayout.EAST)
                body.add(recHeader)
                body.add(Box.createVerticalStrut(2))
                body.add(ioTextArea(res.stdout, editable = false) {})

                if (res.diffHint.isNotBlank()) {
                    body.add(Box.createVerticalStrut(4))
                    body.add(JLabel(res.diffHint).apply {
                        foreground = Color(0xF5, 0xA9, 0x7F)
                        font = Font("JetBrains Mono", Font.PLAIN, 11)
                        alignmentX = Component.LEFT_ALIGNMENT
                    })
                }
                if (res.checkerLog.isNotBlank()) {
                    body.add(Box.createVerticalStrut(6))
                    body.add(ioHeaderRow("Checker Log:", null) {})
                    body.add(ioTextArea(res.checkerLog, editable = false) {})
                }
                if (res.stderr.isNotBlank()) {
                    body.add(Box.createVerticalStrut(6))
                    body.add(ioHeaderRow("Stderr:", null, CphColors.RED_TEXT) {})
                    body.add(ioTextArea(res.stderr, editable = false) {})
                }
            }

            card.add(body, BorderLayout.CENTER)
        }

        card.maximumSize = Dimension(Int.MAX_VALUE, card.preferredSize.height)
        return card
    }

    private fun buildCheckerBox(): JPanel {
        val box = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = CphColors.SURFACE
            border = JBUI.Borders.empty(8)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        val check = JCheckBox("Enable Python Custom Checker", currentProblem.useCustomChecker).apply {
            background = CphColors.SURFACE
            foreground = CphColors.TEXT
            alignmentX = Component.LEFT_ALIGNMENT
        }
        val pathRow = JPanel(BorderLayout(4, 0)).apply {
            background = CphColors.SURFACE
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 28)
        }
        val pathField = JTextField(currentProblem.customCheckerPath).apply {
            background = CphColors.INPUT_BG
            foreground = Color.WHITE
            caretColor = Color.WHITE
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(CphColors.BORDER),
                JBUI.Borders.empty(4, 6)
            )
        }
        val browseBtn = FlatCphButton("Browse", CphColors.BTN_DARK, CphColors.BTN_DARK_BORDER).apply {
            font = Font("Segoe UI", Font.PLAIN, 11)
            preferredSize = Dimension(64, 26)
            addActionListener {
                val chooser = JFileChooser(project.basePath)
                chooser.fileFilter = FileNameExtensionFilter("Python Scripts (*.py)", "py")
                if (chooser.showOpenDialog(this@LionCPHPanel) == JFileChooser.APPROVE_OPTION) {
                    pathField.text = chooser.selectedFile.absolutePath
                }
            }
        }
        pathRow.add(pathField, BorderLayout.CENTER)
        pathRow.add(browseBtn, BorderLayout.EAST)

        val btnRow = JPanel(GridLayout(1, 2, 5, 0)).apply {
            background = CphColors.SURFACE
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 28)
        }
        val applyBtn = FlatCphButton("Apply Checker", CphColors.BLUE).apply {
            font = Font("Segoe UI", Font.BOLD, 11)
            addActionListener {
                currentProblem.useCustomChecker = check.isSelected
                currentProblem.customCheckerPath = pathField.text.trim()
                saveActiveProblem()
                renderUi()
            }
        }
        val sampleBtn = FlatCphButton("Create Sample", CphColors.BTN_DARK, CphColors.BTN_DARK_BORDER).apply {
            font = Font("Segoe UI", Font.PLAIN, 11)
            addActionListener {
                val root = project.basePath?.let { Path.of(it) } ?: return@addActionListener
                val sampleFile = root.resolve(".cph").resolve("checker.py")
                Files.createDirectories(sampleFile.parent)
                if (!Files.exists(sampleFile)) {
                    Files.writeString(
                        sampleFile,
                        """
                        import sys
                        # Usage: checker.py <input_file> <expected_file> <received_file>
                        # Exit 0 = Passed, Non-zero = Failed
                        inp = open(sys.argv[1]).read().strip()
                        exp = open(sys.argv[2]).read().strip()
                        got = open(sys.argv[3]).read().strip()
                        if got == exp:
                            sys.exit(0)
                        print(f"Expected {exp!r}, got {got!r}")
                        sys.exit(1)
                        """.trimIndent() + "\n",
                        StandardCharsets.UTF_8
                    )
                }
                pathField.text = sampleFile.toAbsolutePath().toString()
                check.isSelected = true
                currentProblem.useCustomChecker = true
                currentProblem.customCheckerPath = pathField.text
                saveActiveProblem()
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(sampleFile)?.let {
                    FileEditorManager.getInstance(project).openFile(it, true)
                }
                renderUi()
            }
        }
        btnRow.add(applyBtn)
        btnRow.add(sampleBtn)

        box.add(check)
        box.add(Box.createVerticalStrut(5))
        box.add(pathRow)
        box.add(Box.createVerticalStrut(6))
        box.add(btnRow)
        return box
    }

    private fun buildBottomBar(): JPanel {
        val bar = JPanel(BorderLayout(6, 0)).apply {
            background = CphColors.BG
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, CphColors.BORDER),
                JBUI.Borders.empty(8, 10)
            )
        }
        runAllBtn = FlatCphButton("Run All", CphColors.BLUE, icon = PlayTriangleIcon()).apply {
            preferredSize = Dimension(120, 32)
            addActionListener {
                if (isRunning) stopRunningTests() else runAllTests()
            }
        }
        val rightBtns = JPanel(FlowLayout(FlowLayout.RIGHT, 5, 0)).apply {
            background = CphColors.BG
        }
        val delAllBtn = smallIconButton(TrashCanIcon(13), CphColors.RED, 34, 32) {
            val cppFile = activeCppFile ?: return@smallIconButton
            val root = project.basePath?.let { Path.of(it) } ?: return@smallIconButton
            saveDebounceTimer.stop()
            if (isRunning) stopRunningTests()
            CphStorage.deleteProblem(root, cppFile)
            JudgeService.clearCompileCache()
            hasProblem = false
            currentProblem = ProblemData()
            currentResults.clear()
            compileError = ""
            collapsedMap.clear()
            renderUi()
        }.apply {
            toolTipText = "Delete problem testcases (keeps your .cpp file)"
        }
        val setBtn = smallIconButton(SlidersIcon(), CphColors.AMBER, 34, 32) { openSettingsDialog() }
        rightBtns.add(delAllBtn)
        rightBtns.add(setBtn)

        bar.add(runAllBtn, BorderLayout.CENTER)
        bar.add(rightBtns, BorderLayout.EAST)
        return bar
    }

    private fun flashCopied(label: JLabel) {
        val prev = label.text
        label.text = "Copied!"
        label.foreground = CphColors.GREEN_TEXT
        Timer(900) {
            label.text = prev
            label.foreground = CphColors.MUTED
        }.apply { isRepeats = false }.start()
    }

    private fun ioHeaderRow(
        title: String,
        actionText: String?,
        titleColor: Color = Color(0xBB, 0xBB, 0xBB),
        onAction: (JLabel) -> Unit
    ): JPanel {
        val row = JPanel(BorderLayout()).apply {
            background = CphColors.SURFACE
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 20)
        }
        row.add(JLabel(title).apply {
            foreground = titleColor
            font = Font("Segoe UI", Font.PLAIN, 12)
        }, BorderLayout.WEST)
        if (actionText != null) {
            row.add(linkLabel(actionText, onAction), BorderLayout.EAST)
        }
        return row
    }

    private fun linkLabel(text: String, onClick: (JLabel) -> Unit): JLabel {
        return JLabel(text).apply {
            foreground = CphColors.MUTED
            font = Font("Segoe UI", Font.PLAIN, 11)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = onClick(this@apply)
                override fun mouseEntered(e: MouseEvent) { foreground = Color.WHITE }
                override fun mouseExited(e: MouseEvent) { foreground = CphColors.MUTED }
            })
        }
    }

    private fun ioTextArea(initialText: String, editable: Boolean, onChange: (String) -> Unit): JScrollPane {
        val area = JTextArea(initialText).apply {
            isEditable = editable
            background = CphColors.INPUT_BG
            foreground = Color(0xE0, 0xE0, 0xE0)
            caretColor = Color.WHITE
            font = Font("JetBrains Mono", Font.PLAIN, 12)
            rows = (initialText.lines().size).coerceIn(2, 6)
            border = JBUI.Borders.empty(5, 7)
        }
        if (editable) {
            area.document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent?) = onChange(area.text)
                override fun removeUpdate(e: DocumentEvent?) = onChange(area.text)
                override fun changedUpdate(e: DocumentEvent?) = onChange(area.text)
            })
        }
        return JBScrollPane(area).apply {
            border = BorderFactory.createLineBorder(CphColors.BORDER)
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 140)
        }
    }

    private fun fullWidthButton(text: String, bg: Color, icon: Icon? = null, onClick: () -> Unit): JButton {
        return FlatCphButton(text, bg, icon = icon).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 32)
            preferredSize = Dimension(200, 32)
            addActionListener { onClick() }
        }
    }

    private fun darkGridButton(text: String, icon: Icon, onClick: () -> Unit): JButton {
        return FlatCphButton(text, CphColors.BTN_DARK, CphColors.BTN_DARK_BORDER, icon).apply {
            font = Font("Segoe UI", Font.PLAIN, 11)
            foreground = Color(0xE0, 0xE0, 0xE0)
            iconTextGap = 4
            margin = Insets(0, 3, 0, 3)
            preferredSize = Dimension(80, 28)
            addActionListener { onClick() }
        }
    }

    private fun smallIconButton(icon: Icon, bg: Color, w: Int = 28, h: Int = 22, onClick: () -> Unit): JButton {
        return FlatCphButton("", bg, icon = icon).apply {
            preferredSize = Dimension(w, h)
            margin = Insets(0, 0, 0, 0)
            addActionListener { onClick() }
        }
    }

    private fun handleProblemButton() {
        if (currentProblem.url.isNotBlank()) {
            BrowserUtil.browse(currentProblem.url)
        } else {
            val entered = JOptionPane.showInputDialog(
                this,
                "No URL attached to this local file.\nPaste a Codeforces/Online Judge problem URL to enable Problem & Submit:",
                "Attach Problem URL",
                JOptionPane.PLAIN_MESSAGE
            )
            if (!entered.isNullOrBlank()) {
                currentProblem.url = entered.trim()
                saveActiveProblem()
                renderUi()
            }
        }
    }

    private fun showAboutDialog() {
        val detected = JudgeService.resolveCompiler(settings.compilerPath)
        val ports = CompanionServerService.activePorts().joinToString(", ").ifBlank { "None" }
        JOptionPane.showMessageDialog(
            this,
            "LionCPH v1.5.3\nCompetitive Programming Helper for CLion\n\n" +
                "Active Compiler: $detected\n" +
                "Companion Ports: $ports",
            "About LionCPH",
            JOptionPane.INFORMATION_MESSAGE
        )
    }

    private fun showPortsStatusDialog() {
        val active = CompanionServerService.activePorts()
        val submitExt = if (CompanionServerService.isCphSubmitExtensionActive()) "Connected" else "Not detected (Direct Browser mode)"
        val msg = buildString {
            appendLine("Competitive Companion Listener:")
            for (p in linkedSetOf(settings.companionPort, 27121, 10045)) {
                val state = if (p in active) "Listening" else "Port Busy / Unavailable"
                appendLine("  - Port $p: $state")
            }
            appendLine()
            append("cph-submit Extension: $submitExt")
        }
        JOptionPane.showMessageDialog(this, msg, "LionCPH Live Port Status", JOptionPane.INFORMATION_MESSAGE)
    }

    private fun openSettingsDialog() {
        val detectedCompiler = JudgeService.resolveCompiler(settings.compilerPath)
        val compilerField = JTextField(settings.compilerPath, 24)
        val argsField = JTextField(settings.compilerArgs, 24)
        val timeField = JTextField(currentProblem.timeLimit.toString(), 24)
        val defaultTimeField = JTextField(settings.defaultTimeLimitMs.toString(), 24)
        val pythonField = JTextField(settings.pythonPath, 24)
        val portField = JTextField(settings.companionPort.toString(), 24)
        val trimBox = JCheckBox("Ignore trailing whitespace", settings.ignoreTrailingSpaces)
        val autoCMakeBox = JCheckBox("Auto-sync imported files with CMakeLists.txt", settings.autoSyncCMake)

        val cfLangOptions = arrayOf(
            "Auto (Match CLion / CMake)",
            "91 - GNU G++23 (64 bit)",
            "89 - GNU G++20 (64 bit)",
            "73 - GNU G++17 (64 bit)",
            "54 - GNU G++17 (32 bit)"
        )
        val cfLangCombo = JComboBox(cfLangOptions).apply {
            selectedIndex = when (settings.cfLanguageOverride) {
                "91" -> 1
                "89" -> 2
                "73" -> 3
                "54" -> 4
                else -> 0
            }
        }
        val templateArea = JTextArea(settings.cppTemplate, 5, 24).apply {
            font = Font("JetBrains Mono", Font.PLAIN, 11)
        }

        val form = JPanel(GridBagLayout())
        val c = GridBagConstraints().apply {
            insets = Insets(3, 4, 3, 4)
            fill = GridBagConstraints.HORIZONTAL
        }
        fun row(lbl: String, comp: Component, y: Int) {
            c.gridx = 0; c.gridy = y; c.weightx = 0.0
            form.add(JLabel(lbl), c)
            c.gridx = 1; c.weightx = 1.0
            form.add(comp, c)
        }
        row("Detected Compiler:", JLabel(detectedCompiler).apply { foreground = CphColors.MUTED }, 0)
        row("Compiler (auto / g++ / path):", compilerField, 1)
        row("Compiler Flags:", argsField, 2)
        row("This Problem Time Limit (ms):", timeField, 3)
        row("Default Time Limit (ms):", defaultTimeField, 4)
        row("Codeforces Language:", cfLangCombo, 5)
        row("Python Path:", pythonField, 6)
        row("Companion Primary Port:", portField, 7)
        c.gridx = 1; c.gridy = 8; form.add(trimBox, c)
        c.gridx = 1; c.gridy = 9; form.add(autoCMakeBox, c)
        row("C++ Template:", JBScrollPane(templateArea), 10)

        if (JOptionPane.showConfirmDialog(this, form, "LionCPH Settings", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            settings.compilerPath = compilerField.text.trim().ifBlank { "auto" }
            settings.compilerArgs = argsField.text.trim()
            settings.defaultTimeLimitMs = defaultTimeField.text.trim().toLongOrNull()?.coerceAtLeast(100L) ?: 2000L
            currentProblem.timeLimit = timeField.text.trim().toLongOrNull()?.coerceAtLeast(100L) ?: settings.defaultTimeLimitMs
            settings.cfLanguageOverride = when (cfLangCombo.selectedIndex) {
                1 -> "91"
                2 -> "89"
                3 -> "73"
                4 -> "54"
                else -> "auto"
            }
            settings.pythonPath = pythonField.text.trim().ifBlank { "python3" }
            settings.ignoreTrailingSpaces = trimBox.isSelected
            settings.autoSyncCMake = autoCMakeBox.isSelected
            settings.cppTemplate = templateArea.text
            val newPort = portField.text.trim().toIntOrNull() ?: 27121
            val portChanged = newPort != settings.companionPort
            settings.companionPort = newPort
            JudgeService.clearCompileCache()
            project.basePath?.let { CphStorage.saveSettings(Path.of(it), settings) }
            saveActiveProblem()
            if (portChanged) startCompanionListener(newPort)
            renderUi()
        }
    }

    private fun openImportDialog() {
        val root = project.basePath?.let { Path.of(it) } ?: return
        val area = JTextArea(8, 32)
        val res = JOptionPane.showConfirmDialog(
            this,
            JBScrollPane(area),
            "Paste Competitive Companion JSON",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE
        )
        if (res == JOptionPane.OK_OPTION && area.text.isNotBlank()) {
            val imported = CompanionServerService.importFromJson(root, area.text)
            if (imported != null) {
                CMakeSyncService.triggerCMakeReload(root, project)
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(imported.first)?.let {
                    FileEditorManager.getInstance(project).openFile(it, true)
                }
                loadCppFile(imported.first)
            } else {
                JOptionPane.showMessageDialog(this, "Invalid JSON format.", "LionCPH", JOptionPane.ERROR_MESSAGE)
            }
        }
    }

    /**
     * Dual-mode Smart Codeforces Submit:
     * - Detects exact problem & matched CLion C++ language
     * - Requires explicit human / non-AI confirmation
     * - Auto-sends via cph-submit if Chrome extension is active, or copies code & opens direct Submit URL otherwise
     */
    private fun handleSmartSubmit() {
        val root = project.basePath?.let { Path.of(it) } ?: return
        val cppFile = activeCppFile ?: return
        saveAllDocuments()

        val code = runCatching { Files.readString(cppFile, StandardCharsets.UTF_8) }.getOrDefault("")
        if (code.isBlank()) {
            JOptionPane.showMessageDialog(this, "Source file is empty.", "LionCPH Submit", JOptionPane.WARNING_MESSAGE)
            return
        }

        val cfRef = CodeforcesSubmitService.parseCodeforcesUrl(currentProblem.url)
        val matchedLang = CodeforcesSubmitService.resolveCodeforcesLanguage(root, settings)
        val extActive = CompanionServerService.isCphSubmitExtensionActive()

        val totalTests = currentProblem.tests.size
        val passedTests = currentProblem.tests.count { currentResults[it.id]?.status == "AC" }
        val testStatus = when {
            totalTests == 0 -> "No local testcases"
            passedTests == totalTests -> "All $passedTests / $totalTests local tests PASSED"
            else -> "Warning: Only $passedTests / $totalTests local tests passed"
        }

        val langItems = CodeforcesSubmitService.SUPPORTED_LANGUAGES.map { it.displayName }.toTypedArray()
        val langCombo = JComboBox(langItems).apply {
            val idx = CodeforcesSubmitService.SUPPORTED_LANGUAGES.indexOfFirst { it.id == matchedLang.id }
            if (idx >= 0) selectedIndex = idx
        }
        val humanCheck = JCheckBox("I confirm this solution is written by me (human, not AI-generated).")

        val modeLabel = if (extActive) {
            "Mode: Auto-Submit via cph-submit Chrome extension (Connected)"
        } else {
            "Mode: Direct Codeforces Submit page + Code copied to clipboard"
        }

        val panel = JPanel(GridBagLayout())
        val c = GridBagConstraints().apply {
            insets = Insets(4, 4, 4, 4)
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
            gridwidth = 2
            gridx = 0
        }
        c.gridy = 0; panel.add(JLabel("Problem: ${cfRef?.displayLabel ?: currentProblem.name}").apply { font = Font("Segoe UI", Font.BOLD, 12) }, c)
        c.gridy = 1; panel.add(JLabel("Tests: $testStatus").apply {
            foreground = if (totalTests > 0 && passedTests == totalTests) CphColors.GREEN_TEXT else CphColors.AMBER
        }, c)
        c.gridy = 2; c.gridwidth = 1; c.weightx = 0.0; panel.add(JLabel("Language:"), c)
        c.gridx = 1; c.weightx = 1.0; panel.add(langCombo, c)
        c.gridx = 0; c.gridy = 3; c.gridwidth = 2; panel.add(JLabel(modeLabel).apply { foreground = CphColors.MUTED; font = Font("Segoe UI", Font.PLAIN, 11) }, c)
        c.gridy = 4; panel.add(humanCheck, c)

        val confirm = JOptionPane.showConfirmDialog(
            this,
            panel,
            "LionCPH — Verify & Submit to Codeforces",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE
        )
        if (confirm != JOptionPane.OK_OPTION) return

        if (!humanCheck.isSelected) {
            JOptionPane.showMessageDialog(
                this,
                "Submission blocked: please check the human verification box to confirm the solution is your own.",
                "Verification Required",
                JOptionPane.WARNING_MESSAGE
            )
            return
        }

        val chosenLang = CodeforcesSubmitService.SUPPORTED_LANGUAGES[langCombo.selectedIndex]
        CopyPasteManager.getInstance().setContents(StringSelection(code))
        CompanionServerService.pendingSubmit = PendingSubmit(
            problemName = currentProblem.name,
            url = currentProblem.url,
            sourceCode = code,
            languageId = chosenLang.id
        )

        // If cph-submit is NOT polling, open the exact direct Submit URL in browser right away
        if (!extActive) {
            val targetUrl = cfRef?.directSubmitUrl ?: currentProblem.url
            if (targetUrl.isNotBlank()) {
                BrowserUtil.browse(targetUrl)
            }
        }
    }

    fun runAllTests() {
        val root = project.basePath?.let { Path.of(it) } ?: return
        val cppFile = activeCppFile ?: return
        if (!hasProblem) {
            currentProblem = CphStorage.createLocalProblem(root, cppFile)
            hasProblem = true
            renderUi()
            return
        }
        if (isRunning) return

        flushPendingSave()
        saveAllDocuments()

        val testsSnapshot = currentProblem.tests.toList()
        if (testsSnapshot.isEmpty()) return

        cancelFlag.set(false)
        setRunningState(true)
        compileError = ""
        testsSnapshot.forEach { tc -> currentResults[tc.id] = TestResult(tc.id, "RUNNING") }
        renderUi()

        ApplicationManager.getApplication().executeOnPooledThread {
            val compileOutcome = JudgeService.compile(root, cppFile, settings)
            if (cancelFlag.get()) {
                ApplicationManager.getApplication().invokeLater { setRunningState(false); renderUi() }
                return@executeOnPooledThread
            }
            if (!compileOutcome.success || compileOutcome.binaryPath == null) {
                ApplicationManager.getApplication().invokeLater {
                    setRunningState(false)
                    if (activeCppFile == cppFile) {
                        compileError = compileOutcome.stderr
                        testsSnapshot.forEach { tc -> currentResults[tc.id] = TestResult(tc.id, "CE") }
                        renderUi()
                    }
                }
                return@executeOnPooledThread
            }

            val poolSize = minOf(4, testsSnapshot.size).coerceAtLeast(1)
            val pool = Executors.newFixedThreadPool(poolSize)
            val remaining = AtomicInteger(testsSnapshot.size)

            testsSnapshot.forEach { tc ->
                pool.submit {
                    try {
                        val result = JudgeService.runTestCase(
                            compileOutcome.binaryPath,
                            tc,
                            currentProblem,
                            settings,
                            cancelFlag,
                            activeProcesses
                        )
                        if (activeCppFile == cppFile) {
                            currentResults[tc.id] = result
                            collapsedMap[tc.id] = (result.status == "AC")
                        }
                    } finally {
                        val left = remaining.decrementAndGet()
                        ApplicationManager.getApplication().invokeLater {
                            if (left == 0) setRunningState(false)
                            if (activeCppFile == cppFile) renderUi()
                        }
                    }
                }
            }
            pool.shutdown()
        }
    }

    private fun runSingleTest(testId: String) {
        val root = project.basePath?.let { Path.of(it) } ?: return
        val cppFile = activeCppFile ?: return
        if (isRunning) return
        val tc = currentProblem.tests.find { it.id == testId } ?: return
        flushPendingSave()
        saveAllDocuments()

        cancelFlag.set(false)
        setRunningState(true)
        compileError = ""
        currentResults[tc.id] = TestResult(tc.id, "RUNNING")
        renderUi()

        ApplicationManager.getApplication().executeOnPooledThread {
            val compileOutcome = JudgeService.compile(root, cppFile, settings)
            if (cancelFlag.get()) {
                ApplicationManager.getApplication().invokeLater { setRunningState(false); renderUi() }
                return@executeOnPooledThread
            }
            if (!compileOutcome.success || compileOutcome.binaryPath == null) {
                ApplicationManager.getApplication().invokeLater {
                    setRunningState(false)
                    if (activeCppFile == cppFile) {
                        compileError = compileOutcome.stderr
                        currentResults[tc.id] = TestResult(tc.id, "CE")
                        renderUi()
                    }
                }
                return@executeOnPooledThread
            }

            val result = JudgeService.runTestCase(
                compileOutcome.binaryPath,
                tc,
                currentProblem,
                settings,
                cancelFlag,
                activeProcesses
            )
            ApplicationManager.getApplication().invokeLater {
                setRunningState(false)
                if (activeCppFile == cppFile) {
                    currentResults[tc.id] = result
                    collapsedMap[tc.id] = (result.status == "AC")
                    renderUi()
                }
            }
        }
    }

    private fun saveAllDocuments() {
        ApplicationManager.getApplication().invokeAndWait {
            FileDocumentManager.getInstance().saveAllDocuments()
        }
    }

    private fun refreshStack(savedScroll: Int, scrollToBottom: Boolean) {
        contentStack.revalidate()
        contentStack.repaint()
        SwingUtilities.invokeLater {
            val bar = scrollPane.verticalScrollBar
            bar.value = if (scrollToBottom) bar.maximum else savedScroll.coerceAtMost(bar.maximum)
        }
    }

    override fun dispose() {
        saveDebounceTimer.stop()
        flushPendingSave()
        stopRunningTests()
        LionCPHToolWindowFactory.panels.remove(project)
        val remaining = LionCPHToolWindowFactory.panels.values.firstOrNull()
        if (remaining == null) {
            CompanionServerService.stop()
        } else {
            remaining.rebindCompanionCallbacks()
        }
    }
}
