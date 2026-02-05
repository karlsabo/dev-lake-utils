package com.github.karlsabo.markdown

import io.ktor.http.encodeURLPathPart

/**
 * Pure parsing and transformation functions for markdown content.
 * All functions are side-effect free and operate only on strings.
 */
object MarkdownParser {
    private val BASE64_REFERENCE_PATTERN = Regex(
        """\[([^\]]+)]:\s*<data:image/([^;]+);base64,([^>]+)>"""
    )

    private val REFERENCE_DEFINITION_PATTERN = Regex(
        """^\[([^\]]+)]:\s*(.+)$""",
        RegexOption.MULTILINE
    )

    private val IMAGE_LINK_PATTERN = Regex("""!\[([^\]]*)\]\(([^)]+)\)""")

    private val EXTENSION_MAP = mapOf(
        "png" to "png",
        "jpeg" to "jpg",
        "jpg" to "jpg",
        "gif" to "gif",
        "webp" to "webp",
        "svg+xml" to "svg"
    )

    /**
     * Finds all base64-encoded image references in markdown content.
     */
    fun findBase64ImageReferences(content: String): List<Base64ImageReference> =
        BASE64_REFERENCE_PATTERN.findAll(content).map { match ->
            Base64ImageReference(
                fullMatch = match.value,
                referenceName = match.groupValues[1],
                imageType = match.groupValues[2],
                base64Data = match.groupValues[3]
            )
        }.toList()

    /**
     * Replaces a base64 image reference with a file path reference.
     */
    fun replaceBase64Reference(
        content: String,
        base64Reference: Base64ImageReference,
        newPath: String,
    ): String {
        val newReference = "[${base64Reference.referenceName}]: $newPath"
        return content.replace(base64Reference.fullMatch, newReference)
    }

    /**
     * Gets the file extension for an image type.
     */
    fun getExtensionForImageType(imageType: String): String =
        EXTENSION_MAP[imageType] ?: imageType

    /**
     * Sanitizes a string for use as a filename.
     */
    fun sanitizeFileName(name: String): String =
        name.replace(Regex("""[^\w\s-]"""), "").trim().take(50)

    /**
     * Finds all reference definitions in markdown content.
     */
    fun findReferenceDefinitions(content: String): List<ReferenceDefinition> =
        REFERENCE_DEFINITION_PATTERN.findAll(content).map { match ->
            ReferenceDefinition(
                referenceName = match.groupValues[1],
                path = match.groupValues[2].trim()
            )
        }.toList()

    /**
     * Converts reference-style image links to direct links and removes
     * the reference definitions.
     *
     * Transforms:
     * ```
     * ![][image1]
     * [image1]: images/file.png
     * ```
     *
     * Into:
     * ```
     * ![](images/file.png)
     * ```
     */
    fun convertReferencesToDirectLinks(content: String): String {
        val references = findReferenceDefinitions(content)
        if (references.isEmpty()) return content

        var result = content

        for (ref in references) {
            val usagePattern = Regex("""!\[([^\]]*)\]\[${Regex.escape(ref.referenceName)}\]""")
            result = usagePattern.replace(result) { matchResult ->
                val altText = matchResult.groupValues[1]
                "![$altText](${ref.path})"
            }
        }

        for (ref in references) {
            val defPattern = Regex(
                """^\[${Regex.escape(ref.referenceName)}]:\s*${Regex.escape(ref.path)}\s*$\n?""",
                RegexOption.MULTILINE
            )
            result = defPattern.replace(result, "")
        }

        return result
    }

    /**
     * URL-encodes image paths in markdown image links.
     *
     * Transforms `![](images/My File.png)` into `![](images/My%20File.png)`
     */
    fun urlEncodeImagePaths(content: String): String =
        IMAGE_LINK_PATTERN.replace(content) { matchResult ->
            val altText = matchResult.groupValues[1]
            val path = matchResult.groupValues[2]
            val encodedPath = urlEncodePath(path)
            "![$altText]($encodedPath)"
        }

    private fun urlEncodePath(path: String): String =
        path.split("/").joinToString("/") { segment ->
            segment.encodeURLPathPart()
        }
}
