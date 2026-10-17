package com.flickxr.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.xr.runtime.DeviceTrackingMode
import androidx.xr.runtime.HandTrackingMode
import androidx.xr.runtime.Session
import androidx.xr.runtime.SessionConfigureSuccess
import androidx.xr.runtime.SessionCreateSuccess
import androidx.xr.scenecore.scene
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.EmptyCoroutineContext

class MainActivity : ComponentActivity() {

    private val text = MutableStateFlow("")
    private val status = MutableStateFlow(TrackingStatus())
    private val settings = MutableStateFlow(KeyboardSettings())
    private val message = MutableStateFlow("起動中…")

    private val buffer = TextBuffer()
    private var keyboard: HandKeyboard? = null
    private var started = false

    private val permissions = arrayOf(PERM_HAND, PERM_HEAD)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[PERM_HAND] == true) startXr()
            else message.value = "ハンドトラッキングの権限が必要です"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = SettingsStore(this)
        settings.value = store.load()
        lifecycleScope.launch { settings.collect(store::save) }

        setContent {
            MainScreen(
                text = text,
                status = status,
                settings = settings,
                message = message,
                onClear = {
                    buffer.clear()
                    text.value = ""
                },
                onCopy = ::copyToClipboard,
            )
        }

        if (permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) startXr()
        else permissionLauncher.launch(permissions)
    }

    private fun startXr() {
        if (started) return
        started = true
        lifecycleScope.launch { startXrSession() }
    }

    private suspend fun startXrSession() {
        val result = Session.create(this, EmptyCoroutineContext, this)
        if (result !is SessionCreateSuccess) {
            message.value = "XR セッションを作成できません: $result"
            return
        }
        val session = result.session

        val headGranted = checkSelfPermission(PERM_HEAD) == PackageManager.PERMISSION_GRANTED
        val configured = try {
            session.configure(
                session.config.copy(
                    handTracking = HandTrackingMode.BOTH,
                    deviceTracking = if (headGranted) DeviceTrackingMode.SPATIAL else DeviceTrackingMode.DISABLED,
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "configure failed", e)
            null
        }
        if (configured !is SessionConfigureSuccess) {
            message.value = "ハンドトラッキングを有効にできません: $configured"
            return
        }

        session.scene.requestFullSpace()

        keyboard = HandKeyboard(
            context = this,
            session = session,
            settings = settings,
            onAction = { action ->
                buffer.apply(action)
                text.value = buffer.text
            },
            onStatus = { status.value = it },
        ).also { it.start(lifecycleScope) }
        message.update { "手のひらを顔に向けるとキーボードが出ます" }
    }

    private fun copyToClipboard() {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("flick", buffer.text))
        message.value = "クリップボードにコピーしました"
    }

    override fun onDestroy() {
        keyboard?.stop()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "FlickOnHands"
        private const val PERM_HAND = "android.permission.HAND_TRACKING"
        private const val PERM_HEAD = "android.permission.HEAD_TRACKING"
    }
}
