package com.coroutines.androidresourceusagetracker

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.TextOccurenceProcessor
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlFile

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

                val usages = mutableListOf<ResourceUsage>()
                val seenLocations = mutableSetOf<String>()

                // Special handling for themes/styles - search manifest files across ALL modules
                if (resourceType == "style") {
                    LOG.info("This is a style resource, searching manifest files...")
                    searchManifestFilesInProject(project, resourceName, usages, seenLocations)
                    LOG.info("After manifest search: ${usages.size} usage(s)")
                }

                // Search across the ENTIRE project, not just this module
                val scope = GlobalSearchScope.projectScope(project)
                val searchHelper = PsiSearchHelper.getInstance(project)

                // Search for all components of dotted names
                val searchWords = mutableListOf<String>()
                if (resourceName.contains(".")) {
                    searchWords.addAll(resourceName.split("."))
                } else {
                    searchWords.add(resourceName)
                }

                LOG.info("Searching for $resourceType/$resourceName using search words: $searchWords")

                for (searchWord in searchWords) {
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

                                // Check if this is actually a reference to our resource
                                if (isResourceReference(psiElement, resourceType, resourceName)) {
                                    addUsage(psiElement, path, containingFile, seenLocations, usages)
                                }
                            }

                            true
                        },
                        scope,
                        searchWord,
                        UsageSearchContext.ANY.toShort(),
                        true
                    )
                }

                LOG.info("Found ${usages.size} actual usage(s) of $resourceType/$resourceName")
                return@compute usages

            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                LOG.error("Error counting usages", e)
                return@compute emptyList()
            }
        }
    }

    private fun searchManifestFilesInProject(
        project: Project,
        resourceName: String,
        usages: MutableList<ResourceUsage>,
        seenLocations: MutableSet<String>
    ) {
        try {
            LOG.info("Looking for AndroidManifest.xml across all modules...")

            // Get ALL modules in the project
            val moduleManager = com.intellij.openapi.module.ModuleManager.getInstance(project)
            val allModules = moduleManager.modules

            LOG.info("Project has ${allModules.size} module(s)")

            for (module in allModules) {
                LOG.info("  Checking module: ${module.name}")
                val contentRoots = com.intellij.openapi.roots.ModuleRootManager.getInstance(module).contentRoots

                for (contentRoot in contentRoots) {
                    // Try two locations:
                    // 1. contentRoot/src/main/AndroidManifest.xml (when content root is app/)
                    // 2. contentRoot/AndroidManifest.xml (when content root is app/src/main/)
                    val possiblePaths = listOf(
                        "src/main/AndroidManifest.xml",
                        "AndroidManifest.xml"
                    )

                    for (relativePath in possiblePaths) {
                        val manifestPath = contentRoot.findFileByRelativePath(relativePath)
                        if (manifestPath != null && manifestPath.exists()) {
                            LOG.info("    ✓ Found manifest at: ${manifestPath.path}")
                            val psiManager = PsiManager.getInstance(project)
                            val psiFile = psiManager.findFile(manifestPath)
                            if (psiFile is XmlFile) {
                                searchXmlRecursively(psiFile.rootTag, "style", resourceName, manifestPath.path, psiFile, seenLocations, usages)
                            } else {
                                LOG.info("    Manifest file is not an XmlFile!")
                            }
                            break // Found it, no need to check other paths
                        }
                    }
                }
            }

            LOG.info("After searching all modules: ${usages.size} usage(s)")
        } catch (e: Exception) {
            LOG.error("Error searching manifest files in project", e)
        }
    }

    private fun searchXmlRecursively(
        tag: XmlTag?,
        resourceType: String,
        resourceName: String,
        path: String,
        containingFile: com.intellij.psi.PsiFile,
        seenLocations: MutableSet<String>,
        usages: MutableList<ResourceUsage>
    ) {
        if (tag == null) return

        LOG.info("      Checking tag: <${tag.name}>")

        // Check all attributes of this tag
        for (attribute in tag.attributes) {
            val value = attribute.value
            LOG.info("        Attribute: ${attribute.name}=\"$value\"")

            if (value == "@$resourceType/$resourceName") {
                LOG.info("        ✓ MATCH! Found theme usage: ${attribute.name}=\"$value\"")
                addUsage(attribute.valueElement ?: attribute, path, containingFile, seenLocations, usages)
            }
        }

        // Recursively search child tags
        for (childTag in tag.subTags) {
            searchXmlRecursively(childTag, resourceType, resourceName, path, containingFile, seenLocations, usages)
        }
    }

    private fun addUsage(
        psiElement: PsiElement,
        path: String,
        containingFile: com.intellij.psi.PsiFile,
        seenLocations: MutableSet<String>,
        usages: MutableList<ResourceUsage>
    ) {
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

            // For logging: show display-friendly path
            val displayPath = getDisplayPath(path)

            LOG.info("        -> Added usage at $displayPath:${line + 1}")

            // Store FULL absolute path for navigation
            usages.add(ResourceUsage(
                filePath = path,  // Full absolute path
                lineNumber = line + 1,
                codeSnippet = lineText
            ))
        }
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

    private fun isResourceReference(element: PsiElement, resourceType: String, resourceName: String): Boolean {
        val className = element.javaClass.simpleName

        // For XML: check element and its parents for XmlAttributeValue
        var current: PsiElement? = element
        var depth = 0
        while (current != null && depth < 5) {
            if (current is XmlAttributeValue) {
                val value = current.value
                return value == "@$resourceType/$resourceName"
            }
            current = current.parent
            depth++
        }

        // For code: check for R.type.name (dots converted to underscores)
        val isReferenceClass = className.contains("Reference") ||
                className.contains("Identifier") ||
                className == "KtDotQualifiedExpression"

        val codeName = resourceName.replace(".", "_")
        val hasExactText = element.text == "R.$resourceType.$codeName"

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