package com.amandiofr.photocompressor.worker

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.amandiofr.photocompressor.PhotoCompressorApp
import com.amandiofr.photocompressor.R
import com.amandiofr.photocompressor.data.CompressResult
import com.amandiofr.photocompressor.data.PhotoRepository
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Compresse un lot de photos en tant que service en avant-plan : sans ça, le
 * traitement tourne dans une coroutine liée à l'Activity et Android peut tuer
 * le processus silencieusement dès que l'appli passe en arrière-plan (gros lots
 * = traitement long = forte probabilité de mise en arrière-plan par l'utilisateur).
 */
class CompressionWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val repository = PhotoRepository(appContext)

    override suspend fun doWork(): Result {
        // Le lot vit en mémoire (voir CompressionQueue) ; s'il a disparu, le
        // Worker a été relancé après une mort de processus et n'a plus rien à
        // traiter. Échouer explicitement évite de faire croire à la ViewModel
        // que ce lot a été compressé alors qu'il n'a jamais été touché.
        val batch = CompressionQueue.get(id) ?: run {
            FirebaseCrashlytics.getInstance().recordException(
                IllegalStateException("CompressionQueue vide pour le Worker $id — le process a probablement été tué avant la reprise")
            )
            return Result.failure()
        }

        try {
            FirebaseCrashlytics.getInstance().log("Début du lot de ${batch.size} photos")
            setForeground(foregroundInfo(0, batch.size))

            var saved   = 0L
            var skipped = 0
            var errors  = 0

            batch.forEachIndexed { index, photo ->
                when (val outcome = repository.compress(photo)) {
                    is CompressResult.Compressed  -> saved += outcome.freedBytes
                    CompressResult.SkippedNoGain  -> skipped++
                    is CompressResult.Error       -> errors++
                }
                val done = index + 1
                setProgress(workDataOf(KEY_DONE to done, KEY_SAVED to saved))
                setForeground(foregroundInfo(done, batch.size))
            }

            return Result.success(
                workDataOf(
                    KEY_DONE    to batch.size,
                    KEY_SAVED   to saved,
                    KEY_SKIPPED to skipped,
                    KEY_ERRORS  to errors
                )
            )
        } finally {
            // Toujours nettoyer, même si le Worker est annulé en cours de route
            // (sinon le lot reste dans la map pour le reste de la vie du process).
            CompressionQueue.remove(id)
        }
    }

    private fun foregroundInfo(done: Int, total: Int): ForegroundInfo {
        val notification: Notification = NotificationCompat.Builder(applicationContext, PhotoCompressorApp.CHANNEL_ID)
            .setContentTitle("Compression des photos")
            .setContentText("$done / $total photos traitées")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setProgress(total, done, false)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 42
        const val KEY_DONE    = "done"
        const val KEY_SAVED   = "saved"
        const val KEY_SKIPPED = "skipped"
        const val KEY_ERRORS  = "errors"
    }
}
