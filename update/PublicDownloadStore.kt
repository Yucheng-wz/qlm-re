package com.hnu.pickupassistant.update

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipInputStream

/**
 * 「取了吗」在线更新 —— 系统公共【下载】文件夹存储（Scoped Storage 适配）。
 *
 * ## 为什么要改
 * 2.9.2 及之前把 APK 落在 `getExternalFilesDir(Downloads)` / `filesDir`，
 * 那是 **App 私有目录**（Android/data/包名/...），分区存储下外部程序（系统安装器、
 * 手机文件管理器）读不到 / 看不到，于是出现「无法读取安装包」「文件管理器找不到 APK」。
 *
 * ## 现在怎么做
 *  - minSdk = 29（Android 10）起全版本走 [MediaStore.Downloads]：
 *    下载中 `IS_PENDING=1`（文件管理器暂不显示半包），完成置 0 后立刻可见于
 *    系统【下载】文件夹，**全程不需要 READ/WRITE_EXTERNAL_STORAGE 权限**；
 *  - Android 13+ 自然也不申请任何外部存储读写权限，Manifest 只保留
 *    `REQUEST_INSTALL_PACKAGES`（[needsLegacyStoragePermission] 显式表达版本分级）；
 *  - 安装器直接用 MediaStore 的 content:// Uri，FileProvider 只作兜底；
 *  - 每次启动版本检测时 [cleanupOldApks] 清理本 APP 的历史旧版 APK，
 *    并把旧版本残留在私有目录里的包一并删掉。
 *
 * @author 焱川 (by 焱川)
 */
object PublicDownloadStore {

    private const val TAG = "QlmUpdate"

    /** 公共下载目录相对路径：文件管理器里显示的「下载」/「Download」文件夹 */
    val PUBLIC_RELATIVE_PATH: String = Environment.DIRECTORY_DOWNLOADS

    /** 一条可写的 pending 记录：下载过程直接写这个流，不产生任何私有临时文件 */
    data class PendingTarget(
        val uri: Uri,
        val fileName: String,
        val output: OutputStream
    )

    /** 发布完成后的安装包：真实 File（可能为 null）+ 可直接授权给安装器的 content Uri */
    data class PublicApk(val file: File?, val uri: Uri, val fileName: String)

    /**
     * 是否需要「传统外部存储读写权限」：只有 Android 9（API 28）及以下才需要。
     * 本工程 minSdk=29，故恒为 false —— Android 13+ 更是完全不申请，
     * Manifest 里也不声明 READ/WRITE_EXTERNAL_STORAGE。
     * 抽成函数是为了把「按 Android 版本分级」这件事显式落到代码里、且可单测。
     */
    fun needsLegacyStoragePermission(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        ApkFileRules.needsLegacyStoragePermission(sdkInt)

    /** 是否属于本 APP 下载的安装包（清理时只碰自己的文件，绝不误删用户其它文件） */
    fun isOurApk(name: String): Boolean = ApkFileRules.isOurApk(name)

    /**
     * 创建一条 MediaStore pending 记录并返回可写流。
     * 同名旧记录先删掉，避免系统自动改名成 `xxx(1).apk` 让安装器 / 用户找不到。
     */
    fun openPending(context: Context, fileName: String): PendingTarget? {
        if (fileName.isBlank()) {
            Log.e(TAG, "创建下载记录失败：文件名为空")
            return null
        }
        val resolver = context.applicationContext.contentResolver
        deleteByName(context, fileName, logTag = "覆盖同名旧包")
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, UpdateConfig.APK_MIME_TYPE)
            put(MediaStore.Downloads.RELATIVE_PATH, PUBLIC_RELATIVE_PATH)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        return try {
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri == null) {
                Log.e(TAG, "MediaStore 插入失败：fileName=$fileName")
                return null
            }
            val stream = resolver.openOutputStream(uri, "w")
            if (stream == null) {
                runCatching { resolver.delete(uri, null, null) }
                Log.e(TAG, "MediaStore 输出流为空：uri=$uri")
                return null
            }
            Log.d(TAG, "公共下载记录已创建：uri=$uri name=$fileName path=$PUBLIC_RELATIVE_PATH/")
            PendingTarget(uri, fileName, stream)
        } catch (t: Throwable) {
            Log.e(TAG, "创建公共下载记录异常：${t.message}", t)
            null
        }
    }

    /** 下载完成：IS_PENDING 置 0，文件管理器立刻可见 */
    fun finalizePending(context: Context, uri: Uri): Boolean = try {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.IS_PENDING, 0)
        }
        val rows = context.applicationContext.contentResolver.update(uri, values, null, null)
        Log.d(TAG, "安装包已发布到公共【下载】文件夹：uri=$uri rows=$rows")
        rows >= 0
    } catch (t: Throwable) {
        Log.e(TAG, "发布安装包失败：${t.message}", t)
        false
    }

    /** 下载 / 校验失败：删除 pending 记录，不留半包 */
    fun abortPending(context: Context, uri: Uri?) {
        if (uri == null) return
        runCatching {
            context.applicationContext.contentResolver.delete(uri, null, null)
            Log.d(TAG, "已清理半包记录：uri=$uri")
        }
    }

    /**
     * 解析 MediaStore 记录对应的真实文件路径（供 ZipFile 完整性与 FileProvider 兜底使用）。
     * DATA 列在 Android 10+ 仍可读；取不到时退回公共 Download 目录拼接。
     */
    @Suppress("DEPRECATION")
    fun resolveFile(context: Context, uri: Uri, fileName: String): File? {
        try {
            context.applicationContext.contentResolver.query(
                uri,
                arrayOf(MediaStore.Downloads.DATA),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val path = cursor.getString(0)
                    if (!path.isNullOrBlank()) return File(path)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "读取 DATA 列失败，改用公共目录拼接：${t.message}")
        }
        return try {
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                fileName
            )
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 完整性校验（需求 2）：优先用真实 File 交给 [ApkFileRules.isIntactApk] 读 ZIP 中央目录，
     * 能挡住「PK 头正常但被截断」的半包；个别 ROM 拿不到路径时退回流式校验。
     */
    fun isIntact(context: Context, uri: Uri, file: File?): Boolean {
        // ① 真实路径可读时，用 ZipFile 读中央目录（最严格，能挡截断半包）
        if (file != null && file.exists() && ApkFileRules.isIntactApk(file)) return true
        // ② 个别 ROM 下 DATA 路径不可直接读 → 用 MediaStore Uri 流式复核，
        //    避免把「路径读不到」误判成「安装包损坏」
        val resolver = context.applicationContext.contentResolver
        val headOk = try {
            resolver.openInputStream(uri)?.use { input ->
                val head = ByteArray(2)
                input.read(head) == 2 &&
                    head[0] == 'P'.code.toByte() &&
                    head[1] == 'K'.code.toByte()
            } ?: false
        } catch (t: Throwable) {
            Log.w(TAG, "流式完整性校验失败：${t.message}")
            false
        }
        if (!headOk) return false
        // ③ 再开一次流读 ZIP 条目，确认不是只有 PK 头的假包
        return try {
            resolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use { zip -> zip.nextEntry != null }
            } ?: false
        } catch (t: Throwable) {
            Log.w(TAG, "流式 ZIP 条目校验失败：${t.message}")
            false
        }
    }

    /**
     * 每次启动版本检测时清理本 APP 的历史旧版 APK（需求 4）。
     *
     * 保留策略：
     *  - 传入 [keepFileName] 时保留该文件名（下载前调用，避免删掉本次待写包）；
     *  - 未传时保留**最近修改的 1 个**（用户刚下载、还没来得及安装的那个），
     *    其余历史版本全部删除；
     *  - 只删 [isOurApk] 命中的文件，绝不碰用户其它下载内容。
     *
     * @return 删除的文件数
     */
    fun cleanupOldApks(context: Context, keepFileName: String? = null): Int {
        var removed = 0
        val resolver = context.applicationContext.contentResolver
        val rows = mutableListOf<Triple<Uri, String, Long>>()
        try {
            val projection = arrayOf(
                MediaStore.Downloads._ID,
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.DATE_MODIFIED
            )
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?",
                arrayOf("${UpdateConfig.APK_FILE_PREFIX}%"),
                "${MediaStore.Downloads.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(MediaStore.Downloads._ID)
                val nameIdx = cursor.getColumnIndex(MediaStore.Downloads.DISPLAY_NAME)
                val dateIdx = cursor.getColumnIndex(MediaStore.Downloads.DATE_MODIFIED)
                while (cursor.moveToNext()) {
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty() else ""
                    if (!isOurApk(name)) continue
                    val id = if (idIdx >= 0) cursor.getLong(idIdx) else -1L
                    if (id < 0L) continue
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, id
                    )
                    val date = if (dateIdx >= 0) cursor.getLong(dateIdx) else 0L
                    rows.add(Triple(uri, name, date))
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "查询历史安装包失败（跳过清理）：${t.message}")
        }

        val keep = if (!keepFileName.isNullOrBlank()) {
            rows.firstOrNull { it.second == keepFileName }
        } else {
            rows.maxByOrNull { it.third }
        }
        for (row in rows) {
            if (keep != null && row.first == keep.first) continue
            try {
                if (resolver.delete(row.first, null, null) > 0) {
                    removed++
                    Log.d(TAG, "清理历史旧版安装包：${row.second}")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "删除历史安装包失败：${row.second} ${t.message}")
            }
        }

        removed += cleanupPrivateLeftovers(context)
        if (removed > 0) Log.d(TAG, "本次共清理 $removed 个历史安装包")
        return removed
    }

    /** 清理 2.9.2 及之前遗留在 App 私有目录 / 兜底 cache 里的安装包与半包 */
    private fun cleanupPrivateLeftovers(context: Context): Int {
        var removed = 0
        val app = context.applicationContext
        val dirs = listOfNotNull(
            runCatching { app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) }.getOrNull(),
            app.filesDir,
            File(app.cacheDir, UpdateConfig.APK_CACHE_SUBDIR)
        )
        for (dir in dirs) {
            val files = try {
                dir.listFiles { f ->
                    f.isFile && (
                        isOurApk(f.name) ||
                            f.name.endsWith(UpdateConfig.APK_TEMP_SUFFIX, ignoreCase = true) ||
                            f.name == UpdateConfig.APK_LEGACY_FILE_NAME
                        )
                }
            } catch (t: Throwable) {
                null
            } ?: continue
            for (f in files) {
                if (runCatching { f.delete() }.getOrDefault(false)) {
                    removed++
                    Log.d(TAG, "清理私有目录残留：${f.name}")
                }
            }
        }
        return removed
    }

    /** 删除公共【下载】里同名记录，避免重名副本 */
    private fun deleteByName(context: Context, fileName: String, logTag: String) {
        val resolver = context.applicationContext.contentResolver
        try {
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME),
                "${MediaStore.Downloads.DISPLAY_NAME} = ?",
                arrayOf(fileName),
                null
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(MediaStore.Downloads._ID)
                while (cursor.moveToNext()) {
                    val id = if (idIdx >= 0) cursor.getLong(idIdx) else -1L
                    if (id < 0L) continue
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, id
                    )
                    if (resolver.delete(uri, null, null) > 0) {
                        Log.d(TAG, "$logTag：$fileName")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "$logTag 失败：${t.message}")
        }
    }
}
