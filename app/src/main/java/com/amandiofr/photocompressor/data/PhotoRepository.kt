package com.amandiofr.photocompressor.data

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

data class PhotoInfo(
    val uri: Uri,
    val sizeBytes: Long,
    val displayName: String
)

sealed class CompressResult {
    data class Compressed(val freedBytes: Long) : CompressResult()
    data object SkippedNoGain : CompressResult()
    data class Error(val reason: String) : CompressResult()
}

class PhotoRepository(private val context: Context) {

    companion object {
        private const val TAG            = "PhotoRepository"
        private const val MIN_SIZE_BYTES = 200 * 1024L  // ignore < 200 Ko
        private const val JPEG_QUALITY   = 80
        private const val MIN_GAIN_RATIO = 0.90         // ignorer si gain < 10 %
    }

    fun getFreeSpace(): Long {
        val stat = StatFs(Environment.getExternalStorageDirectory().absolutePath)
        return stat.availableBytes
    }

    fun getTotalSpace(): Long {
        val stat = StatFs(Environment.getExternalStorageDirectory().absolutePath)
        return stat.totalBytes
    }

    fun queryPhotos(): List<PhotoInfo> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE
        )
        val selection    = "${MediaStore.Images.Media.MIME_TYPE} = ?"
        val selectionArgs = arrayOf("image/jpeg")

        val photos = mutableListOf<PhotoInfo>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, selection, selectionArgs,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idCol   = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            while (cursor.moveToNext()) {
                val size = cursor.getLong(sizeCol)
                if (size < MIN_SIZE_BYTES) continue
                val uri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(idCol)
                )
                photos.add(PhotoInfo(uri, size, cursor.getString(nameCol) ?: ""))
            }
        }
        return photos
    }

    suspend fun compress(photo: PhotoInfo): CompressResult = withContext(Dispatchers.IO) {
        try {
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565 // économie mémoire
            }
            val bitmap = context.contentResolver.openInputStream(photo.uri)
                ?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return@withContext CompressResult.Error("Fichier illisible : ${photo.displayName}")

            val buf = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, buf)
            bitmap.recycle()

            val compressed = buf.toByteArray()

            // Pas assez de gain → on ne touche pas au fichier
            if (compressed.size >= photo.sizeBytes * MIN_GAIN_RATIO) return@withContext CompressResult.SkippedNoGain

            writeBack(photo.uri, compressed)

            CompressResult.Compressed(photo.sizeBytes - compressed.size)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Mémoire insuffisante pour compresser ${photo.displayName} (${photo.sizeBytes} o)", e)
            recordNonFatal(e, photo)
            CompressResult.Error("Mémoire insuffisante : ${photo.displayName}")
        } catch (e: Exception) {
            Log.e(TAG, "Échec de compression de ${photo.displayName}", e)
            recordNonFatal(e, photo)
            CompressResult.Error(e.message ?: photo.displayName)
        }
    }

    // Ces échecs sont rattrapés pour ne jamais faire planter l'appli (cf. le
    // bug historique de crash silencieux sur les grosses bibliothèques), donc
    // sans ça ils ne remonteraient nulle part : on les logge comme non-fatals
    // dans Crashlytics pour garder une trace exploitable.
    private fun recordNonFatal(t: Throwable, photo: PhotoInfo) {
        val crashlytics = FirebaseCrashlytics.getInstance()
        crashlytics.setCustomKey("photo_name", photo.displayName)
        crashlytics.setCustomKey("photo_size_bytes", photo.sizeBytes)
        crashlytics.recordException(t)
    }

    private fun writeBack(uri: Uri, data: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(data) }
        } else {
            @Suppress("DEPRECATION")
            val path = context.contentResolver.query(
                uri, arrayOf(MediaStore.Images.Media.DATA), null, null, null
            )?.use { c ->
                if (c.moveToFirst())
                    c.getString(c.getColumnIndexOrThrow(MediaStore.Images.Media.DATA))
                else null
            }
            path?.let { java.io.File(it).writeBytes(data) }
        }
    }
}
