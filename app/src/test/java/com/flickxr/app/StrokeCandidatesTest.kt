package com.flickxr.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeCandidatesTest {
    private val w = 0.10f
    private fun cx(col: Int) = (col + 0.5f) / FlickLayout.COLS
    private fun cy(row: Int) = (row + 0.5f) / FlickLayout.ROWS
    private fun line(a: Triple<Float, Float, Float>, b: Triple<Float, Float, Float>, n: Int) = (1..n).map { i ->
        val t = i.toFloat() / n
        Triple(a.first + (b.first - a.first) * t, a.second + (b.second - a.second) * t, a.third + (b.third - a.third) * t)
    }
    private fun run(points: List<Triple<Float, Float, Float>>): List<StrokeCandidates.Stroke> {
        val g = StrokeCandidates()
        val last = points.last()
        return (points + List(5) { last.copy(third = 0.06f) }).mapIndexedNotNull { i, (x, y, h) -> g.onFrame(TipFrame(i * 20L, x, y, h, w, w, 0.010f)) }
    }

    /** しきい値（10 mm）に届かない 15 mm の浅い単押しも候補になる */
    @Test fun shallowDipIsACandidate() {
        val top = Triple(cx(2), cy(2), 0.04f)
        val bottom = Triple(cx(2), cy(2), 0.015f)
        val s = run(listOf(top) + line(top, bottom, 5) + line(bottom, top, 5))
        assertEquals(1, s.size)
        assertEquals(StrokeCandidates.End.DIP, s[0].endedBy)
        assertTrue(s[0].pre.isNotEmpty())
        assertEquals(0.015f, s[0].points.first().h, 1e-6f)
    }

    /** しきい値まで届いた打鍵は普通の候補 1 つだけ（浅い候補を重ねて出さない） */
    @Test fun deepTapIsOneCandidate() {
        val top = Triple(cx(2), cy(2), 0.04f)
        val bottom = Triple(cx(2), cy(2), 0f)
        val s = run(listOf(top) + line(top, bottom, 5) + line(bottom, top, 5))
        assertEquals(listOf(StrokeCandidates.End.LIFT), s.map { it.endedBy })
    }

    @Test fun repressSplitsCandidates() {
        val top = Triple(cx(3), cy(0), 0.04f)
        val low = Triple(cx(3), cy(0), -0.005f)
        val mid = Triple(cx(3), cy(0), 0.012f)
        val s = run(listOf(top) + line(top, low, 4) + line(low, mid, 3) + line(mid, low, 3) + line(low, top, 4))
        assertEquals(listOf(StrokeCandidates.End.REPRESS, StrokeCandidates.End.LIFT), s.map { it.endedBy })
    }

    /** 「か」を押したあと、離した高さまで上げずに「は」へ移ってまた押す（続けて打つときの動き）→ 候補 2 つ */
    @Test fun repressAfterMovingSplitsCandidates() {
        val top = Triple(cx(1), cy(0), 0.04f)
        val ka = Triple(cx(1), cy(0), -0.005f)
        val travel = Triple(cx(2), cy(1), 0.014f) // しきい値 10 mm + 8 mm には届かない
        val ha = Triple(cx(2), cy(1), -0.005f)
        val s = run(listOf(top) + line(top, ka, 4) + line(ka, travel, 5) + line(travel, ha, 4) + line(ha, top, 4))
        assertEquals(listOf(StrokeCandidates.End.REPRESS, StrokeCandidates.End.LIFT), s.map { it.endedBy })
        assertEquals(listOf(0 to 1, 1 to 2), s.map { FlickLayout.cellAt(it.points.first().x, it.points.first().y) })
    }

    @Test fun featuresMatchNames() {
        val top = Triple(cx(0), cy(0), 0.04f)
        val down = Triple(cx(0), cy(0), 0f)
        val right = Triple(cx(0) + 0.3f, cy(0), 0f)
        val s = run(listOf(top) + line(top, down, 4) + line(down, right, 5) + line(right, top, 4)).single()
        val f = StrokeFeatures.compute(s)
        assertEquals(StrokeFeatures.NAMES.size, f.size)
        val byName = StrokeFeatures.NAMES.zip(f.toList()).toMap()
        assertEquals(1.2f, byName.getValue("farDx"), 0.05f) // 30 mm / キー 25 mm
        assertEquals(FlickDir.RIGHT, StrokeFeatures.ruleDir(s))
    }
}
