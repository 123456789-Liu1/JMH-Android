package com.jmh.app.crypto

/**
 * 密码强度策略。
 *
 * 分享密码的要求高于主密码，因为分享文件会在不受信任的渠道中传输，
 * 一旦落地到对方设备上，攻击者可以离线无限次尝试。
 */
object PasswordPolicy {

    /** 主密码（本机使用）最小长度 */
    const val MIN_MASTER_LENGTH = 6

    /** 分享密码最小长度 */
    const val MIN_SHARE_LENGTH = 12

    /** 强度评估结果 */
    data class Strength(
        /** 0（极弱）~ 4（很强） */
        val score: Int,
        val label: String
    )

    /**
     * 校验分享密码。
     * @return 不符合要求时返回错误提示；通过时返回 null
     */
    fun validateSharePassword(password: String): String? {
        if (password.length < MIN_SHARE_LENGTH) {
            return "分享密码至少需要 $MIN_SHARE_LENGTH 位"
        }

        val missing = mutableListOf<String>()
        if (!password.any { it.isLowerCase() }) missing += "小写字母"
        if (!password.any { it.isUpperCase() }) missing += "大写字母"
        if (!password.any { it.isDigit() }) missing += "数字"
        if (!password.any { !it.isLetterOrDigit() }) missing += "特殊符号"

        if (missing.isNotEmpty()) {
            return "分享密码还需包含：${missing.joinToString("、")}"
        }

        if (password.all { it.isLetterOrDigit() }) {
            return "分享密码过于单调，请混合字母与数字"
        }

        return null
    }

    /**
     * 评估密码强度，用于界面上的强度提示条。
     */
    fun evaluate(password: String): Strength {
        if (password.isEmpty()) return Strength(0, "")

        var score = 0
        if (password.length >= 8) score++
        if (password.length >= 12) score++
        if (password.any { it.isUpperCase() } && password.any { it.isLowerCase() }) score++
        if (password.any { it.isDigit() }) score++
        if (password.any { !it.isLetterOrDigit() }) score++

        return when {
            score <= 1 -> Strength(1, "很弱")
            score == 2 -> Strength(2, "较弱")
            score == 3 -> Strength(3, "中等")
            score == 4 -> Strength(4, "较强")
            else -> Strength(4, "很强")
        }
    }
}
