package com.jmh.app.crypto

import android.content.Context
import android.content.SharedPreferences
import java.util.Base64
import javax.crypto.SecretKey

/**
 * 主密钥管理器 —— 信封加密体系的核心。
 *
 * 设计要点：
 * - 全局只有一个「主密钥 Master Key」（随机 256 位），所有文件都用它包装各自的 DEK；
 * - 主密钥本身被「用户密码派生的 KEK」加密后保存在本地；
 * - 因此**修改密码时只需重新包装主密钥**，所有已加密的 .jmh 文件完全不用改动，
 *   新密码立即对所有历史文件生效。
 *
 * 存储内容（不含任何明文密码）：
 * - salt            : PBKDF2 随机盐
 * - wrapped_master  : 被 KEK 加密的主密钥（IV + 密文 + Tag）
 */
class VaultKeyManager(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 内存中的主密钥，仅在解锁后存在 */
    @Volatile
    private var cachedMasterKey: SecretKey? = null

    /** 是否已经设置过密码 */
    val isInitialized: Boolean
        get() = prefs.contains(KEY_SALT) && prefs.contains(KEY_WRAPPED_MASTER)

    /** 当前是否已解锁（主密钥在内存中） */
    val isUnlocked: Boolean
        get() = cachedMasterKey != null

    /**
     * 首次设置密码：生成主密钥并用密码包装保存。
     */
    fun initialize(password: CharArray) {
        val salt = KeyDerivation.randomBytes(KeyDerivation.SALT_SIZE)
        val masterBytes = CryptoBox.randomKeyBytes()
        try {
            val kek = KeyDerivation.deriveKey(password, salt)
            val wrapped = CryptoBox.wrap(kek, masterBytes, JmhFormat.KEY_AAD)
            prefs.edit()
                .putString(KEY_SALT, salt.toBase64())
                .putString(KEY_WRAPPED_MASTER, wrapped.toBase64())
                .apply()
            cachedMasterKey = CryptoBox.toSecretKey(masterBytes)
        } finally {
            masterBytes.fill(0)
        }
    }

    /**
     * 用密码解锁主密钥。成功返回 true，并把主密钥缓存到内存。
     */
    fun unlock(password: CharArray): Boolean = try {
        val masterBytes = unwrapMaster(password)
        if (masterBytes == null) {
            false
        } else {
            cachedMasterKey = CryptoBox.toSecretKey(masterBytes)
            masterBytes.fill(0)
            true
        }
    } catch (e: Exception) {
        false
    }

    /** 校验密码是否正确（不改变解锁状态） */
    fun verifyPassword(password: CharArray): Boolean = try {
        val bytes = unwrapMaster(password)
        if (bytes == null) false else {
            bytes.fill(0)
            true
        }
    } catch (e: Exception) {
        false
    }

    /** 获取已解锁的主密钥；未解锁返回 null */
    fun masterKeyOrNull(): SecretKey? = cachedMasterKey

    /** 清除内存中的主密钥（锁定） */
    fun lock() {
        cachedMasterKey = null
    }

    /**
     * 修改密码。
     *
     * 只需用新密码重新包装同一个主密钥，**所有已加密文件无需任何改动**，
     * 新密码即刻对全部历史文件生效。
     *
     * @return 旧密码错误时返回 false
     */
    fun changePassword(oldPassword: CharArray, newPassword: CharArray): Boolean {
        val masterBytes = try {
            unwrapMaster(oldPassword)
        } catch (e: Exception) {
            null
        } ?: return false

        return try {
            val newSalt = KeyDerivation.randomBytes(KeyDerivation.SALT_SIZE)
            val newKek = KeyDerivation.deriveKey(newPassword, newSalt)
            val newWrapped = CryptoBox.wrap(newKek, masterBytes, JmhFormat.KEY_AAD)
            prefs.edit()
                .putString(KEY_SALT, newSalt.toBase64())
                .putString(KEY_WRAPPED_MASTER, newWrapped.toBase64())
                .apply()
            cachedMasterKey = CryptoBox.toSecretKey(masterBytes)
            true
        } finally {
            masterBytes.fill(0)
        }
    }

    private fun unwrapMaster(password: CharArray): ByteArray? {
        val salt = prefs.getString(KEY_SALT, null)?.fromBase64() ?: return null
        val wrapped = prefs.getString(KEY_WRAPPED_MASTER, null)?.fromBase64() ?: return null
        val kek = KeyDerivation.deriveKey(password, salt)
        return CryptoBox.unwrap(kek, wrapped, JmhFormat.KEY_AAD)
    }

    private fun ByteArray.toBase64(): String = Base64.getEncoder().encodeToString(this)

    private fun String.fromBase64(): ByteArray = Base64.getDecoder().decode(this)

    companion object {
        private const val PREFS_NAME = "jmh_vault_keys"
        private const val KEY_SALT = "salt"
        private const val KEY_WRAPPED_MASTER = "wrapped_master"
    }
}
