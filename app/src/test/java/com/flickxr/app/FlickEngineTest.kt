package com.flickxr.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FlickEngineTest {
    private val engine = FlickEngine(widthMeters = 0.10f, heightMeters = 0.12f, flickThresholdMeters = 0.01f)

    // 4x4 グリッドのセル中心
    private fun cx(col: Int) = (col + 0.5f) / FlickLayout.COLS
    private fun cy(row: Int) = (row + 0.5f) / FlickLayout.ROWS

    /** (col,row) を押し、(dxm, dym) メートル動かして離す */
    private fun flick(col: Int, row: Int, dxm: Float = 0f, dym: Float = 0f): KeyAction? {
        val x = cx(col)
        val y = cy(row)
        assertNull(engine.onFrame(x, y, touching = true))
        assertNull(engine.onFrame(x + dxm / 0.10f, y + dym / 0.12f, touching = true))
        return engine.onFrame(x + dxm / 0.10f, y + dym / 0.12f, touching = false)
    }

    @Test fun tapGivesCenter() = assertEquals(KeyAction.Text("か"), flick(1, 0))

    @Test fun flickDirections() {
        assertEquals(KeyAction.Text("い"), flick(0, 0, dxm = -0.015f))
        assertEquals(KeyAction.Text("う"), flick(0, 0, dym = -0.015f))
        assertEquals(KeyAction.Text("え"), flick(0, 0, dxm = 0.015f))
        assertEquals(KeyAction.Text("お"), flick(0, 0, dym = 0.015f))
    }

    @Test fun smallMovementIsTap() = assertEquals(KeyAction.Text("は"), flick(2, 1, dxm = 0.005f))

    @Test fun missingDirectionFallsBackToCenter() = assertEquals(KeyAction.Text("わ"), flick(1, 3, dym = 0.015f))

    @Test fun functionKeys() {
        assertEquals(KeyAction.Backspace, flick(3, 0))
        assertEquals(KeyAction.Space, flick(3, 1))
        assertEquals(KeyAction.Enter, flick(3, 2))
    }

    @Test fun modeSwitchCycles() {
        assertEquals(KeyAction.NextMode, flick(3, 3))
        assertEquals(InputMode.ALPHA, engine.mode)
        assertEquals(KeyAction.Text("c"), flick(1, 0, dym = -0.015f))
        flick(3, 3)
        assertEquals(InputMode.NUMBER, engine.mode)
        flick(3, 3)
        assertEquals(InputMode.KANA, engine.mode)
    }

    @Test fun cancelDropsPress() {
        engine.onFrame(cx(0), cy(0), touching = true)
        engine.cancel()
        assertNull(engine.onFrame(cx(0), cy(0), touching = false))
    }

    @Test fun outsideIsIgnored() {
        assertNull(engine.onFrame(1.2f, 0.5f, touching = true))
        assertNull(engine.press)
    }

    @Test fun textBufferModify() {
        val b = TextBuffer()
        listOf(KeyAction.Text("は"), KeyAction.Modify).forEach(b::apply)
        assertEquals("ば", b.text)
        b.apply(KeyAction.Modify)
        assertEquals("ぱ", b.text)
        b.apply(KeyAction.Modify)
        assertEquals("は", b.text)
        b.apply(KeyAction.Text("つ")); b.apply(KeyAction.Modify)
        assertEquals("はっ", b.text)
        b.apply(KeyAction.Text("a")); b.apply(KeyAction.Modify)
        assertEquals("はっA", b.text)
        b.apply(KeyAction.Backspace)
        assertEquals("はっ", b.text)
        b.apply(KeyAction.Space)
        assertEquals("はっ　", b.text)
    }
}
