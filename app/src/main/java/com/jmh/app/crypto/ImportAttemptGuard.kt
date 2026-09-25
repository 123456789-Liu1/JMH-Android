package com.jmh.app.crypto

import android.content.Context
import android.content.SharedPreferences

/**
 * 导入密码失败保护。
 *
 * 采用「连续失败后递增延迟」策略，让密码爆破变得极其缓慢，
 * 同时**不会**像"错误次数上限自毁"那样误删用户文件。
 *
 * 延迟序列：1s → 2s → 4s → 8s → 16s → 30s（封顶）
 *
 * ⚠️ 局限说明：这是应用层的防护，攻击者若绕过本应用直接解析 `.jmh` 文件
 * 则不受此限制。真正的安全边界是「强密码 + 高强度 KDF」。
 */
class ImportAttemptGuard(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 当前连续失败次数 */
    val failureCount: Int
        get() = prefs.getInt(KEY_FAILURES, 0)

    /** 本次尝试前需要等待的毫秒数（首次尝试为 0） */
    fun requiredDelayMillis(): Long {
        val n = failureCount
        if (n <= 0) return 0L
        val seconds = minOf(1L shl (n - 1).coerceAtMost(5), MAX_DELAY_SECONDS)
        return seconds * 1000L
    }

    /** 记录一次失败，返回此后需要等待的毫秒数 */
    fun recordFailure(): Long {
        prefs.edit().putInt(KEY_FAILURES, failureCount + 1).apply()
        return requiredDelayMillis()
    }

    /** 成功后清零 */
    fun recordSuccess() {
        prefs.edit().putInt(KEY_FAILURES, 0).apply()
    }

    /** 是否达到需要额外提醒用户的程度 */
    fun needsStrongWarning(): Boolean = failureCount >= WARN_THRESHOLD

    private companion object {
        const val PREFS_NAME = "jmh_attempt_guard"
        const val KEY_FAILURES = "consecutive_failures"
        const val MAX_DELAY_SECONDS = 30L
        const val WARN_THRESHOLD = 3
    }
}
