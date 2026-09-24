package com.jmh.app.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jmh.app.crypto.VaultKeyManager
import com.jmh.app.storage.JmhRepository
import com.jmh.app.storage.VaultStorage
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

    val storage: VaultStorage get() = repository.storage

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
