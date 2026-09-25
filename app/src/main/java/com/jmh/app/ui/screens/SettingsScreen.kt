package com.jmh.app.ui.screens

import android.content.Intent
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jmh.app.storage.VaultStorage
import com.jmh.app.ui.MainViewModel
import com.jmh.app.ui.components.PasswordTextField

/**
 * 设置界面：存储位置、修改密码、缓存清理。
 */
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onLocked: () -> Unit
) {
    val context = LocalContext.current
    var showChangePassword by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var confirmSwitchToPrivate by remember { mutableStateOf(false) }

    val treePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            // 持久化读写权限，重启后依然可用
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            viewModel.switchLocation(targetPublic = true, treeUri = uri) { }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(imageVector = Icons.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = "设置",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
        ) {

            SectionTitle("安全")

            SettingRow(
                title = "修改密码",
                subtitle = "修改后，所有已加密文件立即改用新密码验证",
                onClick = { showChangePassword = true }
            )

            SettingRow(
                title = "立即锁定",
                subtitle = "清除内存中的密钥，返回首页",
                onClick = {
                    viewModel.lock()
                    onLocked()
                }
            )

            Spacer(modifier = Modifier.height(22.dp))
            SectionTitle("存储位置")

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "当前：${viewModel.vaultPath}",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    val isPublic = viewModel.storage.mode == VaultStorage.Mode.PUBLIC_TREE
                    Text(
                        text = if (isPublic) {
                            "加密文件保存在你授权的公共目录，可在系统文件管理器中直接看到。"
                        } else {
                            "加密文件保存在应用专属目录，其他应用无法访问（零权限，隐私性更好）。"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (!isPublic) {
                            TextButton(onClick = { treePicker.launch(null) }) {
                                Text("切换到公共目录")
                            }
                        } else {
                            TextButton(onClick = { confirmSwitchToPrivate = true }) {
                                Text("切换回应用专属目录")
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "切换位置时，金库中已有的加密文件会一并迁移过去（先复制校验、再删除源文件，不会丢失）。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(22.dp))
            SectionTitle("其他")

            SettingRow(
                title = "检查更新",
                subtitle = "当前版本 ${viewModel.currentVersionName} · 点击检查是否有新版本",
                onClick = { viewModel.manualCheckUpdate() }
            )

            SettingRow(
                title = "项目主页",
                subtitle = "在浏览器中打开 GitHub 仓库（可手动下载最新版）",
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(viewModel.repoUrl))
                        )
                    }.onFailure {
                        viewModel.snackbar = "无法打开浏览器"
                    }
                }
            )

            SettingRow(
                title = "清理预览缓存",
                subtitle = "删除解密预览时产生的临时明文文件",
                onClick = { viewModel.clearPreviewCache() }
            )

            SettingRow(
                title = "关于 JMH",
                subtitle = "版本 ${viewModel.currentVersionName} · 本地加密文件保险箱",
                onClick = { showAbout = true }
            )

            Spacer(modifier = Modifier.height(30.dp))

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(modifier = Modifier.padding(16.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "卸载应用前，请先在金库中把重要文件解密导出。密码一旦遗忘，文件将无法恢复。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }

    if (showChangePassword) {
        ChangePasswordDialog(
            onDismiss = { showChangePassword = false },
            onSubmit = { oldPwd, newPwd, confirmPwd ->
                when {
                    oldPwd.isEmpty() -> "请输入原密码"
                    newPwd.length < MIN_PASSWORD_LENGTH -> "新密码至少 $MIN_PASSWORD_LENGTH 位"
                    newPwd != confirmPwd -> "两次输入的新密码不一致"
                    else -> {
                        val ok = viewModel.changePassword(
                            oldPwd.toCharArray(),
                            newPwd.toCharArray()
                        )
                        if (ok) null else "原密码不正确"
                    }
                }
            },
            onSuccess = {
                showChangePassword = false
                viewModel.snackbar = "密码修改成功，已对全部加密文件生效"
            }
        )
    }

    if (confirmSwitchToPrivate) {
        AlertDialog(
            onDismissRequest = { confirmSwitchToPrivate = false },
            title = { Text("切换回应用专属目录？") },
            text = { Text("金库中已有的加密文件会被迁移到应用专属目录。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmSwitchToPrivate = false
                    viewModel.switchLocation(targetPublic = false, treeUri = null) { }
                }) { Text("确认切换") }
            },
            dismissButton = {
                TextButton(onClick = { confirmSwitchToPrivate = false }) { Text("取消") }
            }
        )
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text("关于 JMH") },
            text = {
                Text(
                    "JMH 是一个本地文件加密保险箱。\n\n" +
                            "· 加密算法：AES-256-GCM\n" +
                            "· 密钥派生：PBKDF2-HMAC-SHA256（15 万次迭代）\n" +
                            "· 密钥分层：主密钥由密码保护，每个文件独立数据密钥\n" +
                            "· 加密通信：可将文件导出为带独立密码的分享文件\n" +
                            "· 所有加密文件仅保存在本机\n" +
                            "· 网络仅用于检查更新，不上传任何数据\n\n" +
                            "版本 ${viewModel.currentVersionName}\n" +
                            viewModel.repoUrl
                )
            },
            confirmButton = {
                TextButton(onClick = { showAbout = false }) { Text("知道了") }
            }
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp)
    )
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 4.dp)
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
}

/**
 * 修改密码对话框：需要输入原密码，并对新密码二次确认。
 */
@Composable
private fun ChangePasswordDialog(
    onDismiss: () -> Unit,
    onSubmit: (String, String, String) -> String?,
    onSuccess: () -> Unit
) {
    var oldPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改密码") },
        text = {
            Column {
                Text(
                    text = "修改后，全部已加密文件立即改用新密码验证，无需重新加密。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                PasswordTextField(
                    value = oldPassword,
                    onValueChange = {
                        oldPassword = it
                        error = null
                    },
                    label = "原密码"
                )
                Spacer(modifier = Modifier.height(12.dp))
                PasswordTextField(
                    value = newPassword,
                    onValueChange = {
                        newPassword = it
                        error = null
                    },
                    label = "新密码（至少 $MIN_PASSWORD_LENGTH 位）"
                )
                Spacer(modifier = Modifier.height(12.dp))
                PasswordTextField(
                    value = confirmPassword,
                    onValueChange = {
                        confirmPassword = it
                        error = null
                    },
                    label = "再次输入新密码",
                    isError = error != null
                )
                if (error != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val message = onSubmit(oldPassword, newPassword, confirmPassword)
                if (message == null) {
                    onSuccess()
                } else {
                    error = message
                }
            }) {
                Text("确认修改")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
