package com.flowble.helpers

import kotlin.math.pow

/**
 * Helper for decoding standard Bluetooth value formats from byte arrays.
 *
 * The format constants and decoding rules mirror Android's legacy
 * `BluetoothGattCharacteristic.getIntValue()`, `getFloatValue()`, and `getStringValue()`
 * helpers.
 */
object ValueInterpreter {

    const val FORMAT_UINT8 = 0x11
    const val FORMAT_UINT16 = 0x12
    const val FORMAT_UINT32 = 0x14
    const val FORMAT_SINT8 = 0x21
    const val FORMAT_SINT16 = 0x22
    const val FORMAT_SINT32 = 0x24
    const val FORMAT_SFLOAT = 0x32
    const val FORMAT_FLOAT = 0x34

    fun getIntValue(value: ByteArray, formatType: Int, offset: Int): Int? {
        if (offset < 0 || offset + getTypeLen(formatType) > value.size) {
            return null
        }

        return when (formatType) {
            FORMAT_UINT8 -> unsignedByteToInt(value[offset])
            FORMAT_UINT16 -> unsignedBytesToInt(value[offset], value[offset + 1])
            FORMAT_UINT32 -> unsignedBytesToInt(
                value[offset],
                value[offset + 1],
                value[offset + 2],
                value[offset + 3]
            )
            FORMAT_SINT8 -> unsignedToSigned(unsignedByteToInt(value[offset]), 8)
            FORMAT_SINT16 -> unsignedToSigned(
                unsignedBytesToInt(value[offset], value[offset + 1]),
                16
            )
            FORMAT_SINT32 -> unsignedToSigned(
                unsignedBytesToInt(
                    value[offset],
                    value[offset + 1],
                    value[offset + 2],
                    value[offset + 3]
                ),
                32
            )
            else -> null
        }
    }

    fun getFloatValue(value: ByteArray, formatType: Int, offset: Int): Float? {
        if (offset < 0 || offset + getTypeLen(formatType) > value.size) {
            return null
        }

        return when (formatType) {
            FORMAT_SFLOAT -> bytesToFloat(value[offset], value[offset + 1])
            FORMAT_FLOAT -> bytesToFloat(
                value[offset],
                value[offset + 1],
                value[offset + 2],
                value[offset + 3]
            )
            else -> null
        }
    }

    fun getStringValue(value: ByteArray, offset: Int): String? {
        if (offset < 0 || offset > value.size) {
            return null
        }

        return value.copyOfRange(offset, value.size).decodeToString()
    }

    private fun getTypeLen(formatType: Int): Int = formatType and 0xF

    private fun unsignedByteToInt(byte: Byte): Int = byte.toInt() and 0xFF

    private fun unsignedBytesToInt(byte0: Byte, byte1: Byte): Int {
        return unsignedByteToInt(byte0) + (unsignedByteToInt(byte1) shl 8)
    }

    private fun unsignedBytesToInt(byte0: Byte, byte1: Byte, byte2: Byte, byte3: Byte): Int {
        return unsignedByteToInt(byte0) +
            (unsignedByteToInt(byte1) shl 8) +
            (unsignedByteToInt(byte2) shl 16) +
            (unsignedByteToInt(byte3) shl 24)
    }

    private fun bytesToFloat(byte0: Byte, byte1: Byte): Float {
        val mantissa = unsignedToSigned(
            unsignedByteToInt(byte0) + ((unsignedByteToInt(byte1) and 0x0F) shl 8),
            12
        )
        val exponent = unsignedToSigned(unsignedByteToInt(byte1) shr 4, 4)
        return (mantissa * 10.0.pow(exponent.toDouble())).toFloat()
    }

    private fun bytesToFloat(byte0: Byte, byte1: Byte, byte2: Byte, byte3: Byte): Float {
        val mantissa = unsignedToSigned(
            unsignedByteToInt(byte0) +
                (unsignedByteToInt(byte1) shl 8) +
                (unsignedByteToInt(byte2) shl 16),
            24
        )
        return (mantissa * 10.0.pow(byte3.toDouble())).toFloat()
    }

    private fun unsignedToSigned(unsigned: Int, size: Int): Int {
        if ((unsigned and (1 shl (size - 1))) != 0) {
            return -1 * ((1 shl (size - 1)) - (unsigned and ((1 shl (size - 1)) - 1)))
        }
        return unsigned
    }
}
