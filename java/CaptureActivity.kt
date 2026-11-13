package com.hnu.pickupassistant

import android.content.Context
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.hnu.pickupassistant.service.ScreenCaptureService

/**
 * 「看见没」磁贴的透明跳板：只负责向系统申请一次录屏授权，
 * 拿到结果后立刻把授权数据交给 mediaProjection 类型的前台服务并自行关闭。
 *
 * Android 14 (targetSdk 34) 要求 MediaProjection 必须在
 * foregroundServiceType="mediaProjection" 的前台服务中使用，因此不能在 Activity 里直接抓帧。
 */
class CaptureActivity : ComponentActivity() {

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            ScreenCaptureService.start(this, result.resultCode, data)
        } else {
            Toast.makeText(this, "已取消截屏授权", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            as? MediaProjectionManager
        if (manager == null) {
            Toast.makeText(this, "当前系统不支持屏幕识别", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        // 只在首次创建时申请；旋转屏幕等重建场景由 ActivityResultRegistry 自己恢复结果
        if (savedInstanceState != null) return
        try {
            projectionLauncher.launch(manager.createScreenCaptureIntent())
        } catch (e: Throwable) {
            Toast.makeText(
                this,
                "无法申请截屏权限：${e.localizedMessage ?: "未知错误"}",
                Toast.LENGTH_SHORT
            ).show()
            finish()
        }
    }
}
