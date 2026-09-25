package com.jmh.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.jmh.app.crypto.PasswordPolicy
import com.jmh.app.ui.components.PasswordTextField

/**
 * 导出为加密文件的对话框：为文件设置独立的分享密码。
 */
@Composable
fun ExportShareDialog(
    fileName: String,
    hasDefaultDir: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (password: String, rememberAsDefault: Boolean) -> Unit
) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var rememberDir by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        val validation = PasswordPolicy.validateSharePassword(password)
        error = when {
            validation != null -> validation
            password != confirm -> "两次输入的密码不一致"
            else -> null
        }
        if (error == null) onConfirm(password, rememberDir)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导出为加密文件") },
        text = {
            Column {
                Text(
                    text = "该文件将使用「独立的分享密码」加密，可以发送给他人。\n" +
                            "对方必须知道此密码才能打开，与本机主密码无关。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "文件名：$fileName",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.height(16.dp))

                PasswordTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        error = null
                    },
                    label = "分享密码（至少 ${PasswordPolicy.MIN_SHARE_LENGTH} 位）"
                )

                Spacer(modifier = Modifier.height(8.dp))
                StrengthIndicator(PasswordPolicy.evaluate(password))

                Spacer(modifier = Modifier.height(12.dp))

                PasswordTextField(
                    value = confirm,
                    onValueChange = {
                        confirm = it
                        error = null
                    },
                    label = "再次输入分享密码",
                    isError = error != null,
                    imeAction = ImeAction.Done,
                    onImeAction = { submit() }
                )

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "需包含大小写字母、数字和特殊符号",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (error != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { rememberDir = !rememberDir }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = rememberDir, onCheckedChange = { rememberDir = it })
                    Spacer(modifier = Modifier.width(4.dp))
                    Column {
                        Text(
                            text = "设为默认导出文件夹",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = if (hasDefaultDir) "当前已设置，勾选后可更换" else "下次导出时自动定位到此文件夹",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { submit() }) { Text("下一步：选择位置") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/**
 * 导入加密文件的对话框：输入该文件对应的分享密码。
 */
@Composable
fun ImportPasswordDialog(
    fileName: String,
    failureCount: Int,
    onDismiss: () -> Unit,
    onConfirm: (password: String) -> Unit
) {
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        if (password.isEmpty()) {
            error = "请输入分享密码"
            return
        }
        onConfirm(password)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入加密文件") },
        text = {
            Column {
                Text(
                    text = "文件：$fileName",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "请输入这个加密文件的分享密码。验证通过后会保存到你的金库，" +
                            "之后用本机主密码即可打开。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                PasswordTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        error = null
                    },
                    label = "分享密码",
                    isError = error != null,
                    imeAction = ImeAction.Done,
                    onImeAction = { submit() }
                )

                if (error != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                if (failureCount >= 1) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (failureCount >= 3) {
                                "已连续输错 $failureCount 次。出于安全考虑，重试间隔会逐渐延长，" +
                                        "请确认密码无误后再提交。"
                            } else {
                                "密码错误 $failureCount 次。连续出错会导致重试等待时间变长。"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { submit() }) { Text("导入") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/**
 * 密码强度指示条。
 */
@Composable
private fun StrengthIndicator(strength: PasswordPolicy.Strength) {
    if (strength.label.isEmpty()) return

    val color = when (strength.score) {
        1 -> Color(0xFFE57373)
        2 -> Color(0xFFFFB74D)
        3 -> Color(0xFF4DD0E1)
        else -> Color(0xFF66BB6A)
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(4) { index ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        if (index < strength.score) color
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
            )
            if (index < 3) Spacer(modifier = Modifier.width(4.dp))
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = strength.label,
            style = MaterialTheme.typography.labelLarge,
            color = color
        )
    }
}
