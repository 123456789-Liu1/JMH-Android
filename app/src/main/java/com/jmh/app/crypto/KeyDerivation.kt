package com.jmh.app.crypto

import java.security.SecureRandom
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 密码学基础工具：随机数生成 + PBKDF2 密钥派生。
 *
 * 「基础加密逻辑 + 用户密码」的落地点就在这里：
 * 用户密码本身不是密钥，而是通过 PBKDF2 与随机盐一起派生出真正的 AES-256 密钥。
 * 盐是随机的，所以即使两个用户设置了相同的密码，派生出的密钥也完全不同。
 */
object KeyDerivation {

    /** 盐长度（字节） */
    const val SALT_SIZE = 16

    /** 派生密钥长度（位） */
    private const val KEY_BITS = 256

    /** PBKDF2 迭代次数：越高越难暴力破解，但解锁耗时也越长 */
    private const val ITERATIONS = 150_000

    private const val ALGORITHM = "PBKDF2WithHmacSHA256"

    private val secureRandom = SecureRandom()

    /** 生成密码学安全的随机字节 */
    fun randomBytes(size: Int): ByteArray {
        val bytes = ByteArray(size)
        secureRandom.nextBytes(bytes)
        return bytes
    }

    /**
     * 从用户密码 + 盐派生出 KEK（密钥加密密钥）。
     * 返回的密钥只用于「包装/解开」主密钥，不会直接用来加密文件内容。
     */
    fun deriveKey(password: CharArray, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(password, salt, ITERATIONS, KEY_BITS)
        return try {
            val factory = SecretKeyFactory.getInstance(ALGORITHM)
            val keyBytes = factory.generateSecret(spec).encoded
            SecretKeySpec(keyBytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
