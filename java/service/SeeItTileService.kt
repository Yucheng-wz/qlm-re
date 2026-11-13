package com.hnu.pickupassistant.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import android.widget.Toast
import com.hnu.pickupassistant.CaptureActivity
import com.hnu.pickupassistant.util.ShizukuHelper

/**
 * 「看见没」控制中心磁贴。
 * 下拉控制中心点一下即可识别当前屏幕上的取件码或文字并自动添加。
 *
 * 优先级：
 *   1. 已获 Shizuku 授权 → 直接后台抓屏，绝不跳转任何界面、不弹系统录屏授权；
 *   2. Shizuku 已运行但未授权 → 直接请求 Shizuku 授权（Shizuku 自身弹窗，一次性），
 *      授权成功后立刻抓屏，全程不打开本 App；
 *   3. 未安装/未运行 Shizuku → 回退到系统录屏授权流程（CaptureActivity 透明跳板）。
 */
class SeeItTileService : TileService() {

    override fun onClick() {
        super.onClick()

        // 1) 已授权：直接后台抓屏，无跳转、无弹窗
        if (ShizukuHelper.isReady()) {
            try {
                ScreenCaptureService.startWithShizuku(this)
                return
            } catch (_: Throwable) {
            }
        }

        // 2) Shizuku 在跑但还没授权：直接请求授权（Shizuku 自己的弹窗），成功即抓屏
        if (ShizukuHelper.isShizukuRunning()) {
            ShizukuHelper.requestPermissionWithCallback { granted ->
                if (granted) {
                    try {
                        ScreenCaptureService.startWithShizuku(this@SeeItTileService)
                    } catch (_: Throwable) {
                        toast("Shizuku 抓屏启动失败，请稍后重试")
                    }
                } else {
                    toast("请在 Shizuku 的「已授权应用」里允许「取了吗」")
                }
            }
            return
        }

        // 3) 回退：系统录屏授权流程
        val intent = Intent(this, CaptureActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pending = PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                startActivityAndCollapse(pending)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (_: Throwable) {
            // 锁屏或系统限制时静默失败，避免下拉面板里弹出异常
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
}
