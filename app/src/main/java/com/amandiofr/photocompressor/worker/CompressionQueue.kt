package com.amandiofr.photocompressor.worker

import com.amandiofr.photocompressor.data.PhotoInfo
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Passe les lots de photos aux CompressionWorker sans passer par WorkManager.Data,
 * dont la taille est limitée (~10 Ko) et bien trop petite pour des milliers d'URIs.
 * En mémoire uniquement : si le processus meurt, le lot en cours est perdu, mais
 * c'est justement ce que le service en avant-plan est censé empêcher.
 */
object CompressionQueue {
    private val pending = ConcurrentHashMap<UUID, List<PhotoInfo>>()

    fun put(id: UUID, batch: List<PhotoInfo>) {
        pending[id] = batch
    }

    fun get(id: UUID): List<PhotoInfo>? = pending[id]

    fun remove(id: UUID) {
        pending.remove(id)
    }
}
