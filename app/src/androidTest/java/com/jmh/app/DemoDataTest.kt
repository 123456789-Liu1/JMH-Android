package com.jmh.app

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jmh.app.crypto.VaultKeyManager
import com.jmh.app.storage.JmhRepository
import com.jmh.app.storage.VaultStorage
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 手动运行的工具型测试：往金库里放几个示例文件，便于体验导出 / 导入 / 预览流程。
 *
 * 只跑这一个类，不会清空金库：
 * ```
 * gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.jmh.app.DemoDataTest
 * ```
 *
 * 注意：普通测试（CryptoEngineTest / RepositoryFlowTest）会清理金库，请勿混跑。
 */
@RunWith(AndroidJUnit4::class)
class DemoDataTest {

    private val demoPassword = "jmh123456"

    @Test
    fun prepareDemoVault() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val keyManager = VaultKeyManager(context)

        // 复用已有密码；若尚未设置则创建
        val unlocked = if (keyManager.isInitialized) {
            keyManager.unlock(demoPassword.toCharArray())
        } else {
            keyManager.initialize(demoPassword.toCharArray())
            true
        }
        if (!unlocked) {
            error("应用已设置其他密码，无法生成演示数据（请先在同一密码下运行）")
        }

        val masterKey = requireNotNull(keyManager.masterKeyOrNull())
        val repository = JmhRepository(context)

        val samples = listOf(
            "会议纪要.txt" to buildString {
                appendLine("JMH 加密通信演示")
                appendLine("======================")
                appendLine("这是一个用于演示「导出为加密文件」与「导入加密文件」的示例。")
                appendLine("你可以把它导出并设置一个独立的分享密码，然后发给朋友。")
                appendLine("对方导入后，就能在他自己的金库里打开这个文件。")
            },
            "使用说明.md" to buildString {
                appendLine("# JMH 使用说明")
                appendLine()
                appendLine("- 添加文件：把本机文件加密保存到金库")
                appendLine("- 导入加密文件：接收他人分享的 .jmh 文件")
                appendLine("- 查看文件：预览、解密导出、或导出为加密文件分享给他人")
            }
        )

        samples.forEach { (name, content) ->
            val source = File(context.cacheDir, name)
            source.writeText(content, Charsets.UTF_8)
            try {
                repository.encryptIntoVault(Uri.fromFile(source), masterKey).getOrThrow()
            } finally {
                source.delete()
            }
        }

        // 保持解锁状态，便于紧接着在界面上操作
        repository.clearPreviewCache()
    }
}
