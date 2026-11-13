package com.hnu.pickupassistant.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.hnu.pickupassistant.util.LiftedToast
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * UpdateManager：在线更新模块的统一门面（首页 / 设置页只需调用这里的两个方法）。
 *
 * 示例调用代码：
 *  - 首页 MainActivity.onCreate：
 *      `UpdateManager.checkOnLaunch(applicationContext)`   // 自动检测，6 小时限流
 *  - 设置页【检查更新】点击：
 *      `UpdateManager.checkManual(context)`                // 手动检测，不受限流
 *  - 首页 Composable：`UpdateHost()` 收集 [updateInfo] 并渲染弹窗 / 进度 / 安装
 *
 * ## 本次修复：唤起安装器报 `Name must not be empty`
 * 该异常来自 `androidx.core FileProvider$SimplePathStrategy.addRoot`，
 * 根因是 FileProvider 拿到的文件 / 文件名参数不合法（空文件名、路径不在白名单内）。
 * 现在的处理链路：
 *  1. [ApkDownloader] 落盘用 `ApkFileRules.buildFileName()` 生成合法文件名并三段校验；
 *  2. [installApk] 先跑完整前置校验清单（null / 空名 / 不存在 / 空文件 / 非 APK 头）；
 *  3. Android 8+ 先判断「安装未知应用」权限，没有就跳系统设置，不等安装报错；
 *  4. Uri 生成走「主 FileProvider → 兜底 FileProvider（cacheDir 副本）」双通道；
 *  5. 全程 try-catch，异常只进日志，用户只会看到中文提示，绝不出现英文堆栈。
 *
 * ## 本次迭代：修复「无法读取安装包、文件管理器看不到 APK」
 * APK 改存系统公共【下载】文件夹（[PublicDownloadStore] / MediaStore.Downloads），
 * 安装优先用 MediaStore content Uri，FileProvider 只作兜底；启动检测时清理历史旧包；
 * 失败提示明确告知位置「请到文件管理器【下载】文件夹手动安装」。
 * 版本检测 / 6 小时限流 / Gitee JSON 解析 / 下载进度逻辑全部未改。
 *
 * @author 焱川 (by 焱川)
 */
object UpdateManager {

    private const val TAG = "QlmUpdate"

    /** 统一协程作用域： SupervisorJob 保证一次失败不影响其它任务 */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, t ->
                Log.e(TAG, "更新模块异常（已兜住，不影响主流程）", t)
            }
    )

    /** 发现的新版本；null = 无更新弹窗。首页 UpdateHost 用 collectAsState 收集 */
    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    val updateInfo: StateFlow<UpdateInfo?> = _updateInfo.asStateFlow()

    /**
     * 「开启未知来源权限后返回 App」的接力信号：UpdateHost 收到后清掉弹窗里的
     * 「请先允许安装未知应用」错误提示，无需用户重新点【立即更新】/ 重新下载。
     */
    private val _installResumed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val installResumed: SharedFlow<Unit> = _installResumed.asSharedFlow()

    /** 已下载完成、只差「安装未知应用」权限的安装包（仅内存态，App 被杀后失效） */
    private data class PendingInstall(val file: File, val contentUri: Uri?)

    @Volatile
    private var pendingInstall: PendingInstall? = null

    /** 关闭更新弹窗（仅非强制更新时由【稍后再说】调用） */
    fun dismiss() {
        _updateInfo.value = null
    }

    /**
     * 首页 onCreate 自动检测：
     * 受 6 小时限流；UpToDate / 超时 / 网络错误 / 限流全部静默，不打扰用户。
     */
    fun checkOnLaunch(context: Context) {
        try {
            scope.launch {
                // 需求 4：每次启动版本更新检查，先清掉公共【下载】里本 APP 的历史旧版 APK
                // （保留最近一个待安装包），以及旧版本残留在私有目录里的包。
                // 即使 6 小时限流把本次检测跳过，清理也照常执行。
                runCatching { PublicDownloadStore.cleanupOldApks(context.applicationContext) }
                    .onFailure { Log.w(TAG, "启动清理历史安装包失败（已忽略）", it) }
                val result = UpdateChecker.check(context, manual = false)
                if (result is CheckResult.Available) {
                    _updateInfo.value = result.info
                }
                // 其余结果一律静默：不弹「已是最新」、不弹网络错误
            }
        } catch (t: Throwable) {
            Log.e(TAG, "启动检测调用异常（已静默）", t)
        }
    }

    /**
     * 设置页【检查更新】手动检测：
     * 完全绕过 6 小时限流，立即请求；仅「确实已是最新」给一条轻提示，
     * 请求超时 / 网络错误依旧静默放弃，不弹任何报错。
     */
    fun checkManual(context: Context) {
        try {
            scope.launch {
                when (val result = UpdateChecker.check(context, manual = true)) {
                    is CheckResult.Available -> _updateInfo.value = result.info
                    CheckResult.UpToDate -> LiftedToast.show("已是最新版本")
                    CheckResult.Skipped, CheckResult.Failed -> Unit // 静默
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "手动检测调用异常（已静默）", t)
        }
    }

    /**
     * 委托给 [ApkDownloader]：多源回退（apkMirrors → 国内加速前缀 + GitHub 直链 → 直链）+ 进度回调。
     * 传入远端 versionName，用来生成 `pickup_assistant_v3.0.9.apk` 这种合法文件名。
     */
    suspend fun downloadApk(
        context: Context,
        info: UpdateInfo,
        onProgress: (percent: Int, receivedBytes: Long, totalBytes: Long) -> Unit
    ): DownloadResult = ApkDownloader.download(
        context = context,
        apkUrl = info.apkUrl,
        versionName = info.versionName,
        onProgress = onProgress,
        mirrors = info.apkMirrors
    )

    /**
     * 下载完成自动调用：前置校验 → 权限引导 → FileProvider 授权 → 唤起系统安装器。
     *
     * @param apk 下载落地的 APK 文件（允许为 null，内部会做空判断并返回中文提示）
     * @return null = 已成功唤起安装器；非 null = 需要提示用户的中文文案（绝不返回英文异常）
     */
    fun installApk(context: Context, apk: File?, contentUri: Uri? = null): String? {
        // ---------- ① 空对象 / 完整性校验 ----------
        val file = apk ?: run {
            Log.e(TAG, "唤起安装器被拦截：apkFile == null")
            return "安装包信息缺失，请重新下载"
        }
        Log.d(
            TAG,
            "安装前置校验：path=${file.absolutePath} name='${file.name}' uri=$contentUri " +
                "sdk=${Build.VERSION.SDK_INT} brand=${Build.BRAND}"
        )
        if (contentUri == null) {
            // 旧兜底路径（私有目录文件）：仍走原来的完整前置清单
            ApkFileRules.validate(file)?.let { reason ->
                Log.e(TAG, "安装前置校验未通过：$reason（name='${file.name}'）")
                return reason
            }
            if (!ApkFileRules.isIntactApk(file)) {
                Log.e(TAG, "安装前置校验未通过：APK 完整性校验失败：${file.absolutePath}")
                return "安装包已损坏，请重新下载"
            }
        } else {
            // 公共【下载】目录：以 MediaStore Uri 为准再复核一次完整性
            if (!PublicDownloadStore.isIntact(
                    context, contentUri, file.takeIf { it.exists() && it.isFile }
                )
            ) {
                Log.e(TAG, "公共安装包完整性复核失败：uri=$contentUri")
                return UpdateConfig.HINT_APK_DAMAGED
            }
        }

        // ---------- ② 安装未知应用权限（Android 8+，澎湃 HyperOS / MIUI 尤其需要） ----------
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            // 记住这个已下载好的包：用户去设置页开权限、返回 App 时由 ON_RESUME 自动接力，
            // 不需要重新下载，也不需要再点一次【立即更新】
            pendingInstall = PendingInstall(file, contentUri)
            Log.w(TAG, "当前没有「安装未知应用」权限，已记录待安装包并跳系统设置页")
            return openUnknownSourceSettings(context)
        }

        // ---------- ③ 优先用公共 Download 的 MediaStore Uri 唤起安装器 ----------
        var lastError: String? = null
        if (contentUri != null) {
            lastError = tryLaunchInstaller(context, contentUri)
            if (lastError == null) return null
            Log.w(TAG, "MediaStore Uri 唤起失败，改用 FileProvider 兜底：$lastError")
        }

        // ---------- ④ FileProvider 双通道兜底（不改原有逻辑） ----------
        val fallback = buildInstallUri(context, file)
        if (fallback != null) {
            val error = tryLaunchInstaller(context, fallback)
            if (error == null) return null
            lastError = error
        } else {
            Log.e(TAG, "FileProvider 双通道均未拿到 Uri")
        }
        return lastError ?: UpdateConfig.HINT_READ_FAILED
    }

    /**
     * 用户从「允许安装未知应用」系统设置页返回 App（ON_RESUME）时调用：
     * 若权限已开启且存在待安装包，**直接接力唤起系统安装器**，不重新下载。
     *
     * @return true = 已成功接力（调用方可清掉弹窗错误）；false = 无需接力 / 权限仍未开启
     */
    fun resumePendingInstall(context: Context): Boolean {
        val pending = pendingInstall ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            Log.d(TAG, "ON_RESUME：未知来源权限仍未开启，继续等待")
            return false
        }
        val message = installApk(context, pending.file, pending.contentUri)
        return if (message == null) {
            pendingInstall = null
            _installResumed.tryEmit(Unit)
            LiftedToast.show("权限已开启，正在打开安装器")
            Log.i(TAG, "ON_RESUME 接力唤起安装器成功，无需重新下载")
            true
        } else {
            Log.w(TAG, "ON_RESUME 接力失败：$message（保留待安装记录）")
            false
        }
    }

    /**
     * 用单个 content:// Uri 唤起安装器：对能处理安装 Intent 的 Activity 逐个显式授权，
     * 兼容澎湃 OS / MIUI 等严格校验 Uri 来源的 ROM。
     * @return null = 已成功唤起；非 null = 中文失败原因（原始英文异常只进日志）
     */
    private fun tryLaunchInstaller(context: Context, uri: Uri): String? = try {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, UpdateConfig.APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val targets = context.packageManager.queryIntentActivities(
            intent, PackageManager.MATCH_DEFAULT_ONLY
        )
        if (targets.isEmpty()) {
            Log.e(TAG, "没有能处理 APK 安装的 Activity（安装器不可见）：uri=$uri")
            UpdateConfig.HINT_NO_INSTALLER
        } else {
            targets.forEach { resolveInfo ->
                runCatching {
                    context.grantUriPermission(
                        resolveInfo.activityInfo.packageName,
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
            context.startActivity(intent)
            Log.d(TAG, "已唤起系统安装器：uri=$uri targets=${targets.size}")
            null
        }
    } catch (t: Throwable) {
        Log.e(TAG, "唤起安装器失败：uri=$uri", t)
        "无法打开安装界面，请手动前往设置开启【允许安装未知应用】权限，或者到文件管理器【下载】文件夹手动安装"
    }

    /**
     * 生成安装用 Uri：主 FileProvider 失败时，把包复制到 cacheDir/apk/ 再用
     * `apkprovider`（独立、干净配置）重试。任意一步失败返回 null。
     */
    private fun buildInstallUri(context: Context, apk: File): Uri? {
        val primaryAuthority = context.packageName + UpdateConfig.FILE_PROVIDER_AUTHORITY_SUFFIX
        try {
            val uri = FileProvider.getUriForFile(context, primaryAuthority, apk)
            Log.d(TAG, "FileProvider 主通道成功：authority=$primaryAuthority uri=$uri")
            return uri
        } catch (t: Throwable) {
            Log.e(TAG, "FileProvider 主通道失败（authority=$primaryAuthority）：${t.message}", t)
        }

        return try {
            val cacheDir = File(context.cacheDir, UpdateConfig.APK_CACHE_SUBDIR)
            if (!cacheDir.exists() && !cacheDir.mkdirs()) {
                Log.e(TAG, "兜底目录创建失败：${cacheDir.absolutePath}")
                return null
            }
            val copyName = apk.name.ifBlank { UpdateConfig.APK_FILE_FALLBACK_NAME }
            val copy = File(cacheDir, copyName)
            if (!copy.exists() || copy.length() != apk.length()) {
                apk.copyTo(copy, overwrite = true)
            }
            if (copy.length() <= 0L) {
                Log.e(TAG, "兜底副本为空：${copy.absolutePath}")
                return null
            }
            val fallbackAuthority =
                context.packageName + UpdateConfig.APK_PROVIDER_AUTHORITY_SUFFIX
            val uri = FileProvider.getUriForFile(context, fallbackAuthority, copy)
            Log.d(
                TAG,
                "FileProvider 兜底通道成功：authority=$fallbackAuthority " +
                    "path=${copy.absolutePath} uri=$uri"
            )
            uri
        } catch (t: Throwable) {
            Log.e(TAG, "FileProvider 兜底通道失败：${t.message}", t)
            null
        }
    }

    /**
     * 读取 / 唤起失败时的一键兜底：跳文件管理器定位到 APK，让用户手动安装（需求 4）。
     * @return true = 已成功跳转；false = 连文件管理器都打不开
     */
    fun openApkInFileManager(context: Context, apk: File?, contentUri: Uri? = null): Boolean {
        // 公共 Download 的 MediaStore Uri 优先；拿不到时再用 FileProvider 兜底
        val uri = contentUri ?: apk?.let { buildInstallUri(context, it) }
        if (uri == null) {
            Log.e(TAG, "跳文件管理器被拦截：没有可用的 content:// Uri")
            return false
        }
        Log.d(TAG, "尝试打开文件管理器定位：uri=$uri path=${apk?.absolutePath}")

        // ① 优先用 APK 类型打开 —— 多数文件管理器会直接展示该文件 / 进入安装流程
        val viewApk = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, UpdateConfig.APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // ② 兜底：不带 MIME，让文件管理器自行识别
        val viewGeneric = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/octet-stream")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // ③ 直达系统【下载】列表：用户能直接看到 pickup_assistant_v*.apk
        val downloads = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // ④ 最后兜底：打开系统文件管理器（部分 ROM 用这个 action）
        val filesApp = Intent(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_APP_FILES)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        for (intent in listOf(viewApk, viewGeneric, downloads, filesApp)) {
            try {
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    Log.d(TAG, "文件管理器已打开：action=${intent.action}")
                    return true
                }
            } catch (t: Throwable) {
                Log.w(TAG, "打开文件管理器失败（尝试下一个）：${t.message}")
            }
        }
        return false
    }

    /** 跳「安装未知应用」设置页，返回中文引导文案 */
    private fun openUnknownSourceSettings(context: Context): String {
        return try {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + context.packageName)
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            UpdateConfig.HINT_UNKNOWN_SOURCE
        } catch (t: Throwable) {
            Log.e(TAG, "跳转未知来源设置页失败：${t.message}", t)
            "请前往系统设置 → 应用管理 → 取了吗 → 允许安装来自此应用的未知应用，然后重新更新"
        }
    }

    /** 字节数格式化成 MB 文本，供进度条展示「x.x MB / y.y MB」 */
    fun formatBytes(bytes: Long): String {
        return if (bytes <= 0L) "0 MB"
        else String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    }
}
