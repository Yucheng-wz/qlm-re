package com.hnu.pickupassistant.update

import java.io.File
import java.io.RandomAccessFile

/**
 * ApkFileRules：APK 文件「命名 / 校验」的纯 Java 规则集合（不依赖 Android，可 JVM 单测）。
 *
 * 为什么单独抽出来：
 *  - 线上反馈的 `Name must not be empty` 本质是「拿到的文件 / 文件名不合法」，
 *    这一类判断必须能单测，而不是等真机崩了才知道；
 *  - 文件名统一由 versionName 生成，禁止出现空名、非法字符、路径分隔符。
 *
 * @author 焱川 (by 焱川)
 */
object ApkFileRules {

    /** 文件名里允许出现的字符，其余一律替换成下划线 */
    private val ILLEGAL_CHARS = Regex("""[^A-Za-z0-9._-]""")

    /** 只保留安全字符的版本号片段；为空时返回空串 */
    fun sanitizeVersion(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return ""
        return text.replace(ILLEGAL_CHARS, "_").trim('_', '.', '-')
    }

    /**
     * 由版本名生成下载文件名，例如 `2.8.8` → `pickup_assistant_v2.8.8.apk`。
     * 版本号为空 / 全是非法字符时退回固定兜底名，**绝不返回空串**。
     */
    fun buildFileName(versionName: String?): String {
        val safe = sanitizeVersion(versionName)
        return if (safe.isBlank()) {
            UpdateConfig.APK_FILE_FALLBACK_NAME
        } else {
            UpdateConfig.APK_FILE_PREFIX + "v" + safe + ".apk"
        }
    }

    /**
     * 是否需要「传统外部存储读写权限」：只有 Android 9（API 28）及以下才需要。
     * 工程 minSdk=29，APK 走 MediaStore 公共【下载】目录，全版本（含 Android 13+）
     * 都不申请 READ/WRITE_EXTERNAL_STORAGE。抽成纯函数便于 JVM 单测锁死版本分级。
     */
    fun needsLegacyStoragePermission(sdkInt: Int): Boolean = sdkInt < 29

    /**
     * 是否是本 APP 下载的安装包文件名。
     * 清理公共 Download 时只删自己的文件，绝不误伤用户其它下载内容。
     */
    fun isOurApk(name: String): Boolean =
        name.startsWith(UpdateConfig.APK_FILE_PREFIX) &&
            name.endsWith(".apk", ignoreCase = true)

    /**
     * 安装前置校验清单（需求 1 / 3）：任意一项不满足都返回中文原因，全部通过返回 null。
     * 顺序按「最容易出错、最能说明问题」排：null → 空名 → 不存在 → 非文件 → 空文件。
     */
    fun validate(apkFile: File?): String? {
        if (apkFile == null) return "安装包信息缺失，请重新下载"
        if (apkFile.name.isBlank()) return "安装包文件名异常，请重新下载"
        if (!apkFile.exists()) return "安装包文件不存在，请重新下载"
        if (!apkFile.isFile) return "安装包不是有效文件，请重新下载"
        if (apkFile.length() <= 0L) return "安装包为空（0 字节），请重新下载"
        if (!apkFile.canRead()) return "安装包无法读取，请重新下载"
        return null
    }

    /**
     * 轻量校验是不是一个 ZIP（APK 本质是 zip）：只读文件头 2 字节是否为 `PK`。
     * 用来挡住「下载到的是 HTML 错误页 / 半包」这种损坏情况。
     */
    fun looksLikeApk(apkFile: File): Boolean {
        return try {
            RandomAccessFile(apkFile, "r").use { raf ->
                if (raf.length() < 4L) return false
                val b0 = raf.read()
                val b1 = raf.read()
                b0 == 'P'.code && b1 == 'K'.code
            }
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * 完整性校验：用 ZipFile 读中央目录，能打开且条目数 > 0 才算完整。
     * 比 [looksLikeApk] 更严 —— 能挡住「文件头是 PK 但中间被截断」的半包。
     * 读失败（IOException / 不是 zip）一律视为损坏。
     */
    fun isIntactApk(apkFile: File): Boolean {
        if (!looksLikeApk(apkFile)) return false
        return try {
            java.util.zip.ZipFile(apkFile).use { zip ->
                zip.entries().hasMoreElements()
            }
        } catch (t: Throwable) {
            false
        }
    }
}
