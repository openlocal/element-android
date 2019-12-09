/*
 * Copyright 2019 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.matrix.android.internal.database.helper

import im.vector.matrix.android.api.session.events.model.Event
import im.vector.matrix.android.api.session.events.model.EventType
import im.vector.matrix.android.api.session.room.send.SendState
import im.vector.matrix.android.internal.database.mapper.toEntity
import im.vector.matrix.android.internal.database.model.ChunkEntity
import im.vector.matrix.android.internal.database.model.EventAnnotationsSummaryEntity
import im.vector.matrix.android.internal.database.model.ReadReceiptEntity
import im.vector.matrix.android.internal.database.model.ReadReceiptsSummaryEntity
import im.vector.matrix.android.internal.database.model.TimelineEventEntity
import im.vector.matrix.android.internal.database.query.fastContains
import im.vector.matrix.android.internal.database.query.getOrCreate
import im.vector.matrix.android.internal.database.query.where
import im.vector.matrix.android.internal.extensions.assertIsManaged
import im.vector.matrix.android.internal.session.room.timeline.PaginationDirection
import io.realm.Realm

internal fun ChunkEntity.deleteOnCascade() {
    assertIsManaged()
    this.stateEvents.deleteAllFromRealm()
    this.timelineEvents.deleteAllFromRealm()
    this.deleteFromRealm()
}

internal fun ChunkEntity.addStateEvent(stateEvent: Event) {
    if (stateEvent.eventId == null || stateEvents.fastContains(stateEvent.eventId)) {
        return
    } else {
        val entity = stateEvent.toEntity(roomId).apply {
            this.stateIndex = Int.MIN_VALUE
            this.isUnlinked = true
            this.sendState = SendState.SYNCED
        }
        stateEvents.add(entity)
    }
}

internal fun ChunkEntity.add(localRealm: Realm,
                             roomId: String,
                             event: Event,
                             direction: PaginationDirection,
                             stateIndexOffset: Int = 0,
                             isUnlinked: Boolean = false) {
    if (event.eventId == null || timelineEvents.fastContains(event.eventId)) {
        return
    }
    var currentDisplayIndex = lastDisplayIndex(direction, 0)
    if (direction == PaginationDirection.FORWARDS) {
        currentDisplayIndex += 1
        forwardsDisplayIndex = currentDisplayIndex
    } else {
        currentDisplayIndex -= 1
        backwardsDisplayIndex = currentDisplayIndex
    }
    var currentStateIndex = lastStateIndex(direction, defaultValue = stateIndexOffset)
    if (direction == PaginationDirection.FORWARDS && EventType.isStateEvent(event.type)) {
        currentStateIndex += 1
        forwardsStateIndex = currentStateIndex
    } else if (direction == PaginationDirection.BACKWARDS && timelineEvents.isNotEmpty()) {
        val lastEventType = timelineEvents.last()?.root?.type ?: ""
        if (EventType.isStateEvent(lastEventType)) {
            currentStateIndex -= 1
            backwardsStateIndex = currentStateIndex
        }
    }
    val localId = TimelineEventEntity.nextId(localRealm)
    val eventId = event.eventId
    val senderId = event.senderId ?: ""

    val readReceiptsSummaryEntity = ReadReceiptsSummaryEntity.where(localRealm, eventId).findFirst()
            ?: ReadReceiptsSummaryEntity(eventId, roomId)

    // Update RR for the sender of a new message with a dummy one

    if (event.originServerTs != null) {
        val timestampOfEvent = event.originServerTs.toDouble()
        val readReceiptOfSender = ReadReceiptEntity.getOrCreate(localRealm, roomId = roomId, userId = senderId)
        // If the synced RR is older, update
        if (timestampOfEvent > readReceiptOfSender.originServerTs) {
            val previousReceiptsSummary = ReadReceiptsSummaryEntity.where(localRealm, eventId = readReceiptOfSender.eventId).findFirst()
            readReceiptOfSender.eventId = eventId
            readReceiptOfSender.originServerTs = timestampOfEvent
            previousReceiptsSummary?.readReceipts?.remove(readReceiptOfSender)
            readReceiptsSummaryEntity.readReceipts.add(readReceiptOfSender)
        }
    }

    val eventEntity = TimelineEventEntity(localId).also {
        it.root = event.toEntity(roomId).apply {
            this.stateIndex = currentStateIndex
            this.isUnlinked = isUnlinked
            this.displayIndex = currentDisplayIndex
            this.sendState = SendState.SYNCED
        }
        it.eventId = eventId
        it.roomId = roomId
        it.annotations = EventAnnotationsSummaryEntity.where(localRealm, eventId).findFirst()
        it.readReceipts = readReceiptsSummaryEntity
    }
    eventEntity.updateSenderData(localRealm, this)
    val position = if (direction == PaginationDirection.FORWARDS) 0 else this.timelineEvents.size
    timelineEvents.add(position, eventEntity)
}

internal fun ChunkEntity.lastDisplayIndex(direction: PaginationDirection, defaultValue: Int = 0): Int {
    return when (direction) {
        PaginationDirection.FORWARDS  -> forwardsDisplayIndex
        PaginationDirection.BACKWARDS -> backwardsDisplayIndex
    } ?: defaultValue
}

internal fun ChunkEntity.lastStateIndex(direction: PaginationDirection, defaultValue: Int = 0): Int {
    return when (direction) {
        PaginationDirection.FORWARDS  -> forwardsStateIndex
        PaginationDirection.BACKWARDS -> backwardsStateIndex
    } ?: defaultValue
}
