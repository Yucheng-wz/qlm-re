package com.hnu.pickupassistant.shizuku

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import rikka.shizuku.Shizuku

/**
 * Shizuku UserService 实现：以 shell（uid 2000）身份运行，
 * 用 Runtime.exec 执行 screencap 抓取当前屏幕，绕过 MediaProjection 授权弹窗。
 *
 * 关键修正（之前完全跑不起来）：
 *   - 必须是真正的 android.app.Service，用 Service 包裹一个内部 Stub 实例，onBind 返回它；
 *   - 必须在 AndroidManifest 中以 moe.shizuku.manager.permission.API_V23 权限声明；
 *   - 事务码必须匹配 Shizuku 框架：attach = 1，destroy = 16777114（见 ICaptureService.aidl）。
 *     之前 attach 错写成 16777113，导致 Shizuku 的握手调用落空、bindUserService 抛异常、
 *     captureScreen() 返回空，进而「看见没」回退到系统录屏授权（app 跳转 + 授权弹窗）。
 */
class CaptureService : Service() {

    private val binder = object : ICaptureService.Stub() {

        override fun attach(binder: IBinder?, options: Bundle?) {
            try {
                if (binder != null) {
                    Shizuku.attachUserService(binder, options ?: Bundle())
                }
            } catch (e: Throwable) {
                Log.w("CaptureService", "attachUserService failed", e)
            }
        }

        override fun captureScreenshot(): ByteArray {
            // 优先：写文件方式抓屏。部分 ROM 上 `screencap -p` 走 stdout 管道会被截断/失败，
            // 改为输出到文件再读回，更稳定；同时收集 stderr 便于排查。
            val file = File("/data/local/tmp/seeit_shot.png")
            try {
                val p = Runtime.getRuntime().exec(arrayOf("/system/bin/screencap", file.absolutePath))
                val stderr = try {
                    p.errorStream.bufferedReader().readText()
                } catch (_: Throwable) {
                    ""
                }
                val exit = p.waitFor()
                if (exit == 0 && file.exists() && file.length() > 0L) {
                    val bytes = file.readBytes()
                    file.delete()
                    return bytes
                }
                Log.w(
                    "CaptureService",
                    "screencap(file) failed exit=$exit stderr=$stderr size=" +
                        if (file.exists()) file.length() else -1
                )
            } catch (e: Throwable) {
                Log.w("CaptureService", "screencap(file) exception", e)
            } finally {
                try {
                    file.delete()
                } catch (_: Throwable) {
                }
            }
            // 兜底：stdout 方式
            return try {
                val out = ByteArrayOutputStream()
                val p2 = Runtime.getRuntime().exec(arrayOf("/system/bin/screencap", "-p"))
                val input = p2.inputStream
                val buffer = ByteArray(8192)
                var n: Int
                while (input.read(buffer).also { n = it } != -1) {
                    if (n > 0) out.write(buffer, 0, n)
                }
                input.close()
                val exit2 = p2.waitFor()
                if (exit2 != 0) ByteArray(0) else out.toByteArray()
            } catch (e: Throwable) {
                Log.w("CaptureService", "screencap(stdout) failed", e)
                ByteArray(0)
            }
        }

        override fun destroy() {
            // Shizuku 回收服务进程时调用，直接退出进程。
            System.exit(0)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder
}
