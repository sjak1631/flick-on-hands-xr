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
import java.io.File
import kotlin.coroutines.EmptyCoroutineContext

class MainActivity : ComponentActivity() {

    private val text = MutableStateFlow("")
    private val status = MutableStateFlow(TrackingStatus())
    private val settings = MutableStateFlow(KeyboardSettings())
    private val message = MutableStateFlow("起動中…")

    private val buffer = TextBuffer()
    private var keyboard: HandKeyboard? = null
    private var started = false
    private lateinit var calibration: Calibration
    private var log: SessionLog? = null

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
        calibration = Calibration(
            scope = lifecycleScope,
            settings = settings,
            onTrialStart = { keyboard?.resetMode() },
            onResult = ::logTrial,
        )

        setContent {
            MainScreen(
                text = text,
                status = status,
                settings = settings,
                message = message,
                calibration = calibration,
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
                if (!calibration.onAction(action)) {
                    buffer.apply(action)
                    text.value = buffer.text
                }
            },
            onStatus = { status.value = it },
            onTipSample = calibration::onTipSample,
            onTipFrame = { t, tip, touching, action ->
                calibration.recordingAttempt?.let { logFrame(it, t, tip, touching, action) }
            },
        ).also { it.start(lifecycleScope) }
        message.update { "手のひらを顔に向けるとキーボードが出ます" }
    }

    private fun copyToClipboard() {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("flick", buffer.text))
        message.value = "クリップボードにコピーしました"
    }

    /**
     * 今の自動調整のセッションの記録（`files/sessions/<開始時刻>/`）。後で判定を再生して比べたり、
     * 誤入力の原因を切り分けたりするため。
     */
    private fun sessionLog(): SessionLog {
        val dir = File(getExternalFilesDir("sessions"), calibration.sessionId.toString())
        log?.takeIf { it.dir == dir }?.let { return it }
        log?.close()
        return SessionLog(dir).also { log = it }
    }

    private fun logFrame(attempt: Int, tMs: Long, tip: TipFrame?, touching: Boolean, action: KeyAction?) {
        runCatching { sessionLog().frame(attempt, tMs, tip, touching, action) }
            .onFailure { Log.e(TAG, "failed to write frame log", it) }
    }

    private fun logTrial(r: TrialResult) {
        Log.i(TAG, "calibration trial $r")
        runCatching { sessionLog().trial(r, calibration.state.value.palmSurface) }
            .onFailure { Log.e(TAG, "failed to write calibration log", it) }
    }

    override fun onDestroy() {
        log?.close()
        keyboard?.stop()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "FlickOnHands"
        private const val PERM_HAND = "android.permission.HAND_TRACKING"
        private const val PERM_HEAD = "android.permission.HEAD_TRACKING"
    }
}
