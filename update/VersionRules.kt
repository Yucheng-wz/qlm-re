package com.hnu.pickupassistant.update

/**
 * 「取了吗」在线更新 —— 版本号语义比较规则（纯 Kotlin，无 Android 依赖，可 JVM 单测）。
 *
 * ## 为什么需要它（真实故障）
 * 线上 Gitee `version.json` 曾长期残留 `versionCode=283 / versionName=2.8.3`，
 * 而历史包因为版本映射切换出现过 `versionCode=85`，也有 `283` 的 2.8.3 包。
 * 若只按「远端 versionCode > 本地」判断，85 版用户会看到「发现新版本 v2.8.3」
 * 并下载安装 2.8.3 旧包 —— 即用户反馈的**降级安装**。
 *
 * 本类提供版本名的语义比较，让检测器在 versionCode 之外再加一道护栏：
 * 远端 versionName 严格旧于本地时，一律判为旧版、静默跳过。
 * 版本名相等但 versionCode 更大仍允许更新（同版本号重发修复包的场景）。
 *
 * @author 焱川 (by 焱川)
 */
object VersionRules {

    /**
     * 把 `X.Y.Z`（可带 v/V 前缀、可带后缀如 -debug）解析成 3 段整数，缺位补 0。
     * 非法 / 空版本名返回 null，交由调用方按「不拦截」处理。
     */
    internal fun parseVersion(name: String): Triple<Int, Int, Int>? {
        val cleaned = name.trim().removePrefix("v").removePrefix("V")
        if (cleaned.isEmpty()) return null
        val parts = cleaned.split(".")
        if (parts.isEmpty()) return null
        val numbers = parts.take(3).map { part ->
            // 只取开头的连续数字，兼容 "3-beta" 这类后缀
            val digits = part.takeWhile { it.isDigit() }
            digits.toIntOrNull() ?: return null
        }
        if (numbers.isEmpty() || numbers.all { it == 0 }) return null
        return Triple(
            numbers.getOrElse(0) { 0 },
            numbers.getOrElse(1) { 0 },
            numbers.getOrElse(2) { 0 }
        )
    }

    /**
     * @return true 表示 remote **严格旧于** local（应拦截，避免降级安装）。
     *         远端非法 / 为空、或与本地相等、或更新，一律返回 false。
     */
    fun isOlderVersionName(remote: String, local: String): Boolean {
        val r = parseVersion(remote) ?: return false
        val l = parseVersion(local) ?: return false
        val rv = listOf(r.first, r.second, r.third)
        val lv = listOf(l.first, l.second, l.third)
        for (i in 0..2) {
            if (rv[i] < lv[i]) return true
            if (rv[i] > lv[i]) return false
        }
        return false // 相等：不是降级
    }

    /** 日志 / 自测用的可读版本描述 */
    fun describe(name: String): String = parseVersion(name)?.let {
        "${it.first}.${it.second}.${it.third}"
    } ?: "<非法版本名>"
}
