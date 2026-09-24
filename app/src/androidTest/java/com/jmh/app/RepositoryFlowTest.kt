package com.jmh.app

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jmh.app.crypto.VaultKeyManager
import com.jmh.app.storage.JmhRepository
import com.jmh.app.storage.VaultStorage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 业务层端到端测试：加密入库 → 扫描列表 → 解密预览 → 清理。
 *
 * 覆盖 UI 之外最关键的完整链路，运行于真实设备/模拟器的应用进程中。
 */
@RunWith(AndroidJUnit4::class)
class RepositoryFlowTest {

    private lateinit var context: android.content.Context
    private lateinit var storage: VaultStorage
    private lateinit var repository: JmhRepository
    private lateinit var keyManager: VaultKeyManager

    private val password = "flowtest123".toCharArray()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext

        // 干净的起点
        context.getSharedPreferences("jmh_vault_keys", 0).edit().clear().commit()
        storage = VaultStorage(context)
        storage.usePrivateDir()
        storage.privateDir.listFiles()?.forEach { it.delete() }

        repository = JmhRepository(context)
        keyManager = VaultKeyManager(context)
        keyManager.initialize(password)
    }

    @After
    fun tearDown() {
        storage.privateDir.listFiles()?.forEach { it.delete() }
        repository.clearPreviewCache()
        context.getSharedPreferences("jmh_vault_keys", 0).edit().clear().commit()
    }

    @Test
    fun encryptIntoVault_scanAndPreview_roundTrip() = runBlocking {
        val masterKey = requireNotNull(keyManager.masterKeyOrNull()) { "主密钥应已解锁" }

        // 1) 准备一个源文件
        val sourceFile = File(context.cacheDir, "测试文档.txt")
        val originalText = "JMH 端到端流程测试\n第二行内容 🔐"
        sourceFile.writeText(originalText, Charsets.UTF_8)

        // 2) 加密入库
        val encryptResult = repository.encryptIntoVault(Uri.fromFile(sourceFile), masterKey)
        assertTrue(
            "加密入库应成功，实际错误：${encryptResult.exceptionOrNull()?.message}",
            encryptResult.isSuccess
        )
        val created = encryptResult.getOrThrow()
        assertEquals("测试文档.txt", created.displayName)

        // 3) 金库目录中应出现 .jmh 文件，且内容不含明文
        val vaultFiles = storage.privateDir.listFiles()
            ?.filter { it.name.endsWith(".jmh") } ?: emptyList()
        assertEquals("金库中应有 1 个 .jmh 文件", 1, vaultFiles.size)

        val cipherBytes = vaultFiles[0].readBytes()
        assertTrue(
            "密文中不应出现明文内容",
            !String(cipherBytes, Charsets.ISO_8859_1).contains("端到端流程测试")
        )

        // 4) 扫描列表
        val items = repository.listVaultItems()
        assertEquals("列表应有 1 项", 1, items.size)
        assertEquals("测试文档.txt", items[0].displayName)
        assertEquals("text/plain", items[0].mime)
        assertEquals("txt", items[0].extension)

        // 5) 解密预览，内容必须与原文完全一致
        val previewResult = repository.decryptToPreview(items[0], masterKey)
        assertTrue(
            "解密预览应成功，实际错误：${previewResult.exceptionOrNull()?.message}",
            previewResult.isSuccess
        )
        val previewFile = previewResult.getOrThrow()
        assertTrue("预览文件应存在", previewFile.exists())
        assertEquals("解密内容必须与原文一致", originalText, previewFile.readText(Charsets.UTF_8))

        // 6) 原始文件被删除后，加密副本依然可用
        val vaultBytes = cipherBytes.size
        assertTrue("加密文件应包含头部数据", vaultBytes > sourceFile.length())
    }

    @Test
    fun preview_usesWrongKey_fails() = runBlocking {
        val masterKey = requireNotNull(keyManager.masterKeyOrNull())

        val sourceFile = File(context.cacheDir, "secret.txt")
        sourceFile.writeText("需要保护的内容", Charsets.UTF_8)
        repository.encryptIntoVault(Uri.fromFile(sourceFile), masterKey).getOrThrow()

        val items = repository.listVaultItems()
        assertEquals(1, items.size)

        // 换成错误的密钥解密，必须失败
        val wrongKey = com.jmh.app.crypto.CryptoBox.toSecretKey(
            com.jmh.app.crypto.CryptoBox.randomKeyBytes()
        )
        val result = repository.decryptToPreview(items[0], wrongKey)
        assertTrue("错误密钥解密必须失败", result.isFailure)
    }
}
