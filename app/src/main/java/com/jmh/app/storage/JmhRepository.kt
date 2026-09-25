package com.jmh.app.storage

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.jmh.app.crypto.JmhCryptor
import com.jmh.app.crypto.JmhFormat
import com.jmh.app.crypto.JmhMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.crypto.SecretKey

/**
 * 金库业务层：把「文件选择 → 加密入库 → 扫描列表 → 解密预览/导出」串起来。
 */
class JmhRepository(private val context: Context) {

    val storage = VaultStorage(context)

    // ---------------------------------------------------------------- 数据模型

    /** 金库中的一个加密条目 */
    data class VaultItem(
        val ref: VaultFileRef,
        val meta: JmhMeta?
    ) {
        /** 展示用文件名（优先使用文件头里的原始名） */
        val displayName: String
            get() = meta?.name ?: JmhFormat.originalName(ref.name)

        /** 原始文件大小 */
        val originalSize: Long
            get() = meta?.size ?: ref.size

        /** 原始 MIME 类型 */
        val mime: String
            get() = meta?.mime ?: "application/octet-stream"

        /** 加密时间 */
        val createdAt: Long
            get() = meta?.createdAt ?: ref.lastModified

        /** 扩展名（小写） */
        val extension: String
            get() = displayName.substringAfterLast('.', "").lowercase()
    }

    // ---------------------------------------------------------------- 加密入库

    /**
     * 把用户选中的文件加密后存入金库。
     *
     * @param sourceUri 系统文件选择器返回的 URI
     * @return 成功时返回新建的条目
     */
    suspend fun encryptIntoVault(
        sourceUri: Uri,
        masterKey: SecretKey,
        onProgress: (Float) -> Unit = {}
    ): Result<VaultItem> = withContext(Dispatchers.IO) {
        try {
            val info = queryFileInfo(sourceUri)
            val displayName = info.first
            val size = info.second
            val mime = context.contentResolver.getType(sourceUri) ?: guessMime(displayName)

            val meta = JmhMeta(
                name = displayName,
                mime = mime,
                size = size,
                createdAt = System.currentTimeMillis()
            )

            val dir = storage.currentDir()
            if (!dir.isReady()) error("金库目录不可用")

            val vaultName = uniqueVaultName(dir, displayName)

            val ref = dir.writeFile(vaultName) { output ->
                val input = context.contentResolver.openInputStream(sourceUri)
                    ?: error("无法读取所选文件")
                input.use {
                    JmhCryptor.encrypt(it, size, meta, masterKey, output) { fraction ->
                        onProgress(fraction)
                    }
                }
            }
            Result.success(VaultItem(ref, meta))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ---------------------------------------------------------------- 扫描列表

    /** 扫描金库目录，读出全部加密条目的元信息 */
    suspend fun listVaultItems(): List<VaultItem> = withContext(Dispatchers.IO) {
        val dir = storage.currentDir()
        if (!dir.isReady()) return@withContext emptyList()
        dir.listVaultFiles().map { ref ->
            val meta = try {
                dir.openInput(ref).use { JmhCryptor.readMeta(it) }
            } catch (e: Exception) {
                null
            }
            VaultItem(ref, meta)
        }
    }

    /** 当前金库目录的展示路径 */
    fun vaultDisplayPath(): String = storage.currentDir().displayPath

    // ---------------------------------------------------------------- 解密预览

    /**
     * 解密到应用私有缓存目录用于预览。
     * 明文只存在于应用内部缓存，不会出现在用户可见的存储空间。
     */
    suspend fun decryptToPreview(
        item: VaultItem,
        masterKey: SecretKey,
        onProgress: (Float) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val cacheDir = File(context.cacheDir, PREVIEW_DIR).apply { mkdirs() }
            val outFile = File(cacheDir, sanitizeFileName(item.displayName))
            val dir = storage.currentDir()
            try {
                dir.openInput(item.ref).use { input ->
                    outFile.outputStream().use { output ->
                        JmhCryptor.decrypt(input, item.ref.size, masterKey, output) { fraction ->
                            onProgress(fraction)
                        }
                    }
                }
            } catch (e: Exception) {
                outFile.delete()
                throw e
            }
            Result.success(outFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 清空预览缓存（明文不留在设备上） */
    fun clearPreviewCache() {
        runCatching {
            File(context.cacheDir, PREVIEW_DIR).listFiles()?.forEach { it.delete() }
        }
    }

    // ---------------------------------------------------------------- 完全解密导出

    /**
     * 完全解密并写入用户指定的位置（SAF 目标 URI）。
     */
    suspend fun decryptToUri(
        item: VaultItem,
        masterKey: SecretKey,
        targetUri: Uri,
        onProgress: (Float) -> Unit = {}
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val dir = storage.currentDir()
            dir.openInput(item.ref).use { input ->
                context.contentResolver.openOutputStream(targetUri)?.use { output ->
                    JmhCryptor.decrypt(input, item.ref.size, masterKey, output) { fraction ->
                        onProgress(fraction)
                    }
                } ?: error("无法写入所选位置")
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ---------------------------------------------------------- 导出为分享文件

    /**
     * 把一个金库文件导出为**分享文件**（使用独立的分享密码保护），便于发送给他人。
     *
     * 流程：主密钥解密 → 分享密码重新加密 → 写入用户选择的位置。
     */
    suspend fun exportShared(
        item: VaultItem,
        masterKey: SecretKey,
        sharePassword: CharArray,
        targetDirUri: Uri,
        fileName: String,
        onProgress: (Float) -> Unit = {}
    ): Result<Uri> = withContext(Dispatchers.IO) {
        var tempFile: File? = null
        try {
            val meta = item.meta ?: error("无法读取文件信息，请返回列表刷新后重试")
            val dir = storage.currentDir()

            // 1) 用主密钥解密到应用私有缓存
            tempFile = File(context.cacheDir, "export_${System.currentTimeMillis()}.tmp")
            dir.openInput(item.ref).use { input ->
                tempFile.outputStream().use { output ->
                    JmhCryptor.decrypt(input, item.ref.size, masterKey, output) { fraction ->
                        onProgress(fraction * 0.5f)
                    }
                }
            }

            // 2) 用分享密码重新加密，写入用户选择的文件夹
            val plainSize = tempFile.length()
            val shareMeta = meta.copy(size = plainSize)

            val targetDir = DocumentFile.fromTreeUri(context, targetDirUri)
                ?: error("无法访问所选文件夹")

            val created = targetDir.createFile(MIME_BINARY, fileName)
                ?: error("无法在所选文件夹中创建文件")

            try {
                context.contentResolver.openOutputStream(created.uri)?.use { output ->
                    tempFile.inputStream().use { input ->
                        JmhCryptor.encryptShared(
                            input, plainSize, shareMeta, sharePassword, output
                        ) { fraction ->
                            onProgress(0.5f + fraction * 0.5f)
                        }
                    }
                } ?: error("无法写入文件")
            } catch (e: Exception) {
                created.delete()
                throw e
            }

            Result.success(created.uri)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            tempFile?.delete()
        }
    }

    // ------------------------------------------------------------ 导入分享文件

    /** 导入前的文件探测结果 */
    data class ImportProbe(
        val meta: JmhMeta,
        val isShareMode: Boolean,
        val fileSize: Long
    )

    /**
     * 读取待导入文件的头部信息，用于在界面上显示文件名等。
     */
    suspend fun probeImportFile(sourceUri: Uri): Result<ImportProbe> = withContext(Dispatchers.IO) {
        try {
            val headerInfo = context.contentResolver.openInputStream(sourceUri)?.use { input ->
                JmhCryptor.readHeaderInfo(input)
            } ?: error("无法读取所选文件")

            Result.success(
                ImportProbe(
                    meta = headerInfo.meta,
                    isShareMode = headerInfo.isShareMode,
                    fileSize = queryFileSize(sourceUri)
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 导入一个分享文件到本机金库。
     *
     * 流程：分享密码解密 → 本机主密钥重新加密 → 存入金库。
     * 导入后它就成为你金库中的普通文件，之后用应用主密码即可访问。
     */
    suspend fun importShared(
        sourceUri: Uri,
        sharePassword: CharArray,
        masterKey: SecretKey,
        onProgress: (Float) -> Unit = {}
    ): Result<VaultItem> = withContext(Dispatchers.IO) {
        var tempFile: File? = null
        try {
            // 1) 读取头部，确认是分享文件
            val headerInfo = context.contentResolver.openInputStream(sourceUri)?.use { input ->
                JmhCryptor.readHeaderInfo(input)
            } ?: error("无法读取所选文件")

            if (!headerInfo.isShareMode) {
                error("这是本机金库文件，无需导入")
            }

            val sourceSize = queryFileSize(sourceUri)

            // 2) 用分享密码解密到缓存（密码错误会在此抛出）
            tempFile = File(context.cacheDir, "import_${System.currentTimeMillis()}.tmp")
            tempFile.outputStream().use { output ->
                context.contentResolver.openInputStream(sourceUri)?.use { input ->
                    JmhCryptor.decryptShared(input, sourceSize, sharePassword, output) { fraction ->
                        onProgress(fraction * 0.5f)
                    }
                } ?: error("无法读取所选文件")
            }

            // 3) 用本机主密钥重新加密，存入金库
            val plainSize = tempFile.length()
            val meta = JmhMeta(
                name = headerInfo.meta.name,
                mime = headerInfo.meta.mime,
                size = plainSize,
                createdAt = System.currentTimeMillis()
            )
            val dir = storage.currentDir()
            val vaultName = uniqueVaultName(dir, meta.name)
            val ref = dir.writeFile(vaultName) { output ->
                tempFile.inputStream().use { input ->
                    JmhCryptor.encrypt(input, plainSize, meta, masterKey, output) { fraction ->
                        onProgress(0.5f + fraction * 0.5f)
                    }
                }
            }
            Result.success(VaultItem(ref, meta))
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            tempFile?.delete()
        }
    }

    // ---------------------------------------------------------------- 文件迁移

    /** 迁移结果 */
    data class MigrationResult(val moved: Int, val failed: Int)

    /**
     * 把金库中的全部加密文件从 [from] 迁移到 [to]。
     * 策略：先复制并校验大小，成功后才删除源文件，确保不丢数据。
     */
    suspend fun migrate(
        from: VaultDir,
        to: VaultDir,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): MigrationResult = withContext(Dispatchers.IO) {
        if (!to.isReady()) return@withContext MigrationResult(0, -1)
        val sources = from.listVaultFiles()
        var moved = 0
        var failed = 0
        sources.forEachIndexed { index, ref ->
            onProgress(index, sources.size)
            try {
                val bytes = from.openInput(ref).use { it.readBytes() }
                to.writeFile(ref.name) { out -> out.write(bytes) }
                val created = to.find(ref.name)
                if (created != null && created.size == bytes.size.toLong()) {
                    from.delete(ref)
                    moved++
                } else {
                    created?.let { to.delete(it) }
                    failed++
                }
            } catch (e: Exception) {
                failed++
            }
        }
        onProgress(sources.size, sources.size)
        MigrationResult(moved, failed)
    }

    // ---------------------------------------------------------------- 工具方法

    /** 生成不冲突的金库文件名 */
    private fun uniqueVaultName(dir: VaultDir, originalName: String): String {
        val base = JmhFormat.encryptedName(originalName)
        if (dir.find(base) == null) return base
        val stem = base.removeSuffix(JmhFormat.SUFFIX)
        var index = 1
        while (true) {
            val candidate = "$stem ($index)${JmhFormat.SUFFIX}"
            if (dir.find(candidate) == null) return candidate
            index++
            if (index > 9999) error("同名文件过多")
        }
    }

    /** 查询文件显示名与大小 */
    private fun queryFileInfo(uri: Uri): Pair<String, Long> {
        var name: String? = null
        var size = -1L

        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex >= 0) cursor.getString(nameIndex)?.let { name = it }
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        }

        // 回退：file:// 形式（内部调用或测试）直接读路径
        if (name == null && uri.scheme == "file") {
            uri.path?.let { path ->
                val file = File(path)
                if (file.exists()) {
                    name = file.name
                    size = file.length()
                }
            }
        }

        return (name ?: "file_${System.currentTimeMillis()}") to size
    }

    /** 查询文件字节数（用于进度计算） */
    private fun queryFileSize(uri: Uri): Long = queryFileInfo(uri).second

    private fun guessMime(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_")

    companion object {
        private const val PREVIEW_DIR = "preview"
        private const val MIME_BINARY = "application/octet-stream"
    }
}
