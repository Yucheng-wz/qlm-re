package com.hnu.pickupassistant.update

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** APK 下载结果：成功给出落地文件 + 公共【下载】文件夹的 content Uri，失败给出中文原因 */
sealed interface DownloadResult {
    data class Success(val apkFile: File, val contentUri: Uri? = null) : DownloadResult
    data class Failed(val reason: String) : DownloadResult
}

/**
 * ApkDownloader：OkHttp 协程后台下载工具，带实时进度回调与**多源回退**。
 *
 * ★ 分发链路（GitHub Releases + 国内加速）：
 *   版本接口给的是 GitHub Release 直链，国内通常连不上，
 *   这里按 [UpdateConfig.APK_PROXY_PREFIXES] 依次拼成 `代理前缀 + 直链` 下载，
 *   某个源失败（超时 / 非 2xx / 内容过小 / 校验不通过）自动换下一个，全部失败才判定失败。
 *   version.json 里若提供 `apkMirrors`，这些地址会排在最前面。
 *
 * ★ 落地规则（保持不变）：
 *   APK **不再落到 App 私有目录**，直接写入系统公共【下载】文件夹的 MediaStore 记录：
 *    - 下载中 IS_PENDING=1（文件管理器不显示半包），完成置 0 后立刻可见；
 *    - minSdk=29，全版本走 MediaStore，**无需任何存储权限**；
 *    - 同名旧记录先删，不会变成 `xxx(1).apk`；
 *    - 落盘后做 PK 头 + ZIP 中央目录完整性校验，损坏自动删半包并提示重试；
 *    - 下载前清理公共 Download 里本 APP 的历史旧版 APK 与私有目录残留。
 *
 * @author 焱川 (by 焱川)
 */
object ApkDownloader {

    private const val TAG = "QlmUpdate"

    /** 下载专用客户端：总超时放宽到 10 分钟，跟随代理 / GitHub 的 302 跳转 */
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(UpdateConfig.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(UpdateConfig.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(UpdateConfig.DOWNLOAD_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * @param apkUrl 版本接口里的 GitHub Release 直链
     * @param versionName 远端版本号，仅用于生成合法文件名（如 3.0.9 → pickup_assistant_v3.0.9.apk）
     * @param mirrors version.json 里可选的 apkMirrors（优先尝试）
     * @param onProgress 进度回调（在 IO 线程触发）：percent 0~100，-1 表示总大小未知
     */
    suspend fun download(
        context: Context,
        apkUrl: String,
        versionName: String,
        onProgress: (percent: Int, receivedBytes: Long, totalBytes: Long) -> Unit,
        mirrors: List<String> = emptyList()
    ): DownloadResult = withContext(Dispatchers.IO) {
        val app = context.applicationContext

        // ① 存储前置自检：minSdk=29 起走 MediaStore，无需任何存储权限
        if (!ensureStorageAvailable(app)) {
            return@withContext DownloadResult.Failed(UpdateConfig.HINT_STORAGE_UNAVAILABLE)
        }

        val fileName = ApkFileRules.buildFileName(versionName)
        // ② 每次下载前清掉公共 Download 里的历史旧版，避免占用空间 / 误装旧包
        PublicDownloadStore.cleanupOldApks(app, keepFileName = fileName)

        // ③ 组装候选源：apkMirrors → 国内加速前缀 + 直链 → 直链
        val candidates = buildCandidateUrls(apkUrl, mirrors)
        Log.d(TAG, "候选下载源 ${candidates.size} 个：$candidates")

        var lastReason = "下载失败，请检查网络后重试"
        for ((index, url) in candidates.withIndex()) {
            if (candidates.size > 1) {
                Log.d(TAG, "下载尝试 ${index + 1}/${candidates.size}：$url")
            }
            when (val result = attemptDownload(app, url, fileName, onProgress)) {
                is DownloadResult.Success -> return@withContext result
                is DownloadResult.Failed -> {
                    lastReason = result.reason
                    Log.w(TAG, "该源失败：${result.reason}（$url）")
                }
            }
        }
        DownloadResult.Failed(lastReason)
    }

    /**
     * 单个源的完整下载流程：开 pending 记录 → 写流 → 体积校验 → 完整性校验 → 发布。
     * 失败时一定 abort 掉 pending 记录，不留半包。
     */
    private suspend fun attemptDownload(
        app: Context,
        url: String,
        fileName: String,
        onProgress: (percent: Int, receivedBytes: Long, totalBytes: Long) -> Unit
    ): DownloadResult {
        val pending = PublicDownloadStore.openPending(app, fileName)
            ?: return DownloadResult.Failed(UpdateConfig.HINT_PUBLISH_FAILED)

        var call: Call? = null
        try {
            Log.d(TAG, "开始下载 APK：url=$url")
            Log.d(TAG, "落地目录=公共 Download/$fileName（MediaStore，非 App 私有目录）")

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", UpdateConfig.USER_AGENT)
                .header(
                    "Accept",
                    "application/vnd.android.package-archive,application/octet-stream,*/*"
                )
                .get()
                .build()

            call = httpClient.newCall(request)
            val response = call.execute()
            if (!response.isSuccessful) {
                val code = response.code
                response.close()
                PublicDownloadStore.abortPending(app, pending.uri)
                return DownloadResult.Failed("下载失败：HTTP $code")
            }
            val body = response.body
            if (body == null) {
                response.close()
                PublicDownloadStore.abortPending(app, pending.uri)
                return DownloadResult.Failed("下载失败：响应内容为空")
            }
            val total = body.contentLength() // 服务端未给时为 -1
            var received = 0L

            // 边读边写公共 Download 的 pending 流，不产生任何私有临时文件
            response.use { resp ->
                resp.body?.byteStream()?.use { input ->
                    pending.output.use { output ->
                        val buffer = ByteArray(UpdateConfig.DOWNLOAD_BUFFER_BYTES)
                        var lastEmit = 0L
                        while (true) {
                            // 响应协程取消：【稍后再说】/ 页面销毁时立即停止读写
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            received += count

                            val now = System.currentTimeMillis()
                            if (now - lastEmit >= UpdateConfig.PROGRESS_EMIT_INTERVAL_MS) {
                                lastEmit = now
                                val percent = if (total > 0L) {
                                    ((received * 100L) / total).toInt().coerceIn(0, 100)
                                } else -1
                                runCatching { onProgress(percent, received, total) }
                            }
                        }
                        output.flush()
                    }
                }
            }

            // 体积校验：代理错误页 / 403 HTML 通常只有几 KB，禁止进入安装器
            if (received < UpdateConfig.MIN_APK_BYTES) {
                Log.e(TAG, "下载内容过小：$received 字节，判定为错误页")
                PublicDownloadStore.abortPending(app, pending.uri)
                return DownloadResult.Failed(UpdateConfig.HINT_APK_DAMAGED)
            }

            // 完整性校验：PK 头 + ZIP 中央目录，能挡住截断的半包 / HTML 错误页
            val file = PublicDownloadStore.resolveFile(app, pending.uri, fileName)
            if (!PublicDownloadStore.isIntact(app, pending.uri, file)) {
                Log.e(TAG, "完整性校验失败：uri=${pending.uri} path=${file?.absolutePath}")
                PublicDownloadStore.abortPending(app, pending.uri)
                return DownloadResult.Failed(UpdateConfig.HINT_APK_DAMAGED)
            }

            // 校验通过才发布：IS_PENDING=0，文件管理器立刻能在【下载】里看到
            if (!PublicDownloadStore.finalizePending(app, pending.uri)) {
                PublicDownloadStore.abortPending(app, pending.uri)
                return DownloadResult.Failed(UpdateConfig.HINT_PUBLISH_FAILED)
            }

            val finalTotal = if (total > 0L) total else received
            runCatching { onProgress(100, received, finalTotal) }
            Log.d(
                TAG,
                "APK 下载完成：公共路径=Download/$fileName size=$received 字节 uri=${pending.uri}"
            )
            return DownloadResult.Success(
                file ?: File(UpdateConfig.APK_FILE_FALLBACK_NAME),
                pending.uri
            )
        } catch (t: CancellationException) {
            // 用户主动取消 / 宿主销毁：删半包记录，不算失败弹窗
            runCatching { call?.cancel() }
            PublicDownloadStore.abortPending(app, pending.uri)
            Log.d(TAG, "下载已取消，半包记录已清理")
            throw t
        } catch (t: Throwable) {
            Log.e(TAG, "APK 下载异常：${t.message}", t)
            PublicDownloadStore.abortPending(app, pending.uri)
            // 原始异常只进日志，界面统一给中文文案
            return DownloadResult.Failed("下载失败，请检查网络后重试")
        } finally {
            runCatching { call?.cancel() }
        }
    }

    /**
     * 候选下载地址：
     *  1) version.json 里的 apkMirrors（人工指定的备用直链，优先级最高）
     *  2) 每个加速前缀 + GitHub 直链
     *  3) GitHub 直链本身（挂了代理时最快）
     * 去重后保持顺序。
     */
    private fun buildCandidateUrls(apkUrl: String, mirrors: List<String>): List<String> {
        val ordered = LinkedHashSet<String>()
        mirrors.filter { it.startsWith("http://") || it.startsWith("https://") }
            .forEach { ordered.add(it) }
        if (apkUrl.startsWith("https://github.com/") || apkUrl.startsWith("http://github.com/")) {
            UpdateConfig.APK_PROXY_PREFIXES.forEach { prefix -> ordered.add(prefix + apkUrl) }
        }
        ordered.add(apkUrl)
        return ordered.toList()
    }

    /**
     * 存储前置自检 + Android 版本分级：
     *  - API ≥ 29（含 Android 13+）：公共 Download 走 MediaStore，**不申请任何外部存储权限**；
     *  - API ≤ 28：才需要 WRITE_EXTERNAL_STORAGE（本工程 minSdk=29，该分支不会进入）。
     */
    private fun ensureStorageAvailable(app: Context): Boolean {
        if (PublicDownloadStore.needsLegacyStoragePermission()) {
            val granted = ContextCompat.checkSelfPermission(
                app, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Log.e(TAG, "API≤28 且无 WRITE_EXTERNAL_STORAGE 权限，提前拦截下载")
                return false
            }
        }
        return true
    }
}
