/*
 * Copyright (C) 2019 The Turms Project
 * https://github.com/turms-im/turms
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package im.turms.client.service

import com.google.protobuf.*
import im.turms.client.TurmsClient
import im.turms.client.constant.TurmsStatusCode
import im.turms.client.exception.TurmsBusinessException
import im.turms.client.model.message.BuiltinSystemMessageType
import im.turms.client.model.message.MessageAddition
import im.turms.client.util.MapUtil
import im.turms.common.model.bo.file.AudioFile
import im.turms.common.model.bo.file.File
import im.turms.common.model.bo.file.ImageFile
import im.turms.common.model.bo.file.VideoFile
import im.turms.common.model.bo.message.Message
import im.turms.common.model.bo.message.MessagesWithTotal
import im.turms.common.model.bo.user.UserLocation
import im.turms.common.model.dto.request.TurmsRequest
import im.turms.common.model.dto.request.message.CreateMessageRequest
import im.turms.common.model.dto.request.message.QueryMessagesRequest
import im.turms.common.model.dto.request.message.UpdateMessageRequest
import im.turms.common.util.Validator
import java.nio.ByteBuffer
import java.util.*
import java.util.regex.Pattern

/**
 * @author James Chen
 */
class MessageService(private val turmsClient: TurmsClient) {
    private var mentionedUserIdsParser: ((Message) -> Set<Long>)? = null
    private var onMessage: ((Message, MessageAddition) -> Unit)? = null

    fun setOnMessage(onMessage: (Message, MessageAddition) -> Unit) {
        this.onMessage = onMessage
    }

    suspend fun sendMessage(
        isGroupMessage: Boolean,
        targetId: Long,
        deliveryDate: Date = Date(),
        text: String? = null,
        records: List<ByteBuffer>? = null,
        burnAfter: Int? = null
    ): Long {
        if (text == null && records == null) {
            throw TurmsBusinessException(TurmsStatusCode.ILLEGAL_ARGUMENT, "text and records must not all be null")
        }
        return turmsClient.driver
            .send(
                CreateMessageRequest.newBuilder().apply {
                    if (isGroupMessage) {
                        this.groupId = Int64Value.of(targetId)
                    } else {
                        this.recipientId = Int64Value.of(targetId)
                    }
                    this.deliveryDate = deliveryDate.time
                    text?.let { this.text = StringValue.of(it) }
                    records?.let { this.addAllRecords(it.map { buffer -> ByteString.copyFrom(buffer) }) }
                    burnAfter?.let { this.burnAfter = Int32Value.of(it) }
                }
            ).data.ids.getValues(0)
    }

    suspend fun forwardMessage(
        messageId: Long,
        isGroupMessage: Boolean,
        targetId: Long
    ): Long = turmsClient.driver
        .send(
            CreateMessageRequest.newBuilder().apply {
                this.messageId = Int64Value.of(messageId)
                if (isGroupMessage) {
                    groupId = Int64Value.of(targetId)
                } else {
                    recipientId = Int64Value.of(targetId)
                }
            }
        ).data.ids.getValues(0)

    suspend fun updateSentMessage(
        messageId: Long,
        text: String? = null,
        records: List<ByteBuffer>? = null
    ) {
        if (!Validator.areAllFalsy(text, records)) {
            return turmsClient.driver
                .send(
                    UpdateMessageRequest.newBuilder().apply {
                        this.messageId = messageId
                        text?.let { this.text = StringValue.of(it) }
                        records?.let { this.addAllRecords(it.map { buffer -> ByteString.copyFrom(buffer) }) }
                    }
                ).run {}
        }
    }

    suspend fun queryMessages(
        ids: Set<Long>? = null,
        areGroupMessages: Boolean? = null,
        areSystemMessages: Boolean? = null,
        senderId: Long? = null,
        deliveryDateStart: Date? = null,
        deliveryDateEnd: Date? = null,
        size: Int = 50
    ): List<Message> = turmsClient.driver
        .send(
            QueryMessagesRequest.newBuilder().apply {
                ids?.let { this.addAllIds(it) }
                areGroupMessages?.let { this.areGroupMessages = BoolValue.of(it) }
                areSystemMessages?.let { this.areSystemMessages = BoolValue.of(it) }
                senderId?.let { this.fromId = Int64Value.of(it) }
                deliveryDateStart?.let { this.deliveryDateAfter = Int64Value.of(it.time) }
                deliveryDateEnd?.let { this.deliveryDateBefore = Int64Value.of(it.time) }
                this.size = Int32Value.of(size)
                withTotal = false
            }
        ).data.messages.messagesList

    suspend fun queryMessagesWithTotal(
        ids: Set<Long?>? = null,
        areGroupMessages: Boolean? = null,
        areSystemMessages: Boolean? = null,
        senderId: Long? = null,
        deliveryDateStart: Date? = null,
        deliveryDateEnd: Date? = null,
        size: Int = 1
    ): List<MessagesWithTotal> = turmsClient.driver
        .send(
            QueryMessagesRequest.newBuilder().apply {
                ids?.let { this.addAllIds(it) }
                areGroupMessages?.let { this.areGroupMessages = BoolValue.of(it) }
                areSystemMessages?.let { this.areSystemMessages = BoolValue.of(it) }
                senderId?.let { this.fromId = Int64Value.of(it) }
                deliveryDateStart?.let { this.deliveryDateAfter = Int64Value.of(it.time) }
                deliveryDateEnd?.let { this.deliveryDateBefore = Int64Value.of(it.time) }
                this.size = Int32Value.of(size)
                withTotal = true
            }
        ).data.messagesWithTotalList.messagesWithTotalListList

    suspend fun recallMessage(messageId: Long, recallDate: Date = Date()) = turmsClient.driver
        .send(
            UpdateMessageRequest.newBuilder().apply {
                this.messageId = messageId
                this.recallDate = Int64Value.of(recallDate.time)
            }
        ).run {}

    val isMentionEnabled: Boolean
        get() = mentionedUserIdsParser != null

    fun enableMention() {
        if (mentionedUserIdsParser == null) {
            mentionedUserIdsParser = DEFAULT_MENTIONED_USER_IDS_PARSER
        }
    }

    fun enableMention(mentionedUserIdsParser: (Message) -> Set<Long>) {
        this.mentionedUserIdsParser = mentionedUserIdsParser
    }

    private fun parseMessageAddition(message: Message): MessageAddition {
        val mentionedUserIds: Set<Long> = mentionedUserIdsParser?.invoke(message) ?: emptySet()
        val isMentioned = mentionedUserIds.contains(turmsClient.userService.userInfo?.userId)
        val records = message.recordsList
        var systemMessageType: BuiltinSystemMessageType? = null
        if (message.isSystemMessage.value && records.isNotEmpty()) {
            val bytes = records[0]
            if (bytes.size() > 0) {
                systemMessageType = BuiltinSystemMessageType[bytes.byteAt(0).toInt()]
            }
        }
        val recalledMessageIds: Set<Long> = if (systemMessageType === BuiltinSystemMessageType.RECALL_MESSAGE) {
            val size = message.recordsCount
            HashSet<Long>(MapUtil.getCapability(size)).apply {
                for (i in 1 until size) {
                    val id = message.getRecords(i).asReadOnlyByteBuffer().long
                    add(id)
                }
            }
        } else {
            emptySet()
        }
        return MessageAddition(isMentioned, mentionedUserIds, recalledMessageIds)
    }

    private fun createMessageRequest2Message(requesterId: Long, request: CreateMessageRequest): Message =
        Message.newBuilder()
            .run {
                if (request.hasMessageId()) {
                    id = request.messageId
                }
                isSystemMessage = BoolValue.of(request.isSystemMessage.value)
                deliveryDate = Int64Value.of(request.deliveryDate)
                if (request.hasText()) {
                    text = request.text
                }
                if (request.recordsCount > 0) {
                    addAllRecords(request.recordsList)
                }
                senderId = Int64Value.of(requesterId)
                if (request.hasGroupId()) {
                    groupId = request.groupId
                }
                if (request.hasRecipientId()) {
                    recipientId = request.recipientId
                }
                return build()
            }

    companion object {
        /**
         * Format: "@{userId}"
         *
         *
         * Example: "@{123}", "I need to talk with @{123} and @{321}"
         */
        private val regex = Pattern.compile("@\\{(\\d+?)}")
        private val DEFAULT_MENTIONED_USER_IDS_PARSER: (Message) -> Set<Long> = {
            if (it.hasText()) {
                val text = it.text.value
                val matcher = regex.matcher(text)
                val userIds: MutableSet<Long> = LinkedHashSet()
                while (matcher.find()) {
                    val group = matcher.group(1)
                    userIds.add(group.toLong())
                }
                userIds
            } else {
                emptySet()
            }
        }

        @JvmStatic
        fun generateLocationRecord(
            latitude: Float,
            longitude: Float,
            locationName: String? = null,
            address: String? = null
        ): ByteBuffer = UserLocation.newBuilder().run {
            setLatitude(latitude)
            setLongitude(longitude)
            locationName?.let { this.name = StringValue.of(it) }
            address?.let { this.address = StringValue.of(it) }
            build().toByteString().asReadOnlyByteBuffer()
        }

        @JvmStatic
        fun generateAudioRecordByDescription(
            url: String,
            duration: Int? = null,
            format: String? = null,
            size: Int? = null
        ): ByteBuffer = AudioFile.newBuilder().run {
            setDescription(AudioFile.Description.newBuilder().apply {
                this.url = url
                duration?.let { this.duration = Int32Value.of(it) }
                format?.let { this.format = StringValue.of(it) }
                size?.let { this.size = Int32Value.of(it) }
            })
                .build()
                .toByteString()
                .asReadOnlyByteBuffer()
        }

        @JvmStatic
        fun generateAudioRecordByData(data: ByteArray): ByteBuffer = AudioFile.newBuilder().run {
            setData(
                BytesValue.newBuilder()
                    .setValue(ByteString.copyFrom(data))
                    .build()
            )
                .build()
                .toByteString()
                .asReadOnlyByteBuffer()
        }

        @JvmStatic
        fun generateVideoRecordByDescription(
            url: String,
            duration: Int? = null,
            format: String? = null,
            size: Int? = null
        ): ByteBuffer = VideoFile.newBuilder().run {
            setDescription(VideoFile.Description.newBuilder().apply {
                this.url = url
                duration?.let { this.duration = Int32Value.of(it) }
                format?.let { this.format = StringValue.of(it) }
                size?.let { this.size = Int32Value.of(it) }
            })
                .build()
                .toByteString()
                .asReadOnlyByteBuffer()
        }

        @JvmStatic
        fun generateVideoRecordByData(data: ByteArray): ByteBuffer = VideoFile.newBuilder()
            .setData(BytesValue.of(ByteString.copyFrom(data)))
            .build()
            .toByteString()
            .asReadOnlyByteBuffer()

        @JvmStatic
        fun generateImageRecordByData(data: ByteArray): ByteBuffer = ImageFile.newBuilder()
            .setData(
                BytesValue.newBuilder()
                    .setValue(ByteString.copyFrom(data))
            )
            .build()
            .toByteString()
            .asReadOnlyByteBuffer()

        @JvmStatic
        fun generateImageRecordByDescription(
            url: String,
            fileSize: Int? = null,
            imageSize: Int? = null,
            original: Boolean? = null
        ): ByteBuffer = ImageFile.newBuilder()
            .setDescription(ImageFile.Description.newBuilder().apply {
                setUrl(url)
                fileSize?.let { this.fileSize = Int32Value.of(it) }
                imageSize?.let { this.imageSize = Int32Value.of(it) }
                original?.let { this.original = BoolValue.of(it) }
            })
            .build()
            .toByteString()
            .asReadOnlyByteBuffer()

        @JvmStatic
        fun generateFileRecordByDate(data: ByteArray): ByteBuffer = File.newBuilder()
            .setData(
                BytesValue.newBuilder()
                    .setValue(ByteString.copyFrom(data))
            )
            .build()
            .toByteString()
            .asReadOnlyByteBuffer()

        @JvmStatic
        fun generateFileRecordByDescription(
            url: String,
            format: String? = null,
            size: Int? = null
        ): ByteBuffer = File.newBuilder()
            .setDescription(File.Description.newBuilder().apply {
                setUrl(url)
                format?.let { this.format = StringValue.of(it) }
                size?.let { this.size = Int32Value.of(it) }
            })
            .build()
            .toByteString()
            .asReadOnlyByteBuffer()
    }

    init {
        this.turmsClient.driver
            .addNotificationListener { notification ->
                if (notification.hasRelayedRequest()) {
                    val relayedRequest: TurmsRequest = notification.relayedRequest
                    if (relayedRequest.hasCreateMessageRequest()) {
                        onMessage?.let {
                            val createMessageRequest: CreateMessageRequest = relayedRequest.createMessageRequest
                            val requesterId: Long = notification.requesterId.value
                            val message = createMessageRequest2Message(requesterId, createMessageRequest)
                            val addition: MessageAddition = parseMessageAddition(message)
                            it.invoke(message, addition)
                        }
                    }
                }
            }
    }

}