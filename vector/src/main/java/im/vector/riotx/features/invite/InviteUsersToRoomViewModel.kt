/*
 * Copyright (c) 2020 New Vector Ltd
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

package im.vector.riotx.features.invite

import com.airbnb.mvrx.ActivityViewModelContext
import com.airbnb.mvrx.MvRxViewModelFactory
import com.airbnb.mvrx.ViewModelContext
import com.squareup.inject.assisted.Assisted
import com.squareup.inject.assisted.AssistedInject
import im.vector.matrix.android.api.session.Session
import im.vector.matrix.android.api.session.user.model.User
import im.vector.matrix.rx.rx
import im.vector.riotx.R
import im.vector.riotx.core.platform.VectorViewModel
import im.vector.riotx.core.resources.StringProvider
import io.reactivex.Observable

class InviteUsersToRoomViewModel @AssistedInject constructor(@Assisted
                                                             initialState: InviteUsersToRoomViewState,
                                                             session: Session,
                                                             val stringProvider: StringProvider)
    : VectorViewModel<InviteUsersToRoomViewState, InviteUsersToRoomAction, InviteUsersToRoomViewEvents>(initialState) {

    private val room = session.getRoom(initialState.roomId)!!

    @AssistedInject.Factory
    interface Factory {
        fun create(initialState: InviteUsersToRoomViewState): InviteUsersToRoomViewModel
    }

    companion object : MvRxViewModelFactory<InviteUsersToRoomViewModel, InviteUsersToRoomViewState> {

        @JvmStatic
        override fun create(viewModelContext: ViewModelContext, state: InviteUsersToRoomViewState): InviteUsersToRoomViewModel? {
            val activity: InviteUsersToRoomActivity = (viewModelContext as ActivityViewModelContext).activity()
            return activity.inviteUsersToRoomViewModelFactory.create(state)
        }
    }

    override fun handle(action: InviteUsersToRoomAction) {
        when (action) {
            is InviteUsersToRoomAction.InviteSelectedUsers -> inviteUsersToRoom(action.selectedUsers)
        }
    }

    private fun inviteUsersToRoom(selectedUsers: Set<User>) {
        _viewEvents.post(InviteUsersToRoomViewEvents.Loading)

        Observable.fromIterable(selectedUsers).flatMapCompletable { user ->
            room.rx().invite(user.userId, null)
        }.subscribe(
                {
                    val successMessage = when (selectedUsers.size) {
                        1    -> stringProvider.getString(R.string.invitation_sent_to_one_user,
                                selectedUsers.first().getBestName())
                        2    -> stringProvider.getString(R.string.invitations_sent_to_two_users,
                                selectedUsers.first().getBestName(),
                                selectedUsers.last().getBestName())
                        else -> stringProvider.getQuantityString(R.plurals.invitations_sent_to_one_and_more_users,
                                selectedUsers.size - 1,
                                selectedUsers.first().getBestName(),
                                selectedUsers.size - 1)
                    }
                    _viewEvents.post(InviteUsersToRoomViewEvents.Success(successMessage))
                },
                {
                    _viewEvents.post(InviteUsersToRoomViewEvents.Failure(it))
                })
                .disposeOnClear()
    }

    fun getUserIdsOfRoomMembers(): Set<String> {
        return room.roomSummary()?.otherMemberIds?.toSet() ?: emptySet()
    }
}
