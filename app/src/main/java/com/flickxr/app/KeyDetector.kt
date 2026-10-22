package com.flickxr.app

import kotlin.math.abs
import kotlin.math.hypot

/**
 * 1 フレーム分の指先。キーボード面の座標系（x: 左→右、y: 上→下 の正規化座標、h: 面からの高さ m）と、
 * そのときのキーボードの実寸・接触しきい値。記録して後から判定を再生できるように、判定に要るものはすべてここに入れる。
 */
data class TipFrame(
    val tMs: Long,
    val x: Float,
    val y: Float,
    val h: Float,
    val widthMeters: Float,
    val heightMeters: Float,
    val touchMeters: Float,
) {
    /** キーボードのまわり（少しはみ出しても可）で、面より下に行きすぎていない */
    val inReach: Boolean get() = x in -0.15f..1.15f && y in -0.15f..1.15f && h > -0.04f

    /** フリックとみなす移動量：キー 1 個の短辺の 4 割 */
    val flickThresholdMeters: Float get() = minOf(widthMeters / FlickLayout.COLS, heightMeters / FlickLayout.ROWS) * 0.4f
}

enum class DetectorType(val label: String) { SLIDE("スライド"), THRESHOLD("押してずらす"), LEARNED("学習") }

/** LEARNED はモデル（[StrokeModel.instance]）が読めていなければスライド判定で代わりにする */
fun DetectorType.create(): KeyDetector = when (this) {
    DetectorType.SLIDE -> SlideDetector()
    DetectorType.THRESHOLD -> ThresholdDetector()
    DetectorType.LEARNED -> StrokeModel.instance?.let(::LearnedDetector) ?: SlideDetector()
}

/** 指先の動きから打鍵を判定する。XR に依存しないので、記録した軌跡を JVM で再生できる。 */
interface KeyDetector {
    val mode: InputMode
    val rows: List<List<FlickKey>> get() = FlickLayout.rows(mode)
    /** 描画用：押下中のキーと方向 */
    val press: FlickEngine.Press?
    val touching: Boolean

    /** 1 フレーム分の入力。確定したアクションを返す。tip == null は指先を見失ったか、キーボードが非表示。 */
    fun onFrame(tip: TipFrame?): KeyAction?

    /** 押下を破棄して、かなモードに戻す。 */
    fun resetMode()
}

/**
 * 高さのしきい値で「押す → ずらす → 離す」を区切る判定。指先が面 + 接触しきい値より下がったらその位置のキーを押し、
 * 押している間のずれで方向を決め、しきい値 + [RELEASE_MARGIN] より上がったら確定する。
 */
class ThresholdDetector : KeyDetector {
    private val engine = FlickEngine(0.08f, 0.08f)

    override val mode: InputMode get() = engine.mode
    override val press: FlickEngine.Press? get() = engine.press
    override var touching = false
        private set

    override fun onFrame(tip: TipFrame?): KeyAction? {
        if (tip == null) {
            engine.cancel()
            touching = false
            return null
        }
        engine.widthMeters = tip.widthMeters
        engine.heightMeters = tip.heightMeters
        engine.flickThresholdMeters = tip.flickThresholdMeters
        // ヒステリシス付きの接触判定（トラッキングの揺れで連打にならないように）
        touching = if (touching) tip.inReach && tip.h < tip.touchMeters + RELEASE_MARGIN else tip.inReach && tip.h < tip.touchMeters
        return engine.onFrame(tip.x, tip.y, touching)
    }

    override fun resetMode() {
        engine.resetMode()
        touching = false
    }

    companion object {
        const val RELEASE_MARGIN = 0.008f
    }
}

/**
 * 指を滑らせるフリックを、手のひらに沿った軌跡から判定する。
 *
 * - 動作：指先が「面 + 接触しきい値」より下がってから、[exitMargin] 上がって [tailMs] 経つまで
 * - キー：触れ始めの位置。滑らせながら押し込むので、最も低くなるのは滑らせた先になる（記録で確認）。最も低い点では決めない
 * - 方向：触れ始めから最も遠くまで動いた点への向き。キー 1 個の短辺の [flickRatio] 倍より動いたらフリック。
 *   単押しでも押している間に 1 cm ほど流れることがあるので、しきい値の判定（0.4 倍）より大きくしている（記録で確認）。離した瞬間の点も含めるので、滑らせながら離しても方向が取れる。
 *   離したあとの [tailMs] も含められるが、記録では指を上げるときの動きを拾って悪くなったので初期値は 0
 * - 指先を見失ったら、そこまでの軌跡で確定する（指が手のひらに重なると見失いやすいため）
 * - 押し直し：触れたまま、最も低い位置から [repressMeters] 上がってまた [repressMeters] 下がったら、そこで確定して次の打鍵を始める。
 *   ⌫ の連打や「かき」のように同じキーを続けて打つとき、「離した」の高さまで指を上げないことが多いため。
 *   触れ始めからほとんど横に動いていない（キーの短辺の 0.4 倍以内の）ときだけ分ける
 * - 浅い単押し：接触しきい値まで届かなくても、すばやく下げて戻したら（[dipWindowMs] のうちに [dipFall] 以上下げ、
 *   一番下が「面 + 接触しきい値 + [dipBand]」以内で、そこから [dipRise] 以上戻した）、一番下の位置のキーの単押しとする。
 *   記録では、単押しは奥まで押し込まれず、しきい値の少し上で折り返すことが多かったため。
 *   ただし実機では、押す前のためらいや面の近くでの上下の揺れまで単押しにしてしまい悪くなったので、初期値は無効
 */
class SlideDetector(
    private val exitMargin: Float = ThresholdDetector.RELEASE_MARGIN,
    private val tailMs: Long = 0,
    private val flickRatio: Float = 0.7f,
    private val repressMeters: Float = 0.006f,
    private val shallowTaps: Boolean = false,
    private val dipBand: Float = 0.013f,
    private val dipFall: Float = 0.008f,
    private val dipRise: Float = 0.006f,
    private val dipWindowMs: Long = 300,
) : KeyDetector {
    private class Point(val t: Long, val x: Float, val y: Float, val h: Float)

    private val points = mutableListOf<Point>()
    /** 触れていない間の最近の指先（浅い単押しを見つけるため） */
    private val hover = ArrayDeque<Point>()
    private var liftedAt: Long? = null
    private var width = 0.08f
    private var height = 0.08f
    private var threshold = 0.01f
    /** 押し直しの判定：今の打鍵での最も低い高さ、そこから上がったか、上がってからの最も高い高さ */
    private var minH = 0f
    private var rose = false
    private var peakH = 0f

    override var mode = InputMode.KANA
        private set
    override var press: FlickEngine.Press? = null
        private set
    override val touching: Boolean get() = points.isNotEmpty() && liftedAt == null

    override fun onFrame(tip: TipFrame?): KeyAction? {
        // 見失ったのが動作の途中なら、そこまでの軌跡で確定する（指が手のひらに重なると見失いやすいため）
        if (tip == null) {
            hover.clear()
            return finish()
        }
        width = tip.widthMeters
        height = tip.heightMeters
        threshold = minOf(tip.widthMeters / FlickLayout.COLS, tip.heightMeters / FlickLayout.ROWS) * flickRatio
        val enter = tip.touchMeters
        val down = tip.inReach && tip.h < enter

        if (points.isEmpty()) {
            // キーボードの外では始めない（外から滑り込んだら、キーの上に来た時点から始める）
            if (down && FlickLayout.cellAt(tip.x, tip.y) != null) {
                hover.clear()
                add(tip)
                return null
            }
            if (!tip.inReach) {
                hover.clear()
                return null
            }
            return if (shallowTaps) shallowTap(tip) else null
        }
        val lifted = liftedAt
        if (lifted == null) {
            if (!tip.inReach || tip.h > enter + exitMargin) {
                liftedAt = tip.tMs
            } else if (repressMeters > 0f && isRepress(tip)) {
                val action = finish()
                if (FlickLayout.cellAt(tip.x, tip.y) != null) add(tip)
                return action
            }
        } else if (down) {
            // 余韻の間に次の打鍵が始まった：今の動作を確定し、新しい動作を始める
            val action = finish()
            if (FlickLayout.cellAt(tip.x, tip.y) != null) add(tip)
            return action
        }
        add(tip)
        val l = liftedAt
        return if (l != null && tip.tMs - l >= tailMs) finish() else null
    }

    override fun resetMode() {
        clear()
        hover.clear()
        mode = InputMode.KANA
    }

    private fun shallowTap(tip: TipFrame): KeyAction? {
        val now = Point(tip.tMs, tip.x, tip.y, tip.h)
        hover.addLast(now)
        while (hover.first().t < now.t - 2 * dipWindowMs) hover.removeFirst()
        val bottom = hover.filter { it.t >= now.t - dipWindowMs }.minBy { it.h }
        if (bottom === now || bottom.h > tip.touchMeters + dipBand || now.h - bottom.h < dipRise) return null
        val peak = hover.filter { it.t >= bottom.t - dipWindowMs && it.t < bottom.t }.maxOfOrNull { it.h } ?: return null
        // 戻すときに上より横に動いていたら、キーの間を移動しているだけとみなす
        val lateral = dist(bottom, now)
        if (peak - bottom.h < dipFall || lateral >= threshold || lateral >= now.h - bottom.h) return null
        val (row, col) = FlickLayout.cellAt(bottom.x, bottom.y) ?: return null
        hover.clear()
        return commit(rows[row][col], FlickDir.NONE)
    }

    /** 触れたまま上がってまた下がったか。触れ始めからほとんど横に動いていないときだけ見る */
    private fun isRepress(tip: TipFrame): Boolean {
        val h = tip.h
        val start = points.first()
        val stayRadius = minOf(width / FlickLayout.COLS, height / FlickLayout.ROWS) * 0.4f
        if (dist(start, Point(tip.tMs, tip.x, tip.y, h)) >= stayRadius) {
            rose = false
            return false
        }
        if (!rose) {
            minH = minOf(minH, h)
            if (h - minH >= repressMeters) {
                rose = true
                peakH = h
            }
            return false
        }
        peakH = maxOf(peakH, h)
        return peakH - h >= repressMeters
    }

    private fun add(tip: TipFrame) {
        if (points.isEmpty()) {
            minH = tip.h
            rose = false
        }
        points += Point(tip.tMs, tip.x, tip.y, tip.h)
        press = currentPress()
    }

    private fun currentPress(): FlickEngine.Press? {
        val start = points.first()
        val (row, col) = FlickLayout.cellAt(start.x, start.y) ?: return null
        val far = points.maxBy { dist(start, it) }
        return FlickEngine.Press(row, col, rows[row][col], start.x, start.y, direction(start, far))
    }

    private fun dist(a: Point, b: Point) = hypot((b.x - a.x) * width, (b.y - a.y) * height)

    private fun direction(from: Point, to: Point): FlickDir {
        if (dist(from, to) < threshold) return FlickDir.NONE
        val dx = (to.x - from.x) * width
        val dy = (to.y - from.y) * height
        return if (abs(dx) > abs(dy)) {
            if (dx < 0) FlickDir.LEFT else FlickDir.RIGHT
        } else {
            if (dy < 0) FlickDir.UP else FlickDir.DOWN
        }
    }

    private fun finish(): KeyAction? {
        val p = press
        clear()
        if (p == null) return null
        return commit(p.key, p.dir)
    }

    private fun commit(key: FlickKey, dir: FlickDir): KeyAction {
        val action = key.actionFor(dir)
        if (action == KeyAction.NextMode) mode = InputMode.entries[(mode.ordinal + 1) % InputMode.entries.size]
        return action
    }

    private fun clear() {
        points.clear()
        liftedAt = null
        press = null
    }
}

/** 設定で判定を切り替える。切り替えたら押下を破棄して、かなモードから始める。 */
class SwitchingDetector(type: DetectorType) : KeyDetector {
    var type = type
        private set
    private var current = type.create()

    override val mode: InputMode get() = current.mode
    override val press: FlickEngine.Press? get() = current.press
    override val touching: Boolean get() = current.touching

    fun select(t: DetectorType) {
        if (t == type) return
        type = t
        current = t.create()
    }

    override fun onFrame(tip: TipFrame?): KeyAction? = current.onFrame(tip)
    override fun resetMode() = current.resetMode()
}
