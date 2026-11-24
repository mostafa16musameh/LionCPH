package com.lioncph.plugin.ui

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
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
import com.lioncph.plugin.models.CpSnippet
import com.lioncph.plugin.models.CphSettings
import com.lioncph.plugin.models.CphStorage
import com.lioncph.plugin.models.ProblemData
import com.lioncph.plugin.models.TestCase
import com.lioncph.plugin.models.TestResult
import com.lioncph.plugin.services.CMakeSyncService
import com.lioncph.plugin.services.CodeforcesSubmitService
import com.lioncph.plugin.services.CodeforcesVerdictService
import com.lioncph.plugin.services.CfProblemNavInfo
import com.lioncph.plugin.services.CfProblemRef
import com.lioncph.plugin.services.LiveVerdictUpdate
import com.lioncph.plugin.services.CompanionServerService
import com.lioncph.plugin.services.FolderOrganizerService
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
import javax.swing.JEditorPane
import javax.swing.JFileChooser
import javax.swing.DefaultListModel
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JSplitPane
import javax.swing.ListSelectionModel
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
import javax.swing.event.HyperlinkEvent
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
    val BG = Color(0x1B, 0x1D, 0x21)
    val SURFACE = Color(0x23, 0x26, 0x2C)
    val INPUT_BG = Color(0x16, 0x18, 0x1C)
    val BORDER = Color(0x2A, 0x2E, 0x37)
    val TEXT = Color(0xD4, 0xD8, 0xE0)
    val MUTED = Color(0x88, 0x91, 0xA0)
    val TITLE = Color(0xF5, 0xF7, 0xFA)
    val TC_BLUE = Color(0x58, 0xA6, 0xFF)
    val GREEN = Color(0x3E, 0x8E, 0x41)
    val GREEN_TEXT = Color(0x7E, 0xE7, 0x87)
    val RED = Color(0xC9, 0x37, 0x42)
    val RED_TEXT = Color(0xFF, 0x7B, 0x72)
    val BLUE = Color(0x1F, 0x6F, 0xEB)
    val AMBER = Color(0xD2, 0x99, 0x22)
    val PURPLE = Color(0x7C, 0x5C, 0xDC)
    val BTN_DARK = Color(0x26, 0x2A, 0x31)
    val BTN_DARK_BORDER = Color(0x33, 0x38, 0x43)
    val CE_BG = Color(0x36, 0x18, 0x1C)
    val CE_BORDER = Color(0x8E, 0x2D, 0x36)
}

private class PlaceholderTextField(
    initialText: String,
    private val placeholder: String
) : JTextField(initialText) {
    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        if (text.isEmpty() && !isFocusOwner) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.color = Color(0x6E, 0x76, 0x84)
            g2.font = font
            val fm = g2.fontMetrics
            val y = (height - fm.height) / 2 + fm.ascent
            g2.drawString(placeholder, insets.left + 2, y)
            g2.dispose()
        }
    }
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
        g2.fillRoundRect(0, 0, width, height, 8, 8)
        if (borderColor != null) {
            g2.color = borderColor
            g2.drawRoundRect(0, 0, width - 1, height - 1, 8, 8)
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

private class ChevronLeftIcon(private val color: Color = Color.WHITE, private val size: Int = 10) : Icon {
    override fun getIconWidth() = size
    override fun getIconHeight() = size
    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = color
        g2.stroke = BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g2.drawLine(x + size - 3, y + 1, x + 3, y + size / 2)
        g2.drawLine(x + 3, y + size / 2, x + size - 3, y + size - 1)
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

private class RoundedCardPanel(
    private val bgColor: Color = CphColors.SURFACE,
    private val borderColor: Color = CphColors.BORDER,
    private val arc: Int = 10
) : JPanel(BorderLayout(6, 0)) {
    init {
        isOpaque = false
        border = JBUI.Borders.empty(6, 8)
    }
    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = bgColor
        g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
        g2.color = borderColor
        g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
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

private class BugIcon(private val size: Int = 12) : Icon {
    override fun getIconWidth() = size
    override fun getIconHeight() = size
    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = Color.WHITE
        g2.stroke = BasicStroke(1.25f)
        g2.drawOval(x + 3, y + 3, size - 6, size - 4)
        g2.drawArc(x + 4, y + 1, size - 8, 4, 0, 180)
        g2.drawLine(x + 1, y + 5, x + 3, y + 6)
        g2.drawLine(x + 1, y + 9, x + 3, y + 8)
        g2.drawLine(x + size - 1, y + 5, x + size - 3, y + 6)
        g2.drawLine(x + size - 1, y + 9, x + size - 3, y + 8)
        g2.dispose()
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
    private var liveVerdict: LiveVerdictUpdate? = null
    private var currentCfRef: CfProblemRef? = null
    private var currentCfNav: CfProblemNavInfo? = null
    private val openedProblemUrls: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private var settings: CphSettings = CphSettings()
    private val collapsedMap: MutableMap<String, Boolean> = ConcurrentHashMap()
    private val deletedProblemBackups: MutableMap<Path, ProblemData> = ConcurrentHashMap()
    private var checkerExpanded = false
    private var judgeOptionsExpanded = false

    @Volatile private var isRunning = false
    private val cancelFlag = AtomicBoolean(false)
    private val activeProcesses: MutableSet<Process> = ConcurrentHashMap.newKeySet()
    private lateinit var runAllBtn: FlatCphButton

    private val saveDebounceTimer = Timer(350) { saveActiveProblem() }.apply { isRepeats = false }

    // Dual Timer: per-problem Stopwatch + Real-Time Synced Contest/Virtual/Sheet Countdown
    private val problemElapsedSec: MutableMap<Path, Long> = ConcurrentHashMap()
    private var stopwatchRunning = false
    private var contestEndEpochMs: Long = 0L
    private var contestPausedRemainingSec: Long = 0L
    private var contestRunning = false
    private var contestEndAlerted = false
    private var contestModeTag: String = "" // "LIVE", "VIRT", "STARTS", "CUSTOM"
    private var contestLabelName: String = ""
    private var syncedContestId: Int? = null

    private val stopwatchBtn = FlatCphButton("00:00", CphColors.BTN_DARK, CphColors.BTN_DARK_BORDER).apply {
        font = Font("JetBrains Mono", Font.BOLD, 10)
        foreground = CphColors.TEXT
        margin = Insets(0, 4, 0, 4)
        preferredSize = Dimension(62, 24)
        minimumSize = Dimension(48, 24)
        toolTipText = "Problem Stopwatch — click to Start / Pause (auto-pauses on AC)"
        addActionListener {
            stopwatchRunning = !stopwatchRunning
            refreshTimerBarLabels()
        }
    }
    private val stopwatchResetBtn = FlatCphButton("Reset", CphColors.BTN_DARK, CphColors.BTN_DARK_BORDER).apply {
        font = Font("Segoe UI", Font.PLAIN, 10)
        foreground = CphColors.MUTED
        margin = Insets(0, 4, 0, 4)
        preferredSize = Dimension(46, 24)
        minimumSize = Dimension(38, 24)
        toolTipText = "Reset stopwatch for current problem"
        addActionListener {
            activeCppFile?.let { problemElapsedSec[it] = 0L }
            stopwatchRunning = false
            refreshTimerBarLabels()
        }
    }
    private val contestTimerBtn = FlatCphButton("Set Timer", CphColors.BTN_DARK, CphColors.BTN_DARK_BORDER).apply {
        font = Font("JetBrains Mono", Font.BOLD, 10)
        foreground = Color(0x75, 0xBE, 0xFF)
        margin = Insets(0, 4, 0, 4)
        preferredSize = Dimension(96, 24)
        minimumSize = Dimension(72, 24)
        toolTipText = "Auto-syncs with live/virtual Codeforces contests — click to Sync, Set, Pause, or Clear"
        addActionListener { openContestTimerMenu() }
    }
    private val timerStrip = RoundedCardPanel(CphColors.SURFACE, CphColors.BORDER, arc = 8).apply {
        layout = GridBagLayout()
        border = JBUI.Borders.empty(4, 5)
        alignmentX = Component.LEFT_ALIGNMENT
        preferredSize = Dimension(200, 32)
        maximumSize = Dimension(Int.MAX_VALUE, 32)
        val tc = GridBagConstraints().apply {
            fill = GridBagConstraints.BOTH
            gridy = 0
            weighty = 1.0
        }
        tc.gridx = 0; tc.weightx = 0.32; tc.insets = Insets(0, 0, 0, 4)
        add(stopwatchBtn, tc)
        tc.gridx = 1; tc.weightx = 0.22; tc.insets = Insets(0, 0, 0, 4)
        add(stopwatchResetBtn, tc)
        tc.gridx = 2; tc.weightx = 0.46; tc.insets = Insets(0, 0, 0, 0)
        add(contestTimerBtn, tc)
    }
    private val uiTickTimer = Timer(1000) {
        var changed = false
        if (stopwatchRunning && activeCppFile != null) {
            val f = activeCppFile
            if (f != null) {
                problemElapsedSec[f] = (problemElapsedSec[f] ?: 0L) + 1L
                changed = true
            }
        }
        if (contestRunning && contestEndEpochMs > 0L) {
            val remSec = ((contestEndEpochMs - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
            changed = true
            if (remSec == 0L) {
                contestRunning = false
                contestEndEpochMs = 0L
                contestPausedRemainingSec = 0L
                if (!contestEndAlerted) {
                    contestEndAlerted = true
                    val msg = if (contestModeTag == "STARTS") {
                        "${contestLabelName.ifBlank { "Contest" }} has started!"
                    } else {
                        "Time's up! ${contestLabelName.ifBlank { "Contest" }} timer finished."
                    }
                    liveVerdict = LiveVerdictUpdate(msg, if (contestModeTag == "STARTS") "OK" else "FAIL", isFinal = true)
                    renderUi()
                }
            }
        }
        if (changed) refreshTimerBarLabels()
    }.apply {
        isRepeats = true
        start()
    }

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

    private val stickyTopPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = CphColors.BG
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, CphColors.BORDER),
            JBUI.Borders.empty(10, 10, 8, 10)
        )
    }

    private val contentStack = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = CphColors.BG
        border = JBUI.Borders.empty(8, 10, 10, 10)
    }

    private val stickyBottomControls = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = CphColors.BG
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, CphColors.BORDER),
            JBUI.Borders.empty(8, 10, 6, 10)
        )
    }

    private val stickyBottomDock = JPanel(BorderLayout()).apply {
        background = CphColors.BG
    }

    private val scrollWrapper = JPanel(BorderLayout()).apply {
        background = CphColors.BG
        add(contentStack, BorderLayout.NORTH)
    }

    private val scrollPane = JBScrollPane(scrollWrapper).apply {
        border = null
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBar.unitIncrement = 32
        verticalScrollBar.blockIncrement = 120
        viewport.background = CphColors.BG
    }

    init {
        background = CphColors.BG
        project.basePath?.let { settings = CphStorage.loadSettings(Path.of(it)) }

        add(stickyTopPanel, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
        bottomBar = buildBottomBar()
        stickyBottomDock.add(stickyBottomControls, BorderLayout.CENTER)
        stickyBottomDock.add(bottomBar, BorderLayout.SOUTH)
        add(stickyBottomDock, BorderLayout.SOUTH)

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
        refreshCfNavigation(cppFile)
        renderUi()
    }

    private fun refreshCfNavigation(targetFile: Path? = activeCppFile) {
        currentCfNav = null
        currentCfRef = if (hasProblem && currentProblem.url.isNotBlank()) {
            openedProblemUrls.add(currentProblem.url)
            CodeforcesSubmitService.parseCodeforcesUrl(currentProblem.url)
        } else null

        val ref = currentCfRef
        if (ref != null && ref.contestId != null && targetFile != null) {
            if (contestModeTag != "CUSTOM" && syncedContestId != ref.contestId) {
                syncContestTimerWithCodeforces(ref, showFeedback = false)
            }
            CodeforcesVerdictService.fetchNavigationInfo(ref, settings.cfApiKey, settings.cfApiSecret) { nav ->
                ApplicationManager.getApplication().invokeLater {
                    if (activeCppFile == targetFile) {
                        currentCfNav = nav
                        renderUi()
                    }
                }
            }
        }
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
        stickyTopPanel.removeAll()
        contentStack.removeAll()
        stickyBottomControls.removeAll()

        val headerWrapper = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = CphColors.BG
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 32)
        }

        val titleRow = JPanel(BorderLayout(8, 0)).apply {
            background = CphColors.BG
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 28)
        }
        val tests = currentProblem.tests
        val passedCount = tests.count { currentResults[it.id]?.status == "AC" }
        val currentFile = activeCppFile
        problemTitleLabel.text = if (currentFile == null) {
            "No C++ File Open"
        } else {
            currentProblem.name.ifBlank { "Local: ${currentFile.fileName}" }
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

        val headerRight = JPanel(FlowLayout(FlowLayout.RIGHT, 5, 0)).apply {
            isOpaque = false
        }
        if (activeCppFile != null && hasProblem && tests.isNotEmpty()) {
            val allCollapsed = tests.all { collapsedMap[it.id] == true }
            val toggleText = if (allCollapsed) "Expand All" else "Collapse All"
            headerRight.add(FlatCphButton(toggleText, CphColors.BTN_DARK, CphColors.BTN_DARK_BORDER).apply {
                font = Font("Segoe UI", Font.PLAIN, 10)
                foreground = CphColors.TEXT
                margin = Insets(0, 6, 0, 6)
                preferredSize = Dimension(74, 22)
                toolTipText = if (allCollapsed) "Expand all testcases" else "Collapse all testcases"
                addActionListener {
                    val targetCollapsed = !allCollapsed
                    tests.forEach { tc -> collapsedMap[tc.id] = targetCollapsed }
                    renderUi()
                }
            })
        }
        headerRight.add(passedBadgeLabel)
        val titleLeft = JPanel(BorderLayout(4, 0)).apply {
            isOpaque = false
            if (activeCppFile != null && hasProblem && currentProblem.url.isNotBlank()) {
                add(FlatCphButton("", CphColors.BTN_DARK, CphColors.BTN_DARK_BORDER, AllIcons.General.Web).apply {
                    preferredSize = Dimension(24, 22)
                    margin = Insets(0, 0, 0, 0)
                    toolTipText = "Open problem in browser"
                    addActionListener {
                        openedProblemUrls.add(currentProblem.url)
                        BrowserUtil.browse(currentProblem.url)
                    }
                }, BorderLayout.WEST)
            }
            add(problemTitleLabel, BorderLayout.CENTER)
        }
        titleRow.add(titleLeft, BorderLayout.CENTER)
        titleRow.add(headerRight, BorderLayout.EAST)
        headerWrapper.add(titleRow)
        val rootPathForCrumb = project.basePath?.let { Path.of(it) }
        val activeParent = activeCppFile?.parent
        if (rootPathForCrumb != null && activeParent != null && activeParent != rootPathForCrumb && activeParent.startsWith(rootPathForCrumb)) {
            val relFolder = rootPathForCrumb.relativize(activeParent).toString().replace('\\', '/')
            headerWrapper.add(Box.createVerticalStrut(3))
            val folderLabel = JLabel("Folder: $relFolder", AllIcons.Nodes.Folder, JLabel.LEFT).apply {
                foreground = CphColors.MUTED
                font = Font("Segoe UI", Font.PLAIN, 11)
                alignmentX = Component.LEFT_ALIGNMENT
            }
            headerWrapper.add(folderLabel)
            headerWrapper.maximumSize = Dimension(Int.MAX_VALUE, 52)
        }
        if (activeCppFile == null || !hasProblem) {
            stickyTopPanel.isVisible = false
            stickyTopPanel.revalidate()
            stickyTopPanel.repaint()
            stickyBottomDock.isVisible = false
            stickyBottomDock.revalidate()
            stickyBottomDock.repaint()
            bottomBar.isVisible = false
            // Clean subtitle-only CPH empty screen
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
                val backup = deletedProblemBackups[activeCppFile]
                if (backup != null) {
                    val restoreLabel = "Restore Deleted Problem (${backup.tests.size} testcases)"
                    contentStack.add(fullWidthButton(restoreLabel, CphColors.AMBER) {
                        val root = project.basePath?.let { Path.of(it) } ?: return@fullWidthButton
                        val cpp = activeCppFile ?: return@fullWidthButton
                        val restored = deletedProblemBackups.remove(cpp) ?: return@fullWidthButton
                        CphStorage.saveProblem(root, cpp, restored)
                        currentProblem = restored
                        hasProblem = true
                        currentResults.clear()
                        compileError = ""
                        refreshCfNavigation(cpp)
                        renderUi()
                    })
                    contentStack.add(Box.createVerticalStrut(6))
                }
                contentStack.add(fullWidthButton("+ Create Problem", CphColors.BLUE) {
                    val root = project.basePath?.let { Path.of(it) } ?: return@fullWidthButton
                    val cpp = activeCppFile ?: return@fullWidthButton
                    currentProblem = CphStorage.createLocalProblem(root, cpp)
                    hasProblem = true
                    currentResults.clear()
                    compileError = ""
                    refreshCfNavigation(cpp)
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
        stickyTopPanel.isVisible = true
        stickyTopPanel.add(headerWrapper)
        stickyTopPanel.add(Box.createVerticalStrut(6))
        refreshTimerBarLabels()
        stickyTopPanel.add(timerStrip)
        stickyBottomDock.isVisible = true
        bottomBar.isVisible = true

        val cfRefActive = currentCfRef
        if (cfRefActive != null && cfRefActive.problemBaseUrl.isNotBlank()) {
            val nav = currentCfNav
            val navBar = RoundedCardPanel(CphColors.SURFACE, CphColors.BORDER, arc = 10).apply {
                layout = GridLayout(1, 3, 5, 0)
                border = JBUI.Borders.empty(4, 5)
                alignmentX = Component.LEFT_ALIGNMENT
                preferredSize = Dimension(200, 34)
                maximumSize = Dimension(Int.MAX_VALUE, 34)
            }

            if (nav?.prevIndex != null) {
                navBar.add(FlatCphButton("Prev", CphColors.BTN_DARK, CphColors.BTN_DARK_BORDER, ChevronLeftIcon(CphColors.TEXT, 8)).apply {
                    font = Font("Segoe UI", Font.BOLD, 11)
                    margin = Insets(0, 4, 0, 4)
                    toolTipText = "Go to previous problem (${nav.prevIndex})"
                    addActionListener { navigateToCfProblem(cfRefActive, nav.prevIndex, null) }
                })
            } else {
                val posTxt = if (nav != null && nav.totalCount > 0) "${nav.currentPosition} / ${nav.totalCount}" else ""
                navBar.add(JLabel(posTxt, JLabel.CENTER).apply {
                    foreground = CphColors.MUTED
                    font = Font("Segoe UI", Font.BOLD, 11)
                })
            }

            val rankLabel = if (nav != null && nav.totalCount > 0 && nav.prevIndex != null) "Rank (${nav.currentPosition}/${nav.totalCount})" else "Standings"
            navBar.add(FlatCphButton(rankLabel, CphColors.BTN_DARK, CphColors.BTN_DARK_BORDER).apply {
                font = Font("Segoe UI", Font.PLAIN, 10)
                margin = Insets(0, 3, 0, 3)
                toolTipText = "Open Standings for this contest/sheet"
                addActionListener { BrowserUtil.browse(cfRefActive.standingsUrl) }
            })

            if (nav?.isLast == true) {
                navBar.add(RoundedBadgeLabel("Last").apply {
                    foreground = Color(0xF5, 0xC2, 0x6B)
                    badgeBg = Color(0x3B, 0x30, 0x1A)
                    badgeBorder = CphColors.AMBER
                    arc = 8
                    font = Font("Segoe UI", Font.BOLD, 11)
                    horizontalAlignment = JLabel.CENTER
                    toolTipText = "Reached the last problem in this sheet/contest"
                })
            } else if (nav?.nextIndex != null) {
                navBar.add(FlatCphButton("Next", CphColors.BLUE, icon = ChevronIcon(false, Color.WHITE, 8)).apply {
                    font = Font("Segoe UI", Font.BOLD, 11)
                    horizontalTextPosition = javax.swing.SwingConstants.LEADING
                    margin = Insets(0, 4, 0, 4)
                    toolTipText = "Go to next problem (${nav.nextIndex}${nav.nextName?.let { ": $it" } ?: ""})"
                    addActionListener { navigateToCfProblem(cfRefActive, nav.nextIndex, nav.nextName) }
                })
            } else {
                navBar.add(JPanel().apply { isOpaque = false })
            }
            stickyTopPanel.add(Box.createVerticalStrut(8))
            stickyTopPanel.add(navBar)
        }
        stickyTopPanel.revalidate()
        stickyTopPanel.repaint()

        liveVerdict?.let { lv ->
            val (bg, bd, fg) = when (lv.state) {
                "OK" -> Triple(Color(0x1E, 0x35, 0x24), Color(0x4E, 0x9A, 0x5A), CphColors.GREEN_TEXT)
                "FAIL" -> Triple(CphColors.CE_BG, CphColors.CE_BORDER, CphColors.RED_TEXT)
                "TESTING", "WAITING" -> Triple(Color(0x1E, 0x2B, 0x3A), Color(0x2E, 0x6B, 0x9E), Color(0x75, 0xBE, 0xFF))
                else -> Triple(CphColors.SURFACE, CphColors.BORDER, CphColors.TEXT)
            }
            val vPanel = JPanel(BorderLayout(6, 0)).apply {
                background = bg
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(bd),
                    JBUI.Borders.empty(6, 8)
                )
                alignmentX = Component.LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, 36)
            }
            vPanel.add(JLabel(lv.text).apply {
                foreground = fg
                font = Font("Segoe UI", Font.BOLD, 11)
            }, BorderLayout.CENTER)
            vPanel.add(linkLabel("x") {
                CodeforcesVerdictService.stopPolling()
                liveVerdict = null
                renderUi()
            }, BorderLayout.EAST)
            contentStack.add(vPanel)
            contentStack.add(Box.createVerticalStrut(8))
        }

        if (compileError.isNotBlank()) {
            val cePanel = JPanel(BorderLayout(4, 4)).apply {
                background = CphColors.CE_BG
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(CphColors.CE_BORDER),
                    JBUI.Borders.empty(8)
                )
                alignmentX = Component.LEFT_ALIGNMENT
            }
            val ceHeader = JPanel(BorderLayout()).apply { isOpaque = false }
            ceHeader.add(JLabel("Compilation Error").apply {
                foreground = Color(0xFF, 0x6B, 0x6B)
                font = Font("Segoe UI", Font.BOLD, 12)
            }, BorderLayout.WEST)
            ceHeader.add(linkLabel("Copy") { lbl ->
                CopyPasteManager.getInstance().setContents(StringSelection(compileError))
                flashCopied(lbl)
            }, BorderLayout.EAST)
            val ceArea = JTextArea(compileError).apply {
                isEditable = false
                background = CphColors.CE_BG
                foreground = Color(0xF4, 0xB8, 0xBC)
                font = Font("JetBrains Mono", Font.PLAIN, 11)
                lineWrap = true
                wrapStyleWord = true
            }
            val ceScroll = JBScrollPane(ceArea).apply {
                border = null
                viewport.background = CphColors.CE_BG
                preferredSize = Dimension(200, 120)
            }
            cePanel.add(ceHeader, BorderLayout.NORTH)
            cePanel.add(ceScroll, BorderLayout.CENTER)
            cePanel.maximumSize = Dimension(Int.MAX_VALUE, 165)
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
            stickyBottomControls.add(topActionRow)
        } else {
            stickyBottomControls.add(fullWidthButton("New Testcase", CphColors.GREEN, PlusIcon()) {
                currentProblem.tests.add(TestCase(input = "", output = ""))
                saveActiveProblem()
                renderUi(scrollToBottom = true)
            })
        }
        stickyBottomControls.add(Box.createVerticalStrut(6))

        // Calm collapsible Judge Options (ONLINE_JUDGE, Sanitizers, Float Tolerance, Custom Checker)
        val effTol = JudgeService.resolveEffectiveFloatTolerance(currentProblem, settings)
        val effTolText = JudgeService.formatTolerance(effTol)
        val summaryParts = mutableListOf<String>()
        summaryParts.add(if (settings.setOnlineJudge) "OJ" else "LOCAL")
        if (settings.enableSanitizers) summaryParts.add("San")
        if (effTol > 0.0) summaryParts.add(effTolText) else summaryParts.add("Exact")
        if (currentProblem.useCustomChecker) summaryParts.add("Checker")

        val optionsToggleBtn = FlatCphButton(
            "Judge Options  (${summaryParts.joinToString(" · ")})",
            CphColors.SURFACE,
            CphColors.BORDER,
            ChevronIcon(judgeOptionsExpanded, CphColors.MUTED)
        ).apply {
            font = Font("Segoe UI", Font.PLAIN, 11)
            foreground = if (settings.enableSanitizers || currentProblem.useCustomChecker || effTol > 0.0) CphColors.TEXT else CphColors.MUTED
            alignmentX = Component.LEFT_ALIGNMENT
            preferredSize = Dimension(200, 28)
            maximumSize = Dimension(Int.MAX_VALUE, 28)
            toolTipText = "Expand to configure ONLINE_JUDGE, Sanitizers, Float Tolerance & Custom Checker"
            addActionListener {
                judgeOptionsExpanded = !judgeOptionsExpanded
                renderUi()
            }
        }
        stickyBottomControls.add(optionsToggleBtn)

        if (judgeOptionsExpanded) {
            stickyBottomControls.add(Box.createVerticalStrut(5))
            val optionsCard = RoundedCardPanel(CphColors.SURFACE, CphColors.BORDER, arc = 8).apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = JBUI.Borders.empty(7, 7, 7, 7)
                alignmentX = Component.LEFT_ALIGNMENT
            }

            val togglesRow1 = JPanel(GridLayout(1, 2, 5, 0)).apply {
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                preferredSize = Dimension(200, 28)
                maximumSize = Dimension(Int.MAX_VALUE, 28)
            }
            val ojPill = PillPanel().apply {
                layout = FlowLayout(FlowLayout.LEFT, 4, 1)
            }
            val ojCheck = JCheckBox("ONLINE_JUDGE", settings.setOnlineJudge).apply {
                isOpaque = false
                foreground = Color(0xB8, 0xBF, 0xC6)
                font = Font("Segoe UI", Font.PLAIN, 11)
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
            togglesRow1.add(ojPill)

            val sanPill = PillPanel().apply {
                layout = FlowLayout(FlowLayout.LEFT, 4, 1)
            }
            val sanCheck = JCheckBox("Sanitizers", settings.enableSanitizers).apply {
                isOpaque = false
                foreground = if (settings.enableSanitizers) Color(0xF5, 0xC2, 0x6B) else Color(0xB8, 0xBF, 0xC6)
                font = Font("Segoe UI", Font.PLAIN, 11)
                isFocusPainted = false
                toolTipText = "Compile with AddressSanitizer/UBSan (or -D_GLIBCXX_DEBUG on MinGW) to catch out-of-bounds & overflow bugs"
                addActionListener {
                    settings.enableSanitizers = isSelected
                    JudgeService.clearCompileCache()
                    project.basePath?.let { CphStorage.saveSettings(Path.of(it), settings) }
                    renderUi()
                }
            }
            sanPill.add(sanCheck)
            togglesRow1.add(sanPill)
            optionsCard.add(togglesRow1)
            optionsCard.add(Box.createVerticalStrut(5))

            val floatLabel = when (settings.floatToleranceMode.lowercase()) {
                "exact" -> "Float Tolerance: Exact (Manual)"
                "custom" -> "Float Tolerance: $effTolText (Manual)"
                else -> "Float Tolerance: Auto ($effTolText)"
            }
            val floatBtn = FlatCphButton(floatLabel, CphColors.BTN_DARK, CphColors.BTN_DARK_BORDER).apply {
                font = Font("Segoe UI", Font.PLAIN, 11)
                foreground = if (effTol > 0.0) Color(0x75, 0xBE, 0xFF) else Color(0xB8, 0xBF, 0xC6)
                alignmentX = Component.LEFT_ALIGNMENT
                preferredSize = Dimension(200, 26)
                maximumSize = Dimension(Int.MAX_VALUE, 26)
                toolTipText = "Auto-detects float tolerance from problem statement & sample outputs — click to customize"
                addActionListener { openFloatToleranceDialog() }
            }
            optionsCard.add(floatBtn)
            optionsCard.add(Box.createVerticalStrut(5))

            val checkerEnabled = currentProblem.useCustomChecker
            val checkerBtnText = if (checkerEnabled) "Custom Checker (Enabled)" else "Custom Checker"
            val checkerBtn = FlatCphButton(
                checkerBtnText,
                if (checkerEnabled) CphColors.BLUE else CphColors.BTN_DARK,
                if (checkerEnabled) null else CphColors.BTN_DARK_BORDER,
                ChevronIcon(checkerExpanded, if (checkerEnabled) Color.WHITE else CphColors.MUTED)
            ).apply {
                font = Font("Segoe UI", if (checkerEnabled) Font.BOLD else Font.PLAIN, 11)
                foreground = if (checkerEnabled) Color.WHITE else CphColors.TEXT
                alignmentX = Component.LEFT_ALIGNMENT
                preferredSize = Dimension(200, 26)
                maximumSize = Dimension(Int.MAX_VALUE, 26)
                addActionListener {
                    checkerExpanded = !checkerExpanded
                    renderUi()
                }
            }
            optionsCard.add(checkerBtn)

            if (checkerExpanded) {
                optionsCard.add(Box.createVerticalStrut(5))
                optionsCard.add(buildCheckerBox())
            }
            optionsCard.maximumSize = Dimension(Int.MAX_VALUE, optionsCard.preferredSize.height + 8)
            stickyBottomControls.add(optionsCard)
        }
        stickyBottomControls.add(Box.createVerticalStrut(8))

        val grid = JPanel(GridLayout(3, 3, 5, 5)).apply {
            background = CphColors.BG
            alignmentX = Component.LEFT_ALIGNMENT
            preferredSize = Dimension(200, 92)
            maximumSize = Dimension(Int.MAX_VALUE, 94)
        }
        grid.add(darkGridButton("Subs", AllIcons.Actions.ListFiles) { showSubmissionsDialog() }.apply {
            toolTipText = "View your latest 15 Codeforces submissions inside CLion"
        })
        grid.add(darkGridButton("Contests", AllIcons.Actions.Profile) { showUpcomingContestsDialog() }.apply {
            toolTipText = "View upcoming Codeforces contests & countdown"
        })
        grid.add(darkGridButton("Snippets", AllIcons.Actions.Copy) { openSnippetsDialog() }.apply {
            toolTipText = "Insert C++ CP templates (DSU, SegTree, Sieve, Dijkstra...) at cursor"
        })
        grid.add(darkGridButton("Template", AllIcons.FileTypes.Text) { openTemplateEditorDialog() }.apply {
            toolTipText = "Edit the starter C++ code written to every new problem file"
        })
        grid.add(darkGridButton("Import", AllIcons.Actions.Download) { openImportDialog() }.apply {
            toolTipText = "Import testcases from .in/.out folder or Competitive Companion JSON"
        })
        grid.add(darkGridButton("Organize", AllIcons.Nodes.Folder) { handleQuickOrganize() }.apply {
            toolTipText = "Organize problem files into Groups / Contests / Problemset folders"
        })
        grid.add(darkGridButton("URL", AllIcons.Actions.Edit) { editProblemUrlDialog() }.apply {
            toolTipText = "Attach or edit the problem URL (enables Submit & Prev/Next)"
        })
        grid.add(darkGridButton("GitHub", AllIcons.Vcs.Branch) {
            BrowserUtil.browse("https://github.com/mostafa16musameh/LionCPH")
        }.apply {
            toolTipText = "Open LionCPH repository on GitHub"
        })
        grid.add(darkGridButton("Info", AllIcons.General.Information) { showInfoAndPortsDialog() }.apply {
            toolTipText = "Version, active compiler, Companion ports & shortcuts"
        })
        stickyBottomControls.add(grid)
        stickyBottomControls.add(Box.createVerticalStrut(8))

        val noteRow = RoundedCardPanel(CphColors.SURFACE, CphColors.BORDER, arc = 8).apply {
            border = JBUI.Borders.empty(4, 8)
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 30)
        }
        noteRow.add(JLabel("Note:").apply {
            foreground = CphColors.MUTED
            font = Font("Segoe UI", Font.PLAIN, 11)
            border = JBUI.Borders.emptyRight(6)
        }, BorderLayout.WEST)
        val noteField = PlaceholderTextField(currentProblem.note, "Add upsolving note or solution idea...").apply {
            isOpaque = false
            foreground = Color(0xE6, 0xEA, 0xF0)
            caretColor = Color.WHITE
            font = Font("Segoe UI", Font.PLAIN, 11)
            border = JBUI.Borders.empty(1, 2)
            toolTipText = "Quick upsolving note / solution idea (auto-saved with this problem)"
            document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent?) { currentProblem.note = text; scheduleSave() }
                override fun removeUpdate(e: DocumentEvent?) { currentProblem.note = text; scheduleSave() }
                override fun changedUpdate(e: DocumentEvent?) { currentProblem.note = text; scheduleSave() }
            })
        }
        noteRow.add(noteField, BorderLayout.CENTER)
        stickyBottomControls.add(noteRow)
        stickyBottomControls.revalidate()
        stickyBottomControls.repaint()
        stickyBottomDock.revalidate()
        stickyBottomDock.repaint()

        refreshStack(savedScroll, scrollToBottom)
    }

    private fun buildTestCard(idx: Int, tc: TestCase): JPanel {
        val res = currentResults[tc.id]
        val status = res?.status ?: "IDLE"
        val isCollapsed = collapsedMap[tc.id] ?: false

        val accentColor = when (status) {
            "AC" -> Color(0x14, 0xB8, 0x14)
            "WA", "RE", "CE" -> CphColors.RED_TEXT
            "TLE", "MLE" -> CphColors.AMBER
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
            "MLE" -> "MLE"
            "RE" -> "Runtime Error"
            "CE" -> "Compile Error"
            "RUNNING" -> "Running..."
            else -> ""
        }
        if (statusLabelText.isNotEmpty()) {
            val stColor = when (status) {
                "AC" -> CphColors.GREEN_TEXT
                "TLE", "MLE" -> Color(0xE5, 0xA9, 0x3C)
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
            if (res.memoryKb > 0) {
                val memText = if (res.memoryKb >= 1024) String.format("%.1fMB", res.memoryKb / 1024.0) else "${res.memoryKb}KB"
                leftInfo.add(RoundedBadgeLabel(memText).apply {
                    foreground = Color(0xC5, 0xD4, 0xE8)
                    badgeBg = Color(0x28, 0x33, 0x42)
                    arc = 6
                    font = Font("Segoe UI", Font.PLAIN, 10)
                    border = JBUI.Borders.empty(2, 6)
                })
            }
        }

        val actions = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            background = CphColors.SURFACE
        }
        actions.add(smallIconButton(BugIcon(12), CphColors.PURPLE) { debugTestCase(idx, tc) }.apply {
            toolTipText = "Debug TC ${idx + 1} in CLion Debugger (saves input to .cph/debug_input.txt & clipboard)"
        })
        actions.add(smallIconButton(PlayTriangleIcon(11), CphColors.GREEN) { runSingleTest(tc.id) }.apply {
            toolTipText = "Run TC ${idx + 1}"
        })
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
                if (!currentProblem.useCustomChecker && status == "WA") {
                    rightLinks.add(linkLabel("Diff") {
                        showVisualDiffDialog(idx + 1, tc.output, res.stdout)
                    }.apply { foreground = CphColors.TC_BLUE })
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
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(CphColors.BORDER),
                JBUI.Borders.empty(10, 10, 10, 10)
            )
            alignmentX = Component.LEFT_ALIGNMENT
        }
        val check = JCheckBox("Enable Python Custom Checker", currentProblem.useCustomChecker).apply {
            isOpaque = false
            foreground = CphColors.TEXT
            font = Font("Segoe UI", Font.PLAIN, 12)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        val pathRow = JPanel(BorderLayout(6, 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            preferredSize = Dimension(200, 28)
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
            preferredSize = Dimension(68, 28)
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

        val btnRow = JPanel(GridLayout(1, 2, 6, 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            preferredSize = Dimension(200, 30)
            maximumSize = Dimension(Int.MAX_VALUE, 30)
        }
        val applyBtn = FlatCphButton("Apply Checker", CphColors.BLUE).apply {
            font = Font("Segoe UI", Font.BOLD, 11)
            preferredSize = Dimension(100, 30)
            addActionListener {
                currentProblem.useCustomChecker = check.isSelected
                currentProblem.customCheckerPath = pathField.text.trim()
                saveActiveProblem()
                renderUi()
            }
        }
        val sampleBtn = FlatCphButton("Create Sample", CphColors.BTN_DARK, CphColors.BTN_DARK_BORDER).apply {
            font = Font("Segoe UI", Font.PLAIN, 11)
            preferredSize = Dimension(100, 30)
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
        box.add(Box.createVerticalStrut(6))
        box.add(pathRow)
        box.add(Box.createVerticalStrut(8))
        box.add(btnRow)
        box.maximumSize = Dimension(Int.MAX_VALUE, box.preferredSize.height)
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
            val choices = arrayOf("Clear Results Only", "Delete Problem & Testcases", "Cancel")
            val picked = JOptionPane.showOptionDialog(
                this,
                "Choose what to clear for '${currentProblem.name.ifBlank { cppFile.fileName.toString() }}':\n" +
                    "- Clear Results Only: resets run outputs & keeps all ${currentProblem.tests.size} testcase(s).\n" +
                    "- Delete Problem: removes testcases (keeps your .cpp file, and lets you Restore in 1 click).",
                "LionCPH — Clear / Delete",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.WARNING_MESSAGE,
                null,
                choices,
                choices[0]
            )
            when (picked) {
                0 -> {
                    if (isRunning) stopRunningTests()
                    currentResults.clear()
                    compileError = ""
                    liveVerdict = null
                    collapsedMap.clear()
                    renderUi()
                }
                1 -> {
                    saveDebounceTimer.stop()
                    if (isRunning) stopRunningTests()
                    deletedProblemBackups[cppFile] = currentProblem.copy(
                        tests = currentProblem.tests.map { it.copy() }.toMutableList()
                    )
                    CphStorage.deleteProblem(root, cppFile)
                    JudgeService.clearCompileCache()
                    hasProblem = false
                    currentProblem = ProblemData()
                    currentResults.clear()
                    compileError = ""
                    liveVerdict = null
                    collapsedMap.clear()
                    renderUi()
                }
            }
        }.apply {
            toolTipText = "Clear results or delete problem testcases (with 1-click Restore)"
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
            isWheelScrollingEnabled = false
            addMouseWheelListener { e -> scrollPane.dispatchEvent(SwingUtilities.convertMouseEvent(this, e, scrollPane)) }
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
            font = Font("Segoe UI", Font.PLAIN, 10)
            foreground = Color(0xE0, 0xE0, 0xE0)
            iconTextGap = 3
            margin = Insets(0, 2, 0, 2)
            preferredSize = Dimension(68, 28)
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

    private fun editProblemUrlDialog() {
        val entered = JOptionPane.showInputDialog(
            this,
            "Paste or edit the Codeforces / Online Judge problem URL\n(enables Submit, Open Problem, and Prev/Next navigation):",
            currentProblem.url
        ) ?: return
        currentProblem.url = entered.trim()
        saveActiveProblem()
        refreshCfNavigation(activeCppFile)
        renderUi()
    }

    private fun handleQuickOrganize() {
        val root = project.basePath?.let { Path.of(it) } ?: return
        var mode = FolderOrganizerService.effectiveMode(settings)
        if (mode == "flat") {
            val choices = arrayOf("Smart Hierarchy (Groups/Sheet, Contests, Problemset)", "Single Folder", "Cancel")
            val picked = JOptionPane.showOptionDialog(
                this,
                "Folder organization is currently set to Flat (Project Root).\nChoose a folder structure to enable & organize existing problem files:",
                "LionCPH Workspace Organizer",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                choices,
                choices[0]
            )
            mode = when (picked) {
                0 -> "hierarchy"
                1 -> "single"
                else -> return
            }
            settings.organizationMode = mode
            settings.organizeByContest = true
            CphStorage.saveSettings(root, settings)
        } else {
            val label = if (mode == "hierarchy") "Smart Hierarchy" else "Single Folder"
            val choices = arrayOf("Organize / Re-Sort All Now ($label)", "Rename a Group Folder...", "Cancel")
            val picked = JOptionPane.showOptionDialog(
                this,
                "Organize all LionCPH-tracked .cpp files (root + Groups/Contests/Problemset) cleanly and remove empty folders?\n(Your own non-CPH files like main.cpp will NOT be touched.)",
                "LionCPH Workspace Organizer",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                choices,
                choices[0]
            )
            if (picked == 1) {
                renameGroupFolderDialog(root)
                return
            }
            if (picked != 0) return
        }

        val moved = FolderOrganizerService.organizeExistingRootFiles(root, settings)
        LocalFileSystem.getInstance().refresh(true)
        if (moved > 0) {
            CMakeSyncService.triggerCMakeReload(root, project)
        }
        JOptionPane.showMessageDialog(
            this,
            if (moved > 0) "Organized $moved problem file(s) into clean Group/Sheet/Contest/Problemset folders and reloaded CMake." else "All LionCPH problem files are already in their clean folders.",
            "LionCPH Workspace Organizer",
            JOptionPane.INFORMATION_MESSAGE
        )
        renderUi()
    }

    private fun renameGroupFolderDialog(root: Path) {
        val groups = FolderOrganizerService.discoverProjectGroupIds(root)
        if (groups.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No Codeforces Group problems found in this workspace yet.", "Rename Group Folder", JOptionPane.INFORMATION_MESSAGE)
            return
        }
        val labels = groups.map { (id, name) -> "$id  ->  $name" }.toTypedArray()
        val selected = JOptionPane.showInputDialog(
            this,
            "Select a Codeforces Group to set its clean folder name:",
            "Rename Group Folder",
            JOptionPane.PLAIN_MESSAGE,
            null,
            labels,
            labels[0]
        ) as? String ?: return
        val idx = labels.indexOf(selected)
        if (idx !in groups.indices) return
        val (groupId, currentName) = groups[idx]
        val newName = JOptionPane.showInputDialog(
            this,
            "Enter clean folder name for Group '$groupId'\n(e.g., ICPC_Assiut_University_Community or PTUK_Training):",
            currentName
        ) ?: return
        if (newName.isBlank()) return

        FolderOrganizerService.saveGroupName(root, groupId, newName.trim())
        val moved = FolderOrganizerService.organizeExistingRootFiles(root, settings)
        LocalFileSystem.getInstance().refresh(true)
        if (moved > 0) {
            CMakeSyncService.triggerCMakeReload(root, project)
        }
        JOptionPane.showMessageDialog(
            this,
            "Saved Group '$groupId' as '${FolderOrganizerService.sanitizeSegment(newName, 40)}' and moved $moved file(s).",
            "Rename Group Folder",
            JOptionPane.INFORMATION_MESSAGE
        )
        renderUi()
    }

    private fun formatHms(totalSec: Long): String {
        val s = totalSec.coerceAtLeast(0L)
        val h = s / 3600L
        val m = (s % 3600L) / 60L
        val sec = s % 60L
        return if (h > 0L) "%02d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
    }

    private fun currentContestRemainingSec(): Long {
        return if (contestRunning && contestEndEpochMs > 0L) {
            ((contestEndEpochMs - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
        } else {
            contestPausedRemainingSec.coerceAtLeast(0L)
        }
    }

    private fun refreshTimerBarLabels() {
        val elapsed = activeCppFile?.let { problemElapsedSec[it] ?: 0L } ?: 0L
        stopwatchBtn.text = formatHms(elapsed)
        stopwatchBtn.foreground = if (stopwatchRunning) CphColors.GREEN_TEXT else CphColors.TEXT

        val canReset = elapsed > 0L || stopwatchRunning
        stopwatchResetBtn.isEnabled = canReset
        stopwatchResetBtn.foreground = when {
            stopwatchRunning -> CphColors.AMBER
            elapsed > 0L -> CphColors.TEXT
            else -> CphColors.MUTED
        }

        val rem = currentContestRemainingSec()
        if (rem > 0L) {
            val tag = when {
                !contestRunning -> "Paused"
                contestModeTag == "LIVE" -> "LIVE"
                contestModeTag == "VIRT" -> "VIRT"
                contestModeTag == "STARTS" -> "Starts"
                else -> "Left"
            }
            contestTimerBtn.text = "$tag ${formatHms(rem)}"
            contestTimerBtn.foreground = when {
                !contestRunning -> CphColors.MUTED
                contestModeTag == "STARTS" -> CphColors.GREEN_TEXT
                rem <= 300L -> CphColors.RED_TEXT
                rem <= 900L -> CphColors.AMBER
                else -> Color(0x75, 0xBE, 0xFF)
            }
            contestTimerBtn.toolTipText = "${contestLabelName.ifBlank { "Contest Timer" }} ($tag) — click to Sync, Pause, or Change"
        } else if (contestEndAlerted) {
            contestTimerBtn.text = "TIME UP!"
            contestTimerBtn.foreground = CphColors.RED_TEXT
        } else {
            contestTimerBtn.text = "Set Timer"
            contestTimerBtn.foreground = Color(0x75, 0xBE, 0xFF)
            contestTimerBtn.toolTipText = "Auto-syncs with live/virtual Codeforces contests — click to Sync or Set Timer"
        }
        timerStrip.revalidate()
        timerStrip.repaint()
    }

    private fun syncContestTimerWithCodeforces(cfRef: CfProblemRef, showFeedback: Boolean) {
        if (showFeedback) {
            contestTimerBtn.text = "Syncing..."
            timerStrip.revalidate()
        }
        CodeforcesVerdictService.fetchContestTimerSync(
            cfRef,
            settings.cfHandle,
            settings.cfApiKey,
            settings.cfApiSecret
        ) { sync ->
            ApplicationManager.getApplication().invokeLater {
                syncedContestId = sync.contestId
                when (sync.mode) {
                    "LIVE", "VIRTUAL", "BEFORE" -> {
                        contestEndEpochMs = sync.endEpochSec * 1000L
                        contestPausedRemainingSec = ((contestEndEpochMs - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
                        contestRunning = true
                        contestEndAlerted = false
                        contestLabelName = sync.contestName
                        contestModeTag = when (sync.mode) {
                            "VIRTUAL" -> "VIRT"
                            "BEFORE" -> "STARTS"
                            else -> "LIVE"
                        }
                        refreshTimerBarLabels()
                        if (showFeedback) {
                            liveVerdict = LiveVerdictUpdate("${sync.message}: ${sync.contestName}", "OK", isFinal = true)
                            renderUi()
                        }
                    }
                    else -> {
                        refreshTimerBarLabels()
                        if (showFeedback) {
                            val hint = if (settings.cfHandle.isBlank()) {
                                "${sync.message}\n\nTip: Set your Codeforces Handle in Settings to auto-sync active Virtual contests!"
                            } else {
                                "${sync.message}\n\nYou can still pick a manual duration (2h / 2.5h / 3h / 5h / Custom) from the Contest Timer menu."
                            }
                            JOptionPane.showMessageDialog(this, hint, "LionCPH Contest Timer Sync", JOptionPane.INFORMATION_MESSAGE)
                        }
                    }
                }
            }
        }
    }

    private fun openFloatToleranceDialog() {
        val options = arrayOf(
            "Auto (Detect from Problem & Samples)",
            "Exact (Off)",
            "1e-4 (0.0001)",
            "1e-6 (0.000001)",
            "1e-9 (0.000000001)",
            "Custom..."
        )
        val currentIdx = when (settings.floatToleranceMode.lowercase()) {
            "exact" -> 1
            "custom" -> when {
                kotlin.math.abs(settings.floatTolerance - 1e-4) < 1e-12 -> 2
                kotlin.math.abs(settings.floatTolerance - 1e-6) < 1e-12 -> 3
                kotlin.math.abs(settings.floatTolerance - 1e-9) < 1e-12 -> 4
                else -> 5
            }
            else -> 0
        }
        val choice = JOptionPane.showInputDialog(
            this,
            "Select floating-point output tolerance:\n(Auto detects precision from problem statement & decimal sample outputs)",
            "LionCPH — Float Tolerance",
            JOptionPane.PLAIN_MESSAGE,
            null,
            options,
            options[currentIdx]
        ) as? String ?: return

        when (choice) {
            options[0] -> {
                settings.floatToleranceMode = "auto"
                settings.floatTolerance = 0.0
            }
            options[1] -> {
                settings.floatToleranceMode = "exact"
                settings.floatTolerance = 0.0
            }
            options[2] -> {
                settings.floatToleranceMode = "custom"
                settings.floatTolerance = 1e-4
            }
            options[3] -> {
                settings.floatToleranceMode = "custom"
                settings.floatTolerance = 1e-6
            }
            options[4] -> {
                settings.floatToleranceMode = "custom"
                settings.floatTolerance = 1e-9
            }
            options[5] -> {
                val raw = JOptionPane.showInputDialog(
                    this,
                    "Enter custom tolerance (e.g. 1e-5, 1e-12, 0.01):",
                    if (settings.floatTolerance > 0.0) JudgeService.formatTolerance(settings.floatTolerance) else "1e-6"
                ) ?: return
                val parsed = raw.trim().toDoubleOrNull()
                if (parsed == null || !parsed.isFinite() || parsed <= 0.0) {
                    JOptionPane.showMessageDialog(this, "Invalid positive number. Example: 1e-6 or 0.001", "Float Tolerance", JOptionPane.WARNING_MESSAGE)
                    return
                }
                settings.floatToleranceMode = "custom"
                settings.floatTolerance = parsed
            }
        }
        project.basePath?.let { CphStorage.saveSettings(Path.of(it), settings) }
        renderUi()
    }

    private fun openContestTimerMenu() {
        val options = mutableListOf<String>()
        val cfRef = currentCfRef
        if (cfRef?.contestId != null) {
            options.add("Sync Live / Virtual CF Contest Now")
        }
        val rem = currentContestRemainingSec()
        if (rem > 0L) {
            options.add(if (contestRunning) "Pause Countdown" else "Resume Countdown")
            options.add("Clear Countdown")
        }
        options.add("2:00h (CF Div.2 / Div.3)")
        options.add("2:30h (CF Div.1 / Div.2)")
        options.add("3:00h (Practice Sheet)")
        options.add("5:00h (ICPC / Virtual)")
        options.add("Custom Minutes...")

        val choice = JOptionPane.showInputDialog(
            this,
            "Sync with live/virtual Codeforces contest or pick a manual countdown:",
            "LionCPH Contest Countdown",
            JOptionPane.PLAIN_MESSAGE,
            null,
            options.toTypedArray(),
            options.first()
        ) as? String ?: return

        when (choice) {
            "Sync Live / Virtual CF Contest Now" -> {
                if (cfRef != null) {
                    contestModeTag = ""
                    syncContestTimerWithCodeforces(cfRef, showFeedback = true)
                }
            }
            "Pause Countdown" -> {
                contestPausedRemainingSec = currentContestRemainingSec()
                contestRunning = false
                contestEndEpochMs = 0L
            }
            "Resume Countdown" -> {
                if (contestPausedRemainingSec > 0L) {
                    contestEndEpochMs = System.currentTimeMillis() + contestPausedRemainingSec * 1000L
                    contestRunning = true
                }
            }
            "Clear Countdown" -> {
                contestEndEpochMs = 0L
                contestPausedRemainingSec = 0L
                contestRunning = false
                contestEndAlerted = false
                contestModeTag = ""
                contestLabelName = ""
            }
            "2:00h (CF Div.2 / Div.3)" -> startContestCountdown(120, "CF Round")
            "2:30h (CF Div.1 / Div.2)" -> startContestCountdown(150, "CF Round")
            "3:00h (Practice Sheet)" -> startContestCountdown(180, "Practice Sheet")
            "5:00h (ICPC / Virtual)" -> startContestCountdown(300, "ICPC Virtual")
            "Custom Minutes..." -> {
                val raw = JOptionPane.showInputDialog(this, "Enter countdown duration in minutes (e.g., 90):", "120") ?: return
                val mins = raw.trim().toLongOrNull()
                if (mins != null && mins > 0L) {
                    startContestCountdown(mins, "Custom Timer")
                }
            }
        }
        refreshTimerBarLabels()
    }

    private fun startContestCountdown(minutes: Long, label: String) {
        contestPausedRemainingSec = minutes * 60L
        contestEndEpochMs = System.currentTimeMillis() + minutes * 60_000L
        contestRunning = true
        contestEndAlerted = false
        contestModeTag = "CUSTOM"
        contestLabelName = label
    }

    private fun showInfoAndPortsDialog() {
        val detected = JudgeService.resolveCompiler(settings.compilerPath)
        val active = CompanionServerService.activePorts()
        val submitExt = if (CompanionServerService.isCphSubmitExtensionActive()) "Connected" else "Not detected (Direct Browser mode)"
        val mode = FolderOrganizerService.effectiveMode(settings)
        val msg = buildString {
            appendLine("LionCPH v1.9.2 — Competitive Programming Helper for CLion")
            appendLine("By Mostafa Musameh  |  Shortcut: Ctrl + Alt + B (Run All)")
            appendLine("QA & Testing Contributor: Tariq Khalid (@Tariq4824) — Thank you!")
            appendLine()
            appendLine("Active Compiler: $detected")
            appendLine("Workspace Mode: $mode")
            appendLine()
            appendLine("Competitive Companion Listener:")
            for (p in linkedSetOf(settings.companionPort, 27121, 10045)) {
                val state = if (p in active) "Listening" else "Port Busy / Unavailable"
                appendLine("  - Port $p: $state")
            }
            appendLine("cph-submit Extension: $submitExt")
            appendLine()
            append("GitHub: https://github.com/mostafa16musameh/LionCPH")
        }
        JOptionPane.showMessageDialog(this, msg, "LionCPH — Info & Live Ports", JOptionPane.INFORMATION_MESSAGE)
    }

    private fun openSnippetsDialog() {
        val root = project.basePath?.let { Path.of(it) } ?: return
        val snippets = CphStorage.loadSnippets(root)
        val listModel = DefaultListModel<String>()
        snippets.forEach { listModel.addElement(it.name) }

        val snippetList = JList(listModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            background = CphColors.SURFACE
            foreground = CphColors.TEXT
            font = Font("Segoe UI", Font.PLAIN, 12)
        }
        val codeArea = JTextArea(16, 46).apply {
            background = CphColors.INPUT_BG
            foreground = Color(0xE6, 0xEA, 0xF0)
            caretColor = Color.WHITE
            font = Font("JetBrains Mono", Font.PLAIN, 12)
            border = JBUI.Borders.empty(6)
        }
        snippetList.addListSelectionListener {
            val idx = snippetList.selectedIndex
            if (idx in snippets.indices) {
                codeArea.text = snippets[idx].code
                codeArea.caretPosition = 0
            }
        }
        if (snippets.isNotEmpty()) snippetList.selectedIndex = 0

        val addBtn = FlatCphButton("+ New", CphColors.GREEN).apply {
            preferredSize = Dimension(72, 26)
            addActionListener {
                val title = JOptionPane.showInputDialog(this@LionCPHPanel, "Snippet name (e.g., Fenwick Tree):", "New Snippet", JOptionPane.PLAIN_MESSAGE)
                if (!title.isNullOrBlank()) {
                    snippets.add(CpSnippet(title.trim(), "// ${title.trim()}\n"))
                    listModel.addElement(title.trim())
                    snippetList.selectedIndex = snippets.lastIndex
                    CphStorage.saveSnippets(root, snippets)
                }
            }
        }
        val saveBtn = FlatCphButton("Save", CphColors.BTN_DARK, CphColors.BTN_DARK_BORDER).apply {
            preferredSize = Dimension(64, 26)
            addActionListener {
                val idx = snippetList.selectedIndex
                if (idx in snippets.indices) {
                    snippets[idx].code = codeArea.text
                    CphStorage.saveSnippets(root, snippets)
                }
            }
        }
        val delBtn = FlatCphButton("Delete", CphColors.RED).apply {
            preferredSize = Dimension(68, 26)
            addActionListener {
                val idx = snippetList.selectedIndex
                if (idx in snippets.indices) {
                    snippets.removeAt(idx)
                    listModel.remove(idx)
                    CphStorage.saveSnippets(root, snippets)
                    if (snippets.isNotEmpty()) snippetList.selectedIndex = idx.coerceAtMost(snippets.lastIndex)
                    else codeArea.text = ""
                }
            }
        }

        val leftCol = JPanel(BorderLayout(0, 6)).apply {
            add(JBScrollPane(snippetList).apply { preferredSize = Dimension(170, 280) }, BorderLayout.CENTER)
            val leftBtns = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                add(addBtn); add(saveBtn); add(delBtn)
            }
            add(leftBtns, BorderLayout.SOUTH)
        }
        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftCol, JBScrollPane(codeArea)).apply {
            dividerLocation = 215
            resizeWeight = 0.3
        }

        val actions = arrayOf("Insert at Cursor", "Copy to Clipboard", "Close")
        val picked = JOptionPane.showOptionDialog(
            this,
            split,
            "LionCPH — CP Snippets Library",
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.PLAIN_MESSAGE,
            null,
            actions,
            actions[0]
        )
        val idx = snippetList.selectedIndex
        if (idx in snippets.indices) {
            snippets[idx].code = codeArea.text
            CphStorage.saveSnippets(root, snippets)
        }
        val chosenCode = codeArea.text
        if (chosenCode.isBlank()) return

        if (picked == 0) {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor
            if (editor != null) {
                WriteCommandAction.runWriteCommandAction(project) {
                    val offset = editor.caretModel.offset
                    editor.document.insertString(offset, chosenCode)
                    editor.caretModel.moveToOffset(offset + chosenCode.length)
                }
            } else {
                CopyPasteManager.getInstance().setContents(StringSelection(chosenCode))
                JOptionPane.showMessageDialog(this, "No active editor focused — snippet copied to clipboard.", "LionCPH Snippets", JOptionPane.INFORMATION_MESSAGE)
            }
        } else if (picked == 1) {
            CopyPasteManager.getInstance().setContents(StringSelection(chosenCode))
        }
    }

    private fun openSettingsDialog() {
        val detectedCompiler = JudgeService.resolveCompiler(settings.compilerPath)
        val compilerField = JTextField(settings.compilerPath, 24)
        val argsField = JTextField(settings.compilerArgs, 24)
        val timeField = JTextField(currentProblem.timeLimit.toString(), 24)
        val defaultTimeField = JTextField(settings.defaultTimeLimitMs.toString(), 24)
        val cfHandleField = JTextField(settings.cfHandle, 24)
        val cfApiKeyField = JTextField(settings.cfApiKey, 24)
        val cfApiSecretField = JTextField(settings.cfApiSecret, 24)
        val pythonField = JTextField(settings.pythonPath, 24)
        val portField = JTextField(settings.companionPort.toString(), 24)
        val trimBox = JCheckBox("Ignore trailing whitespace", settings.ignoreTrailingSpaces)
        val autoCMakeBox = JCheckBox("Auto-sync imported files with CMakeLists.txt", settings.autoSyncCMake)
        val orgModeOptions = arrayOf(
            "Flat (Project Root)",
            "Single Folder (Sheet / Contest / Practice)",
            "Smart Hierarchy (Groups/Sheet, Contests/Round, Practice)"
        )
        val orgModeCombo = JComboBox(orgModeOptions).apply {
            selectedIndex = when (FolderOrganizerService.effectiveMode(settings)) {
                "single" -> 1
                "hierarchy" -> 2
                else -> 0
            }
        }
        val nameStyleOptions = arrayOf(
            "Full Name (H_Two_numbers.cpp)",
            "Short Index (H.cpp / 4A.cpp)"
        )
        val nameStyleCombo = JComboBox(nameStyleOptions).apply {
            selectedIndex = if (settings.fileNameStyle.equals("short", true)) 1 else 0
        }
        val organizeNowBtn = JButton("Organize Existing Root Files Now").apply {
            toolTipText = "Safely moves existing LionCPH-tracked .cpp files from project root into folders (keeps main.cpp untouched)"
            addActionListener {
                val root = project.basePath?.let { Path.of(it) } ?: return@addActionListener
                val previewMode = when (orgModeCombo.selectedIndex) {
                    1 -> "single"
                    2 -> "hierarchy"
                    else -> "flat"
                }
                if (previewMode == "flat") {
                    JOptionPane.showMessageDialog(this@LionCPHPanel, "Select 'Single Folder' or 'Smart Hierarchy' first.", "LionCPH Organizer", JOptionPane.INFORMATION_MESSAGE)
                    return@addActionListener
                }
                val confirm = JOptionPane.showConfirmDialog(
                    this@LionCPHPanel,
                    "Move existing LionCPH-tracked .cpp files in project root into organized folders and reload CMake?\n(Your own non-CPH files like main.cpp will NOT be moved.)",
                    "Organize Existing Files",
                    JOptionPane.YES_NO_OPTION
                )
                if (confirm == JOptionPane.YES_OPTION) {
                    settings.organizationMode = previewMode
                    settings.organizeByContest = true
                    CphStorage.saveSettings(root, settings)
                    val moved = FolderOrganizerService.organizeExistingRootFiles(root, settings)
                    LocalFileSystem.getInstance().refresh(true)
                    CMakeSyncService.triggerCMakeReload(root, project)
                    JOptionPane.showMessageDialog(this@LionCPHPanel, "Organized $moved problem file(s) and triggered CMake reload.", "LionCPH Organizer", JOptionPane.INFORMATION_MESSAGE)
                }
            }
        }

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
        val openTemplateBtn = JButton("Edit C++ Template...").apply {
            addActionListener { openTemplateEditorDialog() }
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
        row("Codeforces Handle (Live Verdict):", cfHandleField, 6)
        row("CF API Key (for Groups/Fast Live):", cfApiKeyField, 7)
        row("CF API Secret (codeforces.com/settings/api):", cfApiSecretField, 8)
        row("Python Path:", pythonField, 9)
        row("Companion Primary Port:", portField, 10)
        c.gridx = 1; c.gridy = 11; form.add(trimBox, c)
        c.gridx = 1; c.gridy = 12; form.add(autoCMakeBox, c)
        row("Folder Organization:", orgModeCombo, 13)
        row("File Naming Style:", nameStyleCombo, 14)
        c.gridx = 1; c.gridy = 15; form.add(organizeNowBtn, c)
        row("C++ Template:", openTemplateBtn, 16)

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
            settings.cfHandle = cfHandleField.text.trim()
            settings.cfApiKey = cfApiKeyField.text.trim()
            settings.cfApiSecret = cfApiSecretField.text.trim()
            CodeforcesVerdictService.clearProblemsCache()
            settings.pythonPath = pythonField.text.trim().ifBlank { "python3" }
            settings.ignoreTrailingSpaces = trimBox.isSelected
            settings.autoSyncCMake = autoCMakeBox.isSelected
            settings.organizationMode = when (orgModeCombo.selectedIndex) {
                1 -> "single"
                2 -> "hierarchy"
                else -> "flat"
            }
            settings.organizeByContest = (settings.organizationMode != "flat")
            settings.fileNameStyle = if (nameStyleCombo.selectedIndex == 1) "short" else "full"
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

    private fun openTemplateEditorDialog() {
        val root = project.basePath?.let { Path.of(it) } ?: return
        val area = JTextArea(settings.cppTemplate, 16, 48).apply {
            background = CphColors.INPUT_BG
            foreground = Color(0xE6, 0xEA, 0xF0)
            caretColor = Color.WHITE
            font = Font("JetBrains Mono", Font.PLAIN, 12)
            border = JBUI.Borders.empty(8)
        }
        val hint = JLabel("Placeholders: \$name (problem name), \$url (problem URL), \$date, \$time").apply {
            foreground = CphColors.MUTED
            font = Font("Segoe UI", Font.PLAIN, 11)
        }
        val resetBtn = FlatCphButton("Reset Default", CphColors.BTN_DARK, CphColors.BTN_DARK_BORDER).apply {
            font = Font("Segoe UI", Font.PLAIN, 11)
            preferredSize = Dimension(100, 24)
            addActionListener { area.text = CphSettings.DEFAULT_CPP_TEMPLATE }
        }
        val topRow = JPanel(BorderLayout(8, 0)).apply {
            add(hint, BorderLayout.WEST)
            add(resetBtn, BorderLayout.EAST)
        }
        val container = JPanel(BorderLayout(0, 8)).apply {
            add(topRow, BorderLayout.NORTH)
            add(JBScrollPane(area), BorderLayout.CENTER)
        }
        val res = JOptionPane.showConfirmDialog(
            this,
            container,
            "LionCPH — Default C++ File Template",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE
        )
        if (res == JOptionPane.OK_OPTION) {
            settings.cppTemplate = area.text
            CphStorage.saveSettings(root, settings)
        }
    }

    private fun openImportDialog() {
        val root = project.basePath?.let { Path.of(it) } ?: return
        val choices = arrayOf("Load .in / .out Folder", "Paste Companion JSON", "Cancel")
        val mode = JOptionPane.showOptionDialog(
            this,
            "How would you like to import testcases?",
            "LionCPH — Import Testcases",
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.QUESTION_MESSAGE,
            null,
            choices,
            choices[0]
        )
        if (mode == 0) {
            importTestcasesFromFolder()
            return
        }
        if (mode != 1) return

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
                ApplicationManager.getApplication().invokeLater {
                    LocalFileSystem.getInstance().refreshAndFindFileByNioFile(imported.first)?.let {
                        FileEditorManager.getInstance(project).openFile(it, true)
                    }
                }
                loadCppFile(imported.first)
            } else {
                JOptionPane.showMessageDialog(this, "Invalid JSON format.", "LionCPH", JOptionPane.ERROR_MESSAGE)
            }
        }
    }

    private fun importTestcasesFromFolder() {
        if (activeCppFile == null || !hasProblem) {
            JOptionPane.showMessageDialog(this, "Open a C++ file with a CPH problem first.", "LionCPH Import", JOptionPane.WARNING_MESSAGE)
            return
        }
        val chooser = JFileChooser(project.basePath).apply {
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            dialogTitle = "Select Folder Containing .in and .out/.ans Test Files"
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        val dir = chooser.selectedFile?.toPath() ?: return

        val inFiles = runCatching {
            Files.list(dir).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".in", true) }
                    .toList()
            }
        }.getOrDefault(emptyList()).sortedBy { p ->
            val stem = p.fileName.toString().substringBeforeLast('.')
            val num = Regex("""(\d+)""").find(stem)?.groupValues?.get(1)?.toLongOrNull() ?: Long.MAX_VALUE
            "%012d_%s".format(num, stem.lowercase())
        }

        if (inFiles.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No .in files found in the selected folder.", "LionCPH Import", JOptionPane.INFORMATION_MESSAGE)
            return
        }

        var added = 0
        for (inPath in inFiles) {
            val stem = inPath.fileName.toString().substringBeforeLast('.')
            val outPath = listOf("$stem.out", "$stem.ans", "$stem.sol")
                .map { dir.resolve(it) }
                .firstOrNull { Files.isRegularFile(it) }
            val inputTxt = runCatching { Files.readString(inPath, StandardCharsets.UTF_8) }.getOrDefault("")
            val outputTxt = outPath?.let { runCatching { Files.readString(it, StandardCharsets.UTF_8) }.getOrDefault("") } ?: ""
            currentProblem.tests.add(TestCase(input = inputTxt, output = outputTxt))
            added++
        }
        saveActiveProblem()
        renderUi(scrollToBottom = true)
        JOptionPane.showMessageDialog(this, "Imported $added testcase(s) from ${dir.fileName}.", "LionCPH Import", JOptionPane.INFORMATION_MESSAGE)
    }

    /**
     * Dual-mode Smart Codeforces Submit:
     * - Detects exact problem & matched CLion C++ language
     * - Requires explicit human / non-AI confirmation
     * - Auto-sends via cph-submit if Chrome extension is active, or copies code & opens direct Submit URL otherwise
     */
    private fun scanCodeForContestPitfalls(code: String): List<String> {
        val warnings = mutableListOf<String>()
        val hasOnlineJudgeGuard = code.contains("ONLINE_JUDGE")
        if (Regex("""\bfreopen\s*\(""").containsMatchIn(code) && !hasOnlineJudgeGuard) {
            warnings.add("Unprotected freopen(...) detected without #ifndef ONLINE_JUDGE.")
        }
        val hasLoop = Regex("""\b(for|while)\s*\(""").containsMatchIn(code)
        if (hasLoop && Regex("""\bendl\b""").containsMatchIn(code)) {
            warnings.add("std::endl detected alongside loops (use '\\n' to avoid TLE on large outputs).")
        }
        if ((Regex("""\bcerr\s*<<""").containsMatchIn(code) || Regex("""\bdebug\s*\(""").containsMatchIn(code)) && !hasOnlineJudgeGuard) {
            warnings.add("Debug output (cerr / debug(...)) found outside #ifndef ONLINE_JUDGE.")
        }
        return warnings
    }

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
        val handleField = JTextField(settings.cfHandle, 18)
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
        c.gridx = 0; c.gridy = 3; c.gridwidth = 1; c.weightx = 0.0; panel.add(JLabel("CF Handle (optional live verdict):"), c)
        c.gridx = 1; c.weightx = 1.0; panel.add(handleField, c)
        c.gridx = 0; c.gridy = 4; c.gridwidth = 2; panel.add(JLabel(modeLabel).apply { foreground = CphColors.MUTED; font = Font("Segoe UI", Font.PLAIN, 11) }, c)
        val warnings = scanCodeForContestPitfalls(code)
        var nextRow = 5
        if (warnings.isNotEmpty()) {
            val warnHtml = "<html><div style='width:320px;color:#f5c26b;'><b>Pre-Submit Safety Warnings:</b><br/>" +
                warnings.joinToString("<br/>") { "- $it" } + "</div></html>"
            c.gridy = nextRow++
            panel.add(JLabel(warnHtml).apply {
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(CphColors.AMBER),
                    JBUI.Borders.empty(6, 8)
                )
            }, c)
        }
        c.gridy = nextRow; panel.add(humanCheck, c)

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

        val enteredHandle = handleField.text.trim()
        if (enteredHandle != settings.cfHandle) {
            settings.cfHandle = enteredHandle
            CphStorage.saveSettings(root, settings)
        }

        // If cph-submit is NOT polling, open the exact direct Submit URL in browser right away
        if (!extActive) {
            val targetUrl = cfRef?.directSubmitUrl ?: currentProblem.url
            if (targetUrl.isNotBlank()) {
                BrowserUtil.browse(targetUrl)
            }
        }

        // Start live Codeforces verdict polling if handle is provided
        if (enteredHandle.isNotBlank() && cfRef != null) {
            val nowSec = System.currentTimeMillis() / 1000L
            CodeforcesVerdictService.startPolling(enteredHandle, cfRef, settings.cfApiKey, settings.cfApiSecret, nowSec) { update ->
                ApplicationManager.getApplication().invokeLater {
                    liveVerdict = update
                    if (update.state == "OK") {
                        stopwatchRunning = false
                        refreshTimerBarLabels()
                    }
                    renderUi()
                }
            }
        }
    }

    /**
     * One-Click Debug TC:
     * Saves tc.input to .cph/debug_input.txt & clipboard, selects the matching CLion Run Configuration,
     * attempts automatic stdin redirection, and launches CLion's native debugger.
     */
    /**
     * Navigates to the next/previous Codeforces problem:
     * 1. Opens the target problem URL on Codeforces in the browser.
     * 2. If a matching local .cpp file already exists in the project (e.g., I_*.cpp), opens it in CLion immediately.
     */
    private fun showSubmissionsDialog() {
        val handle = settings.cfHandle.trim()
        if (handle.isEmpty()) {
            val entered = JOptionPane.showInputDialog(
                this,
                "Enter your Codeforces Handle to view your recent submissions:",
                "Codeforces Handle",
                JOptionPane.PLAIN_MESSAGE
            )?.trim().orEmpty()
            if (entered.isEmpty()) return
            settings.cfHandle = entered
            project.basePath?.let { CphStorage.saveSettings(Path.of(it), settings) }
        }

        val pane = JEditorPane("text/html", "<html><body style='background:#1e1e1e;color:#cccccc;font-family:Segoe UI,sans-serif;padding:16px;'>Loading latest submissions for <b>${settings.cfHandle}</b>...</body></html>").apply {
            isEditable = false
            background = CphColors.BG
            addHyperlinkListener { ev ->
                if (ev.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                    ev.url?.toString()?.let { BrowserUtil.browse(it) }
                }
            }
        }
        val scroll = JBScrollPane(pane).apply { preferredSize = Dimension(700, 420) }

        CodeforcesVerdictService.fetchRecentSubmissions(
            settings.cfHandle,
            currentCfRef,
            settings.cfApiKey,
            settings.cfApiSecret
        ) { rows, error ->
            ApplicationManager.getApplication().invokeLater {
                if (error != null) {
                    pane.text = "<html><body style='background:#1e1e1e;color:#f14c4c;font-family:Segoe UI,sans-serif;padding:16px;'>$error</body></html>"
                    return@invokeLater
                }
                if (rows.isEmpty()) {
                    pane.text = "<html><body style='background:#1e1e1e;color:#cccccc;font-family:Segoe UI,sans-serif;padding:16px;'>No recent submissions found for <b>${settings.cfHandle}</b>.</body></html>"
                    return@invokeLater
                }
                fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                val trs = StringBuilder()
                rows.forEach { r ->
                    val vColor = when (r.verdict) {
                        "OK" -> "#73c991"
                        "TESTING" -> "#75beff"
                        else -> "#f14c4c"
                    }
                    val vText = if (r.verdict == "OK") "Accepted" else "${r.verdict.replace('_', ' ')} (test ${r.passedTests + 1})"
                    trs.append(
                        "<tr>" +
                            "<td><a style='color:#4ea1f3;' href='${r.submissionUrl}'>#${r.id}</a></td>" +
                            "<td>${esc(r.problemLabel)}</td>" +
                            "<td style='color:$vColor;font-weight:bold;'>${esc(vText)}</td>" +
                            "<td>${r.timeMs} ms</td>" +
                            "<td>${r.memoryMb} MB</td>" +
                            "</tr>"
                    )
                }
                val myUrl = currentCfRef?.mySubmissionsUrl ?: "https://codeforces.com/submissions/${settings.cfHandle}"
                pane.text = """
                    <html><head><style>
                    body { background:#1e1e1e; color:#d4d4d4; font-family:Segoe UI,sans-serif; font-size:12px; margin:8px; }
                    table { width:100%; border-collapse:collapse; }
                    th { background:#252526; color:#bbb; text-align:left; padding:6px 8px; border-bottom:1px solid #3c3c3c; }
                    td { padding:6px 8px; border-bottom:1px solid #2b2b2e; }
                    </style></head><body>
                    <div style='margin-bottom:8px;'>Latest submissions for <b>${esc(settings.cfHandle)}</b> — <a style='color:#4ea1f3;' href='$myUrl'>Open All in Browser</a></div>
                    <table>
                      <tr><th>ID</th><th>Problem</th><th>Verdict</th><th>Time</th><th>Mem</th></tr>
                      $trs
                    </table>
                    </body></html>
                """.trimIndent()
                pane.caretPosition = 0
            }
        }

        JOptionPane.showMessageDialog(this, scroll, "LionCPH — Codeforces Submissions", JOptionPane.PLAIN_MESSAGE)
    }

    private fun showUpcomingContestsDialog() {
        val pane = JEditorPane("text/html", "<html><body style='background:#1e1e1e;color:#cccccc;font-family:Segoe UI,sans-serif;padding:16px;'>Loading upcoming Codeforces contests...</body></html>").apply {
            isEditable = false
            background = CphColors.BG
            addHyperlinkListener { ev ->
                if (ev.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                    ev.url?.toString()?.let { BrowserUtil.browse(it) }
                }
            }
        }
        val scroll = JBScrollPane(pane).apply { preferredSize = Dimension(680, 380) }

        CodeforcesVerdictService.fetchUpcomingContests { contests, error ->
            ApplicationManager.getApplication().invokeLater {
                if (error != null) {
                    pane.text = "<html><body style='background:#1e1e1e;color:#f14c4c;font-family:Segoe UI,sans-serif;padding:16px;'>$error</body></html>"
                    return@invokeLater
                }
                if (contests.isEmpty()) {
                    pane.text = "<html><body style='background:#1e1e1e;color:#cccccc;font-family:Segoe UI,sans-serif;padding:16px;'>No upcoming contests found.</body></html>"
                    return@invokeLater
                }
                fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                fun fmtCountdown(sec: Long): String {
                    val d = sec / 86400
                    val h = (sec % 86400) / 3600
                    val m = (sec % 3600) / 60
                    return if (d > 0) "${d}d ${h}h ${m}m" else "${h}h ${m}m"
                }
                fun fmtDur(sec: Long): String = "${sec / 3600}h ${(sec % 3600) / 60}m"

                val trs = StringBuilder()
                contests.forEach { c ->
                    trs.append(
                        "<tr>" +
                            "<td>${esc(c.name)}</td>" +
                            "<td style='color:#73c991;font-weight:bold;'>${fmtCountdown(c.startsInSeconds)}</td>" +
                            "<td>${fmtDur(c.durationSeconds)}</td>" +
                            "<td><a style='color:#4ea1f3;' href='${c.url}'>Register</a></td>" +
                            "</tr>"
                    )
                }
                pane.text = """
                    <html><head><style>
                    body { background:#1e1e1e; color:#d4d4d4; font-family:Segoe UI,sans-serif; font-size:12px; margin:8px; }
                    table { width:100%; border-collapse:collapse; }
                    th { background:#252526; color:#bbb; text-align:left; padding:6px 8px; border-bottom:1px solid #3c3c3c; }
                    td { padding:6px 8px; border-bottom:1px solid #2b2b2e; }
                    </style></head><body>
                    <div style='margin-bottom:8px;'><b>Upcoming Codeforces Contests</b> — <a style='color:#4ea1f3;' href='https://codeforces.com/contests'>Open Contests Page</a></div>
                    <table>
                      <tr><th>Contest</th><th>Starts In</th><th>Length</th><th>Action</th></tr>
                      $trs
                    </table>
                    </body></html>
                """.trimIndent()
                pane.caretPosition = 0
            }
        }

        JOptionPane.showMessageDialog(this, scroll, "LionCPH — Upcoming Codeforces Contests", JOptionPane.PLAIN_MESSAGE)
    }

    private fun navigateToCfProblem(cfRef: CfProblemRef, targetIndex: String, knownName: String?) {
        val targetUrl = "${cfRef.problemBaseUrl}$targetIndex"
        val root = project.basePath?.let { Path.of(it) } ?: return
        val searchDir = activeCppFile?.parent ?: root

        // 1. If a local .cpp file for this problem already exists, open it in CLion ONLY (no browser tab!)
        val existing = runCatching {
            val prefix = "${targetIndex}_"
            val exact = "$targetIndex.cpp"
            val probPrefix = "Problem_$targetIndex"
            Files.list(searchDir).use { stream ->
                stream.filter { p ->
                    if (!Files.isRegularFile(p)) return@filter false
                    val name = p.fileName.toString()
                    (name.endsWith(".cpp", true) || name.endsWith(".cc", true)) &&
                        (name.startsWith(prefix, true) || name.equals(exact, true) || name.startsWith(probPrefix, true))
                }.findFirst().orElse(null)
            }
        }.getOrNull()

        if (existing != null) {
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(existing)?.let { vFile ->
                FileEditorManager.getInstance(project).openFile(vFile, true)
            }
            loadCppFile(existing)
            return
        }

        // 2. Check & fetch from Codeforces BEFORE creating any local file or opening browser
        val fallbackTitle = if (!knownName.isNullOrBlank()) "$targetIndex. $knownName" else "Problem $targetIndex"
        liveVerdict = LiveVerdictUpdate("Checking Problem $targetIndex...", "WAITING")
        renderUi()

        CodeforcesVerdictService.fetchProblemFromWeb(targetUrl, fallbackTitle) { fetched ->
            if (fetched.problemNotFound) {
                ApplicationManager.getApplication().invokeLater {
                    // Mark current problem as the last problem in this sheet/contest!
                    currentCfNav = currentCfNav?.copy(nextIndex = null, nextName = null, isLast = true)
                    liveVerdict = LiveVerdictUpdate("Reached the end — this is the last problem in this contest/sheet!", "INFO", isFinal = true)
                    renderUi()
                }
                return@fetchProblemFromWeb
            }

            // If Cloudflare blocked AND we don't have a verified problem name from API, do NOT create a bogus empty file
            if (fetched.blockedByCloudflare && knownName.isNullOrBlank()) {
                ApplicationManager.getApplication().invokeLater {
                    if (openedProblemUrls.add(targetUrl)) {
                        BrowserUtil.browse(targetUrl)
                    }
                    liveVerdict = LiveVerdictUpdate("Opened $targetIndex in browser — click + in Companion to import (add CF API Key for exact sheet limits)", "INFO", isFinal = true)
                    renderUi()
                }
                return@fetchProblemFromWeb
            }

            val rawName = fetched.name
            val dest = FolderOrganizerService.resolveDestination(root, rawName, currentProblem.group, targetUrl, settings)
            val targetDir = if (FolderOrganizerService.effectiveMode(settings) != "flat" && searchDir != root) searchDir else dest.directory
            val fileName = dest.fileName
            val cppFile = targetDir.resolve(fileName)
            val relPath = root.relativize(cppFile).toString().replace('\\', '/')

            runCatching {
                if (!Files.exists(cppFile)) {
                    val dateStr = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
                    val timeStr = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
                    val code = settings.cppTemplate
                        .replace("\$name", rawName)
                        .replace("\$url", targetUrl)
                        .replace("\$date", dateStr)
                        .replace("\$time", timeStr)
                    Files.writeString(cppFile, code + "\n", StandardCharsets.UTF_8)
                }
                CMakeSyncService.registerPluginFile(root, dest.cmakeTargetHint, relPath)
                val tests = fetched.tests.ifEmpty { listOf(TestCase(input = "", output = "")) }.toMutableList()
                val problem = ProblemData(
                    name = rawName,
                    url = targetUrl,
                    timeLimit = fetched.timeLimitMs,
                    srcPath = cppFile.toAbsolutePath().toString(),
                    group = currentProblem.group.ifBlank { "Codeforces" },
                    local = false,
                    tests = tests,
                    detectedFloatTolerance = fetched.detectedFloatTolerance
                )
                CphStorage.saveProblem(root, cppFile, problem)
            }

            ApplicationManager.getApplication().invokeLater {
                if (openedProblemUrls.add(targetUrl)) {
                    BrowserUtil.browse(targetUrl)
                }
                CMakeSyncService.triggerCMakeReload(root, project)
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(cppFile)?.let { vFile ->
                    FileEditorManager.getInstance(project).openFile(vFile, true)
                }
                loadCppFile(cppFile)
                liveVerdict = if (fetched.blockedByCloudflare) {
                    LiveVerdictUpdate("Created $fileName (click + in Companion to sync tests)", "INFO", isFinal = true)
                } else {
                    LiveVerdictUpdate("Loaded $rawName (${fetched.tests.size} sample testcases)", "OK", isFinal = true)
                }
                renderUi()
            }
        }
    }

    private fun debugTestCase(idx: Int, tc: TestCase) {
        val root = project.basePath?.let { Path.of(it) } ?: return
        val cppFile = activeCppFile ?: return
        flushPendingSave()
        saveAllDocuments()

        val debugFile = root.resolve(".cph").resolve("debug_input.txt")
        runCatching {
            Files.createDirectories(debugFile.parent)
            Files.writeString(debugFile, tc.input, StandardCharsets.UTF_8)
        }
        CopyPasteManager.getInstance().setContents(StringSelection(tc.input))

        val baseName = cppFile.fileName.toString().substringBeforeLast('.')
        val sanitized = baseName.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
        val runManager = RunManager.getInstance(project)
        val match = runManager.allSettings.firstOrNull { cfg ->
            cfg.name.equals(baseName, ignoreCase = true) ||
                cfg.name.equals(sanitized, ignoreCase = true) ||
                cfg.name.equals("prob_$sanitized", ignoreCase = true)
        }

        if (match == null) {
            CMakeSyncService.triggerCMakeReload(root, project)
            JOptionPane.showMessageDialog(
                this,
                "TC ${idx + 1} input was saved to .cph/debug_input.txt & copied to clipboard.\n" +
                    "CMake is reloading to register '$baseName' — click Debug again in a moment.",
                "LionCPH Debug",
                JOptionPane.INFORMATION_MESSAGE
            )
            return
        }

        runManager.selectedConfiguration = match
        runCatching {
            val configObj = match.configuration
            val cls = configObj.javaClass
            cls.methods.firstOrNull { it.name == "setRedirectInput" && it.parameterCount == 1 }
                ?.invoke(configObj, true)
            cls.methods.firstOrNull { it.name == "setInputFilePath" && it.parameterCount == 1 }
                ?.invoke(configObj, debugFile.toAbsolutePath().toString())
        }

        val debugExecutor = DefaultDebugExecutor.getDebugExecutorInstance()
        ProgramRunnerUtil.executeConfiguration(match, debugExecutor)
    }

    /**
     * Opens a side-by-side color-highlighted HTML Diff Viewer comparing Expected vs Received output.
     */
    private fun showVisualDiffDialog(tcNumber: Int, expected: String, received: String) {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val expLines = expected.replace("\r\n", "\n").replace("\r", "\n").lines()
        val recLines = received.replace("\r\n", "\n").replace("\r", "\n").lines()
        val maxLines = maxOf(expLines.size, recLines.size)

        val rows = StringBuilder()
        for (i in 0 until maxLines) {
            val e = expLines.getOrNull(i)
            val r = recLines.getOrNull(i)
            val same = (e?.trimEnd() == r?.trimEnd()) && (e != null && r != null)
            if (same) {
                rows.append("<tr><td class='ln'>${i + 1}</td><td class='same'>${esc(e!!)}</td><td class='same'>${esc(r!!)}</td></tr>")
            } else {
                val (eHtml, rHtml) = highlightCharDiff(e ?: "<EOF>", r ?: "<EOF>")
                rows.append("<tr><td class='ln'>${i + 1}</td><td class='exp'>$eHtml</td><td class='rec'>$rHtml</td></tr>")
            }
        }

        val html = """
            <html><head><style>
            body { background:#1e1e1e; color:#d4d4d4; font-family:Consolas,monospace; font-size:12px; margin:0; }
            table { width:100%; border-collapse:collapse; }
            th { background:#252526; color:#cccccc; text-align:left; padding:6px 8px; border-bottom:1px solid #3c3c3c; }
            td { padding:4px 8px; vertical-align:top; white-space:pre-wrap; border-bottom:1px solid #2a2a2d; }
            td.ln { width:34px; color:#777; text-align:right; background:#252526; }
            td.same { background:#1e1e1e; color:#bcbcbc; }
            td.exp { background:#1e3324; color:#a8e6b4; }
            td.rec { background:#3a1d21; color:#f4a8ae; }
            span.ce { background:#2e6b3e; color:#ffffff; font-weight:bold; }
            span.cr { background:#8a232b; color:#ffffff; font-weight:bold; }
            </style></head><body>
            <table>
              <tr><th>#</th><th>Expected Output</th><th>Received Output</th></tr>
              $rows
            </table>
            </body></html>
        """.trimIndent()

        val pane = JEditorPane("text/html", html).apply {
            isEditable = false
            background = CphColors.BG
        }
        val scroll = JBScrollPane(pane).apply {
            preferredSize = Dimension(680, 380)
        }
        JOptionPane.showMessageDialog(this, scroll, "LionCPH Visual Diff — TC $tcNumber", JOptionPane.PLAIN_MESSAGE)
    }

    private fun highlightCharDiff(exp: String, rec: String): Pair<String, String> {
        fun escChar(ch: Char) = when (ch) {
            '&' -> "&amp;"
            '<' -> "&lt;"
            '>' -> "&gt;"
            else -> ch.toString()
        }
        val eOut = StringBuilder()
        val rOut = StringBuilder()
        val n = maxOf(exp.length, rec.length)
        for (i in 0 until n) {
            val ec = exp.getOrNull(i)
            val rc = rec.getOrNull(i)
            if (ec == rc && ec != null) {
                val s = escChar(ec)
                eOut.append(s)
                rOut.append(s)
            } else {
                if (ec != null) eOut.append("<span class='ce'>${escChar(ec)}</span>")
                if (rc != null) rOut.append("<span class='cr'>${escChar(rc)}</span>")
            }
        }
        return eOut.toString() to rOut.toString()
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
        uiTickTimer.stop()
        CodeforcesVerdictService.stopPolling()
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
