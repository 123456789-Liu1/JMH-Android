package com.jmh.app.update

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.FileProvider
import com.jmh.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 检查更新。
 *
 * 设计要点（针对国内网络环境）：
 * - **多源回退**：依次尝试 jsDelivr CDN → raw.githubusercontent → 公益镜像
 * - **短超时**：单源 5 秒，全部失败立刻返回失败，绝不长时间阻塞
 * - **静默失败**：启动时自动检查失败不打扰用户；手动检查才提示
 * - **不收集任何信息**：仅请求一个静态 JSON 文件，不带任何设备标识
 */
class UpdateChecker(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("jmh_update", Context.MODE_PRIVATE)

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val downloadUrl: String,
        val releaseUrl: String,
        val releaseNotes: String
    )

    sealed interface CheckResult {
        data class Available(val info: UpdateInfo) : CheckResult
        data object UpToDate : CheckResult
        data class Failed(val message: String) : CheckResult
    }

    /** 上次检查时间，用于限制自动检查频率 */
    var lastCheckTime: Long
        get() = prefs.getLong(KEY_LAST_CHECK, 0L)
        private set(value) = prefs.edit().putLong(KEY_LAST_CHECK, value).apply()

    /** 距上次检查是否已超过自动检查间隔 */
    fun shouldAutoCheck(): Boolean =
        System.currentTimeMillis() - lastCheckTime > AUTO_CHECK_INTERVAL_MS

    /**
     * 执行检查：依次尝试多个源，任一成功即返回结果。
     */
    suspend fun check(): CheckResult = withContext(Dispatchers.IO) {
        var lastError: String? = null

        for (source in SOURCES) {
            val body = fetch(source)
            if (body == null) continue

            val info = parse(body)
            if (info == null) {
                lastError = "版本信息格式不正确"
                continue
            }

            lastCheckTime = System.currentTimeMillis()
            return@withContext if (info.versionCode > BuildConfig.VERSION_CODE) {
                CheckResult.Available(info)
            } else {
                CheckResult.UpToDate
            }
        }

        CheckResult.Failed(lastError ?: "无法连接到更新服务器")
    }

    /**
     * 下载 APK 到应用私有缓存。下载源同样支持镜像回退。
     */
    suspend fun download(
        info: UpdateInfo,
        onProgress: (Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val dir = File(appContext.cacheDir, "update").apply { mkdirs() }
        val target = File(dir, "JMH-${info.versionName}.apk")

        val candidates = buildList {
            add(info.downloadUrl)
            MIRROR_PREFIXES.forEach { prefix -> add(prefix + info.downloadUrl) }
        }

        var lastError: Exception? = null
        for (url in candidates) {
            try {
                if (target.exists()) target.delete()
                downloadFrom(url, target, onProgress)
                return@withContext Result.success(target)
            } catch (e: Exception) {
                lastError = e
                target.delete()
            }
        }
        Result.failure(lastError ?: Exception("下载失败"))
    }

    /** 调起系统安装器（覆盖安装会保留原有数据与设置） */
    fun installApk(file: File) {
        val uri = FileProvider.getUriForFile(
            appContext, "${appContext.packageName}.fileprovider", file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        appContext.startActivity(intent)
    }

    /** 清理已下载的安装包 */
    fun clearDownloaded() {
        runCatching {
            File(appContext.cacheDir, "update").listFiles()?.forEach { it.delete() }
        }
    }

    // ------------------------------------------------------------------ 内部

    private fun fetch(url: String): String? = try {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Cache-Control", "no-cache")
        }
        val text = connection.inputStream.use { it.bufferedReader().readText() }
        connection.disconnect()
        text.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    private fun parse(json: String): UpdateInfo? = try {
        val obj = JSONObject(json)
        val info = UpdateInfo(
            versionCode = obj.optInt("versionCode", 0),
            versionName = obj.optString("versionName", ""),
            downloadUrl = obj.optString("downloadUrl", ""),
            releaseUrl = obj.optString("releaseUrl", ""),
            releaseNotes = obj.optString("releaseNotes", "")
        )
        if (info.versionCode > 0 && info.versionName.isNotEmpty() && info.downloadUrl.isNotEmpty()) {
            info
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }

    private fun downloadFrom(url: String, target: File, onProgress: (Float) -> Unit) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = DOWNLOAD_TIMEOUT_MS
            instanceFollowRedirects = true
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${connection.responseCode}")
            }
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) {
                            onProgress((downloaded.toDouble() / total).toFloat().coerceIn(0f, 1f))
                        }
                    }
                }
            }
            onProgress(1f)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        /** 仓库地址（同时用于「关于」页面展示） */
        const val REPO_URL = "https://github.com/123456789-Liu1/JMH-Android"

        /** 版本信息文件名 */
        const val VERSION_FILE = "version.json"

        private const val TIMEOUT_MS = 5000
        private const val DOWNLOAD_TIMEOUT_MS = 30000
        private const val AUTO_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
        private const val KEY_LAST_CHECK = "last_check_time"

        private const val REPO_PATH = "123456789-Liu1/JMH-Android"
        private const val RAW_URL = "https://raw.githubusercontent.com/$REPO_PATH/main/version.json"

        /** 版本信息源（按国内可访问性排序） */
        private val SOURCES = listOf(
            "https://cdn.jsdelivr.net/gh/$REPO_PATH@main/version.json",
            RAW_URL,
            "https://ghproxy.net/$RAW_URL"
        )

        /** APK 下载镜像前缀 */
        private val MIRROR_PREFIXES = listOf(
            "https://ghproxy.net/",
            "https://gh-proxy.com/"
        )
    }
}
