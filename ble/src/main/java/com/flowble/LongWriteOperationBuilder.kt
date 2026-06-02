package com.flowble

import com.flowble.model.BleCharacteristic
import com.flowble.model.OperationConfig
import com.flowble.model.WriteType
import java.util.UUID

/**
 * Builder for advanced long write operations.
 *
 * Call [build] to execute the configured long write and receive the original payload back.
 */
interface LongWriteOperationBuilder {

    fun setBytes(value: ByteArray): LongWriteOperationBuilder

    fun setCharacteristic(characteristic: BleCharacteristic): LongWriteOperationBuilder

    fun setCharacteristicUuid(characteristicUuid: UUID): LongWriteOperationBuilder

    fun setCharacteristicUuid(serviceUuid: UUID, characteristicUuid: UUID): LongWriteOperationBuilder

    fun setWriteType(writeType: WriteType): LongWriteOperationBuilder

    /**
     * Sets the maximum ATT payload per batch.
     */
    fun setMaxBatchSize(maxBatchSize: Int): LongWriteOperationBuilder

    fun setInterChunkDelayMs(delayMs: Long): LongWriteOperationBuilder

    fun setOperationConfig(config: OperationConfig): LongWriteOperationBuilder

    /**
     * Sets a suspendable acknowledgement strategy that runs after each completed batch.
     */
    fun setWriteOperationAckStrategy(strategy: LongWriteAckStrategy): LongWriteOperationBuilder

    /**
     * Sets a suspendable retry strategy that runs when a batch write fails.
     */
    fun setWriteOperationRetryStrategy(strategy: LongWriteRetryStrategy): LongWriteOperationBuilder

    /**
     * Executes the configured long write and returns the original payload on success.
     */
    suspend fun build(): ByteArray
}
