package com.jmh.app.crypto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * .jmh 文件头中保存的元数据（加密文件的「身份证」）。
 */
data class JmhMeta(
    /** 原始文件名（含扩展名） */
    val name: String,
    /** MIME 类型，用于选择预览方式 */
    val mime: String,
    /** 原始文件字节数 */
    val size: Long,
    /** 加密时间戳（毫秒） */
    val createdAt: Long
) {

    /** 原始扩展名（小写，不含点） */
    val extension: String
        get() = name.substringAfterLast('.', "").lowercase()

    /** 编码为紧凑的二进制结构 */
    fun encode(): ByteArray {
        val bos = ByteArrayOutputStream(128)
        DataOutputStream(bos).use { out ->
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            out.writeShort(nameBytes.size)
            out.write(nameBytes)
            val mimeBytes = mime.toByteArray(Charsets.UTF_8)
            out.writeShort(mimeBytes.size)
            out.write(mimeBytes)
            out.writeLong(size)
            out.writeLong(createdAt)
        }
        return bos.toByteArray()
    }

    companion object {

        fun decode(bytes: ByteArray): JmhMeta {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                val nameLen = input.readUnsignedShort()
                val nameBytes = ByteArray(nameLen)
                input.readFully(nameBytes)

                val mimeLen = input.readUnsignedShort()
                val mimeBytes = ByteArray(mimeLen)
                input.readFully(mimeBytes)

                val size = input.readLong()
                val createdAt = input.readLong()

                return JmhMeta(
                    name = String(nameBytes, Charsets.UTF_8),
                    mime = String(mimeBytes, Charsets.UTF_8),
                    size = size,
                    createdAt = createdAt
                )
            }
        }
    }
}
