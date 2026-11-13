package com.hnu.pickupassistant.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.hnu.pickupassistant.data.LocalStorageManager

/** 单号查询渠道：淘宝（菜鸟查件页）/ 菜鸟 App（直接打开主页） */
enum class TrackingChannel(val key: String) {
    TAOBAO("taobao"),
    CAINIAO("cainiao");

    companion object {
        /** 兼容旧版本存过的 "h5"（当年是网页版），统一升级为打开菜鸟 App */
        fun fromKey(key: String?): TrackingChannel =
            if (key == CAINIAO.key || key == "h5") CAINIAO else TAOBAO
    }
}

/**
 * 运单号跳转：
 *  - 淘宝渠道：`tbopen://m.taobao.com/tbopen/index.html?h5Url=<菜鸟查件页>`，
 *    页面 URL 直接带 mailNo 参数，打开即按单号查询；
 *  - 菜鸟渠道：直接打开菜鸟 App 主页（系统启动意图，等价于点桌面图标）。
 *
 * 说明：按需求**不再复制单号到剪贴板**，单号只通过 URL 参数传递；
 * 淘宝唤不起时降级浏览器打开同一页面，菜鸟唤不起时也降级网页。
 */
object TrackingLauncher {

    private const val TAG = "TrackingLauncher"
    private const val TAOBAO_PKG = "com.taobao.taobao"
    private const val TAOBAO_ROUTER = "com.taobao.browser.router.FromH5RouterActivity"
    private const val CAINIAO_PKG = "com.cainiao.wireless"
    private const val CAINIAO_HOME = "com.cainiao.wireless.homepage.view.activity.HomePageActivity"

    /** 淘宝 Deep Link 外壳：h5Url 里放菜鸟查件页 */
    private const val TBOPEN_PREFIX = "tbopen://m.taobao.com/tbopen/index.html?h5Url="

    /**
     * 菜鸟「单号查件」页（学校驿站版 SSR 页面），已在真机确认可用。
     * 末尾拼 mailNo 参数，页面加载后按该单号查询。
     */
    private const val TRACKING_H5 =
        "https://pages-fast.m.taobao.com/wow/z/uniapp/1011717/last-mile-fe/end-collect-platform/school-search-ssr?x-ssr=true&mailNo=%s"

    /**
     * @return true 表示已成功唤起（淘宝 / 菜鸟 / 浏览器）；false 表示全部失败，由界面弹提示
     */
    fun jumpToAddPackage(context: Context, trackingNo: String): Boolean {
        val url = TRACKING_H5.format(Uri.encode(trackingNo))

        return when (LocalStorageManager.trackingChannelFlow.value) {
            TrackingChannel.TAOBAO -> {
                LiftedToast.show("正在打开淘宝查询该单号")
                if (openInTaobao(context, url)) {
                    true
                } else {
                    LiftedToast.show("未检测到淘宝 APP，改用浏览器打开")
                    openInBrowser(context, url).also { ok ->
                        if (!ok) Log.w(TAG, "淘宝与浏览器均唤起失败: $trackingNo")
                    }
                }
            }

            TrackingChannel.CAINIAO -> {
                LiftedToast.show("正在打开菜鸟 App")
                if (openInCainiao(context)) {
                    true
                } else {
                    LiftedToast.show("未检测到菜鸟 App，改用浏览器打开")
                    openInBrowser(context, url).also { ok ->
                        if (!ok) Log.w(TAG, "菜鸟与浏览器均唤起失败: $trackingNo")
                    }
                }
            }
        }
    }

    /**
     * 淘宝：优先用需求指定的 tbopen 外壳（h5Url 参数化），
     * 个别版本对 tbopen 解析失败时再退回显式 FromH5RouterActivity（与出库码同款）。
     */
    private fun openInTaobao(context: Context, url: String): Boolean {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(TBOPEN_PREFIX + Uri.encode(url)))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return true
        } catch (e: Throwable) {
            Log.w(TAG, "tbopen 唤起失败: ${e.message}")
        }
        return try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setClassName(TAOBAO_PKG, TAOBAO_ROUTER)
                    data = Uri.parse(url)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            true
        } catch (e: Throwable) {
            Log.w(TAG, "显式路由唤起失败: ${e.message}")
            false
        }
    }

    /**
     * 菜鸟 App：直接打开主页（不拼任何页面参数）。
     * 三级兜底：系统启动意图（最稳，等价于点桌面图标）→ 显式主页 Activity → guoguo scheme。
     */
    private fun openInCainiao(context: Context): Boolean {
        try {
            val launch = context.packageManager.getLaunchIntentForPackage(CAINIAO_PKG)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launch)
                return true
            }
        } catch (e: Throwable) {
            Log.w(TAG, "菜鸟启动意图失败: ${e.message}")
        }
        try {
            context.startActivity(
                Intent().setClassName(CAINIAO_PKG, CAINIAO_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return true
        } catch (e: Throwable) {
            Log.w(TAG, "菜鸟主页 Activity 失败: ${e.message}")
        }
        return try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("guoguo://go/route?path=/home"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (e: Throwable) {
            Log.w(TAG, "guoguo scheme 失败: ${e.message}")
            false
        }
    }

    private fun openInBrowser(context: Context, url: String): Boolean = try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (e: Throwable) {
        Log.w(TAG, "浏览器打开失败: ${e.message}")
        false
    }
}
