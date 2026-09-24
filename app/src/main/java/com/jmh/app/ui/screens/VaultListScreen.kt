package com.jmh.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jmh.app.storage.JmhRepository
import com.jmh.app.ui.FileTypes
import com.jmh.app.ui.MainViewModel
import com.jmh.app.ui.components.EmptyState
import com.jmh.app.ui.components.FileTypeBadge
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 金库文件列表：展示全部已加密文件。
 */
@Composable
fun VaultListScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onPreview: (JmhRepository.VaultItem) -> Unit
) {
    var pendingExport by remember { mutableStateOf<JmhRepository.VaultItem?>(null) }
    var pendingDelete by remember { mutableStateOf<JmhRepository.VaultItem?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val item = pendingExport
        pendingExport = null
        if (uri != null && item != null) {
            viewModel.exportItem(item, uri) { }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshVault()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // 顶部栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(imageVector = Icons.Filled.ArrowBack, contentDescription = "返回")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "加密文件",
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = viewModel.vaultPath,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = { viewModel.refreshVault() }) {
                Icon(imageVector = Icons.Filled.Refresh, contentDescription = "刷新")
            }
        }

        Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

        if (viewModel.vaultItems.isEmpty() && !viewModel.isLoading) {
            EmptyState(
                title = "金库还是空的",
                subtitle = "返回首页点击「添加文件」，选择需要加密的文件。"
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(viewModel.vaultItems, key = { it.ref.name }) { item ->
                    VaultRow(
                        item = item,
                        onClick = { onPreview(item) },
                        onExport = {
                            pendingExport = item
                            exportLauncher.launch(item.displayName)
                        },
                        onDelete = { pendingDelete = item }
                    )
                    Divider(
                        modifier = Modifier.padding(start = 76.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
                    )
                }
                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }

    // 删除确认
    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除加密文件？") },
            text = { Text("将删除金库中的「${item.displayName}」，此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteItem(item)
                    pendingDelete = null
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun VaultRow(
    item: JmhRepository.VaultItem,
    onClick: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val kind = FileTypes.kindOf(item.extension, item.mime)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FileTypeBadge(kind = kind, label = FileTypes.labelOf(item.extension))

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.displayName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${FileTypes.formatSize(item.originalSize)} · ${formatTime(item.createdAt)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(imageVector = Icons.Filled.MoreVert, contentDescription = "更多")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("解密导出") },
                    onClick = {
                        menuOpen = false
                        onExport()
                    }
                )
                DropdownMenuItem(
                    text = { Text("删除") },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    }
                )
            }
        }
    }
}

private val timeFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

internal fun formatTime(timestamp: Long): String =
    runCatching { timeFormatter.format(Date(timestamp)) }.getOrDefault("")
