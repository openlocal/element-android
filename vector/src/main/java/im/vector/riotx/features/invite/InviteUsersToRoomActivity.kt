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

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.view.View
import androidx.appcompat.app.AlertDialog
import com.airbnb.mvrx.MvRx
import com.airbnb.mvrx.viewModel
import im.vector.matrix.android.api.failure.Failure
import im.vector.riotx.R
import im.vector.riotx.core.di.ScreenComponent
import im.vector.riotx.core.error.ErrorFormatter
import im.vector.riotx.core.extensions.addFragment
import im.vector.riotx.core.extensions.addFragmentToBackstack
import im.vector.riotx.core.platform.SimpleFragmentActivity
import im.vector.riotx.core.platform.WaitingViewData
import im.vector.riotx.core.utils.toast
import im.vector.riotx.features.userdirectory.KnownUsersFragment
import im.vector.riotx.features.userdirectory.KnownUsersFragmentArgs
import im.vector.riotx.features.userdirectory.UserDirectoryFragment
import im.vector.riotx.features.userdirectory.UserDirectorySharedAction
import im.vector.riotx.features.userdirectory.UserDirectorySharedActionViewModel
import im.vector.riotx.features.userdirectory.UserDirectoryViewModel
import kotlinx.android.parcel.Parcelize
import kotlinx.android.synthetic.main.activity.*
import java.net.HttpURLConnection
import javax.inject.Inject

@Parcelize
data class InviteUsersToRoomArgs(val roomId: String) : Parcelable

class InviteUsersToRoomActivity : SimpleFragmentActivity() {

    private val viewModel: InviteUsersToRoomViewModel by viewModel()
    private lateinit var sharedActionViewModel: UserDirectorySharedActionViewModel
    @Inject lateinit var userDirectoryViewModelFactory: UserDirectoryViewModel.Factory
    @Inject lateinit var inviteUsersToRoomViewModelFactory: InviteUsersToRoomViewModel.Factory
    @Inject lateinit var errorFormatter: ErrorFormatter

    override fun injectWith(injector: ScreenComponent) {
        super.injectWith(injector)
        injector.inject(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        toolbar.visibility = View.GONE
        sharedActionViewModel = viewModelProvider.get(UserDirectorySharedActionViewModel::class.java)
        sharedActionViewModel
                .observe()
                .subscribe { sharedAction ->
                    when (sharedAction) {
                        UserDirectorySharedAction.OpenUsersDirectory    ->
                            addFragmentToBackstack(R.id.container, UserDirectoryFragment::class.java)
                        UserDirectorySharedAction.Close                 -> finish()
                        UserDirectorySharedAction.GoBack                -> onBackPressed()
                        is UserDirectorySharedAction.OnMenuItemSelected -> onMenuItemSelected(sharedAction)
                    }
                }
                .disposeOnDestroy()
        if (isFirstCreation()) {
            addFragment(
                    R.id.container,
                    KnownUsersFragment::class.java,
                    KnownUsersFragmentArgs(
                            title = getString(R.string.invite_users_to_room_title),
                            menuResId = R.menu.vector_invite_users_to_room,
                            excludedUserIds = viewModel.getUserIdsOfRoomMembers()
                    )
            )
        }

        viewModel.observeViewEvents { renderInviteEvents(it) }
    }

    private fun onMenuItemSelected(action: UserDirectorySharedAction.OnMenuItemSelected) {
        if (action.itemId == R.id.action_invite_users_to_room_invite) {
            viewModel.handle(InviteUsersToRoomAction.InviteSelectedUsers(action.selectedUsers))
        }
    }

    private fun renderInviteEvents(viewEvent: InviteUsersToRoomViewEvents) {
        when (viewEvent) {
            is InviteUsersToRoomViewEvents.Loading -> renderInviteLoading()
            is InviteUsersToRoomViewEvents.Success -> renderInvitationSuccess(viewEvent.successMessage)
            is InviteUsersToRoomViewEvents.Failure -> renderInviteFailure(viewEvent.throwable)
        }
    }

    private fun renderInviteLoading() {
        updateWaitingView(WaitingViewData(getString(R.string.inviting_users_to_room)))
    }

    private fun renderInviteFailure(error: Throwable) {
        hideWaitingView()
        val message = if (error is Failure.ServerError && error.httpCode == HttpURLConnection.HTTP_INTERNAL_ERROR /*500*/) {
            // This error happen if the invited userId does not exist.
            getString(R.string.invite_users_to_room_failure)
        } else {
            errorFormatter.toHumanReadable(error)
        }
        AlertDialog.Builder(this)
                .setMessage(message)
                .setPositiveButton(R.string.ok, null)
                .show()
    }

    private fun renderInvitationSuccess(successMessage: String) {
        toast(successMessage)
        finish()
    }

    companion object {

        fun getIntent(context: Context, roomId: String): Intent {
            return Intent(context, InviteUsersToRoomActivity::class.java).also {
                it.putExtra(MvRx.KEY_ARG, InviteUsersToRoomArgs(roomId))
            }
        }
    }
}
