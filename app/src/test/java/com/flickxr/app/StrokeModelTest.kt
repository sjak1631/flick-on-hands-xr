package com.flickxr.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StrokeModelTest {
    /** Python（LightGBM）で出した確率と、Kotlin の推論が一致する */
    @Test fun matchesPython() {
        val model = load()
        val lines = javaClass.getResource("/stroke_model_parity.csv")!!.readText().lines().filter { it.isNotBlank() }
        val header = lines[0].split(",")
        var maxDiff = 0.0
        for (line in lines.drop(1)) {
            val v = line.split(",")
            val f = FloatArray(StrokeFeatures.NAMES.size) { i ->
                v[header.indexOf(StrokeFeatures.NAMES[i])].let { if (it.isEmpty()) Float.NaN else it.toFloat() }
            }
            val p = model.predict(f)
            for ((i, c) in model.classes.withIndex()) maxDiff = maxOf(maxDiff, kotlin.math.abs(p[i] - v[header.indexOf("p_$c")].toDouble()))
        }
        assertTrue("max |p_kotlin - p_python| = $maxDiff", maxDiff < 1e-3)
    }

    /**
     * 判定の流れ（種類はモデル、方向は軌跡の向き、打鍵ではないなら何も出さない）を、手書きの小さなモデルで確かめる。
     * 手書きのモデル：最も遠くまで動いた距離が 0.7 キー以上ならフリック、未満なら単押し、浅い候補は打鍵ではない
     */
    @Test fun learnedDetectorUsesModelForTypeAndTrajectoryForDirection() {
        val tiny = """
            features farDist isDip
            classes X N F
            override 1e9
            tree 0 3
            split 1 0.5 1 2 1
            leaf -10
            leaf 10
            tree 1 1
            leaf 0
            tree 2 3
            split 0 0.7 1 2 1
            leaf -5
            leaf 5
        """.trimIndent()
        val d = LearnedDetector(StrokeModel.parse(tiny.byteInputStream()))
        val w = 0.10f
        fun cx(col: Int) = (col + 0.5f) / FlickLayout.COLS
        fun cy(row: Int) = (row + 0.5f) / FlickLayout.ROWS
        var t = 0L
        fun stroke(x0: Float, y0: Float, dxKeys: Float, dyKeys: Float, bottom: Float = -0.005f): List<KeyAction> {
            val key = w / FlickLayout.COLS
            val pts = (0..5).map { Triple(x0, y0, 0.04f + (bottom - 0.04f) * it / 5) } +
                (1..4).map { Triple(x0 + dxKeys * key / w * it / 4, y0 + dyKeys * key / w * it / 4, bottom) } +
                (1..5).map { Triple(x0 + dxKeys * key / w, y0 + dyKeys * key / w, bottom + (0.05f - bottom) * it / 5) } +
                List(5) { Triple(x0 + dxKeys * key / w, y0 + dyKeys * key / w, 0.05f) }
            return pts.mapNotNull { (x, y, h) -> d.onFrame(TipFrame(t, x, y, h, w, w, 0.010f)).also { t += 20 } }
        }
        assertEquals(listOf(KeyAction.Text("な")), stroke(cx(1), cy(1), 0.2f, 0f))
        assertEquals(listOf(KeyAction.Text("ね")), stroke(cx(1), cy(1), 1.5f, 0.3f))
        assertEquals(listOf(KeyAction.Text("ぬ")), stroke(cx(1), cy(1), 0.2f, -1.2f))
        // しきい値（10 mm）に届かない浅い上下は浅い候補になり、モデルが「打鍵ではない」にする
        assertEquals(emptyList<KeyAction>(), stroke(cx(1), cy(1), 0f, 0f, bottom = 0.015f))
    }

    companion object {
        /** テストは app/ をカレントディレクトリにして動く */
        fun load(): StrokeModel = File("src/main/assets/stroke_model.txt").inputStream().use(StrokeModel::parse)
    }
}
