package com.flickxr.app

import android.content.Context

/** キーボードの調整値を端末に保存する（再起動しても残るように）。 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("keyboard_settings_v3", Context.MODE_PRIVATE)

    fun load(): KeyboardSettings {
        val d = KeyboardSettings()
        fun f(key: String, def: Float) = prefs.getFloat(key, def)
        return KeyboardSettings(
            keyboardOnLeft = prefs.getBoolean("keyboardOnLeft", d.keyboardOnLeft),
            offsetX = f("offsetX", d.offsetX),
            offsetY = f("offsetY", d.offsetY),
            offsetZ = f("offsetZ", d.offsetZ),
            widthScale = f("widthScale", d.widthScale),
            heightScale = f("heightScale", d.heightScale),
            rollDegrees = f("rollDegrees", d.rollDegrees),
            tiltDegrees = f("tiltDegrees", d.tiltDegrees),
            yawDegrees = f("yawDegrees", d.yawDegrees),
            touchMeters = f("touchMeters", d.touchMeters),
        )
    }

    fun save(s: KeyboardSettings) {
        prefs.edit()
            .putBoolean("keyboardOnLeft", s.keyboardOnLeft)
            .putFloat("offsetX", s.offsetX)
            .putFloat("offsetY", s.offsetY)
            .putFloat("offsetZ", s.offsetZ)
            .putFloat("widthScale", s.widthScale)
            .putFloat("heightScale", s.heightScale)
            .putFloat("rollDegrees", s.rollDegrees)
            .putFloat("tiltDegrees", s.tiltDegrees)
            .putFloat("yawDegrees", s.yawDegrees)
            .putFloat("touchMeters", s.touchMeters)
            .apply()
    }
}
