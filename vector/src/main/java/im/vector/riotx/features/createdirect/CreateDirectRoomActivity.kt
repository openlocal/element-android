/*
 *
 *  * Copyright 2019 New Vector Ltd
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *     http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package im.vector.riotx.features.createdirect

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import com.airbnb.mvrx.Async
import com.airbnb.mvrx.Fail
import com.airbnb.mvrx.Loading
import com.airbnb.mvrx.Success
import com.airbnb.mvrx.viewModel
import im.vector.matrix.android.api.failure.Failure
import im.vector.matrix.android.api.session.room.failure.CreateRoomFailure
import im.vector.riotx.R
import im.vector.riotx.core.di.ScreenComponent
import im.vector.riotx.core.error.ErrorFormatter
import im.vector.riotx.core.extensions.addFragment
import im.vector.riotx.core.extensions.addFragmentToBackstack
import im.vector.riotx.core.platform.SimpleFragmentActivity
import im.vector.riotx.core.platform.WaitingViewData
import im.vector.riotx.features.userdirectory.KnownUsersFragment
import im.vector.riotx.features.userdirectory.KnownUsersFragmentArgs
import im.vector.riotx.features.userdirectory.UserDirectoryFragment
import im.vector.riotx.features.userdirectory.UserDirectorySharedAction
import im.vector.riotx.features.userdirectory.UserDirectorySharedActionViewModel
import im.vector.riotx.features.userdirectory.UserDirectoryViewModel
import kotlinx.android.synthetic.main.activity.*
import java.net.HttpURLConnection
import javax.inject.Inject

class CreateDirectRoomActivity : SimpleFragmentActivity() {

    private val viewModel: CreateDirectRoomViewModel by viewModel()
    private lateinit var sharedActionViewModel: UserDirectorySharedActionViewModel
    @Inject lateinit var userDirectoryViewModelFactory: UserDirectoryViewModel.Factory
    @Inject lateinit var createDirectRoomViewModelFactory: CreateDirectRoomViewModel.Factory
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
                        UserDirectorySharedAction.OpenUsersDirectory ->
                            addFragmentToBackstack(R.id.container, UserDirectoryFragment::class.java)
                        UserDirectorySharedAction.Close           -> finish()
                        UserDirectorySharedAction.GoBack          -> onBackPressed()
                        is UserDirectorySharedAction.OnMenuItemSelected -> onMenuItemSelected(sharedAction)
                    }
                }
                .disposeOnDestroy()
        if (isFirstCreation()) {
            addFragment(
                    R.id.container,
                    KnownUsersFragment::class.java,
                    KnownUsersFragmentArgs(
                            title = getString(R.string.fab_menu_create_chat),
                            menuResId = R.menu.vector_create_direct_room
                    )
            )
        }
        viewModel.selectSubscribe(this, CreateDirectRoomViewState::createAndInviteState) {
            renderCreateAndInviteState(it)
        }
    }

    private fun onMenuItemSelected(action: UserDirectorySharedAction.OnMenuItemSelected) {
        if (action.itemId == R.id.action_create_direct_room) {
            viewModel.handle(CreateDirectRoomAction.CreateRoomAndInviteSelectedUsers(action.selectedUsers))
        }
    }

    private fun renderCreateAndInviteState(state: Async<String>) {
        when (state) {
            is Loading -> renderCreationLoading()
            is Success -> renderCreationSuccess(state())
            is Fail    -> renderCreationFailure(state.error)
        }
    }

    private fun renderCreationLoading() {
        updateWaitingView(WaitingViewData(getString(R.string.creating_direct_room)))
    }

    private fun renderCreationFailure(error: Throwable) {
        hideWaitingView()
        if (error is CreateRoomFailure.CreatedWithTimeout) {
            finish()
        } else {
            val message = if (error is Failure.ServerError && error.httpCode == HttpURLConnection.HTTP_INTERNAL_ERROR /*500*/) {
                // This error happen if the invited userId does not exist.
                getString(R.string.create_room_dm_failure)
            } else {
                errorFormatter.toHumanReadable(error)
            }
            AlertDialog.Builder(this)
                    .setMessage(message)
                    .setPositiveButton(R.string.ok, null)
                    .show()
        }
    }

    private fun renderCreationSuccess(roomId: String?) {
        // Navigate to freshly created room
        if (roomId != null) {
            navigator.openRoom(this, roomId)
        }
        finish()
    }

    companion object {
        fun getIntent(context: Context): Intent {
            return Intent(context, CreateDirectRoomActivity::class.java)
        }
    }
}
