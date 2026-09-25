package com.jmh.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jmh.app.storage.JmhRepository
import com.jmh.app.ui.MainViewModel
import com.jmh.app.ui.components.LoadingOverlay
import com.jmh.app.ui.screens.HomeScreen
import com.jmh.app.ui.screens.LocationPromptDialog
import com.jmh.app.ui.screens.PlayerScreen
import com.jmh.app.ui.screens.PreviewScreen
import com.jmh.app.ui.screens.SettingsScreen
import com.jmh.app.ui.screens.SetupPasswordScreen
import com.jmh.app.ui.screens.UnlockScreen
import com.jmh.app.ui.screens.UpdateAvailableDialog
import com.jmh.app.ui.screens.UpdateFailedDialog
import com.jmh.app.ui.screens.UpToDateDialog
import com.jmh.app.ui.screens.VaultListScreen
import com.jmh.app.ui.theme.JmhTheme
import java.io.File

/** 应用内页面 */
private sealed interface Screen {
    data object SetupPassword : Screen
    data object Home : Screen
    data object Unlock : Screen
    data object VaultList : Screen
    data object Settings : Screen
    data class Preview(val item: JmhRepository.VaultItem) : Screen
    data class Player(val item: JmhRepository.VaultItem, val file: File) : Screen
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            JmhTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    JmhApp()
                }
            }
        }
    }
}

@Composable
private fun JmhApp() {
    val viewModel: MainViewModel = viewModel()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    var screen by remember {
        mutableStateOf<Screen>(
            if (viewModel.passwordConfigured) Screen.Home else Screen.SetupPassword
        )
    }
    /** 解锁成功后要跳转的目标页面 */
    var pendingTarget by remember { mutableStateOf<Screen?>(null) }

    /** 首次使用的位置引导是否已经处理过 */
    var locationPromptHandled by remember { mutableStateOf(viewModel.storage.locationPromptShown) }

    val treePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            viewModel.switchLocation(targetPublic = true, treeUri = uri) { }
        }
    }

    // 统一处理一次性提示
    LaunchedEffect(viewModel.snackbar) {
        val message = viewModel.snackbar
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.snackbar = null
        }
    }

    // 启动时静默检查更新：失败不会打扰用户，仅在有新版本时弹窗
    LaunchedEffect(Unit) {
        viewModel.autoCheckUpdate()
    }

    /** 需要解锁的页面统一走这里 */
    fun navigateProtected(target: Screen) {
        if (viewModel.unlocked) {
            screen = target
        } else {
            pendingTarget = target
            screen = Screen.Unlock
        }
    }

    // 系统返回键：逐级回退，而不是直接退出应用
    BackHandler(
        enabled = screen != Screen.Home && screen != Screen.SetupPassword
    ) {
        when (val current = screen) {
            Screen.Unlock, Screen.VaultList, Screen.Settings -> screen = Screen.Home
            is Screen.Preview -> {
                viewModel.clearPreviewCache()
                screen = Screen.VaultList
            }
            is Screen.Player -> screen = Screen.Preview(current.item)
            else -> screen = Screen.Home
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (val current = screen) {
            Screen.SetupPassword -> SetupPasswordScreen(
                viewModel = viewModel,
                onFinished = {
                    viewModel.refreshVault()
                    screen = Screen.Home
                }
            )

            Screen.Home -> HomeScreen(
                viewModel = viewModel,
                onOpenVault = { navigateProtected(Screen.VaultList) },
                onOpenSettings = { navigateProtected(Screen.Settings) }
            )

            Screen.Unlock -> UnlockScreen(
                viewModel = viewModel,
                title = "输入密码",
                subtitle = "验证密码后即可访问金库内容",
                onSuccess = {
                    val target = pendingTarget
                    pendingTarget = null
                    viewModel.refreshVault()
                    screen = target ?: Screen.Home
                }
            )

            Screen.VaultList -> VaultListScreen(
                viewModel = viewModel,
                onBack = { screen = Screen.Home },
                onPreview = { item -> screen = Screen.Preview(item) }
            )

            Screen.Settings -> SettingsScreen(
                viewModel = viewModel,
                onBack = { screen = Screen.Home },
                onLocked = { screen = Screen.Home }
            )

            is Screen.Preview -> PreviewScreen(
                viewModel = viewModel,
                item = current.item,
                onBack = { screen = Screen.VaultList },
                onOpenPlayer = { file -> screen = Screen.Player(current.item, file) }
            )

            is Screen.Player -> PlayerScreen(
                file = current.file,
                title = current.item.displayName,
                onBack = { screen = Screen.Preview(current.item) }
            )
        }

        // 加解密进度遮罩
        val progress = viewModel.progress
        if (progress != null) {
            LoadingOverlay(
                label = viewModel.progressLabel.ifBlank { "处理中" },
                progress = progress
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        // 首次使用的位置引导（取消即使用默认的应用专属目录）
        if (screen == Screen.Home && !locationPromptHandled && viewModel.passwordConfigured) {
            LocationPromptDialog(
                onKeepPrivate = {
                    viewModel.storage.locationPromptShown = true
                    locationPromptHandled = true
                },
                onChoosePublic = {
                    viewModel.storage.locationPromptShown = true
                    locationPromptHandled = true
                    treePicker.launch(null)
                }
            )
        }

        // 发现新版本
        viewModel.availableUpdate?.let { info ->
            UpdateAvailableDialog(
                info = info,
                currentVersion = viewModel.currentVersionName,
                downloadProgress = viewModel.updateDownloadProgress,
                onDismiss = { viewModel.dismissUpdateDialog() },
                onUpdate = { viewModel.downloadAndInstallUpdate() }
            )
        }

        // 手动检查结果
        when (val state = viewModel.updateCheckState) {
            is MainViewModel.UpdateCheckState.UpToDate -> UpToDateDialog(
                version = viewModel.currentVersionName,
                onDismiss = { viewModel.dismissUpdateCheckState() }
            )

            is MainViewModel.UpdateCheckState.Failed -> UpdateFailedDialog(
                message = state.message,
                onDismiss = { viewModel.dismissUpdateCheckState() },
                onRetry = { viewModel.manualCheckUpdate() }
            )

            null -> Unit
        }
    }
}
