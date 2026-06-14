package dev.po4yka.frameport.camera.media

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production [MediaStoreGateway] implementation using Android ContentResolver and MediaStore.
 *
 * minSdk is 31 (Android 12), so [MediaStore.MediaColumns.IS_PENDING] and
 * [MediaStore.MediaColumns.RELATIVE_PATH] are always available — no API-level guards needed.
 *
 * Privacy invariants (enforced by callers; verified here by design):
 *   - [displayName] must follow the "FRP_<handle>.<ext>" pattern validated by [FilenameValidator].
 *     No raw camera filename is ever passed in.
 *   - [relativePath] encodes only date + "Frameport" label. No serial, IP, or user data.
 *   - MediaStore rows for video are inserted into [MediaStore.Video.Media.EXTERNAL_CONTENT_URI];
 *     images into [MediaStore.Images.Media.EXTERNAL_CONTENT_URI].
 */
@Singleton
class AndroidMediaStoreGateway
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : MediaStoreGateway {
        private val contentResolver: ContentResolver get() = context.contentResolver

        // NOT cancel-safe: ContentResolver.insert is blocking; cancellation mid-call may
        // produce a row without returning its URI. Caller treats null as failure.
        override fun insertPendingRow(
            displayName: String,
            mimeType: String,
            mediaCategory: MediaCategory,
            relativePath: String,
        ): String? {
            val collection = collectionUriFor(mediaCategory)
            val values =
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            return contentResolver.insert(collection, values)?.toString()
        }

        // NOT cancel-safe: ContentResolver.openFileDescriptor is blocking.
        override fun openWriteFd(uri: String): ParcelFileDescriptor? =
            runCatching {
                contentResolver.openFileDescriptor(android.net.Uri.parse(uri), "w")
            }.getOrNull()

        // NOT cancel-safe: ContentResolver.update is blocking.
        override fun finalizePending(uri: String): Boolean {
            val values =
                ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
            val updated =
                runCatching {
                    contentResolver.update(android.net.Uri.parse(uri), values, null, null)
                }.getOrElse { 0 }
            return updated > 0
        }

        // NOT cancel-safe: ContentResolver.delete is blocking.
        override fun deletePending(uri: String): Boolean {
            val deleted =
                runCatching {
                    contentResolver.delete(android.net.Uri.parse(uri), null, null)
                }.getOrElse { 0 }
            return deleted > 0
        }

        private fun collectionUriFor(mediaCategory: MediaCategory): android.net.Uri =
            when (mediaCategory) {
                MediaCategory.Video -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI

                MediaCategory.Image,
                MediaCategory.Unknown,
                -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
    }
