package com.coroutines.androidresourceusagetracker

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import com.intellij.psi.xml.XmlTag

class ResourceUsageLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        return null
    }

    override fun collectSlowLineMarkers(
        elements: List<PsiElement>,
        result: MutableCollection<in LineMarkerInfo<*>>
    ) {
        for (element in elements) {
            if (element !is XmlTag) continue
            if (!isAndroidResourceTag(element)) continue

            val resourceName = element.getAttributeValue("name") ?: continue
            val usageCount = UsageCounter.countUsages(element)

            val icon = ResourceUsageIconGenerator.createIcon(usageCount)
            val anchorElement = element.firstChild ?: continue

            val lineMarker = LineMarkerInfo(
                anchorElement,
                element.textRange,
                icon,
                { createTooltip(resourceName, usageCount, element) },
                null,
                GutterIconRenderer.Alignment.RIGHT
            )

            result.add(lineMarker)
        }
    }

    private fun createTooltip(resourceName: String, usageCount: Int, element: XmlTag): String {
        if (usageCount == 0) {
            return "$resourceName: Not used"
        }

        val usages = UsageCounter.getUsages(element)
        val displayCount = if (usageCount > 99) "99+" else usageCount.toString()

        val tooltip = buildString {
            append("<html>")
            append("<b>$resourceName</b>: $displayCount usage${if (usageCount != 1) "s" else ""}<br><br>")

            // Show up to 5 usages in tooltip
            usages.take(5).forEach { usage ->
                append("<b>${usage.filePath}:${usage.lineNumber}</b><br>")
                append("<code>${usage.codeSnippet}</code><br><br>")
            }

            if (usages.size > 5) {
                append("<i>...and ${usages.size - 5} more</i>")
            }

            append("</html>")
        }

        return tooltip
    }

    private fun isAndroidResourceTag(tag: XmlTag): Boolean {
        val validTags = setOf(
            "string", "color", "dimen", "style", "drawable",
            "integer", "bool", "array", "string-array", "integer-array",
            "plurals", "attr", "declare-styleable", "item", "id"
        )
        return validTags.contains(tag.name) && tag.getAttribute("name") != null
    }
}

