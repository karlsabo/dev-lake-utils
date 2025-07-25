package com.github.karlsabo.tools

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

/**
 * Global JSON configuration instance for handling serialization and deserialization.
 *
 * This configuration allows trailing commas and comments in JSON,
 * ignores unknown keys during deserialization, encodes default property values,
 * and formats the output with pretty-printing for better readability.
 */
@OptIn(ExperimentalSerializationApi::class)
val lenientJson = Json {
    allowTrailingComma = true
    allowComments = true
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
    coerceInputValues = true
    isLenient = true
}
