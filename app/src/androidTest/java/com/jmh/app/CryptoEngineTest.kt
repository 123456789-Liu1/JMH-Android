package com.jmh.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jmh.app.crypto.CryptoBox
import com.jmh.app.crypto.JmhCryptor
import com.jmh.app.crypto.JmhMeta
import com.jmh.app.crypto.KeyDerivation
import com.jmh.app.crypto.VaultKeyManager
import com.jmh.app.crypto.WrongPasswordException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.crypto.SecretKey

/**
 * 加密引擎设备端测试。
 *
 * 运行方式：gradlew connectedDebugAndroidTest
 * 覆盖：加解密往返、明文泄漏、错误密钥、防篡改、元数据、密钥派生、主密钥管理。
 */
@RunWith(AndroidJUnit4::class)
class CryptoEngineTest {

    private fun randomKey(): SecretKey = CryptoBox.toSecretKey(CryptoBox.randomKeyBytes())

    private fun encryptBytes(
        plain: ByteArray,
        key: SecretKey,
        name: String = "sample.txt",
        mime: String = "text/plain"
    ): ByteArray {
        val meta = JmhMeta(
            name = name,
            mime = mime,
            size = plain.size.toLong(),
            createdAt = System.currentTimeMillis()
        )
        val out = ByteArrayOutputStream()
        JmhCryptor.encrypt(ByteArrayInputStream(plain), plain.size.toLong(), meta, key, out)
        return out.toByteArray()
    }

    // ------------------------------------------------------------ 1. 加解密往返

    @Test
    fun roundTrip_preservesContentAndMeta() {
        val key = randomKey()
        val plain = "你好，JMH 加密测试！\nHello Encryption".toByteArray(Charsets.UTF_8)

        val cipher = encryptBytes(plain, key, name = "日记.txt", mime = "text/plain")
        assertTrue("密文应当比明文长（含文件头与认证标签）", cipher.size > plain.size)

        val out = ByteArrayOutputStream()
        val meta = JmhCryptor.decrypt(
            ByteArrayInputStream(cipher), cipher.size.toLong(), key, out
        )

        assertEquals("日记.txt", meta.name)
        assertEquals("text/plain", meta.mime)
        assertEquals(plain.size.toLong(), meta.size)
        assertArrayEquals("解密结果必须与原文完全一致", plain, out.toByteArray())
    }

    // ------------------------------------------------------------ 2. 无明文泄漏

    @Test
    fun cipherTextDoesNotLeakPlainText() {
        val key = randomKey()
        val secret = "SUPER-SECRET-STRING-12345"
        val cipher = encryptBytes(secret.toByteArray(), key)
        val asText = String(cipher, Charsets.ISO_8859_1)
        assertFalse("密文中不应出现明文片段", asText.contains(secret))
    }

    // ------------------------------------------------------------ 3. 错误密钥

    @Test
    fun wrongKey_cannotDecrypt() {
        val cipher = encryptBytes("top secret".toByteArray(), randomKey())
        try {
            JmhCryptor.decrypt(
                ByteArrayInputStream(cipher), cipher.size.toLong(),
                randomKey(), ByteArrayOutputStream()
            )
            fail("使用错误密钥解密时应当抛出 WrongPasswordException")
        } catch (e: WrongPasswordException) {
            // 预期行为
        }
    }

    // ------------------------------------------------------------ 4. 防篡改

    @Test
    fun tamperedCipher_isRejected() {
        val key = randomKey()
        val cipher = encryptBytes("important data".toByteArray(), key)
        cipher[cipher.size - 1] = (cipher[cipher.size - 1] + 1).toByte()

        try {
            JmhCryptor.decrypt(
                ByteArrayInputStream(cipher), cipher.size.toLong(),
                key, ByteArrayOutputStream()
            )
            fail("被篡改的密文应当被拒绝")
        } catch (e: WrongPasswordException) {
            // 预期行为
        }
    }

    // ------------------------------------------------------------ 5. 元数据读取

    @Test
    fun readMeta_returnsOriginalName() {
        val key = randomKey()
        val cipher = encryptBytes(
            "data".toByteArray(), key,
            name = "report.pdf", mime = "application/pdf"
        )
        val meta = JmhCryptor.readMeta(ByteArrayInputStream(cipher))
        assertNotNull("应能读取文件头元数据", meta)
        assertEquals("report.pdf", meta!!.name)
        assertEquals("application/pdf", meta.mime)
    }

    // ------------------------------------------------------------ 6. 密钥派生

    @Test
    fun samePassword_differentSalt_producesDifferentKeys() {
        val password = "abc123456".toCharArray()

        val key1 = KeyDerivation.deriveKey(password, KeyDerivation.randomBytes(16))
        val key2 = KeyDerivation.deriveKey(password, KeyDerivation.randomBytes(16))
        assertFalse(
            "不同盐必须派生出不同密钥（保证同密码不同用户互不可解）",
            key1.encoded.contentEquals(key2.encoded)
        )

        val salt = KeyDerivation.randomBytes(16)
        val key3 = KeyDerivation.deriveKey(password, salt)
        val key4 = KeyDerivation.deriveKey(password, salt)
        assertArrayEquals("相同密码 + 相同盐的结果必须可复现", key3.encoded, key4.encoded)
    }

    // ------------------------------------------------------------ 7. 主密钥与改密码

    @Test
    fun keyManager_setupVerifyAndChangePassword() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // 清理残留，保证测试可重复运行
        context.getSharedPreferences("jmh_vault_keys", 0).edit().clear().commit()

        val manager = VaultKeyManager(context)
        val oldPassword = "oldpass123".toCharArray()
        val newPassword = "newpass456".toCharArray()

        manager.initialize(oldPassword)
        assertTrue("设置后应处于已初始化状态", manager.isInitialized)
        assertTrue("原密码应通过校验", manager.verifyPassword(oldPassword))
        assertFalse("错误密码不应通过校验", manager.verifyPassword("wrongpwd".toCharArray()))

        // 改密码：只重新包装主密钥，历史加密文件不用动
        assertFalse("原密码错误时不能修改", manager.changePassword("badpwd".toCharArray(), newPassword))
        assertTrue("原密码正确时可以修改", manager.changePassword(oldPassword, newPassword))

        assertFalse("旧密码应失效", manager.verifyPassword(oldPassword))
        assertTrue("新密码应生效", manager.verifyPassword(newPassword))
    }
}
