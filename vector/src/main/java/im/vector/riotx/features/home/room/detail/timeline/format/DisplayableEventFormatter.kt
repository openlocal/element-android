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

package im.vector.riotx.features.home.room.detail.timeline.format

import im.vector.matrix.android.api.session.events.model.EventType
import im.vector.matrix.android.api.session.room.model.message.MessageType
import im.vector.matrix.android.api.session.room.model.message.isReply
import im.vector.matrix.android.api.session.room.timeline.TimelineEvent
import im.vector.matrix.android.api.session.room.timeline.getLastMessageContent
import im.vector.matrix.android.api.session.room.timeline.getTextEditableContent
import im.vector.riotx.R
import im.vector.riotx.core.resources.ColorProvider
import im.vector.riotx.core.resources.StringProvider
import me.gujun.android.span.span
import javax.inject.Inject

class DisplayableEventFormatter @Inject constructor(
        private val stringProvider: StringProvider,
        private val colorProvider: ColorProvider,
        private val noticeEventFormatter: NoticeEventFormatter
) {

    fun format(timelineEvent: TimelineEvent, prependAuthor: Boolean): CharSequence {
        if (timelineEvent.root.isRedacted()) {
            return noticeEventFormatter.formatRedactedEvent(timelineEvent.root)
        }

        if (timelineEvent.root.isEncrypted()
                && timelineEvent.root.mxDecryptionResult == null) {
            return stringProvider.getString(R.string.encrypted_message)
        }

        val senderName = timelineEvent.senderInfo.disambiguatedDisplayName

        return when (timelineEvent.root.getClearType()) {
            EventType.MESSAGE -> {
                timelineEvent.getLastMessageContent()?.let { messageContent ->
                    when (messageContent.msgType) {
                        MessageType.MSGTYPE_VERIFICATION_REQUEST ->
                            simpleFormat(senderName, stringProvider.getString(R.string.verification_request), prependAuthor)
                        MessageType.MSGTYPE_IMAGE                ->
                            simpleFormat(senderName, stringProvider.getString(R.string.sent_an_image), prependAuthor)
                        MessageType.MSGTYPE_AUDIO                ->
                            simpleFormat(senderName, stringProvider.getString(R.string.sent_an_audio_file), prependAuthor)
                        MessageType.MSGTYPE_VIDEO                ->
                            simpleFormat(senderName, stringProvider.getString(R.string.sent_a_video), prependAuthor)
                        MessageType.MSGTYPE_FILE                 ->
                            simpleFormat(senderName, stringProvider.getString(R.string.sent_a_file), prependAuthor)
                        MessageType.MSGTYPE_TEXT                 ->
                            if (messageContent.isReply()) {
                                // Skip reply prefix, and show important
                                // TODO add a reply image span ?
                                simpleFormat(senderName, timelineEvent.getTextEditableContent() ?: messageContent.body, prependAuthor)
                            } else {
                                simpleFormat(senderName, messageContent.body, prependAuthor)
                            }
                        else                                     ->
                            simpleFormat(senderName, messageContent.body, prependAuthor)
                    }
                } ?: span { }
            }
            else              ->
                span {
                    text = noticeEventFormatter.format(timelineEvent) ?: ""
                    textStyle = "italic"
                }
        }
    }

    private fun simpleFormat(senderName: String, body: CharSequence, prependAuthor: Boolean): CharSequence {
        return if (prependAuthor) {
            span {
                text = senderName
                textColor = colorProvider.getColorFromAttribute(R.attr.riotx_text_primary)
            }
                    .append(": ")
                    .append(body)
        } else {
            body
        }
    }
}
