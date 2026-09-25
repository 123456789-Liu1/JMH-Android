package com.jmh.app.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jmh.app.BuildConfig
import com.jmh.app.crypto.ImportAttemptGuard
import com.jmh.app.crypto.VaultKeyManager
import com.jmh.app.crypto.WrongPasswordException
import com.jmh.app.storage.JmhRepository
import com.jmh.app.storage.VaultStorage
import com.jmh.app.update.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 应用级状态容器：密码、金库列表、耗时操作进度。
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val keyManager = VaultKeyManager(application)
    private val repository = JmhRepository(application)
    private val attemptGuard = ImportAttemptGuard(application)
    private val updateChecker = UpdateChecker(application)

    val storage: VaultStorage get() = repository.storage

    /** 当前应用版本名 */
    val currentVersionName: String get() = BuildConfig.VERSION_NAME

    /** 项目主页地址 */
    val repoUrl: String get() = UpdateChecker.REPO_URL

    // ------------------------------------------------------------- 密码状态

    /** 是否已设置过密码 */
    var passwordConfigured by mutableStateOf(keyManager.isInitialized)
        private set

    /** 主密钥是否已解锁 */
    var unlocked by mutableStateOf(keyManager.isUnlocked)
        private set

    // ------------------------------------------------------------- 金库状态

    var vaultItems by mutableStateOf<List<JmhRepository.VaultItem>>(emptyList())
        private set

    var isLoading by mutableStateOf(false)
        private set

    var vaultPath by mutableStateOf(repository.vaultDisplayPath())
        private set

    // ------------------------------------------------------------- 交互反馈

    /** 0f~1f 表示有操作进行中；null 表示空闲 */
    var progress by mutableStateOf<Float?>(null)
        private set

    var progressLabel by mutableStateOf("")
        private set

    /** 一次性提示消息（Snackbar 消费后置空） */
    var snackbar by mutableStateOf<String?>(null)

    // ------------------------------------------------------------- 密码操作

    fun setupPassword(password: CharArray): Boolean = try {
        keyManager.initialize(password)
        passwordConfigured = true
        unlocked = true
        true
    } catch (e: Exception) {
        snackbar = "设置密码失败：${e.message}"
        false
    }

    fun unlock(password: CharArray): Boolean = try {
        val ok = keyManager.unlock(password)
        if (ok) {
            unlocked = true
        } else {
            snackbar = "密码不正确"
        }
        ok
    } catch (e: Exception) {
        snackbar = "解锁失败：${e.message}"
        false
    }

    fun verifyPassword(password: CharArray): Boolean = keyManager.verifyPassword(password)

    fun lock() {
        keyManager.lock()
        unlocked = false
        vaultItems = emptyList()
        repository.clearPreviewCache()
    }

    fun changePassword(oldPassword: CharArray, newPassword: CharArray): Boolean = try {
        val ok = keyManager.changePassword(oldPassword, newPassword)
        if (!ok) snackbar = "原密码不正确"
        ok
    } catch (e: Exception) {
        snackbar = "修改密码失败：${e.message}"
        false
    }

    // ------------------------------------------------------------- 金库操作

    fun refreshVault() {
        viewModelScope.launch {
            isLoading = true
            vaultItems = repository.listVaultItems()
            vaultPath = repository.vaultDisplayPath()
            isLoading = false
        }
    }

    /** 加密用户选中的一批文件 */
    fun encryptFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val master = keyManager.masterKeyOrNull()
        if (master == null) {
            snackbar = "请先解锁"
            return
        }
        viewModelScope.launch {
            var success = 0
            var failed = 0
            uris.forEachIndexed { index, uri ->
                progressLabel = "正在加密 ${index + 1} / ${uris.size}"
                progress = 0f
                val result = repository.encryptIntoVault(uri, master) { fraction ->
                    progress = fraction
                }
                if (result.isSuccess) success++ else failed++
            }
            progress = null
            progressLabel = ""
            snackbar = if (failed == 0) {
                "已加密 $success 个文件"
            } else {
                "已加密 $success 个，失败 $failed 个"
            }
            refreshVault()
        }
    }

    /** 解密到应用私有缓存用于预览 */
    fun previewItem(item: JmhRepository.VaultItem, onReady: (Result<File>) -> Unit) {
        val master = keyManager.masterKeyOrNull()
        if (master == null) {
            snackbar = "请先解锁"
            return
        }
        viewModelScope.launch {
            progressLabel = "正在解密预览"
            progress = 0f
            val result = repository.decryptToPreview(item, master) { fraction -> progress = fraction }
            progress = null
            progressLabel = ""
            if (result.isFailure) {
                snackbar = "解密失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
            }
            onReady(result)
        }
    }

    /** 完全解密导出到指定位置 */
    fun exportItem(item: JmhRepository.VaultItem, targetUri: Uri, onDone: (Boolean) -> Unit) {
        val master = keyManager.masterKeyOrNull()
        if (master == null) {
            snackbar = "请先解锁"
            return
        }
        viewModelScope.launch {
            progressLabel = "正在导出"
            progress = 0f
            val result = repository.decryptToUri(item, master, targetUri) { fraction -> progress = fraction }
            progress = null
            progressLabel = ""
            val ok = result.isSuccess
            snackbar = if (ok) "已导出：${item.displayName}" else "导出失败：${result.exceptionOrNull()?.message}"
            onDone(ok)
        }
    }

    /** 删除金库中的加密文件 */
    fun deleteItem(item: JmhRepository.VaultItem) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { repository.storage.currentDir().delete(item.ref) }
            snackbar = if (ok) "已删除" else "删除失败"
            refreshVault()
        }
    }

    /** 清理预览缓存 */
    fun clearPreviewCache() {
        repository.clearPreviewCache()
        snackbar = "预览缓存已清理"
    }

    // ------------------------------------------------------- 加密通信（导出/导入）

    /**
     * 把金库文件导出为**分享文件**（使用独立的分享密码保护）。
     *
     * @param targetDirUri 用户选择的保存文件夹
     * @param fileName     生成的 .jmh 文件名
     */
    fun exportShared(
        item: JmhRepository.VaultItem,
        sharePassword: CharArray,
        targetDirUri: Uri,
        fileName: String,
        onDone: (Boolean) -> Unit
    ) {
        val master = keyManager.masterKeyOrNull()
        if (master == null) {
            snackbar = "请先解锁"
            return
        }
        viewModelScope.launch {
            progressLabel = "正在生成加密文件"
            progress = 0f
            val result = repository.exportShared(
                item, master, sharePassword, targetDirUri, fileName
            ) { fraction -> progress = fraction }
            progress = null
            progressLabel = ""
            val ok = result.isSuccess
            snackbar = if (ok) {
                "已导出加密文件：$fileName"
            } else {
                "导出失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
            }
            onDone(ok)
        }
    }

    /** 读取待导入文件的头部信息（用于界面展示） */
    fun probeImportFile(uri: Uri, onResult: (Result<JmhRepository.ImportProbe>) -> Unit) {
        viewModelScope.launch {
            onResult(repository.probeImportFile(uri))
        }
    }

    /**
     * 导入分享文件到本机金库。
     * 导入成功后该文件变为金库中的普通文件，之后用应用主密码即可访问。
     */
    fun importShared(
        sourceUri: Uri,
        sharePassword: CharArray,
        onDone: (Boolean) -> Unit
    ) {
        val master = keyManager.masterKeyOrNull()
        if (master == null) {
            snackbar = "请先解锁"
            return
        }
        viewModelScope.launch {
            progressLabel = "正在导入加密文件"
            progress = 0f
            val result = repository.importShared(sourceUri, sharePassword, master) { fraction ->
                progress = fraction
            }
            progress = null
            progressLabel = ""

            if (result.isSuccess) {
                attemptGuard.recordSuccess()
                snackbar = "导入成功：${result.getOrThrow().displayName}"
                refreshVault()
                onDone(true)
            } else {
                val extraDelay = attemptGuard.recordFailure()
                val error = result.exceptionOrNull()
                val base = when (error) {
                    is WrongPasswordException -> "密码错误或文件已损坏"
                    else -> error?.message ?: "导入失败"
                }
                val waitHint = if (extraDelay > 0) "（请等待 ${extraDelay / 1000} 秒后再试）" else ""
                snackbar = base + waitHint
                onDone(false)
            }
        }
    }

    /** 当前连续失败次数（用于提醒用户） */
    fun importFailureCount(): Int = attemptGuard.failureCount

    /** 是否应给出强警告 */
    fun shouldWarnOnImport(): Boolean = attemptGuard.needsStrongWarning()

    // ------------------------------------------------------------- 检查更新

    /** 手动检查的状态（非 null 时展示对应提示框） */
    sealed interface UpdateCheckState {
        data object UpToDate : UpdateCheckState
        data class Failed(val message: String) : UpdateCheckState
    }

    /** 发现的新版本（非 null 时展示更新弹窗） */
    var availableUpdate by mutableStateOf<UpdateChecker.UpdateInfo?>(null)
        private set

    /** 手动检查结果状态 */
    var updateCheckState by mutableStateOf<UpdateCheckState?>(null)
        private set

    /** 更新包下载进度（null 表示未在下载） */
    var updateDownloadProgress by mutableStateOf<Float?>(null)
        private set

    /**
     * 启动时自动检查：**静默失败**。
     * 连不上网络、超时、仓库不可访问都不会给用户任何打扰。
     */
    fun autoCheckUpdate() {
        if (!updateChecker.shouldAutoCheck()) return
        viewModelScope.launch {
            when (val result = updateChecker.check()) {
                is UpdateChecker.CheckResult.Available -> availableUpdate = result.info
                else -> Unit   // 静默忽略
            }
        }
    }

    /** 设置页手动检查：三种结果都会给出明确反馈 */
    fun manualCheckUpdate() {
        viewModelScope.launch {
            updateCheckState = null
            progressLabel = "正在检查更新"
            progress = 0f
            val result = updateChecker.check()
            progress = null
            progressLabel = ""

            updateCheckState = when (result) {
                is UpdateChecker.CheckResult.Available -> {
                    availableUpdate = result.info
                    null
                }
                UpdateChecker.CheckResult.UpToDate -> UpdateCheckState.UpToDate
                is UpdateChecker.CheckResult.Failed -> UpdateCheckState.Failed(result.message)
            }
        }
    }

    fun dismissUpdateDialog() {
        availableUpdate = null
    }

    fun dismissUpdateCheckState() {
        updateCheckState = null
    }

    /**
     * 下载新版本并调起系统安装器。
     * 覆盖安装会保留全部数据与设置。
     */
    fun downloadAndInstallUpdate() {
        val info = availableUpdate ?: return
        viewModelScope.launch {
            updateDownloadProgress = 0f
            val result = updateChecker.download(info) { fraction ->
                updateDownloadProgress = fraction
            }
            updateDownloadProgress = null

            result.fold(
                onSuccess = { file ->
                    availableUpdate = null
                    snackbar = "下载完成，正在打开安装程序…"
                    runCatching { updateChecker.installApk(file) }
                        .onFailure { snackbar = "无法启动安装器：${it.message}" }
                },
                onFailure = { error ->
                    snackbar = "下载失败：${error.message ?: "网络异常"}"
                }
            )
        }
    }

    // ------------------------------------------------------------- 存储位置

    /** 切换存储位置，并把已有文件迁移过去 */
    fun switchLocation(
        targetPublic: Boolean,
        treeUri: Uri?,
        onFinished: (Boolean) -> Unit
    ) {
        viewModelScope.launch {
            val storageManager = repository.storage
            val from = storageManager.currentDir()
            val to = if (targetPublic && treeUri != null) {
                storageManager.dirFor(VaultStorage.Mode.PUBLIC_TREE, treeUri)
            } else {
                storageManager.dirFor(VaultStorage.Mode.APP_PRIVATE, null)
            }

            if (!to.isReady()) {
                snackbar = "目标目录不可用"
                onFinished(false)
                return@launch
            }

            val sources = from.listVaultFiles()
            if (sources.isNotEmpty()) {
                progressLabel = "正在迁移文件"
                progress = 0f
                val result = repository.migrate(from, to) { done, total ->
                    progress = if (total > 0) done.toFloat() / total else 1f
                }
                progress = null
                progressLabel = ""

                if (result.failed > 0) {
                    snackbar = "迁移完成：成功 ${result.moved} 个，失败 ${result.failed} 个"
                } else {
                    snackbar = "已迁移 ${result.moved} 个文件"
                }
                if (result.failed != 0) {
                    onFinished(false)
                    refreshVault()
                    return@launch
                }
            }

            // 迁移成功后才真正切换设置
            if (targetPublic && treeUri != null) {
                storageManager.usePublicDir(treeUri)
            } else {
                storageManager.usePrivateDir()
            }
            vaultPath = repository.vaultDisplayPath()
            refreshVault()
            snackbar = "存储位置已切换"
            onFinished(true)
        }
    }
}
