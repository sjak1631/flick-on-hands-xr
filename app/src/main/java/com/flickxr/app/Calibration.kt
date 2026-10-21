package com.flickxr.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * ベイズ最適化で探すパラメータと、その範囲。範囲は開始時の設定を中心に取る
 * （今の値から大きく外れて打てなくなる設定を試さないように）。
 *
 * 奥行きだけは、評価値（打ちやすさ）からは「手の中に沈める + 接触しきい値を大きくする」設定と区別がつかないので、
 * 手のひらの表面より上だけを探す。
 *
 * @param palmSurface 計測した手のひらの表面の高さ（手のひら座標系の Z）。計測できなかったときは null（開始時の値より上だけを探す）
 */
class CalibrationSpace(base: KeyboardSettings, palmSurface: Float? = null) {
    class Param(
        val label: String,
        val min: Float,
        val max: Float,
        val format: (Float) -> String,
        val get: (KeyboardSettings) -> Float,
        val set: (KeyboardSettings, Float) -> KeyboardSettings,
    )

    val params = listOf(
        Param("奥行き", depthMin(base, palmSurface), depthMin(base, palmSurface) + DEPTH_RANGE, ::mm, { it.offsetZ }, { s, v -> s.copy(offsetZ = v) }),
        Param("接触しきい値", minOf(0.004f, base.touchMeters), maxOf(0.020f, base.touchMeters), ::mm, { it.touchMeters }, { s, v -> s.copy(touchMeters = v) }),
        Param("上下", base.offsetY - 0.015f, base.offsetY + 0.015f, ::mm, { it.offsetY }, { s, v -> s.copy(offsetY = v) }),
        Param("幅", base.widthScale * 0.75f, base.widthScale * 1.3f, ::percent, { it.widthScale }, { s, v -> s.copy(widthScale = v) }),
        Param("高さ", base.heightScale * 0.75f, base.heightScale * 1.3f, ::percent, { it.heightScale }, { s, v -> s.copy(heightScale = v) }),
    )

    val dims: Int get() = params.size

    /** 正規化座標 [0, 1]^dims → 設定。探索しないパラメータは base のまま。 */
    fun toSettings(x: DoubleArray, base: KeyboardSettings): KeyboardSettings =
        params.withIndex().fold(base) { s, (i, p) -> p.set(s, p.min + (p.max - p.min) * x[i].toFloat().coerceIn(0f, 1f)) }

    fun toUnit(s: KeyboardSettings): DoubleArray =
        DoubleArray(dims) { i -> params[i].let { p -> ((p.get(s) - p.min) / (p.max - p.min)).toDouble().coerceIn(0.0, 1.0) } }

    fun describe(s: KeyboardSettings): String = params.joinToString("  ") { "${it.label} ${it.format(it.get(s))}" }

    private companion object {
        /** 表面からの最小の浮かせ量（トラッキングの揺れで沈んで見えないように） */
        const val SURFACE_MARGIN = 0.002f
        const val DEPTH_RANGE = 0.015f

        fun depthMin(base: KeyboardSettings, palmSurface: Float?) = palmSurface?.plus(SURFACE_MARGIN) ?: base.offsetZ

        fun mm(v: Float) = "${(v * 1000).roundToInt()} mm"
        fun percent(v: Float) = "${(v * 100).roundToInt()} %"
    }
}

/** 1 回分のフレーズ入力の結果。score は 1 秒あたりに正しく入力できた文字数。 */
data class TrialResult(
    /** 試行（やり直しも含む）の通し番号。[SessionLog] のフレームと結び付ける */
    val attempt: Int,
    val index: Int,
    val target: String,
    val typed: String,
    val seconds: Float,
    val errors: Int,
    val score: Double,
    val settings: KeyboardSettings,
)

object PhraseScore {
    fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val cur = IntArray(b.length + 1)
            cur[0] = i + 1
            for (j in b.indices) cur[j + 1] = minOf(prev[j + 1] + 1, cur[j] + 1, prev[j] + if (a[i] == b[j]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }

    /**
     * 正しく入力できた文字数（目標の文字数 − 編集距離）を秒で割る。計測は最初の打鍵から始めるので、
     * 文字入力速度の慣例（MacKenzie）にならって最初の 1 文字を除いた (n − 1) / t とする。
     * 誤りは編集距離で、訂正（⌫）は時間で効いてくる。
     */
    fun score(target: String, typed: String, seconds: Float): Double {
        val correct = target.length - levenshtein(target, typed)
        if (correct <= 1) return 0.0
        return (correct - 1) / seconds.coerceAtLeast(0.5f).toDouble()
    }
}

/**
 * フレーズ入力を短く何回か繰り返し、そのたびにキーボードの置き方と判定を変えて、
 * 打ちやすさ（[PhraseScore]）が最大になる設定をベイズ最適化で探す。
 *
 * 最初に手のひらを何度かタップしてもらい、指先が止まる高さから手のひらの表面を測る（奥行きの探索範囲の下限にする）。
 * 1 回目は今の設定、続く [INITIAL_POINTS] 回は探索範囲に散らした設定、残りは期待改善量で選んだ設定を試す。
 * 最後に、試した中で事後平均が最も良い設定を適用する（採用するか元に戻すかはユーザーが選ぶ）。
 *
 * 練習モード（`start(optimize = false)`）では設定を変えずにフレーズ入力だけを繰り返し、軌跡を記録する。
 * 判定の数値を記録の再生で詰めるためのもの。試行の合間にスライダーで設定を変えてもよい。
 */
class Calibration(
    private val scope: CoroutineScope,
    private val settings: MutableStateFlow<KeyboardSettings>,
    /** 試行を始めるたびに呼ぶ（キーボードをかなモードに戻すなど） */
    private val onTrialStart: () -> Unit,
    private val onResult: (TrialResult) -> Unit,
    private val random: Random = Random.Default,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class Phase {
        IDLE,
        /** 手のひらをタップしてもらい、表面の高さを測っている */
        MEASURE,
        /** フレーズを出して、最初の打鍵を待っている */
        WAITING,
        TYPING,
        /** 1 回分の結果を少しのあいだ表示している */
        RESULT,
        /** 終わって、最も良い設定を適用した */
        DONE,
    }

    data class State(
        val phase: Phase = Phase.IDLE,
        val trial: Int = 0,
        val total: Int = TOTAL_TRIALS,
        val target: String = "",
        val typed: String = "",
        val remainingMs: Long = TRIAL_MS,
        val current: String = "",
        val history: List<TrialResult> = emptyList(),
        /** DONE のとき：適用した設定の予測スコア */
        val predicted: Double? = null,
        /** 計測した手のひらの表面の高さ（m）。計測できなかったときは null */
        val palmSurface: Float? = null,
        /** MEASURE のとき：集まった指先の高さのサンプル数 */
        val samples: Int = 0,
        /** false なら練習モード（設定を変えない） */
        val optimize: Boolean = true,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    val active: Boolean get() = _state.value.phase != Phase.IDLE

    /** 開始した時刻。セッションの記録の名前に使う */
    var sessionId = 0L
        private set
    var attempt = 0
        private set

    /** フレーズ入力中なら、その試行の通し番号（軌跡を記録する対象）。 */
    val recordingAttempt: Int?
        get() = attempt.takeIf { _state.value.phase.let { it == Phase.WAITING || it == Phase.TYPING } }

    private var original = KeyboardSettings()
    private var space = CalibrationSpace(original)
    private var bo = BayesOpt(space.dims, INITIAL_POINTS, random)
    private var currentX = DoubleArray(0)
    private val buffer = TextBuffer()
    private var phrases = ArrayDeque<String>()
    private var presentedAt = 0L
    private var startedAt: Long? = null
    private var timer: Job? = null
    private val tipHeights = mutableListOf<Float>()

    fun start(optimize: Boolean = true) {
        if (active) return
        original = settings.value
        sessionId = clock()
        attempt = 0
        phrases = ArrayDeque(PHRASES.shuffled(random))
        tipHeights.clear()
        if (!optimize) {
            space = CalibrationSpace(original)
            _state.value = State(optimize = false, total = PRACTICE_TRIALS)
            beginTrial(1, space.toUnit(original))
            return
        }
        _state.value = State(phase = Phase.MEASURE, remainingMs = MEASURE_MS)
        presentedAt = clock()
        startedAt = null
        timer?.cancel()
        timer = scope.launch {
            while (true) {
                delay(TICK_MS)
                val started = startedAt
                if (started == null) {
                    if (clock() - presentedAt > WAIT_LIMIT_MS * 2) break
                } else {
                    val remaining = MEASURE_MS - (clock() - started)
                    if (remaining <= 0) break
                    _state.update { it.copy(remainingMs = remaining, samples = tipHeights.size) }
                }
            }
            startTrials(estimateSurface(tipHeights))
        }
    }

    /** 手のひら座標系での指先の高さ（[HandKeyboard] から毎フレーム）。MEASURE のときだけ使う。 */
    fun onTipSample(palmZ: Float) {
        if (_state.value.phase != Phase.MEASURE) return
        if (startedAt == null) startedAt = clock()
        tipHeights += palmZ
    }

    private fun startTrials(surface: Float?) {
        space = CalibrationSpace(original, surface)
        bo = BayesOpt(space.dims, INITIAL_POINTS, random)
        _state.update { it.copy(palmSurface = surface) }
        beginTrial(1, space.toUnit(original))
    }

    /** 確定した入力を受け取る。キャリブレーション中で入力を使ったら true（通常のテキストには入れない）。 */
    fun onAction(action: KeyAction): Boolean {
        val st = _state.value
        when (st.phase) {
            Phase.IDLE, Phase.DONE -> return false
            Phase.MEASURE, Phase.RESULT -> return true
            Phase.WAITING, Phase.TYPING -> Unit
        }
        if (startedAt == null) startedAt = clock()
        buffer.apply(action)
        _state.update { it.copy(phase = Phase.TYPING, typed = buffer.text) }
        if (buffer.text == st.target) finishTrial()
        return true
    }

    /** 今の設定のまま、別のフレーズでやり直す（トラッキングが外れたときなど。記録はしない）。 */
    fun retry() {
        val st = _state.value
        if (st.phase == Phase.WAITING || st.phase == Phase.TYPING) beginTrial(st.trial, currentX)
    }

    /** 中止する。自動調整なら開始前の設定に戻す。 */
    fun cancel() {
        timer?.cancel()
        if (_state.value.optimize) settings.value = original
        _state.value = State()
    }

    /** DONE のとき：適用した設定を採用して終わる。 */
    fun accept() {
        timer?.cancel()
        _state.value = State()
    }

    /** DONE のとき：開始前の設定に戻して終わる。 */
    fun revert() = cancel()

    private fun beginTrial(n: Int, x: DoubleArray) {
        currentX = x
        attempt++
        val optimize = _state.value.optimize
        if (optimize) settings.value = space.toSettings(x, original)
        buffer.clear()
        onTrialStart()
        if (phrases.isEmpty()) phrases = ArrayDeque(PHRASES.shuffled(random))
        presentedAt = clock()
        startedAt = null
        _state.update {
            it.copy(
                phase = Phase.WAITING, trial = n, target = phrases.removeFirst(), typed = "",
                remainingMs = TRIAL_MS, current = space.describe(settings.value),
            )
        }
        timer?.cancel()
        timer = scope.launch {
            while (true) {
                delay(TICK_MS)
                val now = clock()
                val started = startedAt
                if (started == null) {
                    // 打てないほど悪い設定のときは、最初の打鍵が来ないまま打ち切る（スコア 0）。練習では待ち続ける
                    if (optimize && now - presentedAt > WAIT_LIMIT_MS) break
                } else {
                    val remaining = TRIAL_MS - (now - started)
                    if (remaining <= 0) break
                    _state.update { it.copy(remainingMs = remaining) }
                }
            }
            finishTrial()
        }
    }

    private fun finishTrial() {
        val st = _state.value
        if (st.phase != Phase.WAITING && st.phase != Phase.TYPING) return
        timer?.cancel()
        val started = startedAt
        val seconds = if (started == null) 0f else ((clock() - started) / 1000f).coerceAtMost(TRIAL_MS / 1000f)
        val typed = buffer.text
        val result = TrialResult(
            attempt = attempt,
            index = st.trial,
            target = st.target,
            typed = typed,
            seconds = seconds,
            errors = PhraseScore.levenshtein(st.target, typed),
            score = if (started == null) 0.0 else PhraseScore.score(st.target, typed, seconds),
            settings = settings.value,
        )
        bo.add(currentX, result.score)
        onResult(result)
        _state.update { it.copy(phase = Phase.RESULT, history = it.history + result) }

        timer = scope.launch {
            delay(RESULT_MS)
            when {
                st.trial >= st.total -> complete()
                st.optimize -> beginTrial(st.trial + 1, bo.suggest())
                else -> beginTrial(st.trial + 1, currentX)
            }
        }
    }

    private fun complete() {
        if (!_state.value.optimize) {
            _state.update { it.copy(phase = Phase.DONE, current = space.describe(settings.value)) }
            return
        }
        val (x, predicted) = bo.best() ?: (space.toUnit(original) to 0.0)
        settings.value = space.toSettings(x, original)
        _state.update { it.copy(phase = Phase.DONE, current = space.describe(settings.value), predicted = predicted) }
    }

    companion object {
        const val TOTAL_TRIALS = 15
        const val PRACTICE_TRIALS = 10
        const val INITIAL_POINTS = 4
        const val MEASURE_MS = 6_000L
        private const val MIN_SAMPLES = 30
        /** タップ中の指先の高さのうち、この割合より低いものは指が手のひらに重なったときのトラッキングの外れとみなす */
        private const val SURFACE_PERCENTILE = 0.1
        const val TRIAL_MS = 10_000L
        private const val WAIT_LIMIT_MS = 8_000L
        private const val RESULT_MS = 1_500L
        private const val TICK_MS = 100L

        /**
         * タップしている間の指先の高さから、手のひらの表面（指先がそれ以上下がらない高さ）を推定する。
         * 最小値はトラッキングの外れに引っ張られるので、下から [SURFACE_PERCENTILE] の値を使う。
         */
        fun estimateSurface(heights: List<Float>): Float? {
            if (heights.size < MIN_SAMPLES) return null
            val sorted = heights.sorted()
            return sorted[(sorted.size * SURFACE_PERCENTILE).toInt()]
        }

        /** かなキーと「゛゜小」だけで打てる、10 秒前後で入力できる長さのフレーズ。 */
        val PHRASES = listOf(
            "さくらさく", "ねこがねる", "あおいそら", "なつやすみ", "うみのいろ",
            "やまのうえ", "あめがふる", "かぜがふく", "もりのなか", "おにぎり",
            "たこやき", "みかんいろ", "ゆきだるま", "しろいくも", "ふるいてら",
            "あたらしい", "つきみそば", "くまのこ", "にわのはな", "よるのまち",
            "すいかわり", "はるのかぜ", "ゆうやけ", "さかなつり", "ほしぞら",
            "あかいはな", "きいろいとり", "そらをとぶ", "てがみをかく", "のはらのみち",
        )
    }
}
