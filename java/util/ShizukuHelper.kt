package com.hnu.pickupassistant.util

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.IBinder
import android.util.Log
import com.hnu.pickupassistant.shizuku.CaptureService
import com.hnu.pickupassistant.shizuku.ICaptureService
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Shizuku 抓屏：通过 UserService 在 shell 身份下执行 screencap，绕过 MediaProjection 授权弹窗。
 *
 * 前提：手机已安装并启动 Shizuku（无线调试或 root），且已授权本应用。
 * 任一条件不满足返回 null，调用方回退到 MediaProjection 授权流程。
 */
object ShizukuHelper {

    private const val TAG = "ShizukuHelper"
    const val REQUEST_CODE = 2333
    private const val TIMEOUT_MS = 8000L

    private var boundService: ICaptureService? = null

    /** Shizuku 是否已安装且服务在运行 */
    fun isShizukuRunning(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    /** 本应用是否已获得 Shizuku 授权 */
    fun hasPermission(): Boolean = try {
        !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    /** Shizuku 是否可用来免弹窗抓屏 */
    fun isReady(): Boolean = isShizukuRunning() && hasPermission()

    /** 申请 Shizuku 授权（结果由调用方通过 addRequestPermissionResultListener 接收） */
    fun requestPermission(): Boolean {
        if (isReady()) return true
        if (!isShizukuRunning()) return false
        return try {
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                false
            } else {
                Shizuku.requestPermission(REQUEST_CODE)
                false
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 申请 Shizuku 授权，并在结果返回后通过回调通知。
     * 适用于磁贴等服务上下文：Shizuku 自身弹窗完成授权，无需跳转本 App。
     */
    fun requestPermissionWithCallback(onResult: (Boolean) -> Unit) {
        if (isReady()) { onResult(true); return }
        if (!isShizukuRunning()) { onResult(false); return }
        try {
            if (Shizuku.shouldShowRequestPermissionRationale()) { onResult(false); return }
            var listener: Shizuku.OnRequestPermissionResultListener? = null
            listener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
                if (requestCode == REQUEST_CODE) {
                    listener?.let { Shizuku.removeRequestPermissionResultListener(it) }
                    onResult(grantResult == PackageManager.PERMISSION_GRANTED)
                }
            }
            Shizuku.addRequestPermissionResultListener(listener)
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (_: Throwable) {
            onResult(false)
        }
    }

    /** 通过 Shizuku 抓屏；不可用或失败返回 null */
    fun captureScreen(): Bitmap? {
        if (!isReady()) return null
        return try {
            val png = captureScreenshotPng()
            if (png.isEmpty()) null else BitmapFactory.decodeByteArray(png, 0, png.size)
        } catch (e: Throwable) {
            Log.w(TAG, "capture via shizuku failed", e)
            null
        }
    }

    private fun captureScreenshotPng(): ByteArray {
        val latch = CountDownLatch(1)
        val holder = Holder()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val service = ICaptureService.Stub.asInterface(binder)
                boundService = service
                try {
                    holder.bytes = service?.captureScreenshot() ?: ByteArray(0)
                } catch (e: Throwable) {
                    Log.w(TAG, "captureScreenshot call failed", e)
                    holder.bytes = ByteArray(0)
                } finally {
                    latch.countDown()
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                boundService = null
                latch.countDown()
            }
        }

        val args = Shizuku.UserServiceArgs(
            ComponentName(
                "com.hnu.pickupassistant",
                CaptureService::class.java.name
            )
        ).daemon(false).version(1)

        try {
            Shizuku.bindUserService(args, connection)
            if (!latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Shizuku.unbindUserService(args, connection, true)
                return ByteArray(0)
            }
            Shizuku.unbindUserService(args, connection, true)
            return holder.bytes
        } catch (e: Throwable) {
            Log.w(TAG, "bindUserService failed", e)
            return ByteArray(0)
        }
    }

    private class Holder {
        var bytes: ByteArray = ByteArray(0)
    }
}
