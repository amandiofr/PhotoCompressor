package com.amandiofr.photocompressor

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.amandiofr.photocompressor.ui.MainScreen
import com.amandiofr.photocompressor.ui.theme.PhotoCompressorTheme
import com.amandiofr.photocompressor.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PhotoCompressorTheme {
                PermissionGate(onGranted = { MainScreen(viewModel) })
            }
        }
    }
}

@Composable
private fun PermissionGate(onGranted: @Composable () -> Unit) {
    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        Manifest.permission.READ_MEDIA_IMAGES
    else
        Manifest.permission.READ_EXTERNAL_STORAGE

    val context = LocalContext.current
    val permissionsToRequest = remember {
        val list = mutableListOf(permission)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        list.toTypedArray()
    }
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        )
    }
    var showRationale by remember { mutableStateOf(false) }

    // La notification de progression du service en avant-plan (compression) dépend
    // de POST_NOTIFICATIONS sur Android 13+ ; on la demande ici sans bloquer l'accès
    // à l'app si elle est refusée (le service continue de tourner sans notification visible).
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        granted = results[permission] == true
        if (!granted) showRationale = true
    }

    LaunchedEffect(Unit) { if (!granted) launcher.launch(permissionsToRequest) }

    if (granted) {
        onGranted()
    } else {
        PermissionRationaleScreen(
            showRationale = showRationale,
            onRetry = { launcher.launch(permissionsToRequest) }
        )
    }
}

@Composable
private fun PermissionRationaleScreen(showRationale: Boolean, onRetry: () -> Unit) {
    Scaffold { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🔒", fontSize = 64.sp)
                Spacer(Modifier.height(16.dp))
                Text(
                    "Accès aux photos nécessaire",
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center
                )
                if (showRationale) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "L'application a besoin d'accéder à vos photos pour les compresser.\nAutorisation refusée.",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = onRetry) { Text("Réessayer") }
                }
            }
        }
    }
}
