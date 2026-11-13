package com.hnu.pickupassistant.update

import android.content.Context
import android.util.Log
import com.hnu.pickupassistant.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 远端 version.json 的完整字段。
 * forceUpdate 必须解析：true 时更新弹窗隐藏【稍后再说】且不可关闭。
 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val updateLog: String,
    val forceUpdate: Boolean,
    /** 可选的额外下载地址（version.json 里写 apkMirrors 数组时生效，优先于自动拼接的加速前缀） */
    val apkMirrors: List<String> = emptyList()
)

/**
 * 版本检测结果。
 * 刻意区分「确实已是最新」与「请求失败 / 被限流」：
 * 失败一律静默放弃，不弹任何报错，只有手动检查且明确 UpToDate 时才给一句轻提示。
 */
sealed interface CheckResult {
    data class Available(val info: UpdateInfo) : CheckResult

    /** 请求成功，但远端 versionCode <= 本地 */
    object UpToDate : CheckResult

    /** 被 6 小时限流跳过 / 已有检测在跑，未发起请求 */
    object Skipped : CheckResult

    /** 超时、网络错误、HTTP 非 2xx、JSON 非法等：静默放弃 */
    object Failed : CheckResult
}

/**
 * UpdateChecker：Kotlin 协程 + OkHttp 的版本检测工具类。
 *
 * 严格实现 4 条硬性规则：
 *  ① 自动检测限流：SharedPreferences 保存上次检测时间，最少间隔 6 小时；
 *     manual = true（设置页【检查更新】）完全绕过限流，立即请求。
 *  ② 网络容错：失败最多重试 1 次（共 2 次请求）；超时 / 网络错误 / 解析异常
 *     最终静默放弃，只打 Log，绝不弹窗报错、绝不抛异常到宿主。
 *  ③ 只按 versionCode 判新旧：远端 > BuildConfig.VERSION_CODE 才认为有新版本。
 *  ④ 全异常兜底：catch Throwable，但 CancellationException 原样抛出以遵守结构化并发。
 *
 * 不引入任何第三方更新 SDK，仅用 OkHttp + org.json + 协程。
 *
 * @author 焱川 (by 焱川)
 */
object UpdateChecker {

    private const val TAG = "QlmUpdate"

    /** 防止 onCreate 自动检测与手动点击并发发两次请求 */
    private val checking = AtomicBoolean(false)

    /** 版本检测专用客户端：短超时，避免拖慢首屏 */
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(UpdateConfig.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(UpdateConfig.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(UpdateConfig.CHECK_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(UpdateConfig.PREF_NAME, Context.MODE_PRIVATE)

    /** 上次发起检测的时间戳（毫秒），读取失败按 0 处理（即允许检测） */
    fun lastCheckTime(context: Context): Long = try {
        prefs(context).getLong(UpdateConfig.PREF_KEY_LAST_CHECK_TIME, 0L)
    } catch (t: Throwable) {
        Log.e(TAG, "读取上次检测时间失败", t)
        0L
    }

    /**
     * 在「发起请求之前」写入时间戳：限制的是发起请求的频率，
     * 因此请求失败也不会在短时间内反复重试，最大程度避免 Gitee 风控。
     */
    private fun markChecked(context: Context) {
        try {
            prefs(context).edit()
                .putLong(UpdateConfig.PREF_KEY_LAST_CHECK_TIME, System.currentTimeMillis())
                .apply()
        } catch (t: Throwable) {
            Log.e(TAG, "保存上次检测时间失败", t)
        }
    }

    /**
     * 统一检测入口。
     *
     * @param manual false = 首页 onCreate 自动检测（受 6 小时限流）；
     *               true  = 设置页【检查更新】手动检测（不受限流，立刻请求）
     */
    suspend fun check(context: Context, manual: Boolean): CheckResult =
        withContext(Dispatchers.IO) {
            // 同一时刻只允许一个检测在跑
            if (!checking.compareAndSet(false, true)) return@withContext CheckResult.Skipped
            try {
                val app = context.applicationContext
                val now = System.currentTimeMillis()

                // ① 自动检测限流：距上次检测不足 6 小时，直接静默跳过
                if (!manual && now - lastCheckTime(app) < UpdateConfig.AUTO_CHECK_INTERVAL_MS) {
                    Log.d(TAG, "自动检测：距上次不足 6 小时，跳过")
                    return@withContext CheckResult.Skipped
                }

                // 手动检查也记录时间：一次真实请求之后 6 小时内不会再自动打扰服务端
                markChecked(app)

                // ② 最多重试 1 次
                var lastError: Throwable? = null
                for (attempt in 0..UpdateConfig.MAX_RETRY_TIMES) {
                    try {
                        val result = requestOnce()
                        if (result != null) return@withContext result
                    } catch (t: CancellationException) {
                        throw t // 协程取消必须向外传播，不能被「全异常兜底」吞掉
                    } catch (t: Throwable) {
                        lastError = t
                        Log.e(TAG, "版本检测第 ${attempt + 1} 次请求异常：${t.message}")
                    }
                    if (attempt < UpdateConfig.MAX_RETRY_TIMES) {
                        delay(UpdateConfig.RETRY_DELAY_MS)
                    }
                }
                // 超时 / 网络错误 / 解析失败：静默放弃，不弹窗、不提示
                Log.e(TAG, "版本检测失败，已静默放弃", lastError)
                CheckResult.Failed
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                Log.e(TAG, "版本检测异常，已静默跳过", t)
                CheckResult.Failed
            } finally {
                checking.set(false)
            }
        }

    /**
     * 单次请求 + JSON 解析。
     *
     * @return null 表示这次请求失败、值得重试；非 null 为最终结论
     */
    private fun requestOnce(): CheckResult? {
        // 依次尝试所有候选源，任一源返回有效结果即结束；全部失败返回 null（交由外层重试/静默）
        for (baseUrl in UpdateConfig.VERSION_JSON_URLS) {
            try {
                val result = requestFrom(baseUrl) ?: continue
                Log.d(TAG, "版本检测命中源：$baseUrl")
                return result
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                Log.w(TAG, "源不可用（${baseUrl}）：${t.message}")
            }
        }
        return null
    }

    /**
     * 从单个地址请求并解析 version.json。
     *
     * @return null 表示该源不可用（非 2xx / 空响应 / JSON 非法 / 字段缺失），继续试下一个源
     */
    private fun requestFrom(baseUrl: String): CheckResult? {
        // 时间戳参数绕开代理与 CDN 缓存，刚提交的 JSON 立即生效
        val separator = if (baseUrl.contains("?")) "&" else "?"
        val url = "$baseUrl${separator}t=${System.currentTimeMillis()}"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UpdateConfig.USER_AGENT)
            .header("Cache-Control", "no-cache")
            .header("Pragma", "no-cache")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "版本检测失败：HTTP ${response.code}（$baseUrl）")
                return null
            }
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) {
                Log.w(TAG, "版本检测失败：响应体为空（$baseUrl）")
                return null
            }

            val json = try {
                JSONObject(cleanJson(body))
            } catch (t: Throwable) {
                Log.w(TAG, "版本检测失败：JSON 解析异常（$baseUrl）", t)
                return null
            }

            // 严格按接口字段解析，缺字段给安全默认值
            val mirrors = json.optJSONArray("apkMirrors")?.let { arr ->
                buildList {
                    for (i in 0 until arr.length()) {
                        val u = arr.optString(i, "")
                        if (u.startsWith("http://") || u.startsWith("https://")) add(u)
                    }
                }
            } ?: emptyList()

            val info = UpdateInfo(
                versionCode = json.optInt("versionCode", -1),
                versionName = json.optString("versionName", ""),
                apkUrl = json.optString("apkUrl", ""),
                updateLog = json.optString("updateLog", "暂无更新说明"),
                forceUpdate = json.optBoolean("forceUpdate", false),
                apkMirrors = mirrors
            )
            if (info.versionCode <= 0 ||
                !(info.apkUrl.startsWith("http://") || info.apkUrl.startsWith("https://"))
            ) {
                Log.w(TAG, "版本检测失败：versionCode / apkUrl 缺失或非法（$baseUrl）")
                return null
            }

            // ★ 防降级护栏：远端版本名严格旧于本地时，即使 versionCode 更大也判为旧版
            if (VersionRules.isOlderVersionName(info.versionName, BuildConfig.VERSION_NAME)) {
                Log.d(
                    TAG,
                    "远端版本名 ${info.versionName} 旧于本地 ${BuildConfig.VERSION_NAME}，判为降级更新，静默跳过"
                )
                return CheckResult.UpToDate
            }

            return if (info.versionCode > BuildConfig.VERSION_CODE) {
                Log.d(TAG, "发现新版本 v${info.versionName} (${info.versionCode})")
                CheckResult.Available(info)
            } else {
                Log.d(
                    TAG,
                    "已是最新版本：本地 ${BuildConfig.VERSION_CODE} >= 远端 ${info.versionCode}"
                )
                CheckResult.UpToDate
            }
        }
    }

    /**
     * JSON 清洗：去 BOM / 首尾空白；若 CDN 在末尾追加了多余字符，
     * 截取第一对完整花括号内的对象再解析，避免整次检测被一个杂字符废掉。
     */
    private fun cleanJson(raw: String): String {
        val trimmed = raw.trim().removePrefix("\uFEFF")
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) return trimmed
        val start = trimmed.indexOf('{')
        if (start < 0) return trimmed
        var depth = 0
        for (i in start until trimmed.length) {
            when (trimmed[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return trimmed.substring(start, i + 1)
                }
            }
        }
        return trimmed
    }
}
