package com.jmh.app.crypto

import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM 加解密封装。
 *
 * 采用 GCM 模式的原因：
 * 1. 认证加密（AEAD）——自带 16 字节校验标签，任何篡改都会导致解密失败；
 * 2. 支持流式处理，适合大文件；
 * 3. Android 全平台硬件加速支持。
 */
object CryptoBox {

    /** GCM 推荐的 IV 长度 */
    const val GCM_IV_SIZE = 12

    /** 认证标签长度（位） */
    private const val GCM_TAG_BITS = 128

    private const val TRANSFORM = "AES/GCM/NoPadding"

    /** 生成一个随机的 256 位数据密钥（DEK） */
    fun randomKeyBytes(): ByteArray = KeyDerivation.randomBytes(32)

    /** 把原始字节封装成 AES SecretKey */
    fun toSecretKey(bytes: ByteArray): SecretKey = SecretKeySpec(bytes, "AES")

    /**
     * 包装：用 KEK 加密一段数据（主密钥或 DEK）。
     * 输出格式：[IV(12)] + [密文 + Tag]
     */
    fun wrap(kek: SecretKey, plain: ByteArray, aad: ByteArray? = null): ByteArray {
        val iv = KeyDerivation.randomBytes(GCM_IV_SIZE)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, kek, GCMParameterSpec(GCM_TAG_BITS, iv))
        aad?.let { cipher.updateAAD(it) }
        val cipherText = cipher.doFinal(plain)
        return iv + cipherText
    }

    /**
     * 解开被包装的数据。
     * @throws WrongPasswordException 当密钥不正确或数据被篡改时抛出
     */
    fun unwrap(kek: SecretKey, wrapped: ByteArray, aad: ByteArray? = null): ByteArray {
        require(wrapped.size > GCM_IV_SIZE) { "被包装数据长度非法" }
        val iv = wrapped.copyOfRange(0, GCM_IV_SIZE)
        val cipherText = wrapped.copyOfRange(GCM_IV_SIZE, wrapped.size)
        return try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, kek, GCMParameterSpec(GCM_TAG_BITS, iv))
            aad?.let { cipher.updateAAD(it) }
            cipher.doFinal(cipherText)
        } catch (e: AEADBadTagException) {
            throw WrongPasswordException("密钥不正确或数据已被篡改", e)
        }
    }

    /** 创建用于流式加解密的 Cipher */
    fun createCipher(mode: Int, key: SecretKey, iv: ByteArray, aad: ByteArray?): Cipher {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(mode, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        aad?.let { cipher.updateAAD(it) }
        return cipher
    }
}

/** 密码错误 / 数据被篡改时抛出 */
class WrongPasswordException(message: String, cause: Throwable? = null) : Exception(message, cause)
