package com.amandiofr.photocompressor.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.amandiofr.photocompressor.BuildConfig
import com.amandiofr.photocompressor.R
import com.amandiofr.photocompressor.viewmodel.MainViewModel
import com.amandiofr.photocompressor.viewmodel.UiState

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsState()

    // Launcher pour la demande de permission d'écriture (Android 11+)
    val writePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK)
            viewModel.onWritePermissionGranted()
        else
            viewModel.onWritePermissionDenied()
    }

    // Déclencher le launcher quand le ViewModel le demande
    LaunchedEffect(state) {
        if (state is UiState.WaitingForPermission) {
            val sender = (state as UiState.WaitingForPermission).sender
            writePermissionLauncher.launch(
                IntentSenderRequest.Builder(sender).build()
            )
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Défilable si la carte dépasse l'écran (grande taille de police), centrée sinon
            BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .heightIn(min = maxHeight),
                    contentAlignment = Alignment.Center
                ) {
                    AnimatedContent(
                        targetState = state,
                        contentKey = { it::class },
                        transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) },
                        label = "state"
                    ) { s ->
                        when (s) {
                            UiState.Idle      -> IdleCard(onScan = { viewModel.scan() })
                            UiState.Scanning  -> ScanningCard()
                            is UiState.Ready  -> ReadyCard(s, onCompress = { viewModel.requestCompression() })
                            is UiState.WaitingForPermission -> ScanningCard() // attend le dialog système
                            is UiState.Compressing -> CompressingCard(s)
                            is UiState.Done   -> DoneCard(s, onReset = { viewModel.reset() })
                        }
                    }
                }
            }

            // Sous la carte (et non superposé) pour ne jamais la chevaucher quand elle est haute
            Text(
                "v${BuildConfig.VERSION_NAME}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

// ── Cartes ──────────────────────────────────────────────────────────────────

@Composable
private fun IdleCard(onScan: () -> Unit) {
    CenteredCard {
        Text("📷", fontSize = 64.sp)
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.idle_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.idle_body),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(32.dp))
        BigButton(stringResource(R.string.idle_button), onClick = onScan)
    }
}

@Composable
private fun ScanningCard() {
    CenteredCard {
        CircularProgressIndicator(Modifier.size(56.dp))
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.scanning), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ReadyCard(state: UiState.Ready, onCompress: () -> Unit) {
    CenteredCard {
        Text("🔍", fontSize = 56.sp)
        Spacer(Modifier.height(16.dp))
        Text(
            pluralStringResource(R.plurals.photos_found, state.photoCount, state.photoCount),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.photos_total_size, formatBytes(state.totalBytes)),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        DiskSpaceBar(
            freeBytes  = state.freeSpaceBefore,
            totalBytes = state.totalDiskSpace,
            modifier   = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(24.dp))
        if (state.photoCount == 0) {
            Text(
                stringResource(R.string.nothing_to_compress_found),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            Text(
                stringResource(R.string.backup_warning),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(16.dp))
            BigButton(stringResource(R.string.compress_button), onClick = onCompress)
        }
    }
}

@Composable
private fun CompressingCard(state: UiState.Compressing) {
    val progress = if (state.total > 0) state.done.toFloat() / state.total else 0f
    CenteredCard {
        Text("⚙️", fontSize = 56.sp)
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.compressing),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.progress_photos, state.done, state.total),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (state.savedBytes > 0) {
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.bytes_recovered, formatBytes(state.savedBytes)),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun DoneCard(state: UiState.Done, onReset: () -> Unit) {
    CenteredCard {
        Text("✅", fontSize = 56.sp)
        Spacer(Modifier.height(16.dp))
        Text(
            if (state.savedBytes > 0) stringResource(R.string.bytes_freed, formatBytes(state.savedBytes))
            else stringResource(R.string.nothing_compressed),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(8.dp))
        Spacer(Modifier.height(16.dp))
        DiskSpaceBar(
            freeBytes       = state.freeSpaceAfter,
            totalBytes      = state.totalDiskSpace,
            newlyFreedBytes = (state.freeSpaceAfter - state.freeSpaceBefore).coerceAtLeast(0L),
            modifier        = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Text(
            pluralStringResource(R.plurals.photos_processed, state.total, state.total) +
                if (state.skipped > 0) " · " + pluralStringResource(R.plurals.already_optimized, state.skipped, state.skipped) else "",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (state.errors > 0) {
            Spacer(Modifier.height(4.dp))
            Text(
                pluralStringResource(R.plurals.errors_count, state.errors, state.errors),
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
        }
        if (state.total < state.scannedTotal) {
            val notProcessed = state.scannedTotal - state.total
            Spacer(Modifier.height(4.dp))
            Text(
                pluralStringResource(R.plurals.interrupted_count, notProcessed, notProcessed),
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(32.dp))
        OutlinedButton(onClick = onReset) { Text(stringResource(R.string.restart)) }
    }
}

// ── Helpers ──────────────────────────────────────────────────────────────────

@Composable
private fun CenteredCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(4.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            content = content
        )
    }
}

@Composable
private fun BigButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(label, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

@Composable
private fun DiskSpaceBar(
    freeBytes: Long,
    totalBytes: Long,
    newlyFreedBytes: Long = 0L,
    modifier: Modifier = Modifier
) {
    if (totalBytes <= 0L) return
    val usedFraction     = ((totalBytes - freeBytes).toFloat() / totalBytes).coerceIn(0f, 1f)
    val gainFraction     = (newlyFreedBytes.toFloat() / totalBytes).coerceIn(0f, 1f - usedFraction)
    val oldFreeFraction  = (1f - usedFraction - gainFraction).coerceIn(0f, 1f)

    val colorUsed    = Color(0xFFBDBDBD)
    val colorGain    = Color(0xFF388E3C)
    val colorOldFree = Color(0xFFA5D6A7)

    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
                .clip(RoundedCornerShape(7.dp))
        ) {
            drawRect(color = colorUsed)
            if (gainFraction > 0f) {
                drawRect(
                    color = colorGain,
                    topLeft = Offset(size.width * usedFraction, 0f),
                    size = Size(size.width * gainFraction, size.height)
                )
            }
            drawRect(
                color = colorOldFree,
                topLeft = Offset(size.width * (usedFraction + gainFraction), 0f),
                size = Size(size.width * oldFreeFraction, size.height)
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                stringResource(R.string.disk_used, formatBytes(totalBytes - freeBytes)),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                stringResource(R.string.disk_free, formatBytes(freeBytes)),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> stringResource(R.string.unit_gb, bytes / 1_073_741_824.0)
    bytes >= 1_048_576L     -> stringResource(R.string.unit_mb, bytes / 1_048_576.0)
    else                    -> stringResource(R.string.unit_kb, bytes / 1_024.0)
}
