package com.flowble.ext

/**
 * Convert a byte array to a hex string representation.
 *
 * Example: `[0x0A, 0x1B, 0x2C]` → `"0A:1B:2C"`
 *
 * @param separator The separator between hex bytes. Default is ":".
 */
fun ByteArray.toHexString(separator: String = ":"): String {
    return joinToString(separator) { byte ->
        "%02X".format(byte)
    }
}

/**
 * Parse a hex string back to a byte array.
 *
 * Example: `"0A:1B:2C"` → `[0x0A, 0x1B, 0x2C]`
 *
 * Supports formats with or without separators: "0A1B2C", "0A:1B:2C", "0A-1B-2C"
 */
fun String.hexToByteArray(): ByteArray {
    val cleanHex = replace(Regex("[^0-9A-Fa-f]"), "")
    require(cleanHex.length % 2 == 0) { "Invalid hex string length" }

    return ByteArray(cleanHex.length / 2) { i ->
        cleanHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}
