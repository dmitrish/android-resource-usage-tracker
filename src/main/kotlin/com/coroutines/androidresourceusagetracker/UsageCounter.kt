package com.coroutines.androidresourceusagetracker

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.TextOccurenceProcessor
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlAttributeValue

data class ResourceUsage(
    val filePath: String,
    val lineNumber: Int,
    val codeSnippet: String
)

object UsageCounter {

    private val LOG = Logger.getInstance(UsageCounter::class.java)

    fun countUsages(element: XmlTag): Int {
        return getUsages(element).size
    }

    fun getUsages(element: XmlTag): List<ResourceUsage> {
        return ReadAction.compute<List<ResourceUsage>, RuntimeException> {
            try {
                val module = ModuleUtilCore.findModuleForPsiElement(element) ?: return@compute emptyList()
                val resourceName = element.getAttributeValue("name") ?: return@compute emptyList()
                val resourceType = getResourceType(element) ?: return@compute emptyList()
                val project = element.project
                val definitionFile = element.containingFile

                val scope = GlobalSearchScope.moduleScope(module)
                val searchHelper = PsiSearchHelper.getInstance(project)

                val usages = mutableListOf<ResourceUsage>()
                val seenLocations = mutableSetOf<String>()

                searchHelper.processElementsWithWord(
                    TextOccurenceProcessor { psiElement, offsetInElement ->
                        val containingFile = psiElement.containingFile

                        // Skip the resource definition file itself
                        if (containingFile == definitionFile) {
                            return@TextOccurenceProcessor true
                        }

                        // Skip generated files
                        val virtualFile = containingFile.virtualFile
                        if (virtualFile != null) {
                            val path = virtualFile.path

                            if (path.contains("/build/") ||
                                path.contains("/generated/") ||
                                path.contains("/.gradle/") ||
                                path.contains("/res/values/") ||
                                virtualFile.name == "R.java" ||
                                virtualFile.name.startsWith("BuildConfig")) {
                                return@TextOccurenceProcessor true
                            }

                            // Only count specific PSI element types that represent actual references
                            if (isLeafReference(psiElement, resourceType, resourceName)) {
                                // Create unique key: file + line number
                                val document = containingFile.viewProvider.document
                                val line = document?.getLineNumber(psiElement.textRange.startOffset) ?: -1
                                val key = "$path:$line"

                                if (!seenLocations.contains(key)) {
                                    seenLocations.add(key)

                                    // Extract code snippet
                                    val lineText = if (document != null && line >= 0) {
                                        val lineStart = document.getLineStartOffset(line)
                                        val lineEnd = document.getLineEndOffset(line)
                                        document.getText(com.intellij.openapi.util.TextRange(lineStart, lineEnd)).trim()
                                    } else {
                                        psiElement.text
                                    }

                                    // Get relative file path
                                    val relativePath = path.substringAfterLast("/app/src/main/", path.substringAfterLast("/"))

                                    usages.add(ResourceUsage(
                                        filePath = relativePath,
                                        lineNumber = line + 1, // 1-indexed for display
                                        codeSnippet = lineText
                                    ))
                                }
                            }
                        }

                        true // continue processing
                    },
                    scope,
                    resourceName,
                    UsageSearchContext.ANY.toShort(),
                    true
                )

                LOG.info("Found ${usages.size} actual usage(s) of $resourceType/$resourceName")
                return@compute usages

            } catch (e: ProcessCanceledException) {
                // CRITICAL: Rethrow ProcessCanceledException - never catch it!
                throw e
            } catch (e: Exception) {
                LOG.error("Error counting usages", e)
                return@compute emptyList()
            }
        }
    }

    /**
     * Only count leaf-level reference expressions, not their parent containers
     */
    private fun isLeafReference(element: PsiElement, resourceType: String, resourceName: String): Boolean {
        val className = element.javaClass.simpleName
        val text = element.text

        // For XML: only count XmlAttributeValue
        if (element is XmlAttributeValue) {
            return text == "@$resourceType/$resourceName"
        }

        // For code: only count elements whose class name suggests they're references
        // AND whose text is EXACTLY the resource reference (not a parent container)
        val isReferenceClass = className.contains("Reference") ||
                className.contains("Identifier") ||
                className == "KtDotQualifiedExpression"

        val hasExactText = text == "R.$resourceType.$resourceName"

        return isReferenceClass && hasExactText
    }

    private fun getResourceType(tag: XmlTag): String? {
        return when (tag.name) {
            "string" -> "string"
            "color" -> "color"
            "dimen" -> "dimen"
            "style" -> "style"
            "drawable" -> "drawable"
            "integer" -> "integer"
            "bool" -> "bool"
            "array", "string-array", "integer-array" -> "array"
            "plurals" -> "plurals"
            "id" -> "id"
            else -> null
        }
    }
}