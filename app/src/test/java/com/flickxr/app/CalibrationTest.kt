package com.flickxr.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationTest {
    @Test fun levenshtein() {
        assertEquals(0, PhraseScore.levenshtein("さくら", "さくら"))
        assertEquals(1, PhraseScore.levenshtein("さくら", "さくろ"))
        assertEquals(2, PhraseScore.levenshtein("さくら", "さ"))
        assertEquals(3, PhraseScore.levenshtein("", "abc"))
    }

    @Test fun scoreCountsCorrectCharactersPerSecond() {
        // 5 文字を 4 秒で正しく入力 → (5 - 1) / 4
        assertEquals(1.0, PhraseScore.score("さくらさく", "さくらさく", 4f), 1e-9)
        // 時間切れで 3 文字目まで → (3 - 1) / 10
        assertEquals(0.2, PhraseScore.score("さくらさく", "さくら", 10f), 1e-9)
        // 誤りは正しい文字数から引く → (5 - 1 - 1) / 5
        assertEquals(0.6, PhraseScore.score("さくらさく", "さくろさく", 5f), 1e-9)
        assertEquals(0.0, PhraseScore.score("さくらさく", "", 10f), 1e-9)
    }

    @Test fun spaceRoundTrip() {
        val base = KeyboardSettings(offsetZ = 0.015f, touchMeters = 0.008f, widthScale = 1.2f)
        val space = CalibrationSpace(base)
        val x = space.toUnit(base)
        val back = space.toSettings(x, base)
        assertEquals(base.offsetZ, back.offsetZ, 1e-5f)
        assertEquals(base.touchMeters, back.touchMeters, 1e-5f)
        assertEquals(base.widthScale, back.widthScale, 1e-5f)
        assertEquals(base.heightScale, back.heightScale, 1e-5f)
        assertEquals(base.offsetY, back.offsetY, 1e-5f)
    }

    @Test fun spaceKeepsUnsearchedParameters() {
        val base = KeyboardSettings(keyboardOnLeft = false, rollDegrees = 30f, offsetX = 0.01f)
        val s = CalibrationSpace(base).toSettings(DoubleArray(5) { 1.0 }, base)
        assertEquals(false, s.keyboardOnLeft)
        assertEquals(30f, s.rollDegrees, 0f)
        assertEquals(0.01f, s.offsetX, 0f)
        assertEquals(base.offsetZ + 0.015f, s.offsetZ, 1e-6f)
    }

    @Test fun depthIsSearchedOnlyAbovePalmSurface() {
        val base = KeyboardSettings(offsetZ = 0.005f)
        // 表面を測れたら、開始時の値が手の中にあっても表面より上だけを探す
        val space = CalibrationSpace(base, palmSurface = 0.014f)
        assertEquals(0.016f, space.toSettings(DoubleArray(5), base).offsetZ, 1e-6f)
        assertEquals(0.016f, space.toSettings(space.toUnit(base), base).offsetZ, 1e-6f)
        // 測れなかったら開始時の値より上だけ
        assertEquals(0.005f, CalibrationSpace(base).toSettings(DoubleArray(5), base).offsetZ, 1e-6f)
    }

    @Test fun surfaceIsLowPercentileOfTipHeights() {
        // ほとんどは浮かせている高さ、タップで表面（15 mm）付近、たまにトラッキングが外れて手の中
        val heights = List(60) { 0.03f } + List(30) { 0.015f } + List(3) { -0.01f }
        assertEquals(0.015f, Calibration.estimateSurface(heights)!!, 1e-6f)
        assertEquals(null, Calibration.estimateSurface(List(5) { 0.01f }))
    }

    @Test fun phrasesAreTypableWithKanaKeys() {
        val typable = FlickLayout.rows(InputMode.KANA).flatten()
            .flatMap { k -> FlickDir.entries.mapNotNull { k.labelFor(it) } }.joinToString("")
        val withModifier = typable + typable.mapNotNull { c -> generateSequence(KanaModifier.modify(c)) { KanaModifier.modify(it) }.take(3).toList().takeIf { it.isNotEmpty() } }.flatten().joinToString("")
        for (p in Calibration.PHRASES) for (c in p) assertTrue("$p: $c", c in withModifier)
    }
}
