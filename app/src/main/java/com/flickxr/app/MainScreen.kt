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
    onClear: () -> Unit,
    onCopy: () -> Unit,
) {
    val t by text.collectAsStateWithLifecycle()
    val st by status.collectAsStateWithLifecycle()
    val s by settings.collectAsStateWithLifecycle()
    val msg by message.collectAsStateWithLifecycle()

    fun set(f: (KeyboardSettings) -> KeyboardSettings) = settings.update(f)

    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF12151E)) {
            Row(Modifier.padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Flick on Hands", style = MaterialTheme.typography.titleLarge)
                    Text(msg, color = Color(0xFF9AA4B8), fontSize = 14.sp)
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
