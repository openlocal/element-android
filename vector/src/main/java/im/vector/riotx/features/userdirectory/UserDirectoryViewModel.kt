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

package im.vector.riotx.features.userdirectory

import androidx.fragment.app.FragmentActivity
import arrow.core.Option
import com.airbnb.mvrx.ActivityViewModelContext
import com.airbnb.mvrx.FragmentViewModelContext
import com.airbnb.mvrx.MvRxViewModelFactory
import com.airbnb.mvrx.ViewModelContext
import com.jakewharton.rxrelay2.BehaviorRelay
import com.squareup.inject.assisted.Assisted
import com.squareup.inject.assisted.AssistedInject
import im.vector.matrix.android.api.session.Session
import im.vector.matrix.android.api.util.toMatrixItem
import im.vector.matrix.rx.rx
import im.vector.riotx.core.extensions.toggle
import im.vector.riotx.core.platform.VectorViewModel
import im.vector.riotx.features.createdirect.CreateDirectRoomActivity
import im.vector.riotx.features.invite.InviteUsersToRoomActivity
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
import java.util.concurrent.TimeUnit

private typealias KnowUsersFilter = String
private typealias DirectoryUsersSearch = String

class UserDirectoryViewModel @AssistedInject constructor(@Assisted
                                                         initialState: UserDirectoryViewState,
                                                         private val session: Session)
    : VectorViewModel<UserDirectoryViewState, UserDirectoryAction, UserDirectoryViewEvents>(initialState) {

    @AssistedInject.Factory
    interface Factory {
        fun create(initialState: UserDirectoryViewState): UserDirectoryViewModel
    }

    private val knownUsersFilter = BehaviorRelay.createDefault<Option<KnowUsersFilter>>(Option.empty())
    private val directoryUsersSearch = BehaviorRelay.create<DirectoryUsersSearch>()

    companion object : MvRxViewModelFactory<UserDirectoryViewModel, UserDirectoryViewState> {

        override fun create(viewModelContext: ViewModelContext, state: UserDirectoryViewState): UserDirectoryViewModel? {
            return when (viewModelContext) {
                is FragmentViewModelContext -> (viewModelContext.fragment() as KnownUsersFragment).userDirectoryViewModelFactory.create(state)
                is ActivityViewModelContext -> {
                    when (viewModelContext.activity<FragmentActivity>()) {
                        is CreateDirectRoomActivity -> viewModelContext.activity<CreateDirectRoomActivity>().userDirectoryViewModelFactory.create(state)
                        is InviteUsersToRoomActivity -> viewModelContext.activity<InviteUsersToRoomActivity>().userDirectoryViewModelFactory.create(state)
                        else                        -> error("Wrong activity or fragment")
                    }
                }
                else                        -> error("Wrong activity or fragment")
            }
        }
    }

    init {
        observeKnownUsers()
        observeDirectoryUsers()
    }

    override fun handle(action: UserDirectoryAction) {
        when (action) {
            is UserDirectoryAction.FilterKnownUsers      -> knownUsersFilter.accept(Option.just(action.value))
            is UserDirectoryAction.ClearFilterKnownUsers -> knownUsersFilter.accept(Option.empty())
            is UserDirectoryAction.SearchDirectoryUsers  -> directoryUsersSearch.accept(action.value)
            is UserDirectoryAction.SelectUser            -> handleSelectUser(action)
            is UserDirectoryAction.RemoveSelectedUser    -> handleRemoveSelectedUser(action)
        }
    }

    private fun handleRemoveSelectedUser(action: UserDirectoryAction.RemoveSelectedUser) = withState { state ->
        val selectedUsers = state.selectedUsers.minus(action.user)
        setState { copy(selectedUsers = selectedUsers) }
    }

    private fun handleSelectUser(action: UserDirectoryAction.SelectUser) = withState { state ->
        // Reset the filter asap
        directoryUsersSearch.accept("")
        val selectedUsers = state.selectedUsers.toggle(action.user)
        setState { copy(selectedUsers = selectedUsers) }
    }

    private fun observeDirectoryUsers() = withState { state ->
        directoryUsersSearch
                .debounce(300, TimeUnit.MILLISECONDS)
                .switchMapSingle { search ->
                    val stream = if (search.isBlank()) {
                        Single.just(emptyList())
                    } else {
                        session.rx()
                                .searchUsersDirectory(search, 50, state.excludedUserIds ?: emptySet())
                                .map { users ->
                                    users.sortedBy { it.toMatrixItem().firstLetterOfDisplayName() }
                                }
                    }
                    stream.toAsync {
                        copy(directoryUsers = it, directorySearchTerm = search)
                    }
                }
                .subscribe()
                .disposeOnClear()
    }

    private fun observeKnownUsers() = withState { state ->
        knownUsersFilter
                .throttleLast(300, TimeUnit.MILLISECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .switchMap {
                    session.rx().livePagedUsers(it.orNull(), state.excludedUserIds)
                }
                .execute { async ->
                    copy(
                            knownUsers = async,
                            filterKnownUsersValue = knownUsersFilter.value ?: Option.empty()
                    )
                }
    }
}
