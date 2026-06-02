package com.flowble.internal

import com.flowble.LongWriteAckStrategy
import com.flowble.LongWriteOperationBuilder
import com.flowble.LongWriteRetryStrategy
import com.flowble.model.BleCharacteristic
import com.flowble.model.OperationConfig
import com.flowble.model.WriteType
import java.util.UUID

internal sealed interface LongWriteTarget {
    data class Characteristic(val characteristic: BleCharacteristic) : LongWriteTarget
    data class CharacteristicUuid(val characteristicUuid: UUID) : LongWriteTarget
    data class ServiceCharacteristicUuid(val serviceUuid: UUID, val characteristicUuid: UUID) : LongWriteTarget
}

internal data class LongWriteExecutionRequest(
    val target: LongWriteTarget,
    val value: ByteArray,
    val writeType: WriteType,
    val maxChunkSize: Int?,
    val interChunkDelayMs: Long,
    val config: OperationConfig,
    val ackStrategy: LongWriteAckStrategy?,
    val retryStrategy: LongWriteRetryStrategy?
)

internal class LongWriteOperationBuilderImpl(
    private val execute: suspend (LongWriteExecutionRequest) -> ByteArray
) : LongWriteOperationBuilder {
    private var target: LongWriteTarget? = null
    private var value: ByteArray? = null
    private var writeType: WriteType = WriteType.DEFAULT
    private var maxBatchSize: Int? = null
    private var interChunkDelayMs: Long = 0L
    private var config: OperationConfig = OperationConfig.DEFAULT
    private var ackStrategy: LongWriteAckStrategy? = null
    private var retryStrategy: LongWriteRetryStrategy? = null

    override fun setBytes(value: ByteArray): LongWriteOperationBuilder = apply {
        this.value = value
    }

    override fun setCharacteristic(characteristic: BleCharacteristic): LongWriteOperationBuilder = apply {
        target = LongWriteTarget.Characteristic(characteristic)
    }

    override fun setCharacteristicUuid(characteristicUuid: UUID): LongWriteOperationBuilder = apply {
        target = LongWriteTarget.CharacteristicUuid(characteristicUuid)
    }

    override fun setCharacteristicUuid(
        serviceUuid: UUID,
        characteristicUuid: UUID
    ): LongWriteOperationBuilder = apply {
        target = LongWriteTarget.ServiceCharacteristicUuid(serviceUuid, characteristicUuid)
    }

    override fun setWriteType(writeType: WriteType): LongWriteOperationBuilder = apply {
        this.writeType = writeType
    }

    override fun setMaxBatchSize(maxBatchSize: Int): LongWriteOperationBuilder = apply {
        this.maxBatchSize = maxBatchSize
    }

    override fun setInterChunkDelayMs(delayMs: Long): LongWriteOperationBuilder = apply {
        interChunkDelayMs = delayMs
    }

    override fun setOperationConfig(config: OperationConfig): LongWriteOperationBuilder = apply {
        this.config = config
    }

    override fun setWriteOperationAckStrategy(strategy: LongWriteAckStrategy): LongWriteOperationBuilder = apply {
        ackStrategy = strategy
    }

    override fun setWriteOperationRetryStrategy(strategy: LongWriteRetryStrategy): LongWriteOperationBuilder = apply {
        retryStrategy = strategy
    }

    override suspend fun build(): ByteArray {
        val resolvedTarget = target ?: throw IllegalStateException(
            "Long write target not set. Call setCharacteristic(...) or setCharacteristicUuid(...) first."
        )
        val resolvedValue = value ?: throw IllegalStateException(
            "Long write bytes not set. Call setBytes(...) first."
        )

        return execute(
            LongWriteExecutionRequest(
                target = resolvedTarget,
                value = resolvedValue,
                writeType = writeType,
                maxChunkSize = maxBatchSize,
                interChunkDelayMs = interChunkDelayMs,
                config = config,
                ackStrategy = ackStrategy,
                retryStrategy = retryStrategy
            )
        )
    }
}
