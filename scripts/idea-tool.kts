#!/usr/bin/env kotlin

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlin.system.exitProcess

private val usage = """
Usage: kotlin scripts/idea-tool.kts <source .idea> <target .idea>

Seeds an IntelliJ IDEA project template into a worktree.

Arguments:
  source .idea  Existing .idea directory from the root checkout.
  target .idea  .idea directory to create or seed in the worktree.

The script is target-first: it copies reusable project-template files only when
target files are missing. Existing target files are never overwritten or repaired;
close IntelliJ, delete a specific target .idea path, and rerun this script to
reseed that path. There is no --force mode.

Copied UTF-8 text files have exact absolute source repository root strings
rewritten to the target repository root. A missing workspace.xml is seeded from
sanitized source components, preserving useful settings such as Go environment
entries and run configurations while dropping worktree-local state. The script
never copies shelf/ or usage.statistics.xml. Datasource files and caches seed
when their target paths are missing.
""".trimIndent()

private class UsageError(
    message: String,
) : RuntimeException(message)

private fun fail(message: String): Nothing = throw UsageError("$message\n\n$usage")

private fun ideaPath(rawPath: String): Path = Path.of(rawPath).toAbsolutePath().normalize()

private fun requireIdeaDirectoryName(path: Path, label: String) {
    if (path.fileName?.toString() != ".idea") {
        fail("$label path must be named .idea: $path")
    }
}

private fun Path.strictlyContains(other: Path): Boolean = this != other && other.startsWith(this)

private fun validatePaths(sourceIdea: Path, targetIdea: Path) {
    requireIdeaDirectoryName(sourceIdea, "Source")
    requireIdeaDirectoryName(targetIdea, "Target")

    if (!Files.isDirectory(sourceIdea)) {
        fail("Source .idea must exist and be a directory: $sourceIdea")
    }

    if (Files.exists(targetIdea) && !Files.isDirectory(targetIdea)) {
        fail("Target .idea exists but is not a directory: $targetIdea")
    }

    val sourceRealPath = sourceIdea.toRealPath()
    val targetComparablePath = if (Files.exists(targetIdea)) {
        targetIdea.toRealPath()
    } else {
        targetIdea
    }

    if (sourceRealPath == targetComparablePath) {
        fail("Source and target .idea paths must be different: $sourceIdea")
    }

    if (
        sourceIdea.strictlyContains(targetIdea) ||
        targetIdea.strictlyContains(sourceIdea) ||
        sourceRealPath.strictlyContains(targetComparablePath) ||
        targetComparablePath.strictlyContains(sourceRealPath)
    ) {
        fail("Source and target .idea paths must not contain each other: $sourceIdea -> $targetIdea")
    }

    if (sourceIdea.parent == null) {
        fail("Source .idea must have a parent repository directory: $sourceIdea")
    }

    if (targetIdea.parent == null) {
        fail("Target .idea must have a parent repository directory: $targetIdea")
    }
}

private fun copyIdeaTemplate(sourceIdea: Path, targetIdea: Path) {
    val sourceRepoRoot = sourceIdea.parent.toString()
    val targetRepoRoot = targetIdea.parent.toString()

    Files.walk(sourceIdea).use { sourcePaths ->
        sourcePaths.forEach { sourcePath ->
            val relativePath = sourceIdea.relativize(sourcePath)
            val targetPath = targetIdea.resolve(relativePath)

            val sourceIsDirectory = Files.isDirectory(sourcePath, LinkOption.NOFOLLOW_LINKS)
            when (ideaPathPolicy(relativePath, sourceIsDirectory)) {
                IdeaPathPolicy.NEVER_COPY -> return@forEach

                IdeaPathPolicy.SANITIZED_FIRST_COPY -> {
                    if (hasBlockingTargetAncestor(targetIdea, relativePath)) return@forEach
                    seedWorkspaceXml(sourcePath, targetPath, sourceRepoRoot, targetRepoRoot)
                }

                IdeaPathPolicy.TARGET_MISSING_COPY -> {
                    if (hasBlockingTargetAncestor(targetIdea, relativePath)) return@forEach
                    if (!sourceIsDirectory) copyIdeaFile(sourcePath, targetPath, sourceRepoRoot, targetRepoRoot)
                }

                IdeaPathPolicy.RECURSIVE_MISSING_CHILD_COPY -> Unit
            }
        }
    }
}

private val deniedWorkspaceComponentNames = setOf(
    "ProjectId",
    "ChangeListManager",
    "TaskManager",
    "VcsManagerConfiguration",
    "Vcs.Log.Tabs.Properties",
    "Git.Settings",
    "Git.Merge.Settings",
    "Git.Rebase.Settings",
    "GitHubPullRequestSearchHistory",
    "GitHubPullRequestState",
    "GithubPullRequestsUISettings",
    "EmbeddingIndexingInfo",
    "SharedIndexes",
    "NextEditCompletionFeaturesState",
    "HighlightingSettingsPerFile",
    "ChangesViewManager",
    "ProjectViewState",
    "PropertiesComponent",
    "RecentsManager",
    "XDebuggerManager",
    "ReactDesignerToolWindowState",
    "FileTemplateManagerImpl",
    "ProjectColorInfo",
    "TypeScriptGeneratedFilesManager",
    "XSLT-Support.FileAssociations.UIState",
    "KubernetesApiPersistence",
)

private fun seedWorkspaceXml(
    sourcePath: Path,
    targetPath: Path,
    sourceRepoRoot: String,
    targetRepoRoot: String,
) {
    if (Files.exists(targetPath, LinkOption.NOFOLLOW_LINKS)) return
    if (!Files.isRegularFile(sourcePath, LinkOption.NOFOLLOW_LINKS)) return

    val document = parseWorkspaceXml(sourcePath)
    removeDeniedWorkspaceComponents(document)
    enforceGoReadonlyInExistingVgoProject(document)
    rewriteSourceRootInWorkspaceXml(document, sourceRepoRoot, targetRepoRoot)

    val workspaceText = serializeWorkspaceXml(document)

    targetPath.parent?.let(Files::createDirectories)
    Files.writeString(
        targetPath,
        workspaceText,
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE_NEW,
    )
}

private fun parseWorkspaceXml(sourcePath: Path): Document = try {
    val factory = DocumentBuilderFactory.newInstance()
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
    factory.isIgnoringComments = false
    factory.isCoalescing = true

    factory.newDocumentBuilder().parse(sourcePath.toFile())
} catch (error: Exception) {
    throw UsageError("Failed to parse source workspace.xml: $sourcePath\n${error.message}")
}

private fun removeDeniedWorkspaceComponents(document: Document) {
    val components = document.getElementsByTagName("component")
    for (index in components.length - 1 downTo 0) {
        val component = components.item(index)
        if (component is Element && component.getAttribute("name") in deniedWorkspaceComponentNames) {
            component.parentNode.removeChild(component)
        }
    }
}

private fun enforceGoReadonlyInExistingVgoProject(document: Document) {
    val vgoProject = findComponent(document, "VgoProject") ?: return
    val environment = directChildElement(vgoProject, "environment")
        ?: document.createElement("environment").also(vgoProject::appendChild)
    val map = directChildElement(environment, "map")
        ?: document.createElement("map").also(environment::appendChild)
    val goFlagsEntry = childElements(map, "entry")
        .firstOrNull { it.getAttribute("key") == "GOFLAGS" }

    if (goFlagsEntry != null) {
        goFlagsEntry.setAttribute("value", readonlyGoFlags(goFlagsEntry.getAttribute("value")))
    } else {
        val entry = document.createElement("entry")
        entry.setAttribute("key", "GOFLAGS")
        entry.setAttribute("value", "-mod=readonly")
        map.appendChild(entry)
    }
}

private fun findComponent(document: Document, name: String): Element? {
    val components = document.getElementsByTagName("component")
    for (index in 0 until components.length) {
        val component = components.item(index)
        if (component is Element && component.getAttribute("name") == name) return component
    }
    return null
}

private fun directChildElement(parent: Element, tagName: String): Element? = childElements(parent, tagName).firstOrNull()

private fun childElements(parent: Element, tagName: String): Sequence<Element> = sequence {
    val children = parent.childNodes
    for (index in 0 until children.length) {
        val child = children.item(index)
        if (child is Element && child.tagName == tagName) yield(child)
    }
}

private fun serializeWorkspaceXml(document: Document): String {
    val transformerFactory = TransformerFactory.newInstance()
    transformerFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
    val transformer = transformerFactory.newTransformer()
    transformer.setOutputProperty(OutputKeys.ENCODING, StandardCharsets.UTF_8.name())
    transformer.setOutputProperty(OutputKeys.INDENT, "yes")
    transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")

    val output = ByteArrayOutputStream()
    transformer.transform(DOMSource(document), StreamResult(output))
    return output.toByteArray().toString(StandardCharsets.UTF_8)
}

private fun rewriteSourceRootInWorkspaceXml(
    node: Node,
    sourceRepoRoot: String,
    targetRepoRoot: String,
) {
    rewriteSourceRootInAttributes(node, sourceRepoRoot, targetRepoRoot)

    if (node.nodeType == Node.TEXT_NODE || node.nodeType == Node.CDATA_SECTION_NODE) {
        rewrittenSourceRootTextOrNull(node.nodeValue, sourceRepoRoot, targetRepoRoot)?.let { node.nodeValue = it }
    }

    val children = node.childNodes
    for (index in 0 until children.length) {
        rewriteSourceRootInWorkspaceXml(children.item(index), sourceRepoRoot, targetRepoRoot)
    }
}

private fun rewriteSourceRootInAttributes(
    node: Node,
    sourceRepoRoot: String,
    targetRepoRoot: String,
) {
    val attributes = node.attributes ?: return
    for (index in 0 until attributes.length) {
        val attribute = attributes.item(index)
        rewrittenSourceRootTextOrNull(attribute.nodeValue, sourceRepoRoot, targetRepoRoot)?.let {
            attribute.nodeValue = it
        }
    }
}

private fun rewrittenSourceRootTextOrNull(
    text: String,
    sourceRepoRoot: String,
    targetRepoRoot: String,
): String? = if (text.contains(sourceRepoRoot)) text.replace(sourceRepoRoot, targetRepoRoot) else null

private fun hasBlockingTargetAncestor(targetIdea: Path, relativePath: Path): Boolean {
    var parent = relativePath.parent ?: return false
    while (parent.nameCount > 0) {
        val targetParent = targetIdea.resolve(parent)
        if (Files.exists(targetParent, LinkOption.NOFOLLOW_LINKS) &&
            !Files.isDirectory(targetParent, LinkOption.NOFOLLOW_LINKS)
        ) {
            return true
        }
        parent = parent.parent ?: break
    }
    return false
}

private fun readonlyGoFlags(rawGoFlags: String): String {
    val flags = rawGoFlags.split(Regex("""\s+""")).filter(String::isNotBlank).toMutableList()
    val moduleFlagIndex = flags.indexOfFirst { it.startsWith("-mod=") }
    if (moduleFlagIndex >= 0) {
        flags[moduleFlagIndex] = "-mod=readonly"
    } else {
        flags.add("-mod=readonly")
    }
    return flags.joinToString(" ")
}

private enum class IdeaPathPolicy {
    SANITIZED_FIRST_COPY,
    TARGET_MISSING_COPY,
    RECURSIVE_MISSING_CHILD_COPY,
    NEVER_COPY,
}

private fun ideaPathPolicy(relativePath: Path, sourceIsDirectory: Boolean): IdeaPathPolicy {
    if (relativePath.nameCount == 0) return IdeaPathPolicy.RECURSIVE_MISSING_CHILD_COPY

    val topLevelName = relativePath.getName(0).toString()
    val isTopLevelPath = relativePath.nameCount == 1

    return when {
        isTopLevelPath && topLevelName == "workspace.xml" -> IdeaPathPolicy.SANITIZED_FIRST_COPY
        topLevelName == "shelf" -> IdeaPathPolicy.NEVER_COPY
        isTopLevelPath && topLevelName == "dataSources.xml" -> IdeaPathPolicy.TARGET_MISSING_COPY
        isTopLevelPath && topLevelName == "dataSources.local.xml" -> IdeaPathPolicy.TARGET_MISSING_COPY
        topLevelName == "dataSources" -> dataSourcesPolicy(sourceIsDirectory)
        isTopLevelPath && topLevelName == "tasks.xml" -> IdeaPathPolicy.TARGET_MISSING_COPY
        topLevelName == "usage.statistics.xml" -> IdeaPathPolicy.NEVER_COPY
        isTopLevelPath && topLevelName == ".gitignore" -> IdeaPathPolicy.TARGET_MISSING_COPY
        else -> unknownIdeaPathPolicy(sourceIsDirectory)
    }
}

private fun dataSourcesPolicy(sourceIsDirectory: Boolean): IdeaPathPolicy = if (sourceIsDirectory) IdeaPathPolicy.RECURSIVE_MISSING_CHILD_COPY else IdeaPathPolicy.TARGET_MISSING_COPY

private fun unknownIdeaPathPolicy(sourceIsDirectory: Boolean): IdeaPathPolicy = if (sourceIsDirectory) unknownDirectoryPolicy() else unknownFilePolicy()

private fun unknownFilePolicy(): IdeaPathPolicy = IdeaPathPolicy.TARGET_MISSING_COPY

private fun unknownDirectoryPolicy(): IdeaPathPolicy = IdeaPathPolicy.RECURSIVE_MISSING_CHILD_COPY

private data class TargetFileContent(
    val bytes: ByteArray,
    val differsFromSource: Boolean,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TargetFileContent

        if (differsFromSource != other.differsFromSource) return false
        if (!bytes.contentEquals(other.bytes)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = differsFromSource.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}

private fun copyIdeaFile(
    sourcePath: Path,
    targetPath: Path,
    sourceRepoRoot: String,
    targetRepoRoot: String,
) {
    if (Files.exists(targetPath, LinkOption.NOFOLLOW_LINKS)) return

    val targetContent = targetFileContent(sourcePath, sourceRepoRoot, targetRepoRoot)
    targetPath.parent?.let(Files::createDirectories)

    Files.copy(
        sourcePath,
        targetPath,
        StandardCopyOption.COPY_ATTRIBUTES,
        LinkOption.NOFOLLOW_LINKS,
    )

    if (targetContent?.differsFromSource == true) {
        rewriteCopiedFileContent(sourcePath, targetPath, targetContent.bytes)
    }
}

private fun rewriteCopiedFileContent(
    sourcePath: Path,
    targetPath: Path,
    bytes: ByteArray,
) {
    val originalPermissions = posixPermissionsOrNull(targetPath)
    val restoreNonPosixReadOnly = originalPermissions == null && !Files.isWritable(targetPath)

    try {
        makeWritableForRewrite(targetPath, originalPermissions)
        Files.write(
            targetPath,
            bytes,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
        restoreCopiedFileModificationTime(sourcePath, targetPath)
    } finally {
        restoreWriteAccess(targetPath, originalPermissions, restoreNonPosixReadOnly)
    }
}

private fun posixPermissionsOrNull(path: Path): Set<PosixFilePermission>? = Files.getFileAttributeView(path, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
    ?.readAttributes()
    ?.permissions()

private fun makeWritableForRewrite(path: Path, originalPermissions: Set<PosixFilePermission>?) {
    if (Files.isWritable(path)) return

    if (originalPermissions != null) {
        Files.setPosixFilePermissions(path, originalPermissions + PosixFilePermission.OWNER_WRITE)
    } else if (!path.toFile().setWritable(true)) {
        throw UsageError("Failed to make copied target writable for root-path rewrite: $path")
    }
}

private fun restoreWriteAccess(
    path: Path,
    originalPermissions: Set<PosixFilePermission>?,
    restoreNonPosixReadOnly: Boolean,
) {
    if (originalPermissions != null) {
        Files.setPosixFilePermissions(path, originalPermissions)
    } else if (restoreNonPosixReadOnly) {
        path.toFile().setWritable(false)
    }
}

private fun restoreCopiedFileModificationTime(sourcePath: Path, targetPath: Path) {
    Files.setLastModifiedTime(
        targetPath,
        Files.getLastModifiedTime(sourcePath, LinkOption.NOFOLLOW_LINKS),
    )
}

private fun targetFileContent(
    sourcePath: Path,
    sourceRepoRoot: String,
    targetRepoRoot: String,
): TargetFileContent? {
    if (!Files.isRegularFile(sourcePath, LinkOption.NOFOLLOW_LINKS)) return null

    val sourceBytes = Files.readAllBytes(sourcePath)
    val rewrittenText = rewriteSourceRootInUtf8Text(sourceBytes, sourceRepoRoot, targetRepoRoot)
        ?: return TargetFileContent(sourceBytes, differsFromSource = false)
    val rewrittenBytes = rewrittenText.toByteArray(StandardCharsets.UTF_8)

    return TargetFileContent(
        bytes = rewrittenBytes,
        differsFromSource = !rewrittenBytes.contentEquals(sourceBytes),
    )
}

private fun rewriteSourceRootInUtf8Text(
    bytes: ByteArray,
    sourceRepoRoot: String,
    targetRepoRoot: String,
): String? {
    val decoder = StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)

    return try {
        val text = decoder.decode(ByteBuffer.wrap(bytes)).toString()
        rewrittenSourceRootTextOrNull(text, sourceRepoRoot, targetRepoRoot)
    } catch (_: CharacterCodingException) {
        null
    }
}

try {
    if (args.size != 2) {
        fail("Expected exactly 2 arguments, got ${args.size}.")
    }

    val sourceIdea = ideaPath(args[0])
    val targetIdea = ideaPath(args[1])
    validatePaths(sourceIdea, targetIdea)
    copyIdeaTemplate(sourceIdea, targetIdea)
} catch (error: UsageError) {
    System.err.println(error.message)
    exitProcess(1)
}
