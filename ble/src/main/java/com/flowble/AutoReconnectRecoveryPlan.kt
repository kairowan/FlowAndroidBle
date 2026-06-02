package com.flowble

import com.flowble.model.BlePhy
import com.flowble.model.ConnectionPriority
import com.flowble.model.OperationConfig
import com.flowble.model.PhyRequest
import com.flowble.model.WriteType
import java.util.UUID

/**
 * Replays a sequence of connection-level recovery steps after each successful reconnect.
 *
 * This is useful for restoring negotiated MTU, preferred PHY, CCCD/descriptor writes,
 * or any other app-specific initialization that should happen every time a fresh
 * [BleConnection] becomes available.
 */
class AutoReconnectRecoveryPlan private constructor(
    private val steps: List<suspend BleConnection.() -> Unit>
) {
    suspend fun execute(connection: BleConnection) {
        for (step in steps) {
            connection.step()
        }
    }

    class Builder {
        private val steps = mutableListOf<suspend BleConnection.() -> Unit>()

        /**
         * Discover services before the remaining recovery steps run.
         */
        fun discoverServices(): Builder = apply {
            steps += { discoverServices() }
        }

        /**
         * Re-request a target MTU on each reconnect.
         */
        fun requestMtu(
            mtu: Int,
            config: OperationConfig = OperationConfig.DEFAULT
        ): Builder = apply {
            steps += { requestMtu(mtu, config) }
        }

        /**
         * Re-apply a preferred connection priority.
         */
        fun requestConnectionPriority(
            priority: ConnectionPriority,
            settleDelayMs: Long = 500L,
            config: OperationConfig = OperationConfig.DEFAULT
        ): Builder = apply {
            steps += {
                requestConnectionPriority(
                    priority = priority,
                    settleDelayMs = settleDelayMs,
                    config = config
                )
            }
        }

        /**
         * Re-apply a preferred PHY selection.
         */
        fun requestPhy(
            request: PhyRequest,
            config: OperationConfig = OperationConfig.DEFAULT
        ): Builder = apply {
            steps += { requestPhy(request, config) }
        }

        /**
         * Rewrite a characteristic by characteristic UUID.
         */
        fun writeCharacteristicByUuid(
            characteristicUuid: UUID,
            value: ByteArray,
            writeType: WriteType = WriteType.DEFAULT,
            config: OperationConfig = OperationConfig.DEFAULT
        ): Builder = apply {
            steps += {
                writeCharacteristicByUuid(
                    characteristicUuid = characteristicUuid,
                    value = value,
                    writeType = writeType,
                    config = config
                )
            }
        }

        /**
         * Alias for rewriting a characteristic by characteristic UUID.
         */
        fun writeCharacteristic(
            characteristicUuid: UUID,
            value: ByteArray,
            writeType: WriteType = WriteType.DEFAULT,
            config: OperationConfig = OperationConfig.DEFAULT
        ): Builder {
            return writeCharacteristicByUuid(characteristicUuid, value, writeType, config)
        }

        /**
         * Rewrite a characteristic by service UUID and characteristic UUID.
         */
        fun writeCharacteristicByUuid(
            serviceUuid: UUID,
            characteristicUuid: UUID,
            value: ByteArray,
            writeType: WriteType = WriteType.DEFAULT,
            config: OperationConfig = OperationConfig.DEFAULT
        ): Builder = apply {
            steps += {
                writeCharacteristicByUuid(
                    serviceUuid = serviceUuid,
                    characteristicUuid = characteristicUuid,
                    value = value,
                    writeType = writeType,
                    config = config
                )
            }
        }

        /**
         * Alias for rewriting a characteristic by service UUID and characteristic UUID.
         */
        fun writeCharacteristic(
            serviceUuid: UUID,
            characteristicUuid: UUID,
            value: ByteArray,
            writeType: WriteType = WriteType.DEFAULT,
            config: OperationConfig = OperationConfig.DEFAULT
        ): Builder {
            return writeCharacteristicByUuid(serviceUuid, characteristicUuid, value, writeType, config)
        }

        /**
         * Rewrite a descriptor by full UUID path.
         */
        fun writeDescriptorByUuid(
            serviceUuid: UUID,
            characteristicUuid: UUID,
            descriptorUuid: UUID,
            value: ByteArray,
            config: OperationConfig = OperationConfig.DEFAULT
        ): Builder = apply {
            steps += {
                writeDescriptorByUuid(
                    serviceUuid = serviceUuid,
                    characteristicUuid = characteristicUuid,
                    descriptorUuid = descriptorUuid,
                    value = value,
                    config = config
                )
            }
        }

        /**
         * Alias for rewriting a descriptor by full UUID path.
         */
        fun writeDescriptor(
            serviceUuid: UUID,
            characteristicUuid: UUID,
            descriptorUuid: UUID,
            value: ByteArray,
            config: OperationConfig = OperationConfig.DEFAULT
        ): Builder {
            return writeDescriptorByUuid(serviceUuid, characteristicUuid, descriptorUuid, value, config)
        }

        /**
         * Read a characteristic by UUID and optionally forward the value to a callback.
         */
        fun readCharacteristicByUuid(
            characteristicUuid: UUID,
            config: OperationConfig = OperationConfig.DEFAULT,
            onValue: suspend (ByteArray) -> Unit = {}
        ): Builder = apply {
            steps += {
                val value = readCharacteristicByUuid(
                    characteristicUuid = characteristicUuid,
                    config = config
                )
                onValue(value)
            }
        }

        /**
         * Alias for reading a characteristic by UUID and optionally forwarding the value.
         */
        fun readCharacteristic(
            characteristicUuid: UUID,
            config: OperationConfig = OperationConfig.DEFAULT,
            onValue: suspend (ByteArray) -> Unit = {}
        ): Builder {
            return readCharacteristicByUuid(characteristicUuid, config, onValue)
        }

        /**
         * Read a characteristic by full UUID path and optionally forward the value to a callback.
         */
        fun readCharacteristicByUuid(
            serviceUuid: UUID,
            characteristicUuid: UUID,
            config: OperationConfig = OperationConfig.DEFAULT,
            onValue: suspend (ByteArray) -> Unit = {}
        ): Builder = apply {
            steps += {
                val value = readCharacteristicByUuid(
                    serviceUuid = serviceUuid,
                    characteristicUuid = characteristicUuid,
                    config = config
                )
                onValue(value)
            }
        }

        /**
         * Alias for reading a characteristic by full UUID path and optionally forwarding the value.
         */
        fun readCharacteristic(
            serviceUuid: UUID,
            characteristicUuid: UUID,
            config: OperationConfig = OperationConfig.DEFAULT,
            onValue: suspend (ByteArray) -> Unit = {}
        ): Builder {
            return readCharacteristicByUuid(serviceUuid, characteristicUuid, config, onValue)
        }

        /**
         * Read a descriptor and optionally forward the value to a callback.
         */
        fun readDescriptorByUuid(
            serviceUuid: UUID,
            characteristicUuid: UUID,
            descriptorUuid: UUID,
            config: OperationConfig = OperationConfig.DEFAULT,
            onValue: suspend (ByteArray) -> Unit = {}
        ): Builder = apply {
            steps += {
                val value = readDescriptorByUuid(
                    serviceUuid = serviceUuid,
                    characteristicUuid = characteristicUuid,
                    descriptorUuid = descriptorUuid,
                    config = config
                )
                onValue(value)
            }
        }

        /**
         * Alias for reading a descriptor and optionally forwarding the value.
         */
        fun readDescriptor(
            serviceUuid: UUID,
            characteristicUuid: UUID,
            descriptorUuid: UUID,
            config: OperationConfig = OperationConfig.DEFAULT,
            onValue: suspend (ByteArray) -> Unit = {}
        ): Builder {
            return readDescriptorByUuid(
                serviceUuid = serviceUuid,
                characteristicUuid = characteristicUuid,
                descriptorUuid = descriptorUuid,
                config = config,
                onValue = onValue
            )
        }

        /**
         * Read RSSI and optionally forward it to a callback.
         */
        fun readRssi(
            config: OperationConfig = OperationConfig.DEFAULT,
            onValue: suspend (Int) -> Unit = {}
        ): Builder = apply {
            steps += {
                val value = readRssi(config)
                onValue(value)
            }
        }

        /**
         * Read PHY and optionally forward it to a callback.
         */
        fun readPhy(
            config: OperationConfig = OperationConfig.DEFAULT,
            onValue: suspend (BlePhy) -> Unit = {}
        ): Builder = apply {
            steps += {
                val value = readPhy(config)
                onValue(value)
            }
        }

        /**
         * Add a custom recovery step.
         */
        fun run(block: suspend BleConnection.() -> Unit): Builder = apply {
            steps += block
        }

        fun build(): AutoReconnectRecoveryPlan = AutoReconnectRecoveryPlan(steps.toList())
    }

    companion object {
        fun build(block: Builder.() -> Unit): AutoReconnectRecoveryPlan {
            return Builder().apply(block).build()
        }
    }
}

fun autoReconnectRecoveryPlan(
    block: AutoReconnectRecoveryPlan.Builder.() -> Unit
): AutoReconnectRecoveryPlan {
    return AutoReconnectRecoveryPlan.build(block)
}
