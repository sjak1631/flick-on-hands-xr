package com.flickxr.app

import android.content.Context

/** キーボードの調整値を端末に保存する（再起動しても残るように）。 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("keyboard_settings_v3", Context.MODE_PRIVATE)

    fun load(): KeyboardSettings {
        val d = KeyboardSettings()
        fun f(key: String, def: Float) = prefs.getFloat(key, def)
        return KeyboardSettings(
            // 当面は右手に固定（左手で入力）。学習のデータとモデルを片側に揃えるため（これまでの記録もほとんど右手）
            keyboardOnLeft = false,
            offsetX = f("offsetX", d.offsetX),
            offsetY = f("offsetY", d.offsetY),
            offsetZ = f("offsetZ", d.offsetZ),
            widthScale = f("widthScale", d.widthScale),
            heightScale = f("heightScale", d.heightScale),
            rollDegrees = f("rollDegrees", d.rollDegrees),
            tiltDegrees = f("tiltDegrees", d.tiltDegrees),
            yawDegrees = f("yawDegrees", d.yawDegrees),
            touchMeters = f("touchMeters", d.touchMeters),
            detector = DetectorType.entries.find { it.name == prefs.getString("detector", null) } ?: d.detector,
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
            .putString("detector", s.detector.name)
            .apply()
    }
}
