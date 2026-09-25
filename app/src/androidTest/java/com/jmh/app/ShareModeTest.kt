package com.jmh.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jmh.app.crypto.CryptoBox
import com.jmh.app.crypto.JmhCryptor
import com.jmh.app.crypto.JmhFormat
import com.jmh.app.crypto.JmhMeta
import com.jmh.app.crypto.KeyDerivation
import com.jmh.app.crypto.PasswordPolicy
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
import java.io.DataOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKey

/**
 * 分享模式（加密通信）与历史格式兼容性测试。
 */
@RunWith(AndroidJUnit4::class)
class ShareModeTest {

    private val sharePassword = "Share@Passw0rd2026".toCharArray()

    private fun randomKey(): SecretKey = CryptoBox.toSecretKey(CryptoBox.randomKeyBytes())

    private fun meta(name: String, size: Long) = JmhMeta(
        name = name,
        mime = "text/plain",
        size = size,
        createdAt = System.currentTimeMillis()
    )

    private fun encryptShared(plain: ByteArray, name: String = "note.txt"): ByteArray {
        val out = ByteArrayOutputStream()
        JmhCryptor.encryptShared(
            ByteArrayInputStream(plain), plain.size.toLong(), meta(name, plain.size.toLong()),
            sharePassword, out
        )
        return out.toByteArray()
    }

    // ------------------------------------------------------------ 1. 分享往返

    @Test
    fun shareMode_roundTrip() {
        val plain = "这是一段通过加密通信传递的秘密内容。".toByteArray(Charsets.UTF_8)
        val cipher = encryptShared(plain)

        val out = ByteArrayOutputStream()
        val resultMeta = JmhCryptor.decryptShared(
            ByteArrayInputStream(cipher), cipher.size.toLong(), sharePassword, out
        )

        assertEquals("note.txt", resultMeta.name)
        assertArrayEquals(plain, out.toByteArray())
    }

    // ------------------------------------------------------------ 2. 头部识别

    @Test
    fun shareFile_headerReportsShareMode() {
        val cipher = encryptShared("data".toByteArray())
        val info = JmhCryptor.readHeaderInfo(ByteArrayInputStream(cipher))
        assertNotNull(info)
        assertTrue("应被识别为分享模式", info!!.isShareMode)
        assertEquals(JmhFormat.VERSION.toInt(), info.version)
        assertTrue("分享文件必须携带盐", info.salt.isNotEmpty())
    }

    // ------------------------------------------------------------ 3. 错误密码

    @Test
    fun shareMode_wrongPassword_fails() {
        val cipher = encryptShared("secret".toByteArray())
        try {
            JmhCryptor.decryptShared(
                ByteArrayInputStream(cipher), cipher.size.toLong(),
                "Wrong@Password123".toCharArray(), ByteArrayOutputStream()
            )
            fail("错误密码必须解密失败")
        } catch (e: WrongPasswordException) {
            // 预期
        }
    }

    // ------------------------------------------------------------ 4. 模式隔离

    @Test
    fun vaultFile_cannotBeDecryptedAsShare() {
        val masterKey = randomKey()
        val plain = "vault content".toByteArray()
        val vaultFile = ByteArrayOutputStream().also { out ->
            JmhCryptor.encrypt(
                ByteArrayInputStream(plain), plain.size.toLong(),
                meta("v.txt", plain.size.toLong()), masterKey, out
            )
        }.toByteArray()

        try {
            JmhCryptor.decryptShared(
                ByteArrayInputStream(vaultFile), vaultFile.size.toLong(),
                sharePassword, ByteArrayOutputStream()
            )
            fail("金库文件不应能通过分享密码解密")
        } catch (e: WrongPasswordException) {
            // 预期：模式不匹配
        }
    }

    @Test
    fun shareFile_cannotBeDecryptedAsVault() {
        val masterKey = randomKey()
        val cipher = encryptShared("shared".toByteArray())
        try {
            JmhCryptor.decrypt(
                ByteArrayInputStream(cipher), cipher.size.toLong(),
                masterKey, ByteArrayOutputStream()
            )
            fail("分享文件不应能通过主密钥解密")
        } catch (e: WrongPasswordException) {
            // 预期：模式不匹配
        }
    }

    // ------------------------------------------------------------ 5. 向后兼容

    @Test
    fun legacyV1File_canStillBeRead() {
        val masterKey = randomKey()
        val plain = "v1 时代加密的旧文件".toByteArray(Charsets.UTF_8)
        val v1File = buildLegacyV1File(plain, meta("old.txt", plain.size.toLong()), masterKey)

        // 旧格式应能被正确识别与解密
        val info = JmhCryptor.readHeaderInfo(ByteArrayInputStream(v1File))
        assertNotNull("v1 文件头应能解析", info)
        assertFalse("v1 文件属于金库模式", info!!.isShareMode)
        assertEquals(JmhFormat.LEGACY_VERSION.toInt(), info.version)

        val out = ByteArrayOutputStream()
        val resultMeta = JmhCryptor.decrypt(
            ByteArrayInputStream(v1File), v1File.size.toLong(), masterKey, out
        )
        assertEquals("old.txt", resultMeta.name)
        assertArrayEquals("v1 文件解密结果必须完全一致", plain, out.toByteArray())
    }

    /** 按 v1 旧格式手工构造一个加密文件 */
    private fun buildLegacyV1File(plain: ByteArray, meta: JmhMeta, masterKey: SecretKey): ByteArray {
        val dekBytes = CryptoBox.randomKeyBytes()
        val dek = CryptoBox.toSecretKey(dekBytes)
        val legacyAad = JmhFormat.MAGIC + byteArrayOf(JmhFormat.LEGACY_VERSION)
        val wrappedDek = CryptoBox.wrap(masterKey, dekBytes, legacyAad)
        val contentIv = KeyDerivation.randomBytes(CryptoBox.GCM_IV_SIZE)
        val metaBytes = meta.encode()

        val headerBuffer = ByteArrayOutputStream()
        DataOutputStream(headerBuffer).use { out ->
            out.write(JmhFormat.MAGIC)
            out.writeByte(JmhFormat.LEGACY_VERSION.toInt())
            out.writeByte(0)                       // v1 的 flags
            out.writeShort(wrappedDek.size)
            out.write(wrappedDek)
            out.writeByte(contentIv.size)
            out.write(contentIv)
            out.writeInt(metaBytes.size)
            out.write(metaBytes)
        }
        val headerBytes = headerBuffer.toByteArray()

        val result = ByteArrayOutputStream()
        result.write(headerBytes)
        val cipher = CryptoBox.createCipher(Cipher.ENCRYPT_MODE, dek, contentIv, headerBytes)
        CipherOutputStream(result, cipher).use { it.write(plain) }
        return result.toByteArray()
    }

    // ------------------------------------------------------------ 6. 密码策略

    @Test
    fun passwordPolicy_enforcesStrongPassword() {
        assertNotNull("过短密码应被拒绝", PasswordPolicy.validateSharePassword("Ab1@short"))
        assertNotNull("缺少大小写应被拒绝", PasswordPolicy.validateSharePassword("abcdef123456"))
        assertNotNull("缺少特殊符号应被拒绝", PasswordPolicy.validateSharePassword("Abcdef123456"))
        assertNotNull("缺少数字应被拒绝", PasswordPolicy.validateSharePassword("Abcdef@ghijk"))

        val error = PasswordPolicy.validateSharePassword("Share@Passw0rd2026")
        assertEquals("强密码应当通过校验", null, error)
    }
}
