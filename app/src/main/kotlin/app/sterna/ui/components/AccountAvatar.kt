package app.sterna.ui.components

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import app.sterna.core.data.account.ACCOUNT_PHOTO_DIR
import app.sterna.core.data.account.AccountPhotoFiles
import app.sterna.ui.decodeThumbnail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/** Side of a stored account photo, in pixels: a 40 dp disc on the densest screens, with room to spare. */
internal const val ACCOUNT_PHOTO_PX = 192

/**
 * An account's photo when it has one this phone can read, its [Monogram] otherwise, in the
 * monogram's own slot. The photo is a file the app wrote from a picture the user chose on the
 * phone: nothing is fetched. A [photo] whose file is missing or unreadable draws the letter.
 */
@Composable
fun AccountAvatar(
    photo: String?,
    seed: String,
    label: String,
    color: Color?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Keyed by the name, which changes with every new photo: a replaced photo is decoded afresh.
    val image by produceState(AccountPhotoCache.cached(photo), photo) {
        value = AccountPhotoCache.load(context, photo)
    }
    Box(modifier.size(MonogramSize)) {
        val shown = image
        if (shown != null) {
            Image(
                bitmap = shown,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(MonogramSize).clip(CircleShape),
            )
        } else {
            Monogram(seed = seed, label = label, color = color)
        }
    }
}

/** The decoded account photos, by file name; a handful at most, one per account. */
internal object AccountPhotoCache {
    private val cache = LruCache<String, ImageBitmap>(MAX_ENTRIES)

    fun cached(name: String?): ImageBitmap? = name?.let { cache.get(it) }

    suspend fun load(context: Context, name: String?): ImageBitmap? {
        if (name == null) return null
        cache.get(name)?.let { return it }
        return withContext(Dispatchers.IO) {
            val file = accountPhotoFiles(context).fileOf(name) ?: return@withContext null
            decodeThumbnail(file, ACCOUNT_PHOTO_PX)?.also { cache.put(name, it) }
        }
    }

    private const val MAX_ENTRIES = 24
}

internal fun accountPhotoFiles(context: Context): AccountPhotoFiles =
    AccountPhotoFiles(File(context.applicationContext.filesDir, ACCOUNT_PHOTO_DIR))

/**
 * The picture behind [uri] as an account photo: upright, cut to its centre square, at most
 * [ACCOUNT_PHOTO_PX] a side, encoded afresh as PNG. Null when it is no picture this phone decodes.
 * The chosen file itself is never copied, so neither its weight nor its metadata, an EXIF location
 * included, reaches the app's storage. Decoded with the thumbnails' bound, so a huge picture is
 * subsampled while it is read and never held whole.
 */
internal fun accountPhotoPng(resolver: ContentResolver, uri: Uri): ByteArray? {
    val image = decodeThumbnail(ACCOUNT_PHOTO_PX) { resolver.openInputStream(uri) } ?: return null
    return ByteArrayOutputStream().use { out ->
        if (!image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, out)) return null
        out.toByteArray()
    }
}
