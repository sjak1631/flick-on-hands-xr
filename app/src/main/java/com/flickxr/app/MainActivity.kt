package com.flickxr.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
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
    private lateinit var prompts: PromptCollection
    /** データ収集で提示が変わったことを知らせる音（画面を見続けなくても打てるように） */
    private val beep by lazy { runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 70) }.getOrNull() }
    private var log: SessionLog? = null

    private val permissions = arrayOf(PERM_HAND, PERM_HEAD)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[PERM_HAND] == true) startXr()
            else message.value = "ハンドトラッキングの権限が必要です"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StrokeModel.instance = runCatching { assets.open("stroke_model.txt").use(StrokeModel::parse) }
            .onFailure { Log.e(TAG, "failed to load stroke model", it) }.getOrNull()
        val store = SettingsStore(this)
        settings.value = store.load()
        lifecycleScope.launch { settings.collect(store::save) }
        calibration = Calibration(
            scope = lifecycleScope,
            settings = settings,
            onTrialStart = { keyboard?.resetMode() },
            onResult = ::logTrial,
        )
        prompts = PromptCollection(
            scope = lifecycleScope,
            settings = settings,
            onReset = { keyboard?.resetMode() },
            onPrompt = { p ->
                beep?.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
                log { it.prompt(p); it.flush() }
            },
            onFinish = { log?.flush() },
        )

        setContent {
            MainScreen(
                text = text,
                status = status,
                settings = settings,
                message = message,
                calibration = calibration,
                prompts = prompts,
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
                if (!calibration.onAction(action) && !prompts.onAction(action)) {
                    buffer.apply(action)
                    text.value = buffer.text
                }
            },
            onStatus = { status.value = it },
            onTipSample = calibration::onTipSample,
            onTipFrame = { t, tip, touching, action ->
                recording()?.let { (_, attempt) -> log { it.frame(attempt, t, tip, touching, action) } }
            },
            recordJoints = { recording() != null },
            showPopup = { !prompts.active },
            onJoints = { t, kbTracked, typeTracked, values ->
                recording()?.let { (_, attempt) -> log { it.joints(attempt, t, kbTracked, typeTracked, values) } }
            },
        ).also { it.start(lifecycleScope) }
        message.update { "手のひらを顔に向けるとキーボードが出ます" }
    }

    private fun copyToClipboard() {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("flick", buffer.text))
        message.value = "クリップボードにコピーしました"
    }

    /** 記録中のセッションと試行（自動調整・練習・データ収集のどれか）。記録中でなければ null */
    private fun recording(): Pair<Long, Int>? =
        calibration.recordingAttempt?.let { calibration.sessionId to it }
            ?: prompts.recordingAttempt?.let { prompts.sessionId to it }

    /**
     * 今のセッションの記録（`files/sessions/<開始時刻>/`）に書く。後で判定を再生して比べたり、学習に使ったりするため。
     * 書けなくても入力は止めない。
     */
    private fun log(write: (SessionLog) -> Unit) {
        val id = recording()?.first ?: if (prompts.active) prompts.sessionId else calibration.sessionId
        runCatching {
            val dir = File(getExternalFilesDir("sessions"), id.toString())
            val l = log?.takeIf { it.dir == dir } ?: run {
                log?.close()
                SessionLog(dir).also { log = it }
            }
            write(l)
        }.onFailure { Log.e(TAG, "failed to write session log", it) }
    }

    private fun logTrial(r: TrialResult) {
        Log.i(TAG, "calibration trial $r")
        log { it.trial(r, calibration.state.value.palmSurface) }
    }

    /** 記録はバッファしているので、裏に回ったら書き出す（そのまま終了されると末尾が欠けるため） */
    override fun onStop() {
        runCatching { log?.flush() }
        super.onStop()
    }

    override fun onDestroy() {
        log?.close()
        beep?.release()
        keyboard?.stop()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "FlickOnHands"
        private const val PERM_HAND = "android.permission.HAND_TRACKING"
        private const val PERM_HEAD = "android.permission.HEAD_TRACKING"
    }
}
