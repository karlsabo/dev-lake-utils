#!/usr/bin/env kotlin

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
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

The script copies every file and directory from source to target, overwrites copied
files, and rewrites exact absolute source repository root strings in UTF-8 text files
to the target repository root.
""".trimIndent()

private class UsageError(message: String) : RuntimeException(message)

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

            if (Files.isDirectory(sourcePath, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectories(targetPath)
            } else {
                targetPath.parent?.let(Files::createDirectories)
                Files.copy(
                    sourcePath,
                    targetPath,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES,
                    LinkOption.NOFOLLOW_LINKS,
                )
                rewriteSourceRootInTextFile(targetPath, sourceRepoRoot, targetRepoRoot)
            }
        }
    }
}

private fun rewriteSourceRootInTextFile(path: Path, sourceRepoRoot: String, targetRepoRoot: String) {
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return

    val text = readUtf8Text(path) ?: return
    if (!text.contains(sourceRepoRoot)) return

    Files.writeString(
        path,
        text.replace(sourceRepoRoot, targetRepoRoot),
        StandardCharsets.UTF_8,
        StandardOpenOption.TRUNCATE_EXISTING,
    )
}

private fun readUtf8Text(path: Path): String? {
    val bytes = Files.readAllBytes(path)
    val decoder = StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)

    return try {
        decoder.decode(ByteBuffer.wrap(bytes)).toString()
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
