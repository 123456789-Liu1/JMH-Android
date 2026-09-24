package com.jmh.app.ui

import java.util.Locale

/**
 * 文件类型识别与展示工具。
 */
object FileTypes {

    enum class Kind { IMAGE, VIDEO, AUDIO, TEXT, PDF, OTHER }

    private val IMAGE = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "svg")
    private val VIDEO = setOf("mp4", "mkv", "mov", "avi", "3gp", "webm", "flv", "ts", "m4v", "wmv", "rmvb")
    private val AUDIO = setOf("mp3", "wav", "aac", "flac", "m4a", "ogg", "opus", "amr", "wma")
    private val TEXT = setOf("txt", "md", "log", "json", "xml", "csv", "kt", "java", "py", "js", "html", "ini", "yaml", "yml")
    private val PDF = setOf("pdf")

    fun kindOf(extension: String, mime: String = ""): Kind {
        val ext = extension.lowercase(Locale.ROOT)
        return when {
            ext in IMAGE || mime.startsWith("image/") -> Kind.IMAGE
            ext in VIDEO || mime.startsWith("video/") -> Kind.VIDEO
            ext in AUDIO || mime.startsWith("audio/") -> Kind.AUDIO
            ext in TEXT || mime.startsWith("text/") -> Kind.TEXT
            ext in PDF || mime == "application/pdf" -> Kind.PDF
            else -> Kind.OTHER
        }
    }

    /** 是否可以在应用内直接预览 */
    fun canPreviewInside(kind: Kind): Boolean = kind != Kind.OTHER

    /** 人类可读的文件大小 */
    fun formatSize(bytes: Long): String {
        if (bytes < 0) return "未知"
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.ROOT, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.ROOT, "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.ROOT, "%.2f GB", gb)
    }

    /** 类型标签（用于图标占位） */
    fun labelOf(extension: String): String {
        val ext = extension.uppercase(Locale.ROOT)
        return if (ext.isEmpty()) "FILE" else ext.take(4)
    }
}
