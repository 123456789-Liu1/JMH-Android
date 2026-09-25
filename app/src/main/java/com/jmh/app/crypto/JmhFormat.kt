package com.jmh.app.crypto

/**
 * .jmh 加密容器格式定义。
 *
 * ### v1（历史格式，只读兼容）
 * ```
 * [4]  magic   "JMH1"
 * [1]  version 1
 * [1]  flags   保留位
 * [2]  wrappedDekLen
 * [N]  wrappedDek     用主密钥包装的 DEK
 * [1]  contentIvLen
 * [12] contentIv
 * [4]  metaLen
 * [M]  meta
 * [?]  content        内容密文（AAD = 以上整个头部）
 * ```
 *
 * ### v2（当前格式）
 * ```
 * [4]  magic   "JMH1"
 * [1]  version 2
 * [1]  mode    0 = 金库模式，1 = 分享模式
 * [1]  saltLen 金库模式为 0，分享模式为 16
 * [S]  salt    分享模式下用于从分享密码派生 KEK
 * [2]  wrappedDekLen
 * [N]  wrappedDek     金库模式用主密钥包装；分享模式用「密码 + salt」派生的 KEK 包装
 * [1]  contentIvLen
 * [12] contentIv
 * [4]  metaLen
 * [M]  meta
 * [?]  content        内容密文（AAD = 以上整个头部）
 * ```
 */
object JmhFormat {

    /** 魔数 "JMH1" */
    val MAGIC = byteArrayOf(0x4A, 0x4D, 0x48, 0x31)

    /** 当前写入的格式版本 */
    const val VERSION: Byte = 2

    /** 历史版本（仅用于读取） */
    const val LEGACY_VERSION: Byte = 1

    /** 金库模式：DEK 由应用主密钥包装（本机自用） */
    const val MODE_VAULT: Int = 0

    /** 分享模式：DEK 由「分享密码 + 文件内盐」派生的 KEK 包装（可发给他人） */
    const val MODE_SHARE: Int = 1

    /** 分享模式使用的盐长度 */
    const val SHARE_SALT_SIZE = 16

    /** 加密文件统一后缀 */
    const val SUFFIX = ".jmh"

    /**
     * 包装密钥时使用的附加认证数据，与格式版本绑定，避免不同版本的文件被交叉误用。
     */
    fun keyAadFor(version: Int): ByteArray = MAGIC + byteArrayOf(version.toByte())

    /**
     * 应用主密钥包装所用的附加认证数据。
     *
     * ⚠️ 该值一旦变更，所有已设置密码的用户都将无法解锁，**务必保持固定**。
     * 它沿用 v1 前缀，与历史版本保持兼容。
     */
    val MASTER_KEY_AAD: ByteArray = MAGIC + byteArrayOf(LEGACY_VERSION)

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
