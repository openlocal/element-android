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

package im.vector.riotx.features.roomprofile.uploads.media

import android.view.View
import android.widget.ImageView
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModelClass
import im.vector.riotx.R
import im.vector.riotx.core.epoxy.VectorEpoxyHolder
import im.vector.riotx.core.epoxy.VectorEpoxyModel
import im.vector.riotx.features.media.ImageContentRenderer

@EpoxyModelClass(layout = R.layout.item_uploads_image)
abstract class UploadsImageItem : VectorEpoxyModel<UploadsImageItem.Holder>() {

    @EpoxyAttribute lateinit var imageContentRenderer: ImageContentRenderer
    @EpoxyAttribute lateinit var data: ImageContentRenderer.Data

    @EpoxyAttribute var listener: Listener? = null

    override fun bind(holder: Holder) {
        super.bind(holder)
        holder.view.setOnClickListener { listener?.onItemClicked(holder.imageView, data) }
        imageContentRenderer.render(data, holder.imageView, IMAGE_SIZE_DP)
    }

    class Holder : VectorEpoxyHolder() {
        val imageView by bind<ImageView>(R.id.uploadsImagePreview)
    }

    interface Listener {
        fun onItemClicked(view: View, data: ImageContentRenderer.Data)
    }
}
