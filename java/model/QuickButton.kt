package com.hnu.pickupassistant.model

import java.util.UUID

/**
 * 主界面顶部快捷按钮的类型：
 * - ROUTE：点击后跳转到外部 App 的指定页面（取件码 / 出库码 / 自定义页面）
 * - IMAGE：点击后全屏最高亮度展示本地保存的二维码截图（例如拼多多出库二维码）
 */
enum class QuickButtonKind {
    ROUTE,
    IMAGE
}

/**
 * 可自定义、可增删、可排序的快捷按钮。
 * 所有字段都随 SharedPreferences 持久化，用户可以任意替换按钮内容与顺序。
 */
data class QuickButton(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val kind: QuickButtonKind = QuickButtonKind.ROUTE,
    val packageName: String = "",
    val targetActivity: String = "",
    val fallbackActivity: String = "",
    val schemeUri: String = "",
    val imageFileName: String = "",
    val styleKey: String = ""   // 配色/图标预设的 key，空串表示按位置使用默认配色
) {
    fun toRouteConfig() = RouteConfig(
        packageName = packageName,
        targetActivity = targetActivity,
        fallbackActivity = fallbackActivity,
        schemeUri = schemeUri
    )

    companion object {
        /** 出厂默认：第一个「取件码」、第二个「出库码」 */
        val DEFAULTS: List<QuickButton>
            get() = listOf(
                QuickButton(
                    id = "default_pickup",
                    label = "取件码",
                    kind = QuickButtonKind.ROUTE,
                    packageName = RouteConfig.DEFAULT_CAINIAO.packageName,
                    targetActivity = RouteConfig.DEFAULT_CAINIAO.targetActivity,
                    fallbackActivity = RouteConfig.DEFAULT_CAINIAO.fallbackActivity,
                    schemeUri = RouteConfig.DEFAULT_CAINIAO.schemeUri
                ),
                QuickButton(
                    id = "default_outbound",
                    label = "出库码",
                    kind = QuickButtonKind.ROUTE,
                    packageName = RouteConfig.DEFAULT_TAOBAO.packageName,
                    targetActivity = RouteConfig.DEFAULT_TAOBAO.targetActivity,
                    fallbackActivity = RouteConfig.DEFAULT_TAOBAO.fallbackActivity,
                    schemeUri = RouteConfig.DEFAULT_TAOBAO.schemeUri
                )
            )
    }
}
