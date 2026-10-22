package com.flickxr.app

import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3

/**
 * キーボードの置き方。手のひら座標系（X: 正面から見て右、Y: 手首→指先、Z: 手のひらから外向き）で持つので、
 * 手を動かしても同じ場所に付いてくる。
 *
 * メインパネルのスライダーで手動調整するか、フレーズ入力の試行を繰り返して [Calibration] で自動調整する。
 */
data class KeyboardSettings(
    /** true: 左手のひらにキーボード・右手で入力 / false: その逆。当面は常に false（[SettingsStore] で固定） */
    val keyboardOnLeft: Boolean = false,
    /** 左右（+ で右） */
    val offsetX: Float = 0f,
    /** 上下（+ で指先側） */
    val offsetY: Float = 0f,
    /** 奥行き：手のひらからの浮かせ量 */
    val offsetZ: Float = 0.012f,
    /** 手のひら（指を除く部分）の実測サイズに対する倍率 */
    val widthScale: Float = 1f,
    val heightScale: Float = 1f,
    /** 面内の回転（正面から見て反時計回りが正、度） */
    val rollDegrees: Float = 0f,
    /** 傾き：左右軸まわり（上端が手前に来る向きが正、度） */
    val tiltDegrees: Float = 0f,
    /** ひねり：上下軸まわり（右端が手前に来る向きが正、度） */
    val yawDegrees: Float = 0f,
    /** 指先がキーボード面からこの高さ未満になったら「接触」 */
    val touchMeters: Float = 0.010f,
    /** 打鍵の判定のしかた */
    val detector: DetectorType = DetectorType.SLIDE,
) {
    val offset: Vector3 get() = Vector3(offsetX, offsetY, offsetZ)

    /** 手のひら座標系での回転。面内回転 → 傾き → ひねり の順に、手のひらの軸まわりで適用する。 */
    val rotation: Quaternion
        get() = Quaternion.fromAxisAngle(Vector3(0f, 1f, 0f), -yawDegrees) *
            Quaternion.fromAxisAngle(Vector3(1f, 0f, 0f), tiltDegrees) *
            Quaternion.fromAxisAngle(Vector3(0f, 0f, 1f), rollDegrees)

    /** 位置・回転・大きさだけを初期値に戻す。 */
    fun resetPlacement() = KeyboardSettings(keyboardOnLeft = keyboardOnLeft, touchMeters = touchMeters, detector = detector)
}
