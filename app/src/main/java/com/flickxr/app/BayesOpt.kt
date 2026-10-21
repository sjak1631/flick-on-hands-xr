package com.flickxr.app

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * ガウス過程（Matern 5/2 カーネル）と期待改善量（EI）によるベイズ最適化。最大化する。
 * 入力は [0, 1]^dims に正規化して扱う。XR に依存しないので JVM 単体テストできる。
 *
 * 人が評価するので試行は数十回まで。行列が小さいので Cholesky 分解で素直に解き、
 * カーネルの長さスケールとノイズは周辺尤度が最大になる値をグリッドから選ぶ。
 *
 * @param initialPoints 最初にモデルを使わずに試す点の数（ラテン超方格で散らす）
 */
class BayesOpt(
    private val dims: Int,
    initialPoints: Int,
    private val random: Random = Random.Default,
) {
    private val xs = mutableListOf<DoubleArray>()
    private val ys = mutableListOf<Double>()
    private val initial = ArrayDeque(latinHypercube(initialPoints))
    private var model: Gp? = null

    val size: Int get() = xs.size

    fun add(x: DoubleArray, y: Double) {
        xs += x.copyOf()
        ys += y
        model = null
    }

    /** 次に試す点。 */
    fun suggest(): DoubleArray {
        initial.removeFirstOrNull()?.let { return it }
        if (xs.size < 2) return DoubleArray(dims) { random.nextDouble() }
        val gp = fit()
        val best = xs.maxOf { gp.predict(it).first }
        return candidates(gp).maxBy { gp.expectedImprovement(it, best) }
    }

    /** 観測した点のうち事後平均が最大の点と、その予測値。評価にノイズが乗るので、観測値の最大ではなく平均で選ぶ。 */
    fun best(): Pair<DoubleArray, Double>? {
        if (xs.isEmpty()) return null
        if (xs.size < 2) return xs[0].copyOf() to ys[0]
        val gp = fit()
        return xs.map { it to gp.predict(it).first }.maxBy { it.second }.let { it.first.copyOf() to it.second }
    }

    private fun fit(): Gp = model ?: run {
        val y = ys.toDoubleArray()
        LENGTHSCALES.flatMap { l -> NOISES.map { n -> Gp(xs, y, l, n) } }.maxBy { it.logMarginal }
    }.also { model = it }

    /** 一様な乱数点と、良い点のまわりの点。EI はその中で最大のものを選ぶ。 */
    private fun candidates(gp: Gp): List<DoubleArray> {
        val top = xs.sortedByDescending { gp.predict(it).first }.take(3)
        return List(RANDOM_CANDIDATES) { DoubleArray(dims) { random.nextDouble() } } +
            top.flatMap { c -> List(LOCAL_CANDIDATES) { DoubleArray(dims) { i -> (c[i] + gaussian() * LOCAL_SIGMA).coerceIn(0.0, 1.0) } } }
    }

    private fun latinHypercube(n: Int): List<DoubleArray> {
        val perms = List(dims) { (0 until n).shuffled(random) }
        return List(n) { i -> DoubleArray(dims) { d -> (perms[d][i] + random.nextDouble()) / n } }
    }

    private fun gaussian(): Double = sqrt(-2.0 * ln(1.0 - random.nextDouble())) * cos(2.0 * PI * random.nextDouble())

    private class Gp(val xs: List<DoubleArray>, ys: DoubleArray, val lengthscale: Double, noise: Double) {
        private val mean = ys.average()
        private val std = sqrt(ys.sumOf { (it - mean) * (it - mean) } / ys.size).takeIf { it > 1e-9 } ?: 1.0
        private val n = xs.size
        private val chol: Array<DoubleArray>
        private val alpha: DoubleArray
        val logMarginal: Double

        init {
            val yn = DoubleArray(n) { (ys[it] - mean) / std }
            val k = Array(n) { i -> DoubleArray(n) { j -> kernel(xs[i], xs[j]) + if (i == j) noise + JITTER else 0.0 } }
            chol = cholesky(k)
            alpha = backward(chol, forward(chol, yn))
            logMarginal = -0.5 * yn.indices.sumOf { yn[it] * alpha[it] } - (0 until n).sumOf { ln(chol[it][it]) } - 0.5 * n * ln(2 * PI)
        }

        /** 事後平均と標準偏差（元の単位）。 */
        fun predict(x: DoubleArray): Pair<Double, Double> {
            val ks = DoubleArray(n) { kernel(x, xs[it]) }
            val mu = ks.indices.sumOf { ks[it] * alpha[it] }
            val v = forward(chol, ks)
            val variance = (1.0 - v.sumOf { it * it }).coerceAtLeast(0.0)
            return (mean + mu * std) to sqrt(variance) * std
        }

        fun expectedImprovement(x: DoubleArray, best: Double): Double {
            val (mu, sd) = predict(x)
            if (sd < 1e-12) return 0.0
            val d = mu - best - XI * std
            val z = d / sd
            return d * normalCdf(z) + sd * normalPdf(z)
        }

        private fun kernel(a: DoubleArray, b: DoubleArray): Double {
            val r = sqrt(a.indices.sumOf { (a[it] - b[it]) * (a[it] - b[it]) }) / lengthscale
            val s = sqrt(5.0) * r
            return (1 + s + s * s / 3) * exp(-s)
        }
    }

    companion object {
        private val LENGTHSCALES = listOf(0.1, 0.15, 0.25, 0.4, 0.6, 1.0, 1.5)
        /** 標準化した評価値に対するノイズ分散 */
        private val NOISES = listOf(0.01, 0.05, 0.15, 0.4)
        private const val JITTER = 1e-8
        /** EI の探索寄りの度合い（評価値の標準偏差に対する比） */
        private const val XI = 0.01
        private const val RANDOM_CANDIDATES = 2000
        private const val LOCAL_CANDIDATES = 200
        private const val LOCAL_SIGMA = 0.08

        private fun cholesky(a: Array<DoubleArray>): Array<DoubleArray> {
            val n = a.size
            val l = Array(n) { DoubleArray(n) }
            for (i in 0 until n) for (j in 0..i) {
                var s = a[i][j]
                for (k in 0 until j) s -= l[i][k] * l[j][k]
                l[i][j] = if (i == j) sqrt(s.coerceAtLeast(1e-12)) else s / l[j][j]
            }
            return l
        }

        /** L y = b */
        private fun forward(l: Array<DoubleArray>, b: DoubleArray): DoubleArray {
            val y = DoubleArray(b.size)
            for (i in b.indices) {
                var s = b[i]
                for (k in 0 until i) s -= l[i][k] * y[k]
                y[i] = s / l[i][i]
            }
            return y
        }

        /** L^T x = y */
        private fun backward(l: Array<DoubleArray>, y: DoubleArray): DoubleArray {
            val x = DoubleArray(y.size)
            for (i in y.indices.reversed()) {
                var s = y[i]
                for (k in i + 1 until y.size) s -= l[k][i] * x[k]
                x[i] = s / l[i][i]
            }
            return x
        }

        private fun normalPdf(z: Double) = exp(-0.5 * z * z) / sqrt(2 * PI)

        /** 標準正規分布の累積分布関数（Abramowitz & Stegun 7.1.26 による erf の近似） */
        internal fun normalCdf(z: Double): Double {
            val x = abs(z) / sqrt(2.0)
            val t = 1 / (1 + 0.3275911 * x)
            val erf = 1 - t * (0.254829592 + t * (-0.284496736 + t * (1.421413741 + t * (-1.453152027 + t * 1.061405429)))) * exp(-x * x)
            return 0.5 * (1 + if (z >= 0) erf else -erf)
        }
    }
}
