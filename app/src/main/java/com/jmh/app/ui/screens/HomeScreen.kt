package com.jmh.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.jmh.app.storage.JmhRepository
import com.jmh.app.ui.MainViewModel

/**
 * 主界面：添加文件 / 导入加密文件 / 查看文件。
 */
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onOpenVault: () -> Unit,
    onOpenSettings: () -> Unit
) {
    // ---- 加密本机文件 ----
    val pickFiles = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.encryptFiles(uris)
    }

    // ---- 导入他人分享的加密文件 ----
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var importProbe by remember { mutableStateOf<JmhRepository.ImportProbe?>(null) }
    var localFailureCount by remember { mutableIntStateOf(0) }

    val pickImportFile = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        viewModel.probeImportFile(uri) { result ->
            result.fold(
                onSuccess = { probe ->
                    if (probe.isShareMode) {
                        localFailureCount = 0
                        pendingImportUri = uri
                        importProbe = probe
                    } else {
                        viewModel.snackbar = "这是本机金库文件，直接「查看文件」即可打开"
                    }
                },
                onFailure = {
                    viewModel.snackbar = "无法读取该文件：${it.message ?: "格式不正确"}"
                }
            )
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshVault()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // 顶部栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 22.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "JMH",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onOpenSettings) {
                    Icon(imageVector = Icons.Filled.Settings, contentDescription = "设置")
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Spacer(modifier = Modifier.height(24.dp))

                Text(text = "文件保险箱", style = MaterialTheme.typography.displaySmall)
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "文件以 AES-256 加密后保存在本机，只有你的密码能打开。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(28.dp))

                ActionCard(
                    title = "添加文件",
                    subtitle = "选择本机文件加密保存到金库",
                    iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    icon = Icons.Filled.Add,
                    onClick = { pickFiles.launch(arrayOf("*/*")) }
                )

                Spacer(modifier = Modifier.height(14.dp))

                ActionCard(
                    title = "导入加密文件",
                    subtitle = "接收他人分享的 .jmh 文件（需分享密码）",
                    iconTint = MaterialTheme.colorScheme.onTertiaryContainer,
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    icon = Icons.Filled.ArrowBack,
                    onClick = { pickImportFile.launch(arrayOf("*/*")) }
                )

                Spacer(modifier = Modifier.height(14.dp))

                ActionCard(
                    title = "查看文件",
                    subtitle = if (viewModel.vaultItems.isEmpty()) {
                        "输入密码后浏览已加密的文件"
                    } else {
                        "金库中已有 ${viewModel.vaultItems.size} 个加密文件"
                    },
                    iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    icon = Icons.Filled.Lock,
                    onClick = onOpenVault
                )

                Spacer(modifier = Modifier.height(32.dp))

                Text(
                    text = "存储位置：${viewModel.vaultPath}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "加密文件数：${viewModel.vaultItems.size}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    // 输入分享密码
    importProbe?.let { probe ->
        ImportPasswordDialog(
            fileName = probe.meta.name,
            failureCount = localFailureCount,
            onDismiss = {
                importProbe = null
                pendingImportUri = null
            },
            onConfirm = { password ->
                val uri = pendingImportUri
                if (uri != null) {
                    viewModel.importShared(uri, password.toCharArray()) { ok ->
                        if (ok) {
                            importProbe = null
                            pendingImportUri = null
                            localFailureCount = 0
                        } else {
                            localFailureCount += 1
                        }
                    }
                }
            }
        )
    }
}

@Composable
private fun ActionCard(
    title: String,
    subtitle: String,
    iconTint: Color,
    containerColor: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.28f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = iconTint
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
