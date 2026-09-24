package com.jmh.app.ui.screens

import android.net.Uri
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File

/** 长按临时加速倍率 */
private const val LONG_PRESS_SPEED = 3f

private val SPEED_OPTIONS = listOf(0.5f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)

/**
 * 视频 / 音频播放器。
 *
 * 特性：
 * - 倍速播放（0.5x ~ 3x）
 * - **长按画面临时 3 倍速，松开恢复**
 * - 播放后返回自动释放资源
 */
@Composable
fun PlayerScreen(
    file: File,
    title: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val speedBoostState = remember { mutableStateOf(false) }
    var normalSpeed by remember { mutableFloatStateOf(1f) }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            prepare()
            playWhenReady = true
        }
    }

    // 根据「长按状态」与「用户选择的速度」应用播放速度
    LaunchedEffect(speedBoostState.value, normalSpeed) {
        exoPlayer.setPlaybackSpeed(
            if (speedBoostState.value) LONG_PRESS_SPEED else normalSpeed
        )
    }

    // 退出时释放播放器
    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    // 切到后台自动暂停
    val lifecycleOwner = context as? LifecycleOwner
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) exoPlayer.pause()
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = true
                    // 长按画面临时加速，松手恢复
                    setOnLongClickListener {
                        speedBoostState.value = true
                        true
                    }
                    setOnTouchListener { _, event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                                speedBoostState.value = false
                            else -> Unit
                        }
                        false // 不消费事件，保证控制器正常工作
                    }
                }
            }
        )

        // 顶部信息栏
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(Color.Black.copy(alpha = 0.45f))
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = Color.White
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (speedBoostState.value) {
                            "加速中 ${LONG_PRESS_SPEED}x"
                        } else {
                            "当前 ${formatSpeed(normalSpeed)} · 长按画面 3 倍速"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (speedBoostState.value) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            Color.White.copy(alpha = 0.75f)
                        }
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SPEED_OPTIONS.forEach { speed ->
                    FilterChip(
                        selected = normalSpeed == speed,
                        onClick = { normalSpeed = speed },
                        label = {
                            Text(
                                text = formatSpeed(speed),
                                fontWeight = FontWeight.Medium
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = Color.White.copy(alpha = 0.12f),
                            labelColor = Color.White,
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                }
            }
        }

        // 加速提示
        if (speedBoostState.value) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(
                        color = Color.Black.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(14.dp)
                    )
                    .padding(horizontal = 22.dp, vertical = 12.dp)
            ) {
                Text(
                    text = "${LONG_PRESS_SPEED}x 加速播放中",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}

private fun formatSpeed(speed: Float): String {
    val rounded = (speed * 100).toInt()
    return when {
        rounded % 100 == 0 -> "${rounded / 100}x"
        rounded % 10 == 0 -> "${rounded / 100.0}x".let { it.replace(".0x", "x") }
        else -> "${speed}x"
    }
}
