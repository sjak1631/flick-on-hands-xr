package com.flickxr.app

import java.io.InputStream
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min

/**
 * 学習した打鍵の判定モデル（ml/export_model.py が書き出す勾配ブースティングの木）。
 * 候補を「打鍵ではない（X）／単押し（N）／フリック（F）」に分ける。フリックの方向はモデルではなく軌跡の向きで決める。
 */
class StrokeModel private constructor(
    private val featureIndex: IntArray,
    val classes: List<String>,
    /** 最も遠くまで動いた距離（キー）がこれ以上なら、モデルにかかわらずフリック */
    val overrideKeys: Float,
    private val trees: List<Tree>,
) {
    private class Tree(val cls: Int, val kind: BooleanArray, val feature: IntArray, val value: DoubleArray, val left: IntArray, val right: IntArray, val defaultLeft: BooleanArray) {
        fun eval(x: FloatArray): Double {
            var i = 0
            while (!kind[i]) {
                val v = x[feature[i]]
                i = if (v.isNaN()) (if (defaultLeft[i]) left[i] else right[i]) else if (v.toDouble() <= value[i]) left[i] else right[i]
            }
            return value[i]
        }
    }

    /** [StrokeFeatures.compute] の値から、クラスごとの確率 */
    fun predict(features: FloatArray): DoubleArray {
        val x = FloatArray(featureIndex.size) { features[featureIndex[it]] }
        val raw = DoubleArray(classes.size)
        for (t in trees) raw[t.cls] += t.eval(x)
        val max = raw.max()
        val e = raw.map { exp(it - max) }
        val sum = e.sum()
        return DoubleArray(raw.size) { e[it] / sum }
    }

    companion object {
        /** 端末で使うモデル（起動時に assets から読む）。読めなければ null で、学習の判定はスライド判定で代わりにする */
        @Volatile var instance: StrokeModel? = null

        fun parse(input: InputStream): StrokeModel {
            val lines = input.bufferedReader().readLines().filter { it.isNotBlank() }.iterator()
            fun next(tag: String): List<String> {
                val p = lines.next().trim().split(" ")
                require(p[0] == tag) { "$tag のはずが ${p[0]}" }
                return p.drop(1)
            }
            val names = next("features")
            val featureIndex = IntArray(names.size) { i ->
                StrokeFeatures.NAMES.indexOf(names[i]).also { require(it >= 0) { "未知の特徴量 ${names[i]}" } }
            }
            val classes = next("classes")
            val override = next("override")[0].toFloat()
            val trees = mutableListOf<Tree>()
            while (lines.hasNext()) {
                val (cls, n) = next("tree").map { it.toInt() }
                val kind = BooleanArray(n)
                val feature = IntArray(n)
                val value = DoubleArray(n)
                val left = IntArray(n)
                val right = IntArray(n)
                val defaultLeft = BooleanArray(n)
                for (i in 0 until n) {
                    val p = lines.next().trim().split(" ")
                    if (p[0] == "leaf") {
                        kind[i] = true
                        value[i] = p[1].toDouble()
                    } else {
                        feature[i] = p[1].toInt()
                        value[i] = p[2].toDouble()
                        left[i] = p[3].toInt()
                        right[i] = p[4].toInt()
                        defaultLeft[i] = p[5] == "1"
                    }
                }
                trees += Tree(cls, kind, feature, value, left, right, defaultLeft)
            }
            return StrokeModel(featureIndex, classes, override, trees)
        }
    }
}

/**
 * 学習したモデルで打鍵を判定する。候補は [StrokeCandidates]、種類（打鍵ではない／単押し／フリック）はモデル、
 * フリックの方向は触れ始めから最も遠くまで動いた向きで決める。
 */
class LearnedDetector(private val model: StrokeModel) : KeyDetector {
    private val candidates = StrokeCandidates()

    override var mode = InputMode.KANA
        private set
    override val touching: Boolean get() = candidates.current.isNotEmpty()

    /** 描画用：触れている間は、触れ始めのキーと今の向き（スライド判定と同じ 0.7 キー）を出す */
    override val press: FlickEngine.Press?
        get() {
            val p = candidates.current
            val a = p.firstOrNull() ?: return null
            val (row, col) = FlickLayout.cellAt(a.x, a.y) ?: return null
            val far = p.maxBy { hypot((it.x - a.x) * a.widthMeters, (it.y - a.y) * a.heightMeters) }
            val key = min(a.widthMeters / FlickLayout.COLS, a.heightMeters / FlickLayout.ROWS)
            return FlickEngine.Press(row, col, rows[row][col], a.x, a.y, direction(far.x - a.x, far.y - a.y, a, key * 0.7f))
        }

    override fun onFrame(tip: TipFrame?): KeyAction? {
        val s = candidates.onFrame(tip) ?: return null
        val a = s.points.first()
        val (row, col) = FlickLayout.cellAt(a.x, a.y) ?: return null
        val f = StrokeFeatures.compute(s)
        val farDist = f[StrokeFeatures.NAMES.indexOf("farDist")]
        val cls = if (farDist >= model.overrideKeys) "F" else model.classes[model.predict(f).withIndex().maxBy { it.value }.index]
        val far = s.points.maxBy { hypot((it.x - a.x) * a.widthMeters, (it.y - a.y) * a.heightMeters) }
        val dir = when (cls) {
            "X" -> return null
            "N" -> FlickDir.NONE
            else -> direction(far.x - a.x, far.y - a.y, a, 0f)
        }
        val action = rows[row][col].actionFor(dir)
        if (action == KeyAction.NextMode) mode = InputMode.entries[(mode.ordinal + 1) % InputMode.entries.size]
        return action
    }

    override fun resetMode() {
        candidates.reset()
        mode = InputMode.KANA
    }

    private fun direction(dxNorm: Float, dyNorm: Float, a: TipFrame, threshold: Float): FlickDir {
        val dx = dxNorm * a.widthMeters
        val dy = dyNorm * a.heightMeters
        if (hypot(dx, dy) < threshold || (dx == 0f && dy == 0f)) return FlickDir.NONE
        return if (abs(dx) > abs(dy)) (if (dx < 0) FlickDir.LEFT else FlickDir.RIGHT) else (if (dy < 0) FlickDir.UP else FlickDir.DOWN)
    }
}
