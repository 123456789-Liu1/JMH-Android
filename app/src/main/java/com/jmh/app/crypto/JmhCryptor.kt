package com.jmh.app.crypto

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKey

/**
 * .jmh 加解密核心（流式处理，支持大文件与进度回调）。
 *
 * 支持两种模式：
 * - **金库模式**：DEK 由应用主密钥包装。解锁应用后即可直接读取，适合本机自用。
 * - **分享模式**：DEK 由「分享密码 + 文件内随机盐」派生的 KEK 包装。
 *   文件可发送给他人，对方用分享密码即可解开，实现端到端的加密通信。
 *
 * 同时兼容读取 v1 历史格式。
 */
object JmhCryptor {

    private const val BUFFER_SIZE = 128 * 1024

    private val EMPTY_SALT = ByteArray(0)

    /** 进度回调：0.0 ~ 1.0 */
    fun interface ProgressListener {
        fun onProgress(fraction: Float)
    }

    /** 文件头信息（不涉及任何密钥） */
    data class HeaderInfo(
        val version: Int,
        val mode: Int,
        val meta: JmhMeta,
        val salt: ByteArray
    ) {
        /** 是否为分享模式文件 */
        val isShareMode: Boolean get() = mode == JmhFormat.MODE_SHARE
    }

    // --------------------------------------------------------------- 加密

    /**
     * 加密为金库文件（DEK 由主密钥包装，本机自用）。
     */
    fun encrypt(
        source: InputStream,
        sourceSize: Long,
        meta: JmhMeta,
        masterKey: SecretKey,
        dest: OutputStream,
        onProgress: ProgressListener? = null
    ) {
        val dekBytes = CryptoBox.randomKeyBytes()
        try {
            val cipherSecret = CryptoBox.toSecretKey(dekBytes)
            val aad = JmhFormat.keyAadFor(JmhFormat.VERSION.toInt())
            val wrappedDek = CryptoBox.wrap(masterKey, dekBytes, aad)
            val contentIv = KeyDerivation.randomBytes(CryptoBox.GCM_IV_SIZE)
            val headerBytes = buildHeader(
                mode = JmhFormat.MODE_VAULT,
                salt = EMPTY_SALT,
                wrappedDek = wrappedDek,
                contentIv = contentIv,
                metaBytes = meta.encode()
            )
            writeEncrypted(cipherSecret, contentIv, headerBytes, source, sourceSize, dest, onProgress)
        } finally {
            dekBytes.fill(0)
        }
    }

    /**
     * 加密为分享文件（DEK 由分享密码派生的 KEK 包装，可发送给他人）。
     */
    fun encryptShared(
        source: InputStream,
        sourceSize: Long,
        meta: JmhMeta,
        sharePassword: CharArray,
        dest: OutputStream,
        onProgress: ProgressListener? = null
    ) {
        val dekBytes = CryptoBox.randomKeyBytes()
        try {
            val cipherSecret = CryptoBox.toSecretKey(dekBytes)
            val salt = KeyDerivation.randomBytes(JmhFormat.SHARE_SALT_SIZE)
            val kek = KeyDerivation.deriveKey(sharePassword, salt)
            val aad = JmhFormat.keyAadFor(JmhFormat.VERSION.toInt())
            val wrappedDek = CryptoBox.wrap(kek, dekBytes, aad)
            val contentIv = KeyDerivation.randomBytes(CryptoBox.GCM_IV_SIZE)
            val headerBytes = buildHeader(
                mode = JmhFormat.MODE_SHARE,
                salt = salt,
                wrappedDek = wrappedDek,
                contentIv = contentIv,
                metaBytes = meta.encode()
            )
            writeEncrypted(cipherSecret, contentIv, headerBytes, source, sourceSize, dest, onProgress)
        } finally {
            dekBytes.fill(0)
        }
    }

    // --------------------------------------------------------------- 解密

    /**
     * 解密金库文件。
     * @throws WrongPasswordException 主密钥不对、文件被篡改，或文件其实是分享模式的
     */
    fun decrypt(
        source: InputStream,
        sourceSize: Long,
        masterKey: SecretKey,
        dest: OutputStream,
        onProgress: ProgressListener? = null
    ): JmhMeta {
        val din = DataInputStream(BufferedInputStream(source, BUFFER_SIZE))
        val header = readHeader(din)
        if (header.mode != JmhFormat.MODE_VAULT) {
            throw WrongPasswordException("这是分享模式的加密文件，请使用「导入加密文件」并输入分享密码")
        }
        val dekBytes = CryptoBox.unwrap(
            masterKey, header.wrappedDek, JmhFormat.keyAadFor(header.version)
        )
        return decryptContent(header, dekBytes, din, sourceSize, dest, onProgress)
    }

    /**
     * 解密分享文件（使用分享密码）。
     * @throws WrongPasswordException 密码错误、文件被篡改，或文件其实是本机金库文件
     */
    fun decryptShared(
        source: InputStream,
        sourceSize: Long,
        sharePassword: CharArray,
        dest: OutputStream,
        onProgress: ProgressListener? = null
    ): JmhMeta {
        val din = DataInputStream(BufferedInputStream(source, BUFFER_SIZE))
        val header = readHeader(din)
        if (header.mode != JmhFormat.MODE_SHARE) {
            throw WrongPasswordException("这是本机金库文件，无需输入分享密码")
        }
        val kek = KeyDerivation.deriveKey(sharePassword, header.salt)
        val dekBytes = CryptoBox.unwrap(kek, header.wrappedDek, JmhFormat.keyAadFor(header.version))
        return decryptContent(header, dekBytes, din, sourceSize, dest, onProgress)
    }

    // ------------------------------------------------------------ 头部读取

    /**
     * 读取文件头信息（不解密内容），用于列表展示与导入前判断。
     * @return 头部信息；若不是有效的 .jmh 文件则返回 null
     */
    fun readHeaderInfo(source: InputStream): HeaderInfo? = try {
        val header = readHeader(DataInputStream(BufferedInputStream(source, 8192)))
        HeaderInfo(header.version, header.mode, header.meta, header.salt)
    } catch (e: Exception) {
        null
    }

    /** 只读取元数据（兼容旧调用） */
    fun readMeta(source: InputStream): JmhMeta? = readHeaderInfo(source)?.meta

    // ------------------------------------------------------------ 内部实现

    private class ParsedHeader(
        val version: Int,
        val mode: Int,
        val salt: ByteArray,
        val wrappedDek: ByteArray,
        val contentIv: ByteArray,
        val meta: JmhMeta,
        val headerBytes: ByteArray,
        val headerSize: Long
    )

    private fun readHeader(din: DataInputStream): ParsedHeader {
        val record = ByteArrayOutputStream(256)

        fun readChunk(len: Int): ByteArray {
            val bytes = ByteArray(len)
            din.readFully(bytes)
            record.write(bytes)
            return bytes
        }

        fun readU8(): Int = readChunk(1)[0].toInt() and 0xFF

        fun readU16(): Int {
            val b = readChunk(2)
            return ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
        }

        fun readI32(): Int {
            val b = readChunk(4)
            return ((b[0].toInt() and 0xFF) shl 24) or
                    ((b[1].toInt() and 0xFF) shl 16) or
                    ((b[2].toInt() and 0xFF) shl 8) or
                    (b[3].toInt() and 0xFF)
        }

        val magic = readChunk(JmhFormat.MAGIC.size)
        if (!magic.contentEquals(JmhFormat.MAGIC)) throw IOException("不是有效的 JMH 加密文件")

        val version = readU8()

        val mode: Int
        val salt: ByteArray
        when (version) {
            JmhFormat.LEGACY_VERSION.toInt() -> {
                readChunk(1)                    // v1 的 flags 保留位
                mode = JmhFormat.MODE_VAULT
                salt = EMPTY_SALT
            }

            JmhFormat.VERSION.toInt() -> {
                mode = readU8()
                val saltLen = readU8()
                salt = if (saltLen > 0) readChunk(saltLen) else EMPTY_SALT
            }

            else -> throw IOException("不支持的 JMH 版本：$version")
        }

        val wrappedDek = readChunk(readU16())
        val contentIv = readChunk(readU8())
        val metaLen = readI32()
        if (metaLen <= 0 || metaLen > (1 shl 20)) throw IOException("文件头元数据异常")
        val meta = JmhMeta.decode(readChunk(metaLen))

        val headerBytes = record.toByteArray()
        return ParsedHeader(
            version, mode, salt, wrappedDek, contentIv, meta, headerBytes, headerBytes.size.toLong()
        )
    }

    private fun buildHeader(
        mode: Int,
        salt: ByteArray,
        wrappedDek: ByteArray,
        contentIv: ByteArray,
        metaBytes: ByteArray
    ): ByteArray {
        val bos = ByteArrayOutputStream(wrappedDek.size + metaBytes.size + 48)
        DataOutputStream(bos).use { out ->
            out.write(JmhFormat.MAGIC)
            out.writeByte(JmhFormat.VERSION.toInt())
            out.writeByte(mode)
            out.writeByte(salt.size)
            if (salt.isNotEmpty()) out.write(salt)
            out.writeShort(wrappedDek.size)
            out.write(wrappedDek)
            out.writeByte(contentIv.size)
            out.write(contentIv)
            out.writeInt(metaBytes.size)
            out.write(metaBytes)
        }
        return bos.toByteArray()
    }

    private fun writeEncrypted(
        cipherSecret: SecretKey,
        contentIv: ByteArray,
        headerBytes: ByteArray,
        source: InputStream,
        sourceSize: Long,
        dest: OutputStream,
        onProgress: ProgressListener?
    ) {
        val buffered = BufferedOutputStream(dest, BUFFER_SIZE)
        buffered.write(headerBytes)

        val cipher = CryptoBox.createCipher(Cipher.ENCRYPT_MODE, cipherSecret, contentIv, headerBytes)
        val cipherOut = CipherOutputStream(buffered, cipher)

        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = source.read(buffer)
            if (read < 0) break
            cipherOut.write(buffer, 0, read)
            total += read
            if (sourceSize > 0) {
                onProgress?.onProgress((total.toDouble() / sourceSize).toFloat().coerceIn(0f, 1f))
            }
        }
        // close 会写出 GCM 认证标签，必须执行
        cipherOut.close()
        onProgress?.onProgress(1f)
    }

    private fun decryptContent(
        header: ParsedHeader,
        dekBytes: ByteArray,
        din: DataInputStream,
        sourceSize: Long,
        dest: OutputStream,
        onProgress: ProgressListener?
    ): JmhMeta {
        try {
            val cipherSecret = CryptoBox.toSecretKey(dekBytes)
            val cipher = CryptoBox.createCipher(
                Cipher.DECRYPT_MODE, cipherSecret, header.contentIv, header.headerBytes
            )

            val plainSize = header.meta.size
            var remaining = if (sourceSize > 0) sourceSize - header.headerSize else -1L

            val out = BufferedOutputStream(dest, BUFFER_SIZE)
            val buffer = ByteArray(BUFFER_SIZE)
            var written = 0L

            if (remaining >= 0) {
                while (remaining > 0) {
                    val want = minOf(buffer.size.toLong(), remaining).toInt()
                    val read = din.read(buffer, 0, want)
                    if (read < 0) throw IOException("文件已被截断")
                    remaining -= read
                    val plain = cipher.update(buffer, 0, read)
                    if (plain != null && plain.isNotEmpty()) {
                        out.write(plain)
                        written += plain.size
                        reportProgress(onProgress, written, plainSize)
                    }
                }
            } else {
                while (true) {
                    val read = din.read(buffer)
                    if (read < 0) break
                    val plain = cipher.update(buffer, 0, read)
                    if (plain != null && plain.isNotEmpty()) {
                        out.write(plain)
                        written += plain.size
                        reportProgress(onProgress, written, plainSize)
                    }
                }
            }

            try {
                val last = cipher.doFinal()
                if (last != null && last.isNotEmpty()) {
                    out.write(last)
                    written += last.size
                }
            } catch (e: AEADBadTagException) {
                throw WrongPasswordException("密码错误或文件已被篡改", e)
            }

            out.flush()
            onProgress?.onProgress(1f)
            return header.meta
        } finally {
            dekBytes.fill(0)
        }
    }

    private fun reportProgress(listener: ProgressListener?, written: Long, total: Long) {
        if (listener != null && total > 0) {
            listener.onProgress((written.toDouble() / total).toFloat().coerceIn(0f, 1f))
        }
    }
}
