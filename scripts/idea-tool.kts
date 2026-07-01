#!/usr/bin/env kotlin

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.system.exitProcess

private val usage = """
Usage: kotlin scripts/idea-tool.kts <source .idea> <target .idea>

Copies an IntelliJ IDEA project template into a worktree.

Arguments:
  source .idea  Existing .idea directory from the root checkout.
  target .idea  .idea directory to create or refresh in the worktree.

The script copies reusable project-template files, skips worktree-local IntelliJ
state such as shelf/ and dataSources*, and rewrites exact absolute source
repository root strings in copied UTF-8 text files to the target repository root.
It preserves workspace.xml local state while enforcing Go module settings that
stop the IDE from updating go.mod/go.sum. Existing target files are left untouched
when the resulting content is unchanged.
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
            if (isVolatileIdeaPath(relativePath)) return@forEach

            val targetPath = targetIdea.resolve(relativePath)

            if (Files.isDirectory(sourcePath, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectories(targetPath)
            } else {
                copyIdeaFile(sourcePath, targetPath, sourceRepoRoot, targetRepoRoot)
            }
        }
    }
}

private fun ensureGoModuleReadonlyWorkspaceSettings(targetIdea: Path) {
    val workspacePath = targetIdea.resolve("workspace.xml")
    val workspaceText = if (Files.isRegularFile(workspacePath, LinkOption.NOFOLLOW_LINKS)) {
        Files.readString(workspacePath, StandardCharsets.UTF_8)
    } else {
        """<?xml version="1.0" encoding="UTF-8"?>
<project version="4">
${goReadonlyVgoProjectComponent()}
</project>
"""
    }

    val updatedText = withGoReadonlyVgoProjectSettings(workspaceText)
    if (updatedText == workspaceText && Files.isRegularFile(workspacePath, LinkOption.NOFOLLOW_LINKS)) return

    workspacePath.parent?.let(Files::createDirectories)
    Files.writeString(
        workspacePath,
        updatedText,
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
    )
}

private fun withGoReadonlyVgoProjectSettings(workspaceText: String): String {
    val vgoProjectRegex = Regex("""(?s)  <component name="VgoProject">.*?\n  </component>""")
    val match = vgoProjectRegex.find(workspaceText)
        ?: return workspaceText.replace(
            Regex("""\s*</project>\s*$"""),
            "\n${goReadonlyVgoProjectComponent()}\n</project>\n",
        )

    val updatedComponent = withGoReadonlyVgoProjectComponentSettings(match.value)
    return workspaceText.replaceRange(match.range, updatedComponent)
}

private fun withGoReadonlyVgoProjectComponentSettings(componentText: String): String = componentText
    .let(::withGoFlagsReadonly)
    .let(::withAutomaticDependenciesDownloadDisabled)

private fun withGoFlagsReadonly(componentText: String): String {
    val goFlagsEntryRegex = Regex("""<entry key="GOFLAGS" value="([^"]*)" />""")
    val existingGoFlags = goFlagsEntryRegex.find(componentText)
    if (existingGoFlags != null) {
        return goFlagsEntryRegex.replace(componentText) { match ->
            "<entry key=\"GOFLAGS\" value=\"${readonlyGoFlags(match.groupValues[1])}\" />"
        }
    }

    if (componentText.contains("<environment>")) {
        val mapClosing = Regex("""(?m)^([ \t]*)</map>""").find(componentText)
            ?: return componentText
        val indent = mapClosing.groupValues[1]
        return componentText.replaceRange(
            mapClosing.range,
            "$indent  <entry key=\"GOFLAGS\" value=\"-mod=readonly\" />\n$indent</map>",
        )
    }

    return componentText.replace(
        "\n  </component>",
        """
    <environment>
      <map>
        <entry key="GOFLAGS" value="-mod=readonly" />
      </map>
    </environment>
  </component>""",
    )
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

private fun withAutomaticDependenciesDownloadDisabled(componentText: String): String {
    val settingRegex = Regex("""<automatic-dependencies-download-enabled>.*?</automatic-dependencies-download-enabled>""")
    if (settingRegex.containsMatchIn(componentText)) {
        return settingRegex.replace(
            componentText,
            "<automatic-dependencies-download-enabled>false</automatic-dependencies-download-enabled>",
        )
    }

    return componentText.replace(
        "\n  </component>",
        "\n    <automatic-dependencies-download-enabled>false</automatic-dependencies-download-enabled>\n  </component>",
    )
}

private fun goReadonlyVgoProjectComponent(): String = """  <component name="VgoProject">
    <environment>
      <map>
        <entry key="GOFLAGS" value="-mod=readonly" />
      </map>
    </environment>
    <automatic-dependencies-download-enabled>false</automatic-dependencies-download-enabled>
  </component>"""

private fun isVolatileIdeaPath(relativePath: Path): Boolean {
    if (relativePath.nameCount == 0) return false

    val firstName = relativePath.getName(0).toString()
    return firstName in volatileIdeaTopLevelPaths
}

private val volatileIdeaTopLevelPaths = setOf(
    "workspace.xml",
    "shelf",
    "dataSources",
    "dataSources.xml",
    "dataSources.local.xml",
    "tasks.xml",
    "usage.statistics.xml",
)

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
    targetPath.parent?.let(Files::createDirectories)

    val targetContent = targetFileContent(sourcePath, sourceRepoRoot, targetRepoRoot)
    if (targetContent != null && hasSameContent(targetPath, targetContent.bytes)) return

    Files.copy(
        sourcePath,
        targetPath,
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.COPY_ATTRIBUTES,
        LinkOption.NOFOLLOW_LINKS,
    )

    if (targetContent?.differsFromSource == true) {
        Files.write(
            targetPath,
            targetContent.bytes,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
    }
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

private fun hasSameContent(path: Path, bytes: ByteArray): Boolean {
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return false

    return Files.readAllBytes(path).contentEquals(bytes)
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
        if (text.contains(sourceRepoRoot)) text.replace(sourceRepoRoot, targetRepoRoot) else null
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
    ensureGoModuleReadonlyWorkspaceSettings(targetIdea)
} catch (error: UsageError) {
    System.err.println(error.message)
    exitProcess(1)
}
