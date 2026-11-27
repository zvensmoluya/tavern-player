package io.github.zvensmoluya.tavernplayer.conversation.web

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.net.Uri
import android.util.Base64
import io.github.zvensmoluya.tavernplayer.conversation.MessageRole
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

internal class ChatAvatars(val images: Map<MessageRole, Bitmap>) {
    companion object { val Empty = ChatAvatars(emptyMap()) }

    val webSources = buildJsonObject {
        images.forEach { (role, image) ->
            val bytes = ByteArrayOutputStream()
            if (image.compress(Bitmap.CompressFormat.PNG, 100, bytes)) {
                put(role.name.lowercase(), "data:image/png;base64," + Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP))
            }
        }
    }
}

private const val THUMBNAIL_SIZE = 96

internal fun ContentResolver.chatThumbnail(uriOrPath: String?): Bitmap? {
    if (uriOrPath.isNullOrBlank()) return null
    val parsed = Uri.parse(uriOrPath)
    val uri = if (parsed.scheme == null) Uri.fromFile(File(uriOrPath)) else parsed
    if (uri.scheme != ContentResolver.SCHEME_FILE && uri.scheme != ContentResolver.SCHEME_CONTENT) return null
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > THUMBNAIL_SIZE * 2) inSampleSize *= 2
        }
        val image = openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        ThumbnailUtils.extractThumbnail(image, THUMBNAIL_SIZE, THUMBNAIL_SIZE, ThumbnailUtils.OPTIONS_RECYCLE_INPUT)
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
