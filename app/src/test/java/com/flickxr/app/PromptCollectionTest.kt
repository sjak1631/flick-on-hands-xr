package com.flickxr.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PromptCollectionTest {
    @Test fun targetsCoverKanaLayoutWithoutModeKey() {
        val labels = PromptCollection.TARGETS.map { it.label }
        assertTrue(labels.containsAll(listOf("あ", "お", "か", "き", "ん", "⌫", "空白", "゛゜小")))
        assertTrue("ABC" !in labels)
        // 提示した打鍵は、配列どおりに打てばその文字になる
        for (t in PromptCollection.TARGETS) {
            val key = FlickLayout.rows(InputMode.KANA)[t.row][t.col]
            val a = key.actionFor(t.dir)
            if (a is KeyAction.Text) assertEquals(t.label, a.text)
        }
    }

    @Test fun wordsSplitIntoStrokes() {
        fun labels(w: String) = PromptCollection.strokesFor(w).map { it.label }
        assertEquals(listOf("か", "せ", "゛゜小"), labels("かぜ"))
        assertEquals(listOf("は", "゛゜小", "゛゜小", "ん"), labels("ぱん"))
        assertEquals(listOf("き", "よ", "゛゜小", "う"), labels("きょう"))
        assertEquals(listOf("か", "゛゜小", "つ", "゛゜小", "こ", "う"), labels("がっこう"))
        assertEquals(listOf("ら", "ー", "め", "ん"), labels("らーめん"))
        // 打鍵の並びを TextBuffer に流すと元の単語になる
        for (w in PromptCollection.WORDS) {
            val b = TextBuffer()
            for (t in PromptCollection.strokesFor(w)) b.apply(FlickLayout.rows(InputMode.KANA)[t.row][t.col].actionFor(t.dir))
            assertEquals(w, b.text)
        }
    }

    @Test fun sequenceMixesRepeatsAndTaps() {
        val seq = PromptCollection.sequence(600, Random(1))
        assertEquals(600, seq.size)
        val sameKeyPairs = seq.zipWithNext().count { (a, b) -> a.row == b.row && a.col == b.col }
        val taps = seq.count { it.dir == FlickDir.NONE }
        assertTrue("same key pairs $sameKeyPairs", sameKeyPairs > 100)
        assertTrue("taps $taps", taps in 200..450)
        assertTrue(seq.zipWithNext().any { (a, b) -> a.label == "⌫" && b.label == "⌫" })
    }
}
