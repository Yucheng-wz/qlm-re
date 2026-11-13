package com.hnu.pickupassistant

import android.app.Activity
import android.os.Bundle
import com.hnu.pickupassistant.data.LocalStorageManager
import com.hnu.pickupassistant.util.RouteLauncher

class ShortcutTrampolineActivity : Activity() {

    companion object {
        const val ACTION_SHORTCUT_CAINIAO = "com.hnu.pickupassistant.ACTION_SHORTCUT_CAINIAO"
        const val ACTION_SHORTCUT_TAOBAO = "com.hnu.pickupassistant.ACTION_SHORTCUT_TAOBAO"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LocalStorageManager.init(applicationContext)

        when (intent?.action) {
            ACTION_SHORTCUT_CAINIAO -> {
                RouteLauncher.launchWithFallback(
                    context = this,
                    config = LocalStorageManager.cainiaoConfigFlow.value
                )
            }
            ACTION_SHORTCUT_TAOBAO -> {
                RouteLauncher.launchWithFallback(
                    context = this,
                    config = LocalStorageManager.taobaoConfigFlow.value
                )
            }
        }

        finish()
        overridePendingTransition(0, 0)
    }
}
