package app.harvester.companion.settings

import android.graphics.Color
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import app.harvester.companion.BuildConfig
import app.harvester.companion.R
import app.harvester.companion.UHBrand
import app.harvester.companion.UpdateChecker
import app.harvester.companion.settings.components.UhSettingsCardGroup
import app.harvester.companion.settings.components.UhSettingsScaffold
import app.harvester.companion.theme.UHTheme
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

class UpdaterActivity : AppCompatActivity() {

    private enum class UpdatePhase { CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, READY_TO_INSTALL, ERROR }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        setContent {
            UHTheme {
                val scope = rememberCoroutineScope()
                var phase by remember { mutableStateOf(UpdatePhase.CHECKING) }
                var manifest by remember { mutableStateOf<UpdateChecker.UpdateManifest?>(null) }
                var errorMessage by remember { mutableStateOf<String?>(null) }
                var downloadedBytes by remember { mutableLongStateOf(0L) }
                var totalBytes by remember { mutableLongStateOf(0L) }
                var speedBps by remember { mutableLongStateOf(0L) }
                var downloadedApk by remember { mutableStateOf<File?>(null) }

                fun checkNow() {
                    phase = UpdatePhase.CHECKING
                    errorMessage = null
                    scope.launch {
                        val res = UpdateChecker.fetchLatestManifest()
                        res.onSuccess { m ->
                            manifest = m
                            phase = if (m.versionCode > BuildConfig.VERSION_CODE) {
                                UpdatePhase.AVAILABLE
                            } else {
                                UpdatePhase.UP_TO_DATE
                            }
                        }.onFailure { err ->
                            errorMessage = err.message ?: "Unable to connect to update server"
                            phase = UpdatePhase.ERROR
                        }
                    }
                }

                fun startDownload(m: UpdateChecker.UpdateManifest) {
                    phase = UpdatePhase.DOWNLOADING
                    downloadedBytes = 0L
                    totalBytes = 0L
                    speedBps = 0L
                    scope.launch {
                        val res = UpdateChecker.downloadApk(this@UpdaterActivity, m) { dl, tot, bps ->
                            downloadedBytes = dl
                            totalBytes = tot
                            speedBps = bps
                        }
                        res.onSuccess { file ->
                            downloadedApk = file
                            phase = UpdatePhase.READY_TO_INSTALL
                            UpdateChecker.installApk(this@UpdaterActivity, file)
                        }.onFailure { err ->
                            errorMessage = err.message ?: "APK download failed"
                            phase = UpdatePhase.ERROR
                        }
                    }
                }

                LaunchedEffect(Unit) { checkNow() }

                UhSettingsScaffold(
                    title = "App Updater",
                    subtitle = "${UHBrand.NAME} v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    onBack = { finish() }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 4.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        UhSettingsCardGroup {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(MaterialTheme.colorScheme.primaryContainer),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.fx_ic_update_24),
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(26.dp)
                                        )
                                    }
                                    Spacer(Modifier.width(14.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        val statusTitle = when (phase) {
                                            UpdatePhase.CHECKING -> "Checking for updates..."
                                            UpdatePhase.UP_TO_DATE -> "You are up to date"
                                            UpdatePhase.AVAILABLE -> "Update Available"
                                            UpdatePhase.DOWNLOADING -> "Downloading Update"
                                            UpdatePhase.READY_TO_INSTALL -> "Ready to Install"
                                            UpdatePhase.ERROR -> "Update Check Failed"
                                        }
                                        Text(
                                            text = statusTitle,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        val isDev = BuildConfig.VERSION_NAME.contains("-dev")
                                        Text(
                                            text = if (isDev) "Channel: Dev Builds" else "Channel: Release",
                                            color = MaterialTheme.colorScheme.primary,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                    if (phase == UpdatePhase.CHECKING) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                                    }
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surface)
                                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                                        .padding(14.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text("Installed", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                                        Text(
                                            "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text("Latest", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                                        Text(
                                            text = manifest?.let { "v${it.versionName} (${it.versionCode})" } ?: "—",
                                            color = if (phase == UpdatePhase.AVAILABLE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }

                                if (phase == UpdatePhase.DOWNLOADING) {
                                    val fraction = if (totalBytes > 0L) (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
                                    if (totalBytes > 0L) {
                                        LinearProgressIndicator(
                                            progress = { fraction },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(8.dp)
                                                .clip(RoundedCornerShape(4.dp)),
                                            color = MaterialTheme.colorScheme.primary,
                                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                                        )
                                    } else {
                                        LinearProgressIndicator(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(8.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                        )
                                    }
                                    val dlMb = String.format(Locale.US, "%.1f MB", downloadedBytes / (1024f * 1024f))
                                    val totMb = if (totalBytes > 0L) String.format(Locale.US, " / %.1f MB", totalBytes / (1024f * 1024f)) else ""
                                    val spd = if (speedBps > 0L) String.format(Locale.US, " • %.1f MB/s", speedBps / (1024f * 1024f)) else ""
                                    Text(
                                        text = "$dlMb$totMb$spd",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 12.sp
                                    )
                                }

                                errorMessage?.let { msg ->
                                    Text(
                                        text = msg,
                                        color = MaterialTheme.colorScheme.error,
                                        fontSize = 13.sp
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = { checkNow() },
                                        enabled = phase != UpdatePhase.CHECKING && phase != UpdatePhase.DOWNLOADING,
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text("Check Again")
                                    }
                                    when {
                                        phase == UpdatePhase.AVAILABLE && manifest != null -> {
                                            Button(
                                                onClick = { startDownload(manifest!!) },
                                                modifier = Modifier.weight(1f),
                                                shape = RoundedCornerShape(12.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                            ) {
                                                Text("Download & Install")
                                            }
                                        }
                                        phase == UpdatePhase.READY_TO_INSTALL && downloadedApk != null -> {
                                            Button(
                                                onClick = { UpdateChecker.installApk(this@UpdaterActivity, downloadedApk!!) },
                                                modifier = Modifier.weight(1f),
                                                shape = RoundedCornerShape(12.dp)
                                            ) {
                                                Text("Install APK")
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        val notes = manifest?.changelog?.trim().orEmpty()
                        if (notes.isNotBlank()) {
                            UhSettingsCardGroup {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(18.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = "Release Notes",
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = notes,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 13.sp,
                                        lineHeight = 19.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        UpdateChecker.resumePendingInstallIfPossible(this)
    }
}
