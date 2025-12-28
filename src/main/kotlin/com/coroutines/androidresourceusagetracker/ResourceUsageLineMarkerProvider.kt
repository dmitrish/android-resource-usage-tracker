package com.coroutines.androidresourceusagetracker

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.xml.XmlTag
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class ResourceUsageLineMarkerProvider : LineMarkerProvider {

    companion object {
        private val CACHE = ConcurrentHashMap<String, Int>()
        private val COMPUTING = ConcurrentHashMap<String, Boolean>()
        private const val CACHE_INVALIDATION_DELAY_MS = 5000L
    }

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        return null // All work happens in collectSlowLineMarkers
    }

    override fun collectSlowLineMarkers(
        elements: List<PsiElement>,
        result: MutableCollection<in LineMarkerInfo<*>>
    ) {
        for (element in elements) {
            if (element !is XmlTag) continue
            if (!isAndroidResourceTag(element)) continue

            val resourceName = element.getAttributeValue("name") ?: continue
            val project = element.project
            val cacheKey = getCacheKey(project, resourceName)

            // Get cached count or use placeholder
            val usageCount = CACHE.getOrDefault(cacheKey, -1)

            // Create icon (use placeholder for -1)
            val icon = if (usageCount >= 0) {
                ResourceUsageIconGenerator.createIcon(usageCount)
            } else {
                ResourceUsageIconGenerator.createLoadingIcon()
            }

            // Create line marker
            val anchorElement = element.firstChild ?: continue
            val lineMarker = LineMarkerInfo(
                anchorElement,
                element.textRange,
                icon,
                { if (usageCount >= 0) "Used $usageCount time${if (usageCount != 1) "s" else ""}" else "Computing..." },
                null,
                GutterIconRenderer.Alignment.RIGHT
            )

            result.add(lineMarker)

            // Start async computation if not cached
            if (usageCount == -1 && COMPUTING.putIfAbsent(cacheKey, true) == null) {
                scheduleUsageCountComputation(element, resourceName, cacheKey, project)
            }
        }
    }

    private fun scheduleUsageCountComputation(
        element: XmlTag,
        resourceName: String,
        cacheKey: String,
        project: Project
    ) {
        AppExecutorUtil.getAppScheduledExecutorService().schedule({
            try {
                val count = UsageCounter.countUsages(element)
                CACHE[cacheKey] = count

                // Schedule cache invalidation
                AppExecutorUtil.getAppScheduledExecutorService().schedule({
                    CACHE.remove(cacheKey)
                }, CACHE_INVALIDATION_DELAY_MS, TimeUnit.MILLISECONDS)

                // Trigger UI update
                ApplicationManager.getApplication().invokeLater {
                    com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
                        .getInstance(project)
                        .restart()
                }
            } finally {
                COMPUTING.remove(cacheKey)
            }
        }, 100, TimeUnit.MILLISECONDS) // Small delay to batch requests
    }

    private fun getCacheKey(project: Project, resourceName: String): String {
        return "${project.name}:$resourceName"
    }

    private fun isAndroidResourceTag(tag: XmlTag): Boolean {
        val tagName = tag.name
        val validTags = setOf(
            "string", "color", "dimen", "style", "drawable",
            "integer", "bool", "array", "string-array", "integer-array",
            "plurals", "attr", "declare-styleable", "item", "id"
        )
        return validTags.contains(tagName) && tag.getAttribute("name") != null
    }
}