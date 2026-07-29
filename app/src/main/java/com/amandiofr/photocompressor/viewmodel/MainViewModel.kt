package com.amandiofr.photocompressor.viewmodel

import android.app.Application
import android.content.IntentSender
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.amandiofr.photocompressor.data.PhotoInfo
import com.amandiofr.photocompressor.data.PhotoRepository
import com.amandiofr.photocompressor.worker.CompressionQueue
import com.amandiofr.photocompressor.worker.CompressionWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class UiState {
    data object Idle       : UiState()
    data object Scanning   : UiState()
    data class  Ready(
        val photoCount: Int,
        val totalBytes: Long,
        val freeSpaceBefore: Long,
        val totalDiskSpace: Long
    ) : UiState()
    data class  WaitingForPermission(val sender: IntentSender) : UiState()
    data class  Compressing(
        val done: Int,
        val total: Int,
        val savedBytes: Long
    ) : UiState()
    data class  Done(
        val total: Int,
        val scannedTotal: Int,
        val savedBytes: Long,
        val skipped: Int,
        val errors: Int,
        val freeSpaceBefore: Long,
        val freeSpaceAfter: Long,
        val totalDiskSpace: Long
    ) : UiState()
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val TAG = "MainViewModel"
        // MediaStore.createWriteRequest passe par le Binder (~1 Mo par appel) :
        // on découpe en lots pour rester très en dessous de cette limite, même
        // avec des dizaines de milliers de photos. Chaque lot affiche aussi une
        // popup système de confirmation : une valeur trop petite (ex. 300)
        // forcerait l'utilisateur à valider des dizaines de popups d'affilée
        // sur une grosse bibliothèque. 2000 reste très en dessous de la limite
        // Binder (~150-200 Ko par appel) tout en limitant le nombre de popups.
        private const val BATCH_SIZE = 2000
    }

    private val repository = PhotoRepository(app)
    private val workManager = WorkManager.getInstance(app)

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var photos: List<PhotoInfo> = emptyList()
    private var freeSpaceBefore: Long = 0L
    private var totalDiskSpace: Long  = 0L

    private var batches: List<List<PhotoInfo>> = emptyList()
    private var currentBatchIndex = 0
    private var grandTotal    = 0
    private var doneCount     = 0
    private var savedTotal    = 0L
    private var skippedTotal  = 0
    private var errorsTotal   = 0

    fun scan() {
        viewModelScope.launch {
            _state.value = UiState.Scanning
            withContext(Dispatchers.IO) {
                freeSpaceBefore = repository.getFreeSpace()
                totalDiskSpace  = repository.getTotalSpace()
                photos          = repository.queryPhotos()
            }
            _state.value = UiState.Ready(
                photoCount     = photos.size,
                totalBytes     = photos.sumOf { it.sizeBytes },
                freeSpaceBefore = freeSpaceBefore,
                totalDiskSpace  = totalDiskSpace
            )
        }
    }

    fun requestCompression() {
        if (photos.isEmpty()) return
        batches          = photos.chunked(BATCH_SIZE)
        currentBatchIndex = 0
        grandTotal   = photos.size
        doneCount    = 0
        savedTotal   = 0L
        skippedTotal = 0
        errorsTotal  = 0
        requestBatchPermission()
    }

    private fun requestBatchPermission() {
        val batch = batches[currentBatchIndex]
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val request = MediaStore.createWriteRequest(
                    getApplication<Application>().contentResolver,
                    batch.map { it.uri }
                )
                _state.value = UiState.WaitingForPermission(request.intentSender)
            } catch (e: Exception) {
                // Ex. TransactionTooLargeException sur certains appareils : on
                // arrête proprement plutôt que de laisser planter l'appli.
                Log.e(TAG, "createWriteRequest a échoué pour un lot de ${batch.size} photos", e)
                errorsTotal += batch.size
                finishAll()
            }
        } else {
            startBatchCompression(batch)
        }
    }

    fun onWritePermissionGranted() = startBatchCompression(batches[currentBatchIndex])

    fun onWritePermissionDenied() {
        // L'utilisateur refuse : on s'arrête là et on récapitule ce qui a déjà été fait.
        finishAll()
    }

    fun reset() {
        photos  = emptyList()
        batches = emptyList()
        _state.value = UiState.Idle
    }

    private fun startBatchCompression(batch: List<PhotoInfo>) {
        val request = OneTimeWorkRequestBuilder<CompressionWorker>().build()
        CompressionQueue.put(request.id, batch)
        workManager.enqueue(request)

        _state.value = UiState.Compressing(done = doneCount, total = grandTotal, savedBytes = savedTotal)

        viewModelScope.launch collector@{
            // WorkManager peut réémettre le même WorkInfo terminal plusieurs fois
            // (le Flow observe la base Room sous-jacente, pas juste le champ state).
            // Sans ce verrou, un SUCCEEDED dupliqué ferait avancer currentBatchIndex
            // deux fois et sauterait un lot entier de photos sans le compresser.
            var handled = false
            workManager.getWorkInfoByIdFlow(request.id).collect { info ->
                if (info == null || handled) return@collect
                when (info.state) {
                    WorkInfo.State.RUNNING -> {
                        val batchDone  = info.progress.getInt(CompressionWorker.KEY_DONE, 0)
                        val batchSaved = info.progress.getLong(CompressionWorker.KEY_SAVED, 0L)
                        _state.value = UiState.Compressing(
                            done       = doneCount + batchDone,
                            total      = grandTotal,
                            savedBytes = savedTotal + batchSaved
                        )
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        handled = true
                        val out = info.outputData
                        doneCount    += out.getInt(CompressionWorker.KEY_DONE, batch.size)
                        savedTotal   += out.getLong(CompressionWorker.KEY_SAVED, 0L)
                        skippedTotal += out.getInt(CompressionWorker.KEY_SKIPPED, 0)
                        errorsTotal  += out.getInt(CompressionWorker.KEY_ERRORS, 0)
                        advanceBatch()
                        this@collector.cancel()
                    }
                    WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                        handled = true
                        errorsTotal += batch.size
                        finishAll()
                        this@collector.cancel()
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun advanceBatch() {
        currentBatchIndex++
        if (currentBatchIndex < batches.size) {
            requestBatchPermission()
        } else {
            finishAll()
        }
    }

    private fun finishAll() {
        viewModelScope.launch {
            val freeSpaceAfter = withContext(Dispatchers.IO) { repository.getFreeSpace() }
            _state.value = UiState.Done(
                total           = doneCount,
                scannedTotal    = grandTotal,
                savedBytes      = savedTotal,
                skipped         = skippedTotal,
                errors          = errorsTotal,
                freeSpaceBefore = freeSpaceBefore,
                freeSpaceAfter  = freeSpaceAfter,
                totalDiskSpace  = totalDiskSpace
            )
        }
    }
}
