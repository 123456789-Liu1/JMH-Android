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
 * .jmh 加解密核心（信封加密 + 流式处理，支持大文件）。
 *
 * 加密：
 *  1. 为每个文件随机生成 DEK（数据密钥）
 *  2. 用主密钥包装 DEK，写入文件头
 *  3. 用 DEK 流式加密内容，AAD = 整个文件头（头部任何篡改都会导致解密失败）
 *
 * 解密时反向执行。因为主密钥由用户密码保护，没有密码就无法解开 DEK。
 */
object JmhCryptor {

    private const val BUFFER_SIZE = 128 * 1024

    /** 进度回调：0.0 ~ 1.0 */
    fun interface ProgressListener {
        fun onProgress(fraction: Float)
    }

    // ------------------------------------------------------------------ 加密

    /**
     * 加密数据流。
     *
     * @param source     明文输入流（由调用方负责关闭）
     * @param sourceSize 明文总字节数（用于进度计算，未知时传 -1）
     * @param meta       原始文件元数据
     * @param masterKey  已解锁的主密钥
     * @param dest       输出流，写入 .jmh 内容（本方法负责关闭）
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
            val dek = CryptoBox.toSecretKey(dekBytes)
            val wrappedDek = CryptoBox.wrap(masterKey, dekBytes, JmhFormat.KEY_AAD)
            val contentIv = KeyDerivation.randomBytes(CryptoBox.GCM_IV_SIZE)
            val headerBytes = buildHeader(wrappedDek, contentIv, meta.encode())

            val buffered = BufferedOutputStream(dest, BUFFER_SIZE)
            buffered.write(headerBytes)

            val cipher = CryptoBox.createCipher(Cipher.ENCRYPT_MODE, dek, contentIv, headerBytes)
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
        } finally {
            dekBytes.fill(0)
        }
    }

    // ------------------------------------------------------------------ 解密

    /**
     * 解密 .jmh 数据流。
     *
     * @param source     密文输入流（由调用方负责关闭）
     * @param sourceSize 密文总字节数（用于进度计算，未知时传 -1）
     * @param masterKey  已解锁的主密钥
     * @param dest       明文输出流（本方法负责 flush 并关闭）
     * @return 原始文件元数据
     * @throws WrongPasswordException 密码错误或文件被篡改
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

        val dekBytes = CryptoBox.unwrap(masterKey, header.wrappedDek, JmhFormat.KEY_AAD)
        try {
            val dek = CryptoBox.toSecretKey(dekBytes)
            val cipher = CryptoBox.createCipher(
                Cipher.DECRYPT_MODE, dek, header.contentIv, header.headerBytes
            )

            val plainSize = header.meta.size
            var remaining = if (sourceSize > 0) sourceSize - header.headerSize else -1L

            val out = BufferedOutputStream(dest, BUFFER_SIZE)
            val buffer = ByteArray(BUFFER_SIZE)
            var written = 0L

            if (remaining >= 0) {
                // 已知密文长度：按长度精确读取
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
                // 长度未知：读到流结尾
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

    // -------------------------------------------------------------- 头部读取

    /**
     * 只读取文件头元信息（用于扫描列表，不解密内容）。
     * @return 元数据；若不是有效的 .jmh 文件则返回 null
     */
    fun readMeta(source: InputStream): JmhMeta? = try {
        readHeader(DataInputStream(BufferedInputStream(source, 8192))).meta
    } catch (e: Exception) {
        null
    }

    private class ParsedHeader(
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

        val version = readChunk(1)[0].toInt()
        if (version != JmhFormat.VERSION.toInt()) throw IOException("不支持的 JMH 版本：$version")

        readChunk(1) // flags 保留

        val wrappedDekLen = readU16()
        val wrappedDek = readChunk(wrappedDekLen)

        val ivLen = readChunk(1)[0].toInt()
        val contentIv = readChunk(ivLen)

        val metaLen = readI32()
        if (metaLen <= 0 || metaLen > 1 shl 20) throw IOException("文件头元数据异常")
        val metaBytes = readChunk(metaLen)
        val meta = JmhMeta.decode(metaBytes)

        val headerBytes = record.toByteArray()
        return ParsedHeader(wrappedDek, contentIv, meta, headerBytes, headerBytes.size.toLong())
    }

    private fun buildHeader(wrappedDek: ByteArray, contentIv: ByteArray, metaBytes: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream(wrappedDek.size + metaBytes.size + 32)
        DataOutputStream(bos).use { out ->
            out.write(JmhFormat.MAGIC)
            out.writeByte(JmhFormat.VERSION.toInt())
            out.writeByte(0) // flags
            out.writeShort(wrappedDek.size)
            out.write(wrappedDek)
            out.writeByte(contentIv.size)
            out.write(contentIv)
            out.writeInt(metaBytes.size)
            out.write(metaBytes)
        }
        return bos.toByteArray()
    }

    private fun reportProgress(listener: ProgressListener?, written: Long, total: Long) {
        if (listener != null && total > 0) {
            listener.onProgress((written.toDouble() / total).toFloat().coerceIn(0f, 1f))
        }
    }
}
