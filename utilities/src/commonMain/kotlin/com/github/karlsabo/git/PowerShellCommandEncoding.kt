package com.github.karlsabo.git

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val UTF_16_CODE_UNIT_BYTES = 2
private const val BITS_PER_BYTE = 8

@OptIn(ExperimentalEncodingApi::class)
internal fun encodePowerShellCommand(script: String): String {
    val utf16LittleEndian = ByteArray(script.length * UTF_16_CODE_UNIT_BYTES)
    script.forEachIndexed { index, char ->
        val codeUnitOffset = index * UTF_16_CODE_UNIT_BYTES
        utf16LittleEndian[codeUnitOffset] = char.code.toByte()
        utf16LittleEndian[codeUnitOffset + 1] = (char.code shr BITS_PER_BYTE).toByte()
    }
    return Base64.Default.encode(utf16LittleEndian)
}
