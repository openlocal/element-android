/*
 * Copyright (c) 2021 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.app.test.fakes

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.matrix.android.sdk.api.session.room.location.LocationSharingService
import org.matrix.android.sdk.api.session.room.location.UpdateLiveLocationShareResult
import org.matrix.android.sdk.api.session.room.model.livelocation.LiveLocationShareAggregatedSummary
import org.matrix.android.sdk.api.util.Optional

class FakeLocationSharingService : LocationSharingService by mockk() {

    fun givenRunningLiveLocationShareSummariesReturns(
            summaries: List<LiveLocationShareAggregatedSummary>
    ): LiveData<List<LiveLocationShareAggregatedSummary>> {
        return MutableLiveData(summaries).also {
            every { getRunningLiveLocationShareSummaries() } returns it
        }
    }

    fun givenLiveLocationShareSummaryReturns(
            eventId: String,
            summary: LiveLocationShareAggregatedSummary
    ): LiveData<Optional<LiveLocationShareAggregatedSummary>> {
        return MutableLiveData(Optional(summary)).also {
            every { getLiveLocationShareSummary(eventId) } returns it
        }
    }

    fun givenStopLiveLocationShareReturns(result: UpdateLiveLocationShareResult) {
        coEvery { stopLiveLocationShare() } returns result
    }
}