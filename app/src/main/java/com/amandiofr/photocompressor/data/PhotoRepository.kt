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
import androidx.exifinterface.media.ExifInterface
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.lang.reflect.Modifier

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
            // Bitmap.compress() réécrit un JPEG sans aucune métadonnée EXIF ; on
            // capture donc tout ce qui existe AVANT d'écraser le fichier, pour
            // le réinjecter après coup (orientation, GPS, date de prise de vue…).
            val exifSnapshot = readExif(photo.uri)

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
            restoreExif(photo.uri, exifSnapshot)

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
            resolveFilePath(uri)?.let { java.io.File(it).writeBytes(data) }
        }
    }

    // Tous les tags TAG_* connus de la lib, découverts par réflexion pour ne
    // rien oublier plutôt que de maintenir une liste à la main.
    private val exifTags: List<String> by lazy {
        ExifInterface::class.java.fields
            .filter { Modifier.isStatic(it.modifiers) && it.name.startsWith("TAG_") }
            .mapNotNull { it.get(null) as? String }
    }

    private fun readExif(uri: Uri): Map<String, String> {
        val exif = context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) } ?: return emptyMap()
        return exifTags.mapNotNull { tag -> exif.getAttribute(tag)?.let { tag to it } }.toMap()
    }

    private fun restoreExif(uri: Uri, values: Map<String, String>) {
        if (values.isEmpty()) return
        try {
            // Le descripteur doit rester ouvert jusqu'à saveAttributes() inclus :
            // le fermer plus tôt (ex. via .use{} juste pour construire l'objet)
            // fait échouer l'écriture avec un EBADF.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                    val exif = ExifInterface(pfd.fileDescriptor)
                    values.forEach { (tag, value) -> exif.setAttribute(tag, value) }
                    exif.saveAttributes()
                }
            } else {
                resolveFilePath(uri)?.let { path ->
                    val exif = ExifInterface(path)
                    values.forEach { (tag, value) -> exif.setAttribute(tag, value) }
                    exif.saveAttributes()
                }
            }
        } catch (e: Exception) {
            // La compression a réussi ; on ne fait pas échouer toute la photo
            // pour une métadonnée EXIF qui n'a pas pu être réécrite.
            Log.w(TAG, "Impossible de restaurer l'EXIF de $uri", e)
        }
    }

    private fun resolveFilePath(uri: Uri): String? {
        @Suppress("DEPRECATION")
        return context.contentResolver.query(
            uri, arrayOf(MediaStore.Images.Media.DATA), null, null, null
        )?.use { c ->
            if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)) else null
        }
    }
}
