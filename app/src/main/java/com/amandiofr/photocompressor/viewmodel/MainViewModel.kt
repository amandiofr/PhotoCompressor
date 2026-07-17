package com.amandiofr.photocompressor.viewmodel

import android.app.Application
import android.content.IntentSender
import android.os.Build
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.amandiofr.photocompressor.data.PhotoInfo
import com.amandiofr.photocompressor.data.PhotoRepository
import kotlinx.coroutines.Dispatchers
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
        val savedBytes: Long,
        val skipped: Int,
        val freeSpaceBefore: Long,
        val freeSpaceAfter: Long,
        val totalDiskSpace: Long
    ) : UiState()
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = PhotoRepository(app)

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var photos: List<PhotoInfo> = emptyList()
    private var freeSpaceBefore: Long = 0L
    private var totalDiskSpace: Long  = 0L

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val request = MediaStore.createWriteRequest(
                getApplication<Application>().contentResolver,
                photos.map { it.uri }
            )
            _state.value = UiState.WaitingForPermission(request.intentSender)
        } else {
            startCompression()
        }
    }

    fun onWritePermissionGranted() = startCompression()

    fun onWritePermissionDenied() {
        _state.value = UiState.Ready(photos.size, photos.sumOf { it.sizeBytes }, freeSpaceBefore, totalDiskSpace)
    }

    fun reset() {
        photos = emptyList()
        _state.value = UiState.Idle
    }

    private fun startCompression() {
        val list = photos
        viewModelScope.launch {
            var saved   = 0L
            var skipped = 0
            _state.value = UiState.Compressing(done = 0, total = list.size, savedBytes = 0L)
            list.forEachIndexed { i, photo ->
                val freed = repository.compress(photo)
                if (freed > 0L) saved += freed else skipped++
                _state.value = UiState.Compressing(done = i + 1, total = list.size, savedBytes = saved)
            }
            val freeSpaceAfter = withContext(Dispatchers.IO) { repository.getFreeSpace() }
            _state.value = UiState.Done(
                total           = list.size,
                savedBytes      = saved,
                skipped         = skipped,
                freeSpaceBefore = freeSpaceBefore,
                freeSpaceAfter  = freeSpaceAfter,
                totalDiskSpace  = totalDiskSpace
            )
        }
    }
}
