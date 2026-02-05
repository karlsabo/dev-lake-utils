package com.github.karlsabo.markdown

import kotlinx.io.files.Path

/**
 * Demo for MarkdownImageExtractor functionality.
 *
 * Usage:
 *   --file=<path>        Process a single markdown file
 *   --directory=<path>   Process all markdown files in a directory
 *   --recursive          Process subdirectories (use with --directory)
 *   --dry-run            Show what would be done without saving changes
 *   --images-dir=<name>  Name of images subdirectory (default: "images")
 *
 * Examples:
 *   --file=/path/to/document.md
 *   --directory=/path/to/docs --recursive
 *   --file=/path/to/document.md --dry-run --images-dir=assets
 */
fun main(args: Array<String>) {
    println("MarkdownImageExtractor Demo")
    println("===========================")

    val file = args.find { it.startsWith("--file=") }?.substringAfter("=")
    val directory = args.find { it.startsWith("--directory=") }?.substringAfter("=")
    val recursive = args.any { it == "--recursive" }
    val dryRun = args.any { it == "--dry-run" }
    val imagesDir = args.find { it.startsWith("--images-dir=") }?.substringAfter("=") ?: "images"

    if (file == null && directory == null) {
        printUsage()
        return
    }

    val saveChanges = !dryRun
    if (dryRun) {
        println("(Dry run - no changes will be saved)")
    }

    val extractor = MarkdownImageExtractor(imagesDirectoryName = imagesDir)

    try {
        when {
            file != null -> processSingleFile(extractor, Path(file), saveChanges)
            directory != null -> processDirectory(extractor, Path(directory), recursive, saveChanges)
        }
    } catch (e: Exception) {
        println("Error: ${e.message}")
        e.printStackTrace()
    }
}

private fun processSingleFile(
    extractor: MarkdownImageExtractor,
    path: Path,
    saveChanges: Boolean,
) {
    println("\nProcessing file: $path")
    val result = extractor.processMarkdownFile(path, saveChanges)
    printResult(result)
}

private fun processDirectory(
    extractor: MarkdownImageExtractor,
    path: Path,
    recursive: Boolean,
    saveChanges: Boolean,
) {
    println("\nProcessing directory: $path (recursive=$recursive)")
    val results = extractor.processDirectory(path, recursive, saveChanges)

    if (results.isEmpty()) {
        println("No markdown files found.")
        return
    }

    println("\nProcessed ${results.size} file(s):")
    results.forEach { printResult(it) }

    val totalImages = results.sumOf { it.extractedImages.size }
    println("\n--- Summary ---")
    println("Files processed: ${results.size}")
    println("Total images extracted: $totalImages")
}

private fun printResult(result: MarkdownImageExtractionResult) {
    println("\n  File: ${result.markdownFile.name}")
    if (result.extractedImages.isEmpty()) {
        println("    No base64 images found")
    } else {
        println("    Extracted ${result.extractedImages.size} image(s):")
        result.extractedImages.forEach { image ->
            println("      - ${image.referenceName} -> ${image.outputPath.name} (${image.sizeBytes} bytes)")
        }
    }
}

private fun printUsage() {
    println(
        """
        |
        |Usage:
        |  --file=<path>        Process a single markdown file
        |  --directory=<path>   Process all markdown files in a directory
        |  --recursive          Process subdirectories (use with --directory)
        |  --dry-run            Show what would be done without saving changes
        |  --images-dir=<name>  Name of images subdirectory (default: "images")
        |
        |Examples:
        |  --file=/path/to/document.md
        |  --directory=/path/to/docs --recursive
        |  --file=/path/to/document.md --dry-run --images-dir=assets
    """.trimMargin()
    )
}
