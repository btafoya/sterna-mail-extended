package app.sterna.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract.CommonDataKinds.Email
import androidx.core.content.ContextCompat

/** Reads email addresses from the device's contacts, only when the user has granted the permission. */
object AndroidContacts {
    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Every address of the phone's contacts, with the contact's thumbnail URI when they have a picture.
     */
    fun all(context: Context): List<ContactSuggestion> {
        if (!hasPermission(context)) return emptyList()
        val results = mutableListOf<ContactSuggestion>()
        val projection = arrayOf(Email.ADDRESS, Email.DISPLAY_NAME_PRIMARY, Email.PHOTO_THUMBNAIL_URI)
        runCatching {
            context.contentResolver.query(
                Email.CONTENT_URI, projection, null, null, null,
            )?.use { cursor ->
                val addressIdx = cursor.getColumnIndexOrThrow(Email.ADDRESS)
                val nameIdx = cursor.getColumnIndexOrThrow(Email.DISPLAY_NAME_PRIMARY)
                val photoIdx = cursor.getColumnIndex(Email.PHOTO_THUMBNAIL_URI)
                val seen = HashSet<String>()
                while (cursor.moveToNext()) {
                    val email = cursor.getString(addressIdx)?.trim() ?: continue
                    if (!email.contains('@') || !seen.add(email.lowercase())) continue
                    results += ContactSuggestion(
                        email = email,
                        name = cursor.getString(nameIdx)?.trim()?.ifBlank { null },
                        photoUri = if (photoIdx >= 0) {
                            cursor.getString(photoIdx)?.trim()?.ifBlank { null }
                        } else {
                            null
                        },
                    )
                }
            }
        }
        return results
    }
}
