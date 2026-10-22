package com.flickxr.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionLogTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun frameRoundTrip() {
        val tip = TipFrame(1234L, 0.25f, 0.875f, -0.0031f, 0.0912f, 0.0733f, 0.01f)
        val f = SessionLog.parseFrame(SessionLog.formatFrame(3, 1234L, tip, true, KeyAction.Text(",")))
        assertEquals(3, f.attempt)
        assertEquals(tip, f.tip)
        assertEquals(true, f.touching)
        assertEquals(",", f.action)

        val lost = SessionLog.parseFrame(SessionLog.formatFrame(3, 1250L, null, false, null))
        assertNull(lost.tip)
        assertEquals(1250L, lost.tMs)
        assertEquals("", lost.action)
    }

    @Test fun jointsAreWrittenOnlyWhenUsed() {
        val dir = tmp.newFolder("joints")
        val log = SessionLog(dir)
        val v = FloatArray(HandKeyboard.JOINT_COLUMNS.size) { Float.NaN }.also { it[0] = 0.08123f; it[2] = -0.5f }
        log.joints(1, 100L, kbTracked = true, typeTracked = false, values = v)
        log.close()
        assertEquals(listOf("joints.csv"), dir.list()!!.toList())
        val lines = java.io.File(dir, "joints.csv").readLines()
        assertEquals(HandKeyboard.JOINT_COLUMNS.size + 4, lines[0].split(",").size)
        val row = lines[1].split(",")
        assertEquals(listOf("1", "100", "1", "0", "8123", ""), row.take(6))
        assertEquals("-50000", row[6])
        assertEquals(lines[0].split(",").size, row.size)
    }

    @Test fun quotedFields() {
        assertEquals(listOf("a", "b,\"c\"", "", "d\ne"), SessionLog.splitCsv("a,\"b,\"\"c\"\"\",,\"d\\ne\""))
    }

    /** 実機と同じ流れで記録し、読み戻して再生すると、記録したときと同じアクションになる */
    @Test fun replayReproducesLiveDetector() {
        val dir = tmp.newFolder("session")
        val log = SessionLog(dir)
        val live = ThresholdDetector()
        val buffer = TextBuffer()
        // 「か」をタップ → 「あ」を右にフリック
        val path = listOf(
            0.375f to 0.04f, 0.375f to 0.005f, 0.375f to 0.0f, 0.375f to 0.04f,
            0.125f to 0.04f, 0.125f to 0.004f, 0.2f to 0.003f, 0.3f to 0.002f, 0.3f to 0.04f,
        )
        path.forEachIndexed { i, (x, h) ->
            val tip = TipFrame(i * 16L, x, 0.125f, h, 0.1f, 0.1f, 0.01f)
            val a = live.onFrame(tip)
            log.frame(1, tip.tMs, tip, live.touching, a)
            a?.let(buffer::apply)
        }
        log.frame(1, 200L, null, false, live.onFrame(null))
        log.trial(TrialResult(1, 1, "かえ", buffer.text, 1f, 0, 1.0, KeyboardSettings()), palmSurface = 0.015f)
        log.close()

        val attempt = Replay.load(dir).single()
        assertEquals("かえ", attempt.trial.typed)
        assertEquals(listOf("か", "え"), attempt.liveActions)
        val r = Replay.run(ThresholdDetector(), attempt.frames)
        assertEquals(attempt.liveActions, r.actions)
        assertEquals("かえ", r.text)
    }
}
