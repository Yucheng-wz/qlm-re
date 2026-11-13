package com.hnu.pickupassistant.model

/**
 * 「配置快捷导入」的预置路由，供编辑按钮时一键填入（2×2 网格）。
 * 除内置的菜鸟/淘宝配置外，其余为常见页面地址的参考值，
 * 若对方 App 版本更新导致打不开，可在下面的输入框里自行修改。
 */
data class QuickImportPreset(
    val label: String,
    val isDefault: Boolean = false,
    val packageName: String,
    val targetActivity: String = "",
    val schemeUri: String = "",
    val fallbackActivity: String = "",
    val hint: String = ""
)

object QuickImportPresets {
    val ALL: List<QuickImportPreset>
        get() = listOf(
            QuickImportPreset(
                label = "淘宝",
                isDefault = true,
                packageName = RouteConfig.DEFAULT_TAOBAO.packageName,
                targetActivity = RouteConfig.DEFAULT_TAOBAO.targetActivity,
                schemeUri = RouteConfig.DEFAULT_TAOBAO.schemeUri,
                fallbackActivity = RouteConfig.DEFAULT_TAOBAO.fallbackActivity
            ),
            QuickImportPreset(
                label = "拼多多",
                packageName = "com.xunmeng.pinduoduo",
                schemeUri = "pinduoduo://com.xunmeng.pinduoduo/mdkd/package?tab=ID_CODE"
            ),
            QuickImportPreset(
                label = "菜鸟",
                packageName = RouteConfig.DEFAULT_CAINIAO.packageName,
                targetActivity = RouteConfig.DEFAULT_CAINIAO.targetActivity,
                schemeUri = RouteConfig.DEFAULT_CAINIAO.schemeUri,
                fallbackActivity = RouteConfig.DEFAULT_CAINIAO.fallbackActivity
            ),
            QuickImportPreset(
                label = "京东",
                packageName = "com.jingdong.app.mall",
                hint = "已填入 App 包名，页面地址请按实际版本自行补充"
            )
        )
}
