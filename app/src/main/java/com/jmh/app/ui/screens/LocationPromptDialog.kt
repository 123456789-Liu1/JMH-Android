package com.jmh.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 首次使用引导：选择加密文件的保存位置。
 *
 * 用户可以直接取消（关闭），取消后自动使用「应用专属目录」。
 */
@Composable
fun LocationPromptDialog(
    onKeepPrivate: () -> Unit,
    onChoosePublic: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onKeepPrivate,
        title = { Text("加密文件保存在哪里？") },
        text = {
            Column {
                Text(
                    text = "JMH 会把所有加密文件集中放在一个文件夹里。你可以：",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(14.dp))
                LocationOption(
                    title = "应用专属目录（推荐）",
                    description = "无需任何权限，其他应用无法访问，隐私性最好",
                    onClick = onKeepPrivate
                )
                Spacer(modifier = Modifier.height(10.dp))
                LocationOption(
                    title = "自选公共目录",
                    description = "可在系统文件管理器中直接看到并拷贝加密文件",
                    onClick = onChoosePublic
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onKeepPrivate) { Text("以后再说") }
        }
    )
}

@Composable
private fun LocationOption(
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
