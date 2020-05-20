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

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ScrollView
import androidx.core.view.forEach
import com.airbnb.mvrx.activityViewModel
import com.airbnb.mvrx.args
import com.airbnb.mvrx.withState
import com.google.android.material.chip.Chip
import com.jakewharton.rxbinding3.widget.textChanges
import im.vector.matrix.android.api.session.user.model.User
import im.vector.riotx.R
import im.vector.riotx.core.extensions.cleanup
import im.vector.riotx.core.extensions.configureWith
import im.vector.riotx.core.extensions.hideKeyboard
import im.vector.riotx.core.extensions.setupAsSearch
import im.vector.riotx.core.platform.VectorBaseFragment
import im.vector.riotx.core.utils.DimensionConverter
import kotlinx.android.synthetic.main.fragment_known_users.*
import javax.inject.Inject

class KnownUsersFragment @Inject constructor(
        val userDirectoryViewModelFactory: UserDirectoryViewModel.Factory,
        private val knownUsersController: KnownUsersController,
        private val dimensionConverter: DimensionConverter
) : VectorBaseFragment(), KnownUsersController.Callback {

    private val args: KnownUsersFragmentArgs by args()

    override fun getLayoutResId() = R.layout.fragment_known_users

    override fun getMenuRes() = args.menuResId

    private val viewModel: UserDirectoryViewModel by activityViewModel()
    private lateinit var sharedActionViewModel: UserDirectorySharedActionViewModel

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        sharedActionViewModel = activityViewModelProvider.get(UserDirectorySharedActionViewModel::class.java)

        knownUsersTitle.text = args.title

        vectorBaseActivity.setSupportActionBar(knownUsersToolbar)
        setupRecyclerView()
        setupFilterView()
        setupAddByMatrixIdView()
        setupCloseView()
        viewModel.selectSubscribe(this, UserDirectoryViewState::selectedUsers) {
            renderSelectedUsers(it)
        }
    }

    override fun onDestroyView() {
        knownUsersController.callback = null
        recyclerView.cleanup()
        super.onDestroyView()
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        withState(viewModel) {
            val showMenuItem = it.selectedUsers.isNotEmpty()
            menu.forEach { menuItem ->
                menuItem.isVisible = showMenuItem
            }
        }
        super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = withState(viewModel) {
        sharedActionViewModel.post(UserDirectorySharedAction.OnMenuItemSelected(item.itemId, it.selectedUsers))
        return@withState true
    }

    private fun setupAddByMatrixIdView() {
        addByMatrixId.debouncedClicks {
            sharedActionViewModel.post(UserDirectorySharedAction.OpenUsersDirectory)
        }
    }

    private fun setupRecyclerView() {
        knownUsersController.callback = this
        // Don't activate animation as we might have way to much item animation when filtering
        recyclerView.configureWith(knownUsersController, disableItemAnimation = true)
    }

    private fun setupFilterView() {
        knownUsersFilter
                .textChanges()
                .startWith(knownUsersFilter.text)
                .subscribe { text ->
                    val filterValue = text.trim()
                    val action = if (filterValue.isBlank()) {
                        UserDirectoryAction.ClearFilterKnownUsers
                    } else {
                        UserDirectoryAction.FilterKnownUsers(filterValue.toString())
                    }
                    viewModel.handle(action)
                }
                .disposeOnDestroyView()

        knownUsersFilter.setupAsSearch()
        knownUsersFilter.requestFocus()
    }

    private fun setupCloseView() {
        knownUsersClose.debouncedClicks {
            requireActivity().finish()
        }
    }

    override fun invalidate() = withState(viewModel) {
        knownUsersController.setData(it)
    }

    private fun renderSelectedUsers(selectedUsers: Set<User>) {
        invalidateOptionsMenu()

        val currentNumberOfChips = chipGroup.childCount
        val newNumberOfChips = selectedUsers.size

        chipGroup.removeAllViews()
        selectedUsers.forEach { addChipToGroup(it) }

        // Scroll to the bottom when adding chips. When removing chips, do not scroll
        if (newNumberOfChips >= currentNumberOfChips) {
            chipGroupScrollView.post {
                chipGroupScrollView.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
    }

    private fun addChipToGroup(user: User) {
        val chip = Chip(requireContext())
        chip.setChipBackgroundColorResource(android.R.color.transparent)
        chip.chipStrokeWidth = dimensionConverter.dpToPx(1).toFloat()
        chip.text = user.getBestName()
        chip.isClickable = true
        chip.isCheckable = false
        chip.isCloseIconVisible = true
        chipGroup.addView(chip)
        chip.setOnCloseIconClickListener {
            viewModel.handle(UserDirectoryAction.RemoveSelectedUser(user))
        }
    }

    override fun onItemClick(user: User) {
        view?.hideKeyboard()
        viewModel.handle(UserDirectoryAction.SelectUser(user))
    }
}
