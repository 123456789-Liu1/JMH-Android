package com.jmh.app.storage

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import java.io.File

/**
 * 金库位置管理器。
 *
 * 支持两种存储位置：
 * - [Mode.APP_PRIVATE]：应用专属目录（默认，零权限）
 * - [Mode.PUBLIC_TREE]：用户授权的公共目录（可在系统文件管理器中直接看到加密文件）
 */
class VaultStorage(private val context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    enum class Mode { APP_PRIVATE, PUBLIC_TREE }

    /** 应用专属金库目录：Android/data/<包名>/files/JMH/ */
    val privateDir: File
        get() = File(context.getExternalFilesDir(null), "JMH")

    /** 当前存储模式 */
    val mode: Mode
        get() = if (prefs.getString(KEY_MODE, MODE_PRIVATE_VALUE) == MODE_PUBLIC_VALUE) {
            Mode.PUBLIC_TREE
        } else {
            Mode.APP_PRIVATE
        }

    /** 已授权的公共目录 URI（未授权为 null） */
    val publicTreeUri: Uri?
        get() = prefs.getString(KEY_TREE_URI, null)?.let { runCatching { Uri.parse(it) }.getOrNull() }

    /** 是否已经授权过公共目录 */
    val hasPublicDir: Boolean
        get() = publicTreeUri != null

    /** 是否已经展示过位置选择引导 */
    var locationPromptShown: Boolean
        get() = prefs.getBoolean(KEY_PROMPT_SHOWN, false)
        set(value) {
            prefs.edit().putBoolean(KEY_PROMPT_SHOWN, value).apply()
        }

    /**
     * 当前生效的金库目录。
     * 若选择了公共目录但授权失效，则自动回退到应用专属目录。
     */
    fun currentDir(): VaultDir {
        if (mode == Mode.PUBLIC_TREE) {
            val uri = publicTreeUri
            if (uri != null) {
                val safDir = SafVaultDir(context, uri)
                if (safDir.isReady()) return safDir
            }
        }
        return PrivateVaultDir(privateDir)
    }

    /** 锁定到指定的目录实现（用于迁移操作） */
    fun dirFor(mode: Mode, uri: Uri? = publicTreeUri): VaultDir = when {
        mode == Mode.PUBLIC_TREE && uri != null -> SafVaultDir(context, uri)
        else -> PrivateVaultDir(privateDir)
    }

    /** 切换到公共目录 */
    fun usePublicDir(uri: Uri) {
        prefs.edit()
            .putString(KEY_MODE, MODE_PUBLIC_VALUE)
            .putString(KEY_TREE_URI, uri.toString())
            .apply()
    }

    /** 切换回应用专属目录 */
    fun usePrivateDir() {
        prefs.edit().putString(KEY_MODE, MODE_PRIVATE_VALUE).apply()
    }

    /** 该模式是否属于当前设置 */
    fun isCurrentMode(mode: Mode): Boolean = this.mode == mode

    companion object {
        private const val PREFS_NAME = "jmh_settings"
        private const val KEY_MODE = "vault_mode"
        private const val KEY_TREE_URI = "vault_tree_uri"
        private const val KEY_PROMPT_SHOWN = "location_prompt_shown"
        private const val MODE_PRIVATE_VALUE = "private"
        private const val MODE_PUBLIC_VALUE = "public"
    }
}
