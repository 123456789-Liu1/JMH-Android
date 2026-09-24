package com.jmh.app.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.jmh.app.storage.JmhRepository
import com.jmh.app.ui.FileTypes
import com.jmh.app.ui.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 解密预览界面。
 *
 * 明文只解密到应用私有缓存目录用于展示，不会写入用户可见的存储空间。
 */
@Composable
fun PreviewScreen(
    viewModel: MainViewModel,
    item: JmhRepository.VaultItem,
    onBack: () -> Unit,
    onOpenPlayer: (File) -> Unit
) {
    val context = LocalContext.current
    val kind = FileTypes.kindOf(item.extension, item.mime)

    var decrypted by remember { mutableStateOf<File?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(item.ref.name) {
        viewModel.previewItem(item) { result ->
            result.fold(
                onSuccess = { decrypted = it },
                onFailure = { failure = it.message ?: "解密失败" }
            )
        }
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
                .padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                viewModel.clearPreviewCache()
                onBack()
            }) {
                Icon(imageVector = Icons.Filled.ArrowBack, contentDescription = "返回")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${FileTypes.formatSize(item.originalSize)} · ¥加密预览",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = {
                    val file = decrypted
                    if (file != null) openWithSystem(context, file, item.mime)
                }
            ) {
                Icon(imageVector = Icons.Filled.Share, contentDescription = "用其他应用打开")
            }
        }

        Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

        Box(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            when {
                failure != null -> {
                    Text(
                        text = "无法预览：$failure",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(32.dp)
                    )
                }

                decrypted == null -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(text = "正在解密…", style = MaterialTheme.typography.bodyMedium)
                    }
                }

                else -> {
                    val file = decrypted!!
                    when (kind) {
                        FileTypes.Kind.IMAGE -> ImagePreview(file)
                        FileTypes.Kind.TEXT -> TextPreview(file)
                        FileTypes.Kind.VIDEO, FileTypes.Kind.AUDIO -> PlayPrompt(
                            isVideo = kind == FileTypes.Kind.VIDEO,
                            onPlay = { onOpenPlayer(file) },
                            onExternal = { openWithSystem(context, file, item.mime) }
                        )
                        else -> ExternalPrompt(
                            name = item.displayName,
                            onExternal = { openWithSystem(context, file, item.mime) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ImagePreview(file: File) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, file) {
        value = withContext(Dispatchers.IO) {
            decodeSampledBitmap(file.absolutePath, 2400)?.asImageBitmap()
        }
    }
    val image = bitmap
    if (image == null) {
        Text(text = "图片解码失败", color = MaterialTheme.colorScheme.error)
    } else {
        Image(
            bitmap = image,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            contentScale = ContentScale.Fit
        )
    }
}

@Composable
private fun TextPreview(file: File) {
    val content by produceState(initialValue = "读取中…", file) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val maxBytes = 200 * 1024
                if (file.length() > maxBytes) {
                    file.inputStream().use { stream ->
                        val buffer = ByteArray(maxBytes)
                        val read = stream.read(buffer)
                        String(buffer, 0, maxOf(read, 0), Charsets.UTF_8) + "\n\n…(文件过大，仅显示前 200KB)"
                    }
                } else {
                    file.readText(Charsets.UTF_8)
                }
            }.getOrElse { "无法读取文本内容：${it.message}" }
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp)
    ) {
        Text(
            text = content,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun PlayPrompt(isVideo: Boolean, onPlay: () -> Unit, onExternal: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(84.dp)
                .background(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(26.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(44.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        Spacer(modifier = Modifier.height(18.dp))
        Text(
            text = if (isVideo) "已解密，可播放视频" else "已解密，可播放音频",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "播放器支持倍速播放，长按画面可临时 3 倍速",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(20.dp))
        Row {
            Button(onClick = onPlay) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("播放")
            }
            Spacer(modifier = Modifier.width(12.dp))
            Button(onClick = onExternal) {
                Text("其他应用打开")
            }
        }
    }
}

@Composable
private fun ExternalPrompt(name: String, onExternal: () -> Unit) {
    Column(
        modifier = Modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "该类型不支持内置预览",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "$name 已解密到应用缓存，可交给系统应用打开查看。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(20.dp))
        Button(onClick = onExternal) {
            Text("用其他应用打开")
        }
    }
}

/** 用系统应用打开解密后的文件 */
private fun openWithSystem(context: Context, file: File, mime: String) {
    try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime.ifBlank { "*/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "选择打开方式"))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "没有找到可以打开该文件的应用", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, "打开失败：${e.message}", Toast.LENGTH_SHORT).show()
    }
}

/** 按最大边长采样解码，避免大图 OOM */
private fun decodeSampledBitmap(path: String, maxSize: Int): android.graphics.Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / sample > maxSize || bounds.outHeight / sample > maxSize) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeFile(path, options)
    } catch (e: Exception) {
        null
    }
}
