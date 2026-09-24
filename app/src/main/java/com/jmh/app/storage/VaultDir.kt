package com.jmh.app.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.jmh.app.crypto.JmhFormat
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * 金库中某个加密文件的引用（不区分存储后端）。
 */
data class VaultFileRef(
    val name: String,
    val size: Long,
    val lastModified: Long,
    internal val file: File? = null,
    internal val uri: Uri? = null
)

/**
 * 金库目录抽象。
 *
 * 两种实现：
 * - [PrivateVaultDir]：应用专属目录（零权限，默认方案）
 * - [SafVaultDir]   ：用户授权的公共目录（SAF，用户可在文件管理器中直接看到文件）
 */
interface VaultDir {

    /** 给用户看的路径描述 */
    val displayPath: String

    /** 该目录是否真实可用 */
    fun isReady(): Boolean

    /** 列出目录下全部 .jmh 文件 */
    fun listVaultFiles(): List<VaultFileRef>

    /** 打开某个加密文件的输入流 */
    fun openInput(ref: VaultFileRef): InputStream

    /** 在目录中创建/覆盖一个文件并写入内容，返回文件引用 */
    fun writeFile(fileName: String, writer: (OutputStream) -> Unit): VaultFileRef

    /** 删除文件 */
    fun delete(ref: VaultFileRef): Boolean

    /** 按名称查找文件 */
    fun find(fileName: String): VaultFileRef?

    /** 目录剩余可用空间（字节，未知时返回 Long.MAX_VALUE） */
    fun usableSpace(): Long
}

// ------------------------------------------------------------------ 应用专属目录

/**
 * 应用专属外部目录：`Android/data/<包名>/files/JMH/`
 * 无需任何权限与授权，其他应用无法访问，隐私隔离性好。
 */
class PrivateVaultDir(private val rootDir: File) : VaultDir {

    override val displayPath: String
        get() = "应用专属目录/JMH"

    override fun isReady(): Boolean = rootDir.exists() || rootDir.mkdirs()

    override fun listVaultFiles(): List<VaultFileRef> {
        val files = rootDir.listFiles() ?: return emptyList()
        return files
            .filter { it.isFile && JmhFormat.isVaultFile(it.name) }
            .map { it.toRef() }
            .sortedByDescending { it.lastModified }
    }

    override fun openInput(ref: VaultFileRef): InputStream {
        val file = ref.file ?: error("无效的文件引用")
        return file.inputStream()
    }

    override fun writeFile(fileName: String, writer: (OutputStream) -> Unit): VaultFileRef {
        if (!isReady()) error("无法创建金库目录")
        val target = File(rootDir, fileName)
        val temp = File(rootDir, "$fileName.tmp")
        temp.outputStream().use(writer)
        // 先写临时文件，再原子替换，避免中途失败产生半截文件
        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        return target.toRef()
    }

    override fun delete(ref: VaultFileRef): Boolean = ref.file?.delete() ?: false

    override fun find(fileName: String): VaultFileRef? {
        val file = File(rootDir, fileName)
        return if (file.isFile) file.toRef() else null
    }

    override fun usableSpace(): Long = rootDir.usableSpace

    private fun File.toRef() = VaultFileRef(
        name = name,
        size = length(),
        lastModified = lastModified(),
        file = this
    )
}

// ------------------------------------------------------------------ SAF 公共目录

/**
 * 用户授权的公共目录（Storage Access Framework）。
 * 用户可以在系统文件管理器中直接看到并管理这些加密文件。
 */
class SafVaultDir(private val context: Context, private val treeUri: Uri) : VaultDir {

    private val root: DocumentFile? by lazy {
        try {
            DocumentFile.fromTreeUri(context, treeUri)
        } catch (e: Exception) {
            null
        }
    }

    override val displayPath: String
        get() = root?.name?.let { "公共目录/$it" } ?: "公共目录（未授权）"

    override fun isReady(): Boolean = root?.canWrite() == true

    override fun listVaultFiles(): List<VaultFileRef> {
        val files = root?.listFiles() ?: return emptyList()
        return files
            .filter { it.isFile && JmhFormat.isVaultFile(it.name ?: "") }
            .map { it.toRef() }
            .sortedByDescending { it.lastModified }
    }

    override fun openInput(ref: VaultFileRef): InputStream {
        val uri = ref.uri ?: error("无效的文件引用")
        return context.contentResolver.openInputStream(uri) ?: error("无法打开文件")
    }

    override fun writeFile(fileName: String, writer: (OutputStream) -> Unit): VaultFileRef {
        val dir = root ?: error("目录未授权")
        // 覆盖同名旧文件
        dir.findFile(fileName)?.delete()
        val created = dir.createFile("application/octet-stream", fileName)
            ?: error("无法在所选目录创建文件")
        try {
            context.contentResolver.openOutputStream(created.uri)?.use(writer)
                ?: error("无法写入文件")
        } catch (e: Exception) {
            created.delete()
            throw e
        }
        return created.toRef()
    }

    override fun delete(ref: VaultFileRef): Boolean {
        val uri = ref.uri ?: return false
        return DocumentFile.fromSingleUri(context, uri)?.delete() ?: false
    }

    override fun find(fileName: String): VaultFileRef? =
        root?.findFile(fileName)?.takeIf { it.isFile }?.toRef()

    override fun usableSpace(): Long = Long.MAX_VALUE

    private fun DocumentFile.toRef() = VaultFileRef(
        name = name ?: "unknown",
        size = length(),
        lastModified = lastModified(),
        uri = uri
    )
}
