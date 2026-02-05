package com.github.karlsabo.markdown

import com.github.karlsabo.formatting.formatBytes
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private val logger = KotlinLogging.logger {}

/**
 * Extracts base64-embedded images from markdown files and replaces them with file references.
 *
 * Handles reference-style image definitions like:
 * ```
 * [image1]: <data:image/png;base64,iVBORw0KGgo...>
 * ```
 */
class MarkdownImageExtractor(
    private val imagesDirectoryName: String = "images",
    private val fileSystem: kotlinx.io.files.FileSystem = SystemFileSystem,
) {
    /**
     * Extracts base64 images from a markdown file, saves them as files,
     * and updates the markdown with file path references.
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun extractImages(
        markdownPath: Path,
        outputImagesDirectory: Path? = null,
    ): MarkdownImageExtractionResult {
        val content = readFile(markdownPath)
        val markdownDir = markdownPath.parent ?: Path(".")
        val imagesDir = outputImagesDirectory ?: Path(markdownDir, imagesDirectoryName)
        val baseName = MarkdownParser.sanitizeFileName(markdownPath.name.removeSuffix(".md"))

        val references = MarkdownParser.findBase64ImageReferences(content)

        if (references.isEmpty()) {
            logger.debug { "No base64 images found in ${markdownPath.name}" }
            return MarkdownImageExtractionResult(markdownPath, emptyList(), content)
        }

        logger.info { "Found ${references.size} base64 image(s) in ${markdownPath.name}" }
        ensureDirectoryExists(imagesDir)

        var updatedContent = content
        val extractedImages = mutableListOf<ExtractedImage>()

        for (ref in references) {
            val extension = MarkdownParser.getExtensionForImageType(ref.imageType)
            val imageFileName = "$baseName-${ref.referenceName}.$extension"
            val imagePath = Path(imagesDir, imageFileName)

            try {
                val imageData = Base64.decode(ref.base64Data)
                writeImageFile(imagePath, imageData)

                val sizeBytes = imageData.size.toLong()
                logger.info { "  Extracted: $imageFileName (${sizeBytes.formatBytes()})" }

                extractedImages.add(ExtractedImage(ref.referenceName, imagePath, sizeBytes))

                val relativePath = "$imagesDirectoryName/$imageFileName"
                updatedContent = MarkdownParser.replaceBase64Reference(updatedContent, ref, relativePath)
            } catch (e: Exception) {
                logger.error(e) { "Error extracting image ${ref.referenceName}" }
            }
        }

        return MarkdownImageExtractionResult(markdownPath, extractedImages, updatedContent)
    }

    /**
     * Processes a markdown file with all transformations:
     * 1. Extract base64 images to files
     * 2. Convert reference-style links to direct links
     * 3. URL-encode image paths
     */
    fun processMarkdownFile(
        markdownPath: Path,
        saveChanges: Boolean = true,
    ): MarkdownImageExtractionResult {
        val extractionResult = extractImages(markdownPath)

        val content = extractionResult.updatedContent
            .let { MarkdownParser.convertReferencesToDirectLinks(it) }
            .let { MarkdownParser.urlEncodeImagePaths(it) }

        if (saveChanges) {
            writeFile(markdownPath, content)
            logger.info { "Updated: ${markdownPath.name}" }
        }

        return extractionResult.copy(updatedContent = content)
    }

    /**
     * Processes all markdown files in a directory.
     */
    fun processDirectory(
        directory: Path,
        recursive: Boolean = false,
        saveChanges: Boolean = true,
    ): List<MarkdownImageExtractionResult> {
        val paths = listMarkdownFiles(directory, recursive)

        return paths.map { mdPath ->
            logger.info { "\nProcessing: ${mdPath.name}" }
            processMarkdownFile(mdPath, saveChanges)
        }
    }

    private fun listMarkdownFiles(directory: Path, recursive: Boolean): List<Path> =
        if (recursive) {
            listMarkdownFilesRecursively(directory)
        } else {
            fileSystem.list(directory).filter { it.name.endsWith(".md") }
        }

    private fun listMarkdownFilesRecursively(directory: Path): List<Path> =
        fileSystem.list(directory).flatMap { path ->
            when {
                fileSystem.metadataOrNull(path)?.isDirectory == true ->
                    listMarkdownFilesRecursively(path)

                path.name.endsWith(".md") -> listOf(path)
                else -> emptyList()
            }
        }

    private fun readFile(path: Path): String =
        fileSystem.source(path).buffered().readString()

    private fun writeFile(path: Path, content: String) {
        fileSystem.sink(path).buffered().use { it.writeString(content) }
    }

    private fun writeImageFile(path: Path, data: ByteArray) {
        fileSystem.sink(path).buffered().use { it.write(data) }
    }

    private fun ensureDirectoryExists(path: Path) {
        if (!fileSystem.exists(path)) {
            fileSystem.createDirectories(path)
        }
    }
}
