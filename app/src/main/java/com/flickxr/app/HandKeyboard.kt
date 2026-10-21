package com.flickxr.app

import android.content.Context
import android.util.Log
import androidx.xr.arcore.ArDevice
import androidx.xr.arcore.Hand
import androidx.xr.arcore.HandJointType
import androidx.xr.arcore.TrackingState
import androidx.xr.runtime.Session
import androidx.xr.runtime.math.FloatSize2d
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.PanelEntity
import androidx.xr.scenecore.Space
import androidx.xr.scenecore.scene
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt

data class TrackingStatus(
    val keyboardHandTracked: Boolean = false,
    val typingHandTracked: Boolean = false,
    val palmFacing: Boolean = false,
    /** キーボード面からの指先の高さ（mm）。計測できないときは null */
    val fingerHeightMm: Float? = null,
    val touching: Boolean = false,
)

/**
 * 片手の手のひらにフリックキーボードのパネルを追従させ、もう片方の人差し指の先で打鍵を判定する。
 * 置き方（位置・回転・大きさ）は [KeyboardSettings] に従う。
 *
 * 計算はすべて perception space（ARCore の関節座標系）で行い、パネルに設定する直前に
 * activity space へ変換する。手の向きは関節の位置だけから求めるので、関節の回転の軸規約に依存しない。
 */
class HandKeyboard(
    private val context: Context,
    private val session: Session,
    private val settings: StateFlow<KeyboardSettings>,
    private val onAction: (KeyAction) -> Unit,
    private val onStatus: (TrackingStatus) -> Unit,
    /** 指先がキーボードの範囲の上にあるフレームごとに、手のひらの面（関節の中心を通る面）からの指先の高さを渡す */
    private val onTipSample: (Float) -> Unit = {},
    /** 毎フレーム、判定に渡した指先と判定の結果を渡す（軌跡の記録用）。アクションは [onAction] より先に渡す */
    private val onTipFrame: (tMs: Long, tip: TipFrame?, touching: Boolean, action: KeyAction?) -> Unit = { _, _, _, _ -> },
) {
    private val detector = SwitchingDetector(settings.value.detector)
    private val view = FlickKeyboardView(context, detector)
    private lateinit var panel: PanelEntity
    private var panelSize = FloatSize2d(BASE_SIZE, BASE_SIZE)

    private var palmFacing = false
    /** 前のフレームで判定に渡した入力側の手の状態。左右どちらの手の更新でもフレームが来るので、入力側が更新されたときだけ判定に渡す */
    private var lastTypeHand: Hand.State? = null
    private var smoothedPalm: Frame? = null
    private var lastStatusAt = 0L
    private var job: Job? = null

    /** 手のひら（指を除く部分）の実測サイズ。手の大きさはほぼ一定なので強めに平滑化する。 */
    private var palmWidth = BASE_SIZE
    private var palmLength = BASE_SIZE

    /** 正規直交の座標系（原点 center）。手のひらにもキーボードにも使う。 */
    private data class Frame(val center: Vector3, val right: Vector3, val up: Vector3, val normal: Vector3) {
        fun toLocal(p: Vector3) = dirToLocal(p - center)
        fun dirToLocal(v: Vector3) = Vector3(v.dot(right), v.dot(up), v.dot(normal))
        fun toWorld(l: Vector3) = center + dirToWorld(l)
        fun dirToWorld(l: Vector3) = right * l.x + up * l.y + normal * l.z
    }

    fun start(scope: CoroutineScope) {
        // 親を activity space に固定し、以降は親基準（Space.PARENT）で姿勢を設定する。
        // Space.ACTIVITY 指定の setPose は親の種類によって IllegalStateException になるため使わない。
        panel = PanelEntity.create(session, view, panelSize, "flick-keyboard", Pose.Identity, session.scene.activitySpace)
        panel.cornerRadius = 0f
        panel.setEnabled(false)

        val left = Hand.left(session)
        val right = Hand.right(session)
        job = scope.launch {
            combine(left.state, right.state) { l, r -> l to r }.collect { (l, r) ->
                val s = settings.value
                try {
                    if (s.keyboardOnLeft) onFrame(l, r, isLeftKeyboard = true, s) else onFrame(r, l, isLeftKeyboard = false, s)
                } catch (e: RuntimeException) {
                    // 1 フレームの失敗でアプリごと落とさない
                    Log.e(TAG, "frame update failed", e)
                }
            }
        }
    }

    fun resetMode() {
        detector.resetMode()
        view.invalidate()
    }

    fun stop() {
        job?.cancel()
        if (::panel.isInitialized) panel.parent = null
    }

    private fun onFrame(kbHand: Hand.State, typeHand: Hand.State, isLeftKeyboard: Boolean, s: KeyboardSettings) {
        val newTip = typeHand !== lastTypeHand
        lastTypeHand = typeHand
        detector.select(s.detector)
        val kbTracked = kbHand.trackingState == TrackingState.TRACKING
        val typeTracked = typeHand.trackingState == TrackingState.TRACKING
        val palm = if (kbTracked) palmFrame(kbHand, isLeftKeyboard)?.let(::smoothPalm) else null

        if (palm == null) {
            hide(newTip)
            publish(TrackingStatus(kbTracked, typeTracked))
            return
        }

        palmFacing = isFacingHead(palm)
        if (!palmFacing) {
            hide(newTip)
            publish(TrackingStatus(true, typeTracked, palmFacing = false))
            return
        }

        val kb = keyboardFrame(palm, s)
        val width = palmWidth * s.widthScale
        val height = palmLength * s.heightScale
        placePanel(kb, width, height)
        if (!newTip) return

        val tipPos = if (typeTracked) typeHand.handJoints[HandJointType.INDEX_TIP]?.translation else null
        val tip = tipPos?.let {
            val l = kb.toLocal(it)
            TipFrame(nowMs(), l.x / width + 0.5f, 0.5f - l.y / height, l.z, width, height, s.touchMeters)
        }
        if (tipPos != null && tip != null && tip.x in 0f..1f && tip.y in 0f..1f) {
            palm.toLocal(tipPos).z.takeIf { it < 0.06f }?.let(onTipSample)
        }
        feed(tip)
        view.cursor = tip?.takeIf { it.h < 0.06f && it.inReach }?.let { FlickKeyboardView.Cursor(it.x, it.y, it.h.coerceAtLeast(0f), detector.touching) }

        view.invalidate()
        publish(TrackingStatus(true, typeTracked, palmFacing = true, fingerHeightMm = tip?.h?.times(1000f), touching = detector.touching))
    }

    private fun feed(tip: TipFrame?) {
        val action = detector.onFrame(tip)
        onTipFrame(tip?.tMs ?: nowMs(), tip, detector.touching, action)
        action?.let(onAction)
    }

    private fun nowMs() = System.nanoTime() / 1_000_000

    /**
     * 手首・中指付け根・人差し指/小指付け根の位置から手のひらの面を求める。
     * 中心は手首と中指付け根の中間（指を除いた手のひらの中央）。
     */
    private fun palmFrame(state: Hand.State, isLeft: Boolean): Frame? {
        val j = state.handJoints
        val wrist = j[HandJointType.WRIST]?.translation ?: return null
        val middle = j[HandJointType.MIDDLE_PROXIMAL]?.translation ?: return null
        val index = j[HandJointType.INDEX_PROXIMAL]?.translation ?: return null
        val little = j[HandJointType.LITTLE_PROXIMAL]?.translation ?: return null

        val up0 = (middle - wrist).toNormalized()
        val across = index - little // 小指 → 人差し指
        // 手のひら側の法線。左手は up × across、右手は across × up が手のひらから外向き（実機で確認）。
        val normal = (if (isLeft) up0.cross(across) else across.cross(up0)).toNormalized()
        val right = up0.cross(normal).toNormalized()
        val up = normal.cross(right)

        // 指の付け根の関節中心どうしの距離は手のひらの幅より狭いので少し広げる。
        // 縦は手首〜中指付け根の 8 割に収めて、指にかからないようにする。
        palmWidth += (across.length * PALM_WIDTH_FACTOR - palmWidth) * SIZE_SMOOTHING
        palmLength += ((middle - wrist).length * PALM_LENGTH_FACTOR - palmLength) * SIZE_SMOOTHING
        return Frame(Vector3.lerp(wrist, middle, 0.5f), right, up, normal)
    }

    private fun smoothPalm(f: Frame): Frame {
        val p = smoothedPalm
        val out = if (p == null) f else {
            val a = SMOOTHING
            val normal = Vector3.lerp(p.normal, f.normal, a).toNormalized()
            val up0 = Vector3.lerp(p.up, f.up, a)
            val right = up0.cross(normal).toNormalized()
            Frame(Vector3.lerp(p.center, f.center, a), right, normal.cross(right), normal)
        }
        smoothedPalm = out
        return out
    }

    private fun keyboardFrame(palm: Frame, s: KeyboardSettings): Frame {
        val q = s.rotation
        return Frame(
            center = palm.toWorld(s.offset),
            right = palm.dirToWorld(q * Vector3(1f, 0f, 0f)),
            up = palm.dirToWorld(q * Vector3(0f, 1f, 0f)),
            normal = palm.dirToWorld(q * Vector3(0f, 0f, 1f)),
        )
    }

    /** 手のひらが頭の方を向いているか（ヒステリシス付き）。頭の位置が取れなければ常に true。 */
    private fun isFacingHead(f: Frame): Boolean {
        val device = runCatching { ArDevice.getInstance(session).state.value }.getOrNull()
        if (device == null || device.trackingState != TrackingState.TRACKING) return true
        val toHead = (device.devicePose.translation - f.center).toNormalized()
        val cos = f.normal.dot(toHead)
        return if (palmFacing) cos > FACING_HIDE_COS else cos > FACING_SHOW_COS
    }

    /** キーボードのパネルを置き、手のひらの大きさに合わせてサイズを変える。 */
    private fun placePanel(kb: Frame, width: Float, height: Float) {
        if (abs(width - panelSize.width) > 0.001f || abs(height - panelSize.height) > 0.001f) {
            panelSize = FloatSize2d(width, height)
            panel.size = panelSize
        }
        val poseInPerception = Pose(kb.center, quaternionFromBasis(kb.right, kb.up, kb.normal))
        val scene = session.scene
        val pose = scene.perceptionSpace.transformPoseTo(poseInPerception, scene.activitySpace)
        panel.setPose(pose, Space.PARENT)
        if (!panel.isEnabled()) panel.setEnabled(true)
    }

    private fun hide(newTip: Boolean) {
        if (newTip) feed(null)
        view.cursor = null
        if (::panel.isInitialized && panel.isEnabled()) panel.setEnabled(false)
    }

    private fun publish(status: TrackingStatus) {
        val now = System.currentTimeMillis()
        if (now - lastStatusAt < 100) return
        lastStatusAt = now
        onStatus(status)
    }

    companion object {
        private const val TAG = "HandKeyboard"
        private const val BASE_SIZE = 0.08f
        private const val PALM_WIDTH_FACTOR = 1.15f
        private const val PALM_LENGTH_FACTOR = 0.8f
        private const val SIZE_SMOOTHING = 0.05f
        private const val SMOOTHING = 0.6f
        private const val FACING_SHOW_COS = 0.45f
        private const val FACING_HIDE_COS = 0.2f
    }
}

/** 正規直交基底（列ベクトル = ローカル X, Y, Z）を四元数に変換する。 */
internal fun quaternionFromBasis(x: Vector3, y: Vector3, z: Vector3): Quaternion {
    val m00 = x.x; val m01 = y.x; val m02 = z.x
    val m10 = x.y; val m11 = y.y; val m12 = z.y
    val m20 = x.z; val m21 = y.z; val m22 = z.z
    val trace = m00 + m11 + m22
    return if (trace > 0f) {
        val s = sqrt(trace + 1f) * 2f
        Quaternion((m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s, 0.25f * s)
    } else if (m00 > m11 && m00 > m22) {
        val s = sqrt(1f + m00 - m11 - m22) * 2f
        Quaternion(0.25f * s, (m01 + m10) / s, (m02 + m20) / s, (m21 - m12) / s)
    } else if (m11 > m22) {
        val s = sqrt(1f + m11 - m00 - m22) * 2f
        Quaternion((m01 + m10) / s, 0.25f * s, (m12 + m21) / s, (m02 - m20) / s)
    } else {
        val s = sqrt(1f + m22 - m00 - m11) * 2f
        Quaternion((m02 + m20) / s, (m12 + m21) / s, 0.25f * s, (m10 - m01) / s)
    }
}
