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

package im.vector.riotx.features.crypto.recover

import android.app.Activity.RESULT_OK
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.text.toSpannable
import androidx.core.view.isVisible
import com.airbnb.mvrx.parentFragmentViewModel
import com.airbnb.mvrx.withState
import im.vector.riotx.R
import im.vector.riotx.core.platform.VectorBaseFragment
import im.vector.riotx.core.resources.ColorProvider
import im.vector.riotx.core.utils.colorizeMatchingText
import im.vector.riotx.core.utils.startSharePlainTextIntent
import im.vector.riotx.core.utils.toast
import kotlinx.android.synthetic.main.fragment_bootstrap_save_key.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import javax.inject.Inject

class BootstrapSaveRecoveryKeyFragment @Inject constructor(
        private val colorProvider: ColorProvider
) : VectorBaseFragment() {

    override fun getLayoutResId() = R.layout.fragment_bootstrap_save_key

    val sharedViewModel: BootstrapSharedViewModel by parentFragmentViewModel()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val messageKey = getString(R.string.message_key)
        val recoveryPassphrase = getString(R.string.recovery_passphrase)
        val color = colorProvider.getColorFromAttribute(R.attr.vctr_toolbar_link_text_color)
        bootstrapSaveText.text = getString(R.string.bootstrap_save_key_description, messageKey, recoveryPassphrase)
                .toSpannable()
                .colorizeMatchingText(messageKey, color)
                .colorizeMatchingText(recoveryPassphrase, color)

        recoverySave.clickableView.debouncedClicks { downloadRecoveryKey() }
        recoveryCopy.clickableView.debouncedClicks { shareRecoveryKey() }
        recoveryContinue.clickableView.debouncedClicks { sharedViewModel.handle(BootstrapActions.GoToCompleted) }
    }

    private fun downloadRecoveryKey() = withState(sharedViewModel) { _ ->

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "text/plain"
        intent.putExtra(Intent.EXTRA_TITLE, "riot-recovery-key.txt")

        try {
            sharedViewModel.handle(BootstrapActions.SaveReqQueryStarted)
            startActivityForResult(Intent.createChooser(intent, getString(R.string.keys_backup_setup_step3_please_make_copy)), REQUEST_CODE_SAVE)
        } catch (activityNotFoundException: ActivityNotFoundException) {
            requireActivity().toast(R.string.error_no_external_application_found)
            sharedViewModel.handle(BootstrapActions.SaveReqFailed)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_CODE_SAVE) {
            val uri = data?.data
            if (resultCode == RESULT_OK && uri != null) {
                GlobalScope.launch(Dispatchers.IO) {
                    try {
                        sharedViewModel.handle(BootstrapActions.SaveKeyToUri(context!!.contentResolver!!.openOutputStream(uri)!!))
                    } catch (failure: Throwable) {
                        sharedViewModel.handle(BootstrapActions.SaveReqFailed)
                    }
                }
            } else {
                // result code seems to be always cancelled here.. so act as if it was saved
                sharedViewModel.handle(BootstrapActions.SaveReqFailed)
            }
            return
        } else if (requestCode == REQUEST_CODE_COPY) {
            sharedViewModel.handle(BootstrapActions.RecoveryKeySaved)
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun shareRecoveryKey() = withState(sharedViewModel) { state ->
        val recoveryKey = state.recoveryKeyCreationInfo?.recoveryKey?.formatRecoveryKey()
                ?: return@withState

        startSharePlainTextIntent(this,
                context?.getString(R.string.keys_backup_setup_step3_share_intent_chooser_title),
                recoveryKey,
                context?.getString(R.string.recovery_key), REQUEST_CODE_COPY)
    }

    override fun invalidate() = withState(sharedViewModel) { state ->
        val step = state.step
        if (step !is BootstrapStep.SaveRecoveryKey) return@withState

        recoveryContinue.isVisible = step.isSaved
        bootstrapRecoveryKeyText.text = state.recoveryKeyCreationInfo?.recoveryKey?.formatRecoveryKey()
    }

    companion object {
        const val REQUEST_CODE_SAVE = 123
        const val REQUEST_CODE_COPY = 124
    }
}
