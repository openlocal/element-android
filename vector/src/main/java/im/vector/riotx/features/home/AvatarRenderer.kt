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

package im.vector.riotx.features.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.annotation.AnyThread
import androidx.annotation.UiThread
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.amulyakhare.textdrawable.TextDrawable
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.DrawableImageViewTarget
import com.bumptech.glide.request.target.Target
import im.vector.matrix.android.api.session.content.ContentUrlResolver
import im.vector.matrix.android.api.util.MatrixItem
import im.vector.riotx.core.di.ActiveSessionHolder
import im.vector.riotx.core.glide.GlideApp
import im.vector.riotx.core.glide.GlideRequest
import im.vector.riotx.core.glide.GlideRequests
import im.vector.riotx.core.utils.getColorFromUserId
import javax.inject.Inject

/**
 * This helper centralise ways to retrieve avatar into ImageView or even generic Target<Drawable>
 */

class AvatarRenderer @Inject constructor(private val activeSessionHolder: ActiveSessionHolder) {

    companion object {
        private const val THUMBNAIL_SIZE = 250
    }

    @UiThread
    fun render(matrixItem: MatrixItem, imageView: ImageView) {
        render(imageView.context,
                GlideApp.with(imageView),
                matrixItem,
                DrawableImageViewTarget(imageView))
    }

    @UiThread
    fun render(matrixItem: MatrixItem, imageView: ImageView, glideRequests: GlideRequests) {
        render(imageView.context,
                glideRequests,
                matrixItem,
                DrawableImageViewTarget(imageView))
    }

    @UiThread
    fun render(context: Context,
               glideRequest: GlideRequests,
               matrixItem: MatrixItem,
               target: Target<Drawable>) {
        val placeholder = getPlaceholderDrawable(context, matrixItem)
        buildGlideRequest(glideRequest, matrixItem.avatarUrl)
                .placeholder(placeholder)
                .into(target)
    }

    @AnyThread
    fun shortcutDrawable(context: Context, glideRequest: GlideRequests, matrixItem: MatrixItem, iconSize: Int): Bitmap {
        return glideRequest
                .asBitmap()
                .apply {
                    val resolvedUrl = resolvedUrl(matrixItem.avatarUrl)
                    if (resolvedUrl != null) {
                        load(resolvedUrl)
                    } else {
                        val avatarColor = avatarColor(matrixItem, context)
                        load(TextDrawable.builder()
                                .beginConfig()
                                .bold()
                                .endConfig()
                                .buildRect(matrixItem.firstLetterOfDisplayName(), avatarColor)
                                .toBitmap(width = iconSize, height = iconSize))
                    }
                }
                .submit(iconSize, iconSize)
                .get()
    }

    @AnyThread
    fun getCachedDrawable(glideRequest: GlideRequests, matrixItem: MatrixItem): Drawable {
        return buildGlideRequest(glideRequest, matrixItem.avatarUrl)
                .onlyRetrieveFromCache(true)
                .submit()
                .get()
    }

    @AnyThread
    fun getPlaceholderDrawable(context: Context, matrixItem: MatrixItem): Drawable {
        val avatarColor = avatarColor(matrixItem, context)
        return TextDrawable.builder()
                .beginConfig()
                .bold()
                .endConfig()
                .buildRound(matrixItem.firstLetterOfDisplayName(), avatarColor)
    }

    // PRIVATE API *********************************************************************************

    private fun buildGlideRequest(glideRequest: GlideRequests, avatarUrl: String?): GlideRequest<Drawable> {
        val resolvedUrl = resolvedUrl(avatarUrl)
        return glideRequest
                .load(resolvedUrl)
                .apply(RequestOptions.circleCropTransform())
    }

    private fun resolvedUrl(avatarUrl: String?): String? {
        return activeSessionHolder.getSafeActiveSession()?.contentUrlResolver()
                ?.resolveThumbnail(avatarUrl, THUMBNAIL_SIZE, THUMBNAIL_SIZE, ContentUrlResolver.ThumbnailMethod.SCALE)
    }

    private fun avatarColor(matrixItem: MatrixItem, context: Context): Int {
        return when (matrixItem) {
            is MatrixItem.UserItem -> ContextCompat.getColor(context, getColorFromUserId(matrixItem.id))
            else                   -> ContextCompat.getColor(context, getColorFromRoomId(matrixItem.id))
        }
    }
}
