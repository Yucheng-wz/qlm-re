package com.hnu.pickupassistant.update

/**
 * 「取了吗」在线版本更新 —— 全局可配置常量区（GitHub Releases + 国内加速镜像）。
 *
 * 发布渠道：GitHub 仓库 + Release 附件；国内访问通过加速前缀代理，
 * 版本检测与 APK 下载都按「多源顺序回退」执行，任一源可用即可完成更新。
 *
 * 后续要换仓库、加镜像、调限流时长、改超时，**只改本文件即可**，
 * 其余模块不写死任何 URL / 时间 / 文件名。
 *
 * @author 焱川 (by 焱川)
 */
object UpdateConfig {

    // ==================== GitHub 仓库（唯一需要按实际仓库改的地方） ====================

    /** GitHub 用户名 / 组织名 */
    const val GITHUB_OWNER: String = "yanchuan-qlmwz"

    /** 版本发布仓库名（只需放 version.json + Release 附件） */
    const val GITHUB_REPO: String = "qlm-release"

    /** 默认分支（version.json 提交在这里） */
    const val GITHUB_BRANCH: String = "main"

    // ==================== 服务端接口（多源，按顺序回退） ====================

    /**
     * version.json 的候选地址，**顺序即优先级**：
     *  1~3 国内加速代理（不缓存，刚发布的版本立即可见）
     *  4    jsDelivr（CDN 快，但会缓存一段时间，放后面）
     *  5    GitHub 原始地址（海外/开了代理时最快）
     *  6    Gitee 旧地址（迁移过渡期兜底，确认稳定后可删除）
     */
    val VERSION_JSON_URLS: List<String> = listOf(
        "https://ghproxy.net/https://raw.githubusercontent.com/$GITHUB_OWNER/$GITHUB_REPO/$GITHUB_BRANCH/version.json",
        "https://ghfast.top/https://raw.githubusercontent.com/$GITHUB_OWNER/$GITHUB_REPO/$GITHUB_BRANCH/version.json",
        "https://gh-proxy.com/https://raw.githubusercontent.com/$GITHUB_OWNER/$GITHUB_REPO/$GITHUB_BRANCH/version.json",
        "https://gcore.jsdelivr.net/gh/$GITHUB_OWNER/$GITHUB_REPO@$GITHUB_BRANCH/version.json",
        "https://raw.githubusercontent.com/$GITHUB_OWNER/$GITHUB_REPO/$GITHUB_BRANCH/version.json",
        "https://gitee.com/yanchuan_qlmwz/qlm-release/raw/master/version.json"
    )

    /**
     * APK 下载用的加速前缀，**顺序即优先级**：
     * 下载时按 `前缀 + GitHub Release 直链` 依次尝试，全部失败才判定下载失败。
     * 直链放在最后：国内大概率连不上，但如果用户挂了代理，直链反而最快。
     */
    val APK_PROXY_PREFIXES: List<String> = listOf(
        "https://ghproxy.net/",
        "https://ghfast.top/",
        "https://gh-proxy.com/"
    )

    /** 统一 User-Agent：GitHub 原始地址与部分代理对无 UA 请求不友好 */
    const val USER_AGENT: String = "PickupAssistant-Update/1.0 (Android; QLM)"

    // ==================== 自动检测限流 ====================

    /** 启动自动检测最小间隔：6 小时（毫秒）。手动【检查更新】不受此限制 */
    const val AUTO_CHECK_INTERVAL_MS: Long = 6L * 60L * 60L * 1000L

    /** 上次检测时间的 SharedPreferences 文件名与键名 */
    const val PREF_NAME: String = "qlm_update_prefs"
    const val PREF_KEY_LAST_CHECK_TIME: String = "last_check_time_ms"

    // ==================== 网络容错 ====================

    /** 请求失败最多「重试 1 次」（即最多发起 2 次请求）；超时/网络错误最终静默放弃 */
    const val MAX_RETRY_TIMES: Int = 1

    /** 两次请求之间的短暂等待，避免瞬时故障连续打接口触发风控 */
    const val RETRY_DELAY_MS: Long = 600L

    /** 版本检测超时（秒）：短超时保证启动检测不会拖慢首屏 */
    const val CONNECT_TIMEOUT_SECONDS: Long = 8L
    const val READ_TIMEOUT_SECONDS: Long = 15L
    const val CHECK_CALL_TIMEOUT_SECONDS: Long = 20L

    /** 下载整包的总超时（秒）：APK 约 60~70 MB，给足 10 分钟 */
    const val DOWNLOAD_CALL_TIMEOUT_SECONDS: Long = 600L

    // ==================== APK 下载与落地 ====================

    /** 进度回调最小刷新间隔（毫秒），避免高频刷新 Compose 造成卡顿 */
    const val PROGRESS_EMIT_INTERVAL_MS: Long = 120L

    /** 下载缓冲区大小（字节） */
    const val DOWNLOAD_BUFFER_BYTES: Int = 8 * 1024

    /** 合法 APK 最小体积：小于它说明拿到的是 403 页面 / 错误 HTML，禁止进入安装器 */
    const val MIN_APK_BYTES: Long = 100L * 1024L

    /**
     * APK 落地文件名规则（需求：文件名必须非空且合法）。
     *
     * 实际文件名由 `ApkFileRules.buildFileName(versionName)` 生成，形如
     * `pickup_assistant_v2.8.8.apk`；版本号异常时退回 [APK_FILE_FALLBACK_NAME]，
     * **任何情况下都不会出现空文件名**（空名会让 FileProvider / 安装器直接抛
     * `Name must not be empty`）。
     */
    const val APK_FILE_PREFIX: String = "pickup_assistant_"
    const val APK_FILE_FALLBACK_NAME: String = "pickup_assistant_update.apk"

    /** 旧版本残留包名，下载前顺手清掉，避免装到上一版 */
    const val APK_LEGACY_FILE_NAME: String = "take_code_new.apk"

    /** 下载中临时文件后缀（半包绝不进安装器）；新方案用 MediaStore IS_PENDING 代替临时文件 */
    const val APK_TEMP_SUFFIX: String = ".tmp"

    /** APK 的 MIME 类型：MediaStore 记录与安装 Intent 共用 */
    const val APK_MIME_TYPE: String = "application/vnd.android.package-archive"

    // ==================== 面向用户的中文提示（统一在此配置） ====================

    /** 下载前自检失败（公共下载目录不可用） */
    const val HINT_STORAGE_UNAVAILABLE: String =
        "存储不可用，请在系统设置中检查本应用的存储权限后重试"

    /** 下载内容损坏 / 完整性校验失败 */
    const val HINT_APK_DAMAGED: String = "安装包下载损坏，请重新检查更新"

    /** 无法写入公共【下载】文件夹 */
    const val HINT_PUBLISH_FAILED: String =
        "安装包无法保存到【下载】文件夹，请检查存储空间后重试"

    /** 读取 / 唤起安装器失败：明确告知 APK 位置 */
    const val HINT_READ_FAILED: String =
        "无法读取安装包，请到文件管理器【下载】文件夹手动安装"

    /** 系统安装器不可见 */
    const val HINT_NO_INSTALLER: String =
        "未找到系统安装器，请到文件管理器【下载】文件夹手动安装"

    /** 文件管理器也打不开 */
    const val HINT_FILE_MANAGER_FAILED: String =
        "未能打开文件管理器，请到【下载】文件夹手动查找安装包"

    /** 未知来源权限未开启 */
    const val HINT_UNKNOWN_SOURCE: String =
        "请先允许「安装未知应用」，开启后重新点击立即更新"

    // ==================== FileProvider ====================

    /** 主 FileProvider authority 后缀，Manifest 里写作 ${applicationId}.fileprovider */
    const val FILE_PROVIDER_AUTHORITY_SUFFIX: String = ".fileprovider"

    /**
     * 兜底 FileProvider authority 后缀（${applicationId}.apkprovider）。
     * 个别 ROM / 被改坏的安装包上，主 provider 的 paths 解析会抛
     * `Name must not be empty`，此时把包复制到 cacheDir/apk/ 用这个「干净配置」的
     * provider 再取一次 Uri，尽最大努力让用户能装上。
     */
    const val APK_PROVIDER_AUTHORITY_SUFFIX: String = ".apkprovider"

    /** 兜底 provider 的临时目录（相对 cacheDir），对应 res/xml/apk_paths.xml 的 cache-path */
    const val APK_CACHE_SUBDIR: String = "apk"
}
