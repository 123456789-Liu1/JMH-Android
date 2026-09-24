package com.jmh.app.crypto

/**
 * .jmh 加密容器格式定义（v1）。
 *
 * 文件布局：
 * ```
 * [4]  magic   "JMH1"
 * [1]  version 版本号
 * [1]  flags   保留位
 * [2]  wrappedDekLen 被包装的 DEK 长度
 * [N]  wrappedDek     用主密钥加密的 DEK（IV + 密文 + Tag）
 * [1]  contentIvLen  内容 IV 长度
 * [12] contentIv     内容加密 IV
 * [4]  metaLen       元数据长度
 * [M]  meta          文件名 / 类型 / 原始大小等
 * [?]  content       内容密文（AAD = 以上整个头部，防篡改）
 * ```
 */
object JmhFormat {

    /** 魔数 "JMH1" */
    val MAGIC = byteArrayOf(0x4A, 0x4D, 0x48, 0x31)

    /** 当前格式版本 */
    const val VERSION: Byte = 1

    /** 加密文件统一后缀 */
    const val SUFFIX = ".jmh"

    /** 包装密钥时使用的附加认证数据（固定前缀，防止跨格式重放） */
    val KEY_AAD: ByteArray = MAGIC + VERSION

    /** 生成加密后的文件名 */
    fun encryptedName(originalName: String): String =
        if (originalName.endsWith(SUFFIX, ignoreCase = true)) originalName
        else "$originalName$SUFFIX"

    /** 去掉 .jmh 后缀得到原始文件名 */
    fun originalName(encryptedName: String): String =
        if (encryptedName.endsWith(SUFFIX, ignoreCase = true)) {
            encryptedName.dropLast(SUFFIX.length)
        } else {
            encryptedName
        }

    /** 是否为 .jmh 文件 */
    fun isVaultFile(name: String): Boolean = name.endsWith(SUFFIX, ignoreCase = true)
}
