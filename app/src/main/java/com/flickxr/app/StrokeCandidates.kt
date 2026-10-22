package com.flickxr.app

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 学習で判定するための打鍵の候補。打鍵かどうか・どの方向かは学習したモデルに決めさせる。学習と端末での推論で同じものを使う。
 *
 * - 普通の候補：[SlideDetector] と同じ区切り方（接触しきい値まで下がってから離すまで、押し直しで分ける）
 * - 浅い候補（[End.DIP]）：しきい値に届かず折り返した上下。[dipWindowMs] のうちに [dipFall] 以上下げ、一番下が
 *   しきい値 + [dipBand] 以内で、そこから [dipRise] 以上戻したもの。押す前のためらいなども入るので、ゆるめに拾ってモデルに任せる
 *
 * 始まりの高さそのものを上げると、打鍵の間も指がその高さより下に残り、続けて打った打鍵が 1 つにつながってしまう（記録で確認）。
 */
class StrokeCandidates(
    private val enterOffset: Float = 0f,
    private val exitMargin: Float = ThresholdDetector.RELEASE_MARGIN,
    private val repressMeters: Float = 0.006f,
    /**
     * 横に動いたあとの押し直しの高さ（0 なら分けない）。続けて打つと、指を「離した」の高さまで上げずに次のキーへ移って
     * 押すので、2 打鍵が 1 つの候補につながる（単語のデータで確認）。フリックの途中の上下と区別するため、その場の押し直しより大きくする。
     * 単語のデータでは、取りこぼした打鍵が 71 → 12 に減り、フリックの正解率は下がらなかった（6〜12 mm でほぼ同じ）
     */
    private val moveRepressMeters: Float = 0.008f,
    private val preMs: Long = 150,
    private val dipBand: Float = 0.013f,
    private val dipFall: Float = 0.006f,
    private val dipRise: Float = 0.004f,
    private val dipWindowMs: Long = 300,
) {
    /** 候補 1 つ。points は区間中のフレーム、pre は始まる直前 [preMs] のフレーム */
    class Stroke(val pre: List<TipFrame>, val points: List<TipFrame>, val endedBy: End)

    enum class End { LIFT, REPRESS, LOST, OUT_OF_REACH, DIP }

    /** 触れていない間の最近のフレーム（[preMs] と浅い候補の [dipWindowMs] の長い方だけ残す） */
    private val recent = ArrayDeque<TipFrame>()
    private val points = mutableListOf<TipFrame>()
    private var pre: List<TipFrame> = emptyList()
    private var minH = 0f
    private var rose = false
    private var peakH = 0f

    /** 今触れている候補のフレーム（描画用）。触れていなければ空 */
    val current: List<TipFrame> get() = points

    /** 1 フレーム分の入力。候補が終わったらそれを返す。 */
    fun onFrame(tip: TipFrame?): Stroke? {
        if (tip == null) {
            recent.clear()
            return if (points.isNotEmpty()) finish(End.LOST) else null
        }
        val enter = tip.touchMeters + enterOffset
        if (points.isEmpty()) {
            if (tip.inReach && tip.h < enter && FlickLayout.cellAt(tip.x, tip.y) != null) {
                pre = recent.filter { it.tMs >= tip.tMs - preMs }
                start(tip)
                return null
            }
            if (!tip.inReach) {
                recent.clear()
                return null
            }
            remember(tip)
            return dip(tip)
        }
        if (!tip.inReach) return finish(End.OUT_OF_REACH).also { remember(tip) }
        if (tip.h > enter + exitMargin) {
            points += tip
            return finish(End.LIFT).also { remember(tip) }
        }
        if (repressMeters > 0f && isRepress(tip)) {
            val s = finish(End.REPRESS)
            pre = points.lastOrNull()?.let { listOf(it) } ?: emptyList()
            if (FlickLayout.cellAt(tip.x, tip.y) != null) start(tip)
            return s
        }
        points += tip
        return null
    }

    fun reset() {
        recent.clear()
        points.clear()
    }

    private fun start(tip: TipFrame) {
        // 打鍵の前の軌跡は pre に移したので捨てる（離したあとの上がりを、浅い候補の下げと取り違えないように）
        recent.clear()
        points.clear()
        points += tip
        minH = tip.h
        rose = false
    }

    private fun remember(tip: TipFrame) {
        recent.addLast(tip)
        val keep = max(preMs, 2 * dipWindowMs)
        while (recent.first().tMs < tip.tMs - keep) recent.removeFirst()
    }

    /** 浅い候補：一番下から戻ったところで、下げ始め（直前の一番高い点）から今までを候補にする */
    private fun dip(now: TipFrame): Stroke? {
        val window = recent.filter { it.tMs >= now.tMs - dipWindowMs }
        val bottom = window.minBy { it.h }
        if (bottom === now || bottom.h > now.touchMeters + dipBand || now.h - bottom.h < dipRise) return null
        val before = recent.filter { it.tMs >= bottom.tMs - dipWindowMs && it.tMs < bottom.tMs }
        val top = before.maxByOrNull { it.h } ?: return null
        if (top.h - bottom.h < dipFall || FlickLayout.cellAt(bottom.x, bottom.y) == null) return null
        // 一番下を始まりとする（キーと移動量はそこから測る）。そこまでの下げは pre に入れる
        val points = recent.filter { it.tMs >= bottom.tMs }
        val pre = recent.filter { it.tMs >= top.tMs && it.tMs < bottom.tMs }
        recent.clear()
        return Stroke(pre, points, End.DIP)
    }

    /**
     * 押し直し：上がってまた下がった。ほとんど横に動いていなければ [repressMeters]（[SlideDetector] と同じ）、
     * 横に動いたあとは [moveRepressMeters] で見る
     */
    private fun isRepress(tip: TipFrame): Boolean {
        val s = points.first()
        val stay = min(tip.widthMeters / FlickLayout.COLS, tip.heightMeters / FlickLayout.ROWS) * 0.4f
        val moved = hypot((tip.x - s.x) * tip.widthMeters, (tip.y - s.y) * tip.heightMeters) >= stay
        val r = if (moved) moveRepressMeters else repressMeters
        if (!rose) minH = min(minH, tip.h)
        if (r <= 0f) {
            rose = false
            return false
        }
        if (!rose) {
            if (tip.h - minH >= r) {
                rose = true
                peakH = tip.h
            }
            return false
        }
        peakH = max(peakH, tip.h)
        return peakH - tip.h >= r
    }

    private fun finish(end: End): Stroke {
        val s = Stroke(pre, points.toList(), end)
        points.clear()
        pre = emptyList()
        return s
    }
}

/**
 * 候補 1 つの特徴量。長さはキー 1 個の短辺（key）や mm、時間は ms。高さは接触しきい値からの高さ（mm）。
 * 学習（Python）と推論（端末）で同じ値になるよう、計算はここだけで行う。
 */
object StrokeFeatures {
    val NAMES: List<String> = listOf(
        "durMs", "nFrames", "endedByLift", "endedByRepress", "endedByLost", "isDip",
        "isFunctionKey", "cellX", "cellY",
        "hStart", "hMin", "hEnd", "hMean", "tMinFrac", "preFall", "preDescentSpeed", "liftSpeed",
        "farDx", "farDy", "farDist", "farAngleSin", "farAngleCos", "endDx", "endDy", "endDist",
        "pathLen", "maxSpeed", "tFar", "tCross04", "tCross07",
        "earlyDx", "earlyDy", "approachVx", "approachVy", "keyMm",
    )

    fun compute(s: StrokeCandidates.Stroke): FloatArray {
        val p = s.points
        val a = p.first()
        val key = min(a.widthMeters / FlickLayout.COLS, a.heightMeters / FlickLayout.ROWS)
        fun dx(f: TipFrame) = (f.x - a.x) * a.widthMeters / key
        fun dy(f: TipFrame) = (f.y - a.y) * a.heightMeters / key
        fun dist(f: TipFrame) = hypot(dx(f), dy(f))
        fun hRel(f: TipFrame) = (f.h - f.touchMeters) * 1000f
        val dur = (p.last().tMs - a.tMs).toFloat()
        val (row, col) = FlickLayout.cellAt(a.x, a.y) ?: (0 to 0)
        val keyDef = FlickLayout.rows(InputMode.KANA)[row][col]
        val minIdx = p.indices.minBy { p[it].h }
        val far = p.maxBy { dist(it) }
        val end = p.last()
        var path = 0f
        var maxSpeed = 0f
        for (i in 1 until p.size) {
            val step = hypot(dx(p[i]) - dx(p[i - 1]), dy(p[i]) - dy(p[i - 1]))
            path += step
            val dt = (p[i].tMs - p[i - 1].tMs).coerceAtLeast(1)
            maxSpeed = max(maxSpeed, step / dt * 1000f)
        }
        fun tCross(r: Float) = p.firstOrNull { dist(it) >= r }?.let { (it.tMs - a.tMs).toFloat() } ?: -1f
        val early = p.lastOrNull { it.tMs - a.tMs <= 60 } ?: a
        val pre = s.pre
        val preTop = pre.maxByOrNull { it.h }
        val preFirst = pre.firstOrNull()
        val preDt = preFirst?.let { (a.tMs - it.tMs).coerceAtLeast(1) }
        val prev = p.getOrNull(p.size - 2)
        val farAngle = atan2(-dy(far), dx(far))
        return floatArrayOf(
            dur, p.size.toFloat(),
            if (s.endedBy == StrokeCandidates.End.LIFT) 1f else 0f,
            if (s.endedBy == StrokeCandidates.End.REPRESS) 1f else 0f,
            if (s.endedBy == StrokeCandidates.End.LOST) 1f else 0f,
            if (s.endedBy == StrokeCandidates.End.DIP) 1f else 0f,
            if (keyDef.isFunction) 1f else 0f,
            a.x * FlickLayout.COLS - col, a.y * FlickLayout.ROWS - row,
            hRel(a), hRel(p[minIdx]), hRel(end), p.map(::hRel).average().toFloat(),
            if (dur > 0) (p[minIdx].tMs - a.tMs) / dur else 0f,
            preTop?.let { (it.h - p[minIdx].h) * 1000f } ?: 0f,
            if (preFirst != null && preDt != null) (preFirst.h - a.h) * 1000f / preDt * 1000f else 0f,
            if (prev != null) (end.h - prev.h) * 1000f / (end.tMs - prev.tMs).coerceAtLeast(1) * 1000f else 0f,
            dx(far), dy(far), dist(far), kotlin.math.sin(farAngle), kotlin.math.cos(farAngle),
            dx(end), dy(end), dist(end),
            path, maxSpeed, (far.tMs - a.tMs).toFloat(), tCross(0.4f), tCross(0.7f),
            dx(early), dy(early),
            if (preFirst != null && preDt != null) (a.x - preFirst.x) * a.widthMeters / key / preDt * 1000f else 0f,
            if (preFirst != null && preDt != null) (a.y - preFirst.y) * a.heightMeters / key / preDt * 1000f else 0f,
            key * 1000f,
        )
    }

    /** 今のルール（[SlideDetector] と同じ 0.7 キー）での方向。比較用 */
    fun ruleDir(s: StrokeCandidates.Stroke, ratio: Float = 0.7f): FlickDir {
        val p = s.points
        val a = p.first()
        val key = min(a.widthMeters / FlickLayout.COLS, a.heightMeters / FlickLayout.ROWS)
        val far = p.maxBy { hypot((it.x - a.x) * a.widthMeters, (it.y - a.y) * a.heightMeters) }
        val dx = (far.x - a.x) * a.widthMeters
        val dy = (far.y - a.y) * a.heightMeters
        if (hypot(dx, dy) < key * ratio) return FlickDir.NONE
        return if (abs(dx) > abs(dy)) (if (dx < 0) FlickDir.LEFT else FlickDir.RIGHT) else (if (dy < 0) FlickDir.UP else FlickDir.DOWN)
    }
}
