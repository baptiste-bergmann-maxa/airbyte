/*
 * Copyright (c) 2024 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.cdk.read

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.airbyte.cdk.StreamIdentifier
import io.airbyte.cdk.command.OpaqueStateValue
import io.airbyte.cdk.discover.Field
import io.airbyte.cdk.discover.MetaFieldDecorator
import io.airbyte.cdk.output.OutputConsumer
import io.airbyte.cdk.util.Jsons
import io.airbyte.protocol.models.v0.AirbyteMessage
import io.airbyte.protocol.models.v0.AirbyteRecordMessage
import io.airbyte.protocol.models.v0.AirbyteRecordMessageMeta
import io.airbyte.protocol.models.v0.AirbyteRecordMessageMetaChange
import java.time.ZoneOffset

/**
 * [FeedBootstrap] is the input to a [PartitionsCreatorFactory].
 *
 * This object conveniently packages the [StateManager] singleton with the [feed] for which the
 * [PartitionsCreatorFactory] is to operate on, eventually causing the emission of Airbyte RECORD
 * messages for the [Stream]s in the [feed]. For this purpose, [FeedBootstrap] provides
 * [StreamRecordConsumer] instances which essentially provide a layer of caching over
 * [OutputConsumer], leveraging the fact that all records for a given stream share the same schema.
 */
sealed class FeedBootstrap<T : Feed>(
    /** The [OutputConsumer] instance to which [StreamRecordConsumer] will delegate to. */
    val outputConsumer: OutputConsumer,
    /**
     * The [MetaFieldDecorator] instance which [StreamRecordConsumer] will use to decorate records.
     */
    val metaFieldDecorator: MetaFieldDecorator,
    /** [StateManager] singleton which is encapsulated by this [FeedBootstrap]. */
    private val stateManager: StateManager,
    /** [Feed] to emit records for. */
    val feed: T
) {

    /** Delegates to [StateManager.feeds]. */
    val feeds: List<Feed>
        get() = stateManager.feeds

    /** Deletages to [StateManager] to return the current state value for any [Feed]. */
    fun currentState(feed: Feed): OpaqueStateValue? = stateManager.scoped(feed).current()

    /** Convenience getter for the current state value for this [feed]. */
    val currentState: OpaqueStateValue?
        get() = currentState(feed)

    /** Resets the state value of this feed and the streams in it to zero. */
    fun resetAll() {
        stateManager.scoped(feed).reset()
        for (stream in feed.streams) {
            stateManager.scoped(stream).reset()
        }
    }

    /** A map of all [StreamRecordConsumer] for this [feed]. */
    fun streamRecordConsumers(): Map<StreamIdentifier, StreamRecordConsumer> =
        feed.streams.associate { stream: Stream ->
            stream.id to EfficientStreamRecordConsumer(stream)
        }

    /**
     * Efficient implementation of [StreamRecordConsumer].
     *
     * It's efficient because it re-uses the same Airbyte protocol message instance from one record
     * to the next. Not doing this generates a lot of garbage and the increased GC activity has a
     * measurable impact on performance.
     */
    private inner class EfficientStreamRecordConsumer(override val stream: Stream) :
        StreamRecordConsumer {

        override fun accept(recordData: ObjectNode, changes: Map<Field, FieldValueChange>?) {
            if (changes.isNullOrEmpty()) {
                acceptWithoutChanges(recordData)
            } else {
                val protocolChanges: List<AirbyteRecordMessageMetaChange> =
                    changes.map { (field: Field, fieldValueChange: FieldValueChange) ->
                        AirbyteRecordMessageMetaChange()
                            .withField(field.id)
                            .withChange(fieldValueChange.protocolChange())
                            .withReason(fieldValueChange.protocolReason())
                    }
                acceptWithChanges(recordData, protocolChanges)
            }
        }

        private fun acceptWithoutChanges(recordData: ObjectNode) {
            synchronized(this) {
                for ((fieldName, defaultValue) in defaultRecordData.fields()) {
                    reusedRecordData.set<JsonNode>(fieldName, recordData[fieldName] ?: defaultValue)
                }
                outputConsumer.accept(reusedMessageWithoutChanges)
            }
        }

        private fun acceptWithChanges(
            recordData: ObjectNode,
            changes: List<AirbyteRecordMessageMetaChange>
        ) {
            synchronized(this) {
                for ((fieldName, defaultValue) in defaultRecordData.fields()) {
                    reusedRecordData.set<JsonNode>(fieldName, recordData[fieldName] ?: defaultValue)
                }
                reusedRecordMeta.changes = changes
                outputConsumer.accept(reusedMessageWithChanges)
            }
        }

        private val precedingGlobalFeed: Global? =
            stateManager.feeds
                .filterIsInstance<Global>()
                .filter { it.streams.contains(stream) }
                .firstOrNull()

        // Ideally we should check if sync is trigger-based CDC by checking source connector
        // configuration. But we don't have that information here. So this is just a hacky solution
        private val isTriggerBasedCdc: Boolean =
            precedingGlobalFeed == null &&
                metaFieldDecorator.globalCursor != null &&
                stream.schema.none { it.id == metaFieldDecorator.globalCursor?.id } &&
                stream.configuredCursor?.id == metaFieldDecorator.globalCursor?.id &&
                stream.configuredSyncMode == ConfiguredSyncMode.INCREMENTAL

        private val defaultRecordData: ObjectNode =
            Jsons.objectNode().also { recordData: ObjectNode ->
                stream.schema.forEach { recordData.putNull(it.id) }
                if (feed is Stream && precedingGlobalFeed != null || isTriggerBasedCdc) {
                    metaFieldDecorator.decorateRecordData(
                        timestamp = outputConsumer.recordEmittedAt.atOffset(ZoneOffset.UTC),
                        globalStateValue =
                            if (precedingGlobalFeed != null)
                                stateManager.scoped(precedingGlobalFeed).current()
                            else null,
                        stream,
                        recordData,
                    )
                }
            }

        private val reusedRecordData: ObjectNode = defaultRecordData.deepCopy()

        private val reusedMessageWithoutChanges: AirbyteMessage =
            AirbyteMessage()
                .withType(AirbyteMessage.Type.RECORD)
                .withRecord(
                    AirbyteRecordMessage()
                        .withStream(stream.name)
                        .withNamespace(stream.namespace)
                        .withEmittedAt(outputConsumer.recordEmittedAt.toEpochMilli())
                        .withData(reusedRecordData)
                )

        private val reusedRecordMeta = AirbyteRecordMessageMeta()

        private val reusedMessageWithChanges: AirbyteMessage =
            AirbyteMessage()
                .withType(AirbyteMessage.Type.RECORD)
                .withRecord(
                    AirbyteRecordMessage()
                        .withStream(stream.name)
                        .withNamespace(stream.namespace)
                        .withEmittedAt(outputConsumer.recordEmittedAt.toEpochMilli())
                        .withData(reusedRecordData)
                        .withMeta(reusedRecordMeta)
                )
    }

    companion object {

        @JvmStatic
        private fun FieldValueChange.protocolChange(): AirbyteRecordMessageMetaChange.Change =
            when (this) {
                FieldValueChange.RECORD_SIZE_LIMITATION_ERASURE ->
                    AirbyteRecordMessageMetaChange.Change.NULLED
                FieldValueChange.RECORD_SIZE_LIMITATION_TRUNCATION ->
                    AirbyteRecordMessageMetaChange.Change.TRUNCATED
                FieldValueChange.FIELD_SIZE_LIMITATION_ERASURE ->
                    AirbyteRecordMessageMetaChange.Change.NULLED
                FieldValueChange.FIELD_SIZE_LIMITATION_TRUNCATION ->
                    AirbyteRecordMessageMetaChange.Change.TRUNCATED
                FieldValueChange.DESERIALIZATION_FAILURE_TOTAL ->
                    AirbyteRecordMessageMetaChange.Change.NULLED
                FieldValueChange.DESERIALIZATION_FAILURE_PARTIAL ->
                    AirbyteRecordMessageMetaChange.Change.TRUNCATED
                FieldValueChange.RETRIEVAL_FAILURE_TOTAL ->
                    AirbyteRecordMessageMetaChange.Change.NULLED
                FieldValueChange.RETRIEVAL_FAILURE_PARTIAL ->
                    AirbyteRecordMessageMetaChange.Change.TRUNCATED
            }

        @JvmStatic
        private fun FieldValueChange.protocolReason(): AirbyteRecordMessageMetaChange.Reason =
            when (this) {
                FieldValueChange.RECORD_SIZE_LIMITATION_ERASURE ->
                    AirbyteRecordMessageMetaChange.Reason.SOURCE_RECORD_SIZE_LIMITATION
                FieldValueChange.RECORD_SIZE_LIMITATION_TRUNCATION ->
                    AirbyteRecordMessageMetaChange.Reason.SOURCE_RECORD_SIZE_LIMITATION
                FieldValueChange.FIELD_SIZE_LIMITATION_ERASURE ->
                    AirbyteRecordMessageMetaChange.Reason.SOURCE_FIELD_SIZE_LIMITATION
                FieldValueChange.FIELD_SIZE_LIMITATION_TRUNCATION ->
                    AirbyteRecordMessageMetaChange.Reason.SOURCE_FIELD_SIZE_LIMITATION
                FieldValueChange.DESERIALIZATION_FAILURE_TOTAL ->
                    AirbyteRecordMessageMetaChange.Reason.SOURCE_SERIALIZATION_ERROR
                FieldValueChange.DESERIALIZATION_FAILURE_PARTIAL ->
                    AirbyteRecordMessageMetaChange.Reason.SOURCE_SERIALIZATION_ERROR
                FieldValueChange.RETRIEVAL_FAILURE_TOTAL ->
                    AirbyteRecordMessageMetaChange.Reason.SOURCE_RETRIEVAL_ERROR
                FieldValueChange.RETRIEVAL_FAILURE_PARTIAL ->
                    AirbyteRecordMessageMetaChange.Reason.SOURCE_RETRIEVAL_ERROR
            }

        /** [FeedBootstrap] factory method. */
        fun create(
            outputConsumer: OutputConsumer,
            metaFieldDecorator: MetaFieldDecorator,
            stateManager: StateManager,
            feed: Feed,
        ): FeedBootstrap<*> =
            when (feed) {
                is Global ->
                    GlobalFeedBootstrap(outputConsumer, metaFieldDecorator, stateManager, feed)
                is Stream ->
                    StreamFeedBootstrap(outputConsumer, metaFieldDecorator, stateManager, feed)
            }
    }
}

/**
 * Emits an Airbyte RECORD message for the [Stream] associated with this instance.
 *
 * The purpose of this interface is twofold:
 * 1. to encapsulate a performance-minded implementation behind a simple abstraction;
 * 2. to decorate the RECORD messages with
 * ```
 *    a) meta-fields in the record data, and
 *    b) field value changes and the motivating reason for these in the record metadata.
 * ```
 */
interface StreamRecordConsumer {

    val stream: Stream

    fun accept(recordData: ObjectNode, changes: Map<Field, FieldValueChange>?)
}

/**
 * This enum maps to [AirbyteRecordMessageMetaChange.Change] and
 * [AirbyteRecordMessageMetaChange.Reason] enum value pairs.
 */
enum class FieldValueChange {
    RECORD_SIZE_LIMITATION_ERASURE,
    RECORD_SIZE_LIMITATION_TRUNCATION,
    FIELD_SIZE_LIMITATION_ERASURE,
    FIELD_SIZE_LIMITATION_TRUNCATION,
    DESERIALIZATION_FAILURE_TOTAL,
    DESERIALIZATION_FAILURE_PARTIAL,
    RETRIEVAL_FAILURE_TOTAL,
    RETRIEVAL_FAILURE_PARTIAL,
}

/** [FeedBootstrap] implementation for [Global] feeds. */
class GlobalFeedBootstrap(
    outputConsumer: OutputConsumer,
    metaFieldDecorator: MetaFieldDecorator,
    stateManager: StateManager,
    global: Global,
) : FeedBootstrap<Global>(outputConsumer, metaFieldDecorator, stateManager, global)

/** [FeedBootstrap] implementation for [Stream] feeds. */
class StreamFeedBootstrap(
    outputConsumer: OutputConsumer,
    metaFieldDecorator: MetaFieldDecorator,
    stateManager: StateManager,
    stream: Stream,
) : FeedBootstrap<Stream>(outputConsumer, metaFieldDecorator, stateManager, stream) {

    /** A [StreamRecordConsumer] instance for this [Stream]. */
    fun streamRecordConsumer(): StreamRecordConsumer = streamRecordConsumers()[feed.id]!!
}
