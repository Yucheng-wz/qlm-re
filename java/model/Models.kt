package com.hnu.pickupassistant.model

import java.util.UUID

/**
 * 取件码核心数据模型
 */
data class PickupItem(
    val id: String = UUID.randomUUID().toString(),
    val rawCode: String,       // 如 "46-5-6171"
    val shelfNum: Int,         // 货架号，如 46
    val layerNum: Int,         // 层数，如 5
    val tailNum: String,       // 尾号，如 "6171"
    val isPicked: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val note: String = "",     // 备注：只存用户手写的补充说明，绝不自动拼入驿站 / 单号
    val isTodo: Boolean = false, // 只有备注、没有取件码的「待办」记录
    val courier: String = "",  // 快递公司展示名（如「圆通快递」），识别不到为空串
    /** 驿站名称：结构化字段，与 note 分开保存；旧数据没有该字段时默认空串 */
    val station: String = "",
    /** 物流单号：结构化字段，与 note 分开保存；旧数据没有该字段时默认空串 */
    val trackingNo: String = ""
) {
    /** 展示用的取件码：有快递公司时追加「·公司名」，如「14-5-3965·圆通」 */
    val displayCode: String
        get() = if (courier.isBlank()) rawCode else "$rawCode·$courier"
}

/**
 * 外部 App 路由跳转配置项
 */
data class RouteConfig(
    val packageName: String,
    val targetActivity: String,
    val fallbackActivity: String,
    val schemeUri: String
) {
    companion object {
        // 预设 1：菜鸟驿站包裹列表
        val DEFAULT_CAINIAO = RouteConfig(
            packageName = "com.cainiao.wireless",
            targetActivity = "com.cainiao.wireless.homepage.view.activity.SchemeTransformActivity",
            schemeUri = "guoguo://go/route?path=/station/packageList&stationId=K10000038714",
            fallbackActivity = "com.cainiao.wireless.homepage.view.activity.HomePageActivity"
        )

        // 预设 2：淘宝出库条形码（XN码）
        // 来源：Anywhere- 卡片实测可用配置 —— ACTION_VIEW + 显式 FromH5RouterActivity + identity-code 页面 URL。
        // 旧版 "com.taobao.tao.homepage.SchemeTransformActivity" + "taobao://?..." 已失效，无法打开。
        val DEFAULT_TAOBAO = RouteConfig(
            packageName = "com.taobao.taobao",
            targetActivity = "com.taobao.browser.router.FromH5RouterActivity",
            schemeUri = "https://pages-fast.m.taobao.com/wow/z/uniapp/1011717/last-mile-fe/end-collect-platform/identity-code",
            fallbackActivity = "com.taobao.tao.TBMainActivity"
        )
    }
}
