package com.flickxr.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.roundToInt

@Composable
fun MainScreen(
    text: StateFlow<String>,
    status: StateFlow<TrackingStatus>,
    settings: MutableStateFlow<KeyboardSettings>,
    message: StateFlow<String>,
    calibration: Calibration,
    onClear: () -> Unit,
    onCopy: () -> Unit,
) {
    val t by text.collectAsStateWithLifecycle()
    val st by status.collectAsStateWithLifecycle()
    val s by settings.collectAsStateWithLifecycle()
    val msg by message.collectAsStateWithLifecycle()
    val cal by calibration.state.collectAsStateWithLifecycle()

    fun set(f: (KeyboardSettings) -> KeyboardSettings) = settings.update(f)

    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF12151E)) {
            Row(Modifier.padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Flick on Hands", style = MaterialTheme.typography.titleLarge)
                    Text(msg, color = Color(0xFF9AA4B8), fontSize = 14.sp)
                    if (cal.phase != Calibration.Phase.IDLE) {
                        CalibrationPanel(cal, calibration, t, Modifier.fillMaxWidth().weight(1f))
                    } else {
                        Text(
                            text = t.ifEmpty { "（ここに入力が表示されます）" },
                            color = if (t.isEmpty()) Color(0xFF5C6578) else Color.White,
                            fontSize = 26.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .background(Color(0xFF1C2130), RoundedCornerShape(12.dp))
                                .padding(16.dp)
                                .verticalScroll(rememberScrollState()),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = onCopy) { Text("コピー") }
                            OutlinedButton(onClick = onClear) { Text("クリア") }
                            FilledTonalButton(onClick = { calibration.start(optimize = false) }) { Text("練習（記録）") }
                            FilledTonalButton(onClick = { calibration.start() }) { Text("自動調整（BO）") }
                        }
                    }

                    Text("状態", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        StatusLine(if (s.keyboardOnLeft) "キーボードの手（左）" else "キーボードの手（右）", st.keyboardHandTracked)
                        StatusLine(if (s.keyboardOnLeft) "入力する手（右）" else "入力する手（左）", st.typingHandTracked)
                        StatusLine("手のひらの向き", st.palmFacing)
                    }
                    Text(
                        "指先の高さ: " + (st.fingerHeightMm?.let { "%.0f mm".format(it) } ?: "—") +
                            if (st.touching) "  ● 接触" else "",
                        fontSize = 14.sp,
                        color = if (st.touching) Color(0xFF50E678) else Color(0xFFCDD3E0),
                    )
                }

                Column(
                    Modifier
                        .width(340.dp)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("キーボードの調整", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = { set { it.resetPlacement() } }) { Text("リセット") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = s.keyboardOnLeft, onClick = { set { it.copy(keyboardOnLeft = true) } }, label = { Text(if (s.keyboardOnLeft) "✓ 左手に表示" else "左手に表示") })
                        FilterChip(selected = !s.keyboardOnLeft, onClick = { set { it.copy(keyboardOnLeft = false) } }, label = { Text(if (!s.keyboardOnLeft) "✓ 右手に表示" else "右手に表示") })
                    }

                    Section("打鍵の判定")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (type in DetectorType.entries) {
                            FilterChip(
                                selected = s.detector == type,
                                onClick = { set { it.copy(detector = type) } },
                                label = { Text(if (s.detector == type) "✓ ${type.label}" else type.label) },
                            )
                        }
                    }

                    Section("位置")
                    Adjuster("奥行き（浮かせ量）", s.offsetZ, -0.02f..0.05f, 0.001f, ::mm) { v -> set { it.copy(offsetZ = v) } }
                    Adjuster("上下（+ で指先側）", s.offsetY, -0.06f..0.06f, 0.002f, ::mm) { v -> set { it.copy(offsetY = v) } }
                    Adjuster("左右（+ で右）", s.offsetX, -0.06f..0.06f, 0.002f, ::mm) { v -> set { it.copy(offsetX = v) } }

                    Section("大きさ（手のひらの実測サイズに対する倍率）")
                    Adjuster("幅", s.widthScale, 0.4f..2.5f, 0.05f, ::percent) { v -> set { it.copy(widthScale = v) } }
                    Adjuster("高さ", s.heightScale, 0.4f..2.5f, 0.05f, ::percent) { v -> set { it.copy(heightScale = v) } }

                    Section("回転")
                    Adjuster("回転（+ で反時計回り）", s.rollDegrees, -180f..180f, 5f, ::deg) { v -> set { it.copy(rollDegrees = v) } }
                    Adjuster("傾き（+ で上端が手前）", s.tiltDegrees, -60f..60f, 5f, ::deg) { v -> set { it.copy(tiltDegrees = v) } }
                    Adjuster("ひねり（+ で右端が手前）", s.yawDegrees, -60f..60f, 5f, ::deg) { v -> set { it.copy(yawDegrees = v) } }

                    Section("タッチ判定")
                    Adjuster("接触しきい値", s.touchMeters, 0.002f..0.03f, 0.001f, ::mm) { v -> set { it.copy(touchMeters = v) } }
                }
            }
        }
    }
}

private fun mm(v: Float) = "${(v * 1000).roundToInt()} mm"
private fun percent(v: Float) = "${(v * 100).roundToInt()} %"
private fun deg(v: Float) = "${v.roundToInt()}°"

/** 自動調整中の表示：入力するフレーズ、入力中の文字列、残り時間、これまでの結果。 */
@Composable
private fun CalibrationPanel(cal: Calibration.State, calibration: Calibration, text: String, modifier: Modifier) {
    Column(
        modifier
            .background(Color(0xFF1C2130), RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text((if (cal.optimize) "自動調整" else "練習") + "  ${cal.trial} / ${cal.total}", style = MaterialTheme.typography.titleMedium)
        when (cal.phase) {
            Calibration.Phase.MEASURE -> {
                Text("まず手のひらの表面の高さを測ります", fontSize = 16.sp)
                Text(
                    "キーボードのあたりを、人差し指で手のひらに触れるまで何度かタップしてください" +
                        if (cal.samples > 0) "（残り %.1f 秒）".format(cal.remainingMs / 1000f) else "",
                    fontSize = 14.sp,
                    color = Color(0xFF9AA4B8),
                )
                OutlinedButton(onClick = calibration::cancel) { Text("中止") }
            }
            Calibration.Phase.WAITING, Calibration.Phase.TYPING, Calibration.Phase.RESULT -> {
                Text(
                    when (cal.phase) {
                        Calibration.Phase.WAITING -> "下のフレーズを入力してください（最初のキーで計測開始、一致すると次へ）"
                        Calibration.Phase.TYPING -> "残り %.1f 秒".format(cal.remainingMs / 1000f)
                        else -> cal.history.lastOrNull()?.let { "%.2f 文字/秒（誤り %d）".format(it.score, it.errors) } ?: ""
                    },
                    fontSize = 14.sp,
                    color = Color(0xFF9AA4B8),
                )
                Text(cal.target, fontSize = 34.sp, color = Color.White)
                Text(
                    cal.typed.ifEmpty { "　" },
                    fontSize = 34.sp,
                    color = if (cal.target.startsWith(cal.typed)) Color(0xFF50E678) else Color(0xFFFF7A7A),
                )
                Text("試行中の設定: ${cal.current}", fontSize = 12.sp, color = Color(0xFF7A8396))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = calibration::retry) { Text("やり直し") }
                    OutlinedButton(onClick = calibration::cancel) { Text("中止") }
                }
            }
            Calibration.Phase.DONE -> if (!cal.optimize) {
                Text(
                    "練習を終えました（平均 %.2f 文字/秒）。記録は端末の files/sessions に残っています".format(
                        cal.history.map { it.score }.average().takeIf { !it.isNaN() } ?: 0.0,
                    ),
                    fontSize = 14.sp,
                )
                Button(onClick = calibration::accept) { Text("終了") }
            } else {
                val best = cal.history.maxOfOrNull { it.score } ?: 0.0
                Text(
                    "最も良さそうな設定を適用しました（予測 %.2f 文字/秒、1 回目 %.2f、最高 %.2f）".format(
                        cal.predicted ?: 0.0, cal.history.firstOrNull()?.score ?: 0.0, best,
                    ),
                    fontSize = 14.sp,
                )
                Text(cal.current, fontSize = 13.sp, color = Color(0xFFCDD3E0))
                Text(
                    cal.palmSurface?.let { "手のひらの表面: %.0f mm（奥行きはこれより上で探索）".format(it * 1000) }
                        ?: "手のひらの表面を測れなかったため、奥行きは開始時の値より上で探索しました",
                    fontSize = 12.sp,
                    color = Color(0xFF7A8396),
                )
                Text("試しに打ってみて、採用するか元に戻すかを選んでください", fontSize = 13.sp, color = Color(0xFF9AA4B8))
                Text(text.takeLast(40).ifEmpty { "　" }, fontSize = 26.sp, color = Color.White)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = calibration::accept) { Text("採用") }
                    OutlinedButton(onClick = calibration::revert) { Text("元に戻す") }
                }
            }
            Calibration.Phase.IDLE -> Unit
        }
        Text(
            cal.history.joinToString("  ") { "%d: %.2f".format(it.index, it.score) },
            fontSize = 12.sp,
            color = Color(0xFF7A8396),
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(title, fontSize = 13.sp, color = Color(0xFF7FA8FF), modifier = Modifier.padding(top = 10.dp))
}

@Composable
private fun StatusLine(label: String, ok: Boolean) {
    Text(
        (if (ok) "● " else "○ ") + label,
        fontSize = 14.sp,
        color = if (ok) Color(0xFF50E678) else Color(0xFF7A8396),
    )
}

/** スライダーと、細かく合わせるための − / ＋ ボタン。 */
@Composable
private fun Adjuster(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    format: (Float) -> String,
    onChange: (Float) -> Unit,
) {
    Column {
        Text("$label  ${format(value)}", fontSize = 13.sp, color = Color(0xFFCDD3E0))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilledTonalButton(onClick = { onChange((value - step).coerceIn(range)) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(40.dp)) { Text("−") }
            Slider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f))
            FilledTonalButton(onClick = { onChange((value + step).coerceIn(range)) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(40.dp)) { Text("＋") }
        }
    }
}
