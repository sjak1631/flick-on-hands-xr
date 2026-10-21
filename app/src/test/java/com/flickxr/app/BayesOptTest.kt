package com.flickxr.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt
import kotlin.random.Random

class BayesOptTest {
    private fun dist(a: DoubleArray, b: DoubleArray) = sqrt(a.indices.sumOf { (a[it] - b[it]) * (a[it] - b[it]) })

    @Test fun findsMaximumOfNoisyQuadratic() {
        val peak = doubleArrayOf(0.3, 0.7, 0.55)
        val noise = Random(1)
        val bo = BayesOpt(dims = 3, initialPoints = 5, random = Random(42))
        repeat(25) {
            val x = bo.suggest()
            bo.add(x, -dist(x, peak).let { it * it } * 10 + noise.nextDouble(-0.1, 0.1))
        }
        val (best, _) = bo.best()!!
        assertTrue("best=${best.toList()}", dist(best, peak) < 0.2)
    }

    @Test fun initialPointsCoverEachStratum() {
        val bo = BayesOpt(dims = 2, initialPoints = 4, random = Random(0))
        val pts = List(4) { bo.suggest() }
        for (d in 0 until 2) assertEquals(setOf(0, 1, 2, 3), pts.map { (it[d] * 4).toInt() }.toSet())
    }

    @Test fun suggestionsStayInUnitCube() {
        val bo = BayesOpt(dims = 2, initialPoints = 2, random = Random(3))
        repeat(8) {
            val x = bo.suggest()
            assertTrue(x.all { it in 0.0..1.0 })
            bo.add(x, x.sum())
        }
    }

    @Test fun normalCdf() {
        assertEquals(0.5, BayesOpt.normalCdf(0.0), 1e-6)
        assertEquals(0.8413, BayesOpt.normalCdf(1.0), 1e-3)
        assertEquals(0.0228, BayesOpt.normalCdf(-2.0), 1e-3)
    }
}
