package com.coroutines.androidresourceusagetracker

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.xml.XmlTag
import com.intellij.ui.Gray
import com.intellij.ui.JBColor
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.*
import javax.swing.border.EmptyBorder

class ResourceUsageLineMarkerProvider : LineMarkerProvider {

    /*
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (element !is XmlTag) return null
        if (element.name !in listOf("string", "color", "dimen", "style", "drawable", "integer", "bool", "array", "string-array", "integer-array", "plurals", "id")) {
            return null
        }

        val resourceName = element.getAttributeValue("name") ?: return null
        val count = UsageCounter.countUsages(element)

        return LineMarkerInfo(
            element,
            element.textRange,
            createUsageIcon(count),
            { "$count usage${if (count != 1) "s" else ""}" },
            { e, elt ->
                if (count > 0) {
                    showUsagesPopup(e, elt as XmlTag, elt.project)
                }
            },
            GutterIconRenderer.Alignment.RIGHT,
            { "$count usage${if (count != 1) "s" else ""}" }
        )
    }

     */

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        // Only process the tag name identifier (leaf element), not the whole tag
        if (element !is com.intellij.psi.xml.XmlToken) return null
        if (element.tokenType != com.intellij.psi.xml.XmlTokenType.XML_NAME) return null

        // Make sure this is the opening tag name, not closing tag or attribute name
        // The previous sibling should be XML_START_TAG_START ("<")
        val prevSibling = element.prevSibling
        if (prevSibling !is com.intellij.psi.xml.XmlToken ||
            prevSibling.tokenType != com.intellij.psi.xml.XmlTokenType.XML_START_TAG_START) {
            return null
        }

        val parent = element.parent
        if (parent !is XmlTag) return null

        // Check if this is a resource tag we care about
        if (parent.name !in listOf("string", "color", "dimen", "style", "drawable", "integer", "bool", "array", "string-array", "integer-array", "plurals", "id")) {
            return null
        }

        // Only process if this tag has a "name" attribute (it's a resource definition)
        val resourceName = parent.getAttributeValue("name") ?: return null

        // Make sure we're in a values XML file (check parent directory name)
        val parentDirName = element.containingFile.virtualFile?.parent?.name ?: ""
        if (!parentDirName.contains("values")) {
            return null
        }

        val count = UsageCounter.countUsages(parent)

        return LineMarkerInfo(
            element,  // Register on the leaf element (XML_NAME token)
            element.textRange,
            createUsageIcon(count),
            { "$count usage${if (count != 1) "s" else ""}" },
            { e, elt ->
                if (count > 0) {
                    // Navigate up to the XmlTag for processing
                    val tag = elt.parent as? XmlTag ?: return@LineMarkerInfo
                    showUsagesPopup(e, tag, tag.project)
                }
            },
            GutterIconRenderer.Alignment.RIGHT,
            { "$count usage${if (count != 1) "s" else ""}" }
        )
    }
    private fun createUsageIcon(count: Int): Icon {
        return object : Icon {
            override fun getIconWidth() = 21
            override fun getIconHeight() = 21

            override fun paintIcon(c: Component?, g: Graphics?, x: Int, y: Int) {
                val g2d = g as Graphics2D
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

                val color = when {
                    count == 0 -> JBColor(Color(200, 200, 200), Gray._100)
                    count < 5 -> JBColor(Color(100, 180, 255), Color(80, 140, 200))
                    else -> JBColor(Color(100, 200, 100), Color(80, 160, 80))
                }

                g2d.color = color
                g2d.fill(RoundRectangle2D.Double(x.toDouble(), y.toDouble(), 20.0, 20.0, 6.0, 6.0))

                g2d.color = JBColor.WHITE
                g2d.font = Font("SansSerif", Font.BOLD, 11)
                val text = if (count > 99) "99+" else count.toString()
                val fm = g2d.fontMetrics
                val textWidth = fm.stringWidth(text)
                val textX = x + (20 - textWidth) / 2
                val textY = y + ((20 - fm.height) / 2) + fm.ascent
                g2d.drawString(text, textX, textY)
            }
        }
    }

    private fun showUsagesPopup(event: MouseEvent, element: XmlTag, project: Project) {
        val usages = UsageCounter.getUsages(element)
        if (usages.isEmpty()) return

        val popup = JWindow()
        popup.type = Window.Type.POPUP

        val panel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border(), 1),
                EmptyBorder(8, 8, 8, 8)
            )
            background = JBColor.background()
        }

        val titleLabel = JLabel("${usages.size} usage${if (usages.size != 1) "s" else ""}").apply {
            font = font.deriveFont(Font.BOLD, 13f)
            border = EmptyBorder(0, 0, 8, 0)
        }

        panel.add(titleLabel, BorderLayout.NORTH)

        val usagesList = createUsagesList(usages, project)
        val scrollPane = JScrollPane(usagesList).apply {
            preferredSize = Dimension(600, minOf(300, usages.size * 60 + 20))
            border = null
        }

        panel.add(scrollPane, BorderLayout.CENTER)

        popup.contentPane = panel
        popup.pack()

        val locationOnScreen = event.component.locationOnScreen
        popup.setLocation(locationOnScreen.x + event.x + 10, locationOnScreen.y + event.y)

        popup.isVisible = true
     //   popup.isFocusableWindowState = true
        popup.requestFocus()

        popup.addWindowFocusListener(object : java.awt.event.WindowFocusListener {
            override fun windowGainedFocus(e: java.awt.event.WindowEvent?) {}
            override fun windowLostFocus(e: java.awt.event.WindowEvent?) {
                popup.dispose()
            }
        })
    }

    private fun createUsagesList(usages: List<ResourceUsage>, project: Project): JList<ResourceUsage> {
        val listModel = DefaultListModel<ResourceUsage>()
        usages.forEach { listModel.addElement(it) }

        return JList(listModel).apply {
            cellRenderer = UsageCellRenderer()
            selectionMode = ListSelectionModel.SINGLE_SELECTION

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2) {
                        val usage = selectedValue ?: return

                        // Use the full absolute path directly for navigation
                        val virtualFile = LocalFileSystem.getInstance().findFileByPath(usage.filePath)

                        if (virtualFile != null) {
                            val descriptor = OpenFileDescriptor(project, virtualFile, usage.lineNumber - 1, 0)
                            descriptor.navigate(true)
                        }
                    }
                }
            })
        }
    }

    private class UsageCellRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            val usage = value as ResourceUsage

            val panel = JPanel(BorderLayout()).apply {
                border = EmptyBorder(4, 8, 4, 8)
                background = if (isSelected) JBColor.background() else JBColor.background()
            }

            // Get display-friendly path
            val displayPath = getDisplayPath(usage.filePath)

            val fileLabel = JLabel("<html><b style='color: #589df6;'>$displayPath:${usage.lineNumber}</b></html>").apply {
                font = Font("Monospaced", Font.PLAIN, 12)
            }

            val codeLabel = JLabel("<html><span style='color: #808080; font-family: monospace;'>${escapeHtml(usage.codeSnippet)}</span></html>").apply {
                font = Font("Monospaced", Font.PLAIN, 11)
            }

            val labelsPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(fileLabel)
                add(Box.createVerticalStrut(2))
                add(codeLabel)
                background = if (isSelected) JBColor.background() else JBColor.background()
            }

            panel.add(labelsPanel, BorderLayout.CENTER)

            if (isSelected) {
                panel.background = JBColor(Color(220, 230, 240), Color(60, 70, 80))
                labelsPanel.background = panel.background
            }

            return panel
        }

        private fun getDisplayPath(path: String): String {
            // Try to get path relative to common source roots for display
            return when {
                path.contains("/src/main/") -> path.substringAfter("/src/main/")
                path.contains("/src/test/") -> path.substringAfter("/src/test/")
                path.contains("/src/androidTest/") -> path.substringAfter("/src/androidTest/")
                else -> path.substringAfterLast("/")
            }
        }

        private fun escapeHtml(text: String): String {
            return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
        }
    }
}