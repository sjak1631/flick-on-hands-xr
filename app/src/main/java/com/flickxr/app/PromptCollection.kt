package com.flickxr.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * 学習データの収集：打鍵か単語を一定のテンポで提示し、そのとおりに打ってもらう。正解は提示したものなので、
 * フレーズ入力（訂正が混ざり、狙った文字を推定するしかない）よりきれいなラベルが取れる。
 *
 * - [Kind.SINGLE]：1 打鍵ずつ。⌫ の連打や「かき」のような同じキーの連続も混ぜる
 * - [Kind.WORDS]：短い単語を普段の速さで続けて打つ。1 打鍵ずつだと打鍵が孤立していて、続けて打つときの
 *   「打ち終わった指が低いまま次のキーへ移る」動きが学習に入らないため（実機で学習の判定がうまくいかなかった原因）
 *
 * 判定の結果では先に進めない（判定のくせがラベルに混ざらないように）。訂正もしない。
 */
class PromptCollection(
    private val scope: CoroutineScope,
    private val settings: StateFlow<KeyboardSettings>,
    /** 始めるときと、誤ってモードが変わったときに呼ぶ（キーボードをかなモードに戻す） */
    private val onReset: () -> Unit,
    private val onPrompt: (Prompt) -> Unit,
    /** 終わったときと中止したときに呼ぶ（記録を書き出す） */
    private val onFinish: () -> Unit = {},
    private val random: Random = Random.Default,
    /** [HandKeyboard] のフレームの時刻と同じ時計 */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    enum class Kind { SINGLE, WORDS }

    /** 提示した 1 打鍵。tMs は提示した時刻。単語のときは同じ単語の打鍵が同じ index・tMs で、pos が単語の中での順番 */
    data class Prompt(
        val attempt: Int,
        val index: Int,
        val tMs: Long,
        val row: Int,
        val col: Int,
        val dir: FlickDir,
        val label: String,
        val detector: DetectorType,
        val word: String = "",
        val pos: Int = 0,
    )

    /** 提示できる打鍵（かなモード）。モード切替キーは配列が変わるので除く */
    data class Target(val row: Int, val col: Int, val dir: FlickDir, val label: String)

    /** 1 回の提示。1 打鍵か、1 単語 */
    data class Item(val label: String, val strokes: List<Target>, val word: Boolean)

    enum class Phase { IDLE, COUNTDOWN, RUNNING, DONE }

    data class State(
        val phase: Phase = Phase.IDLE,
        val kind: Kind = Kind.SINGLE,
        val index: Int = 0,
        val total: Int = 0,
        val current: String = "",
        val next: String = "",
        /** 今の提示の経過（0..1） */
        val progress: Float = 0f,
        /** 判定が最後に出した文字（1 打鍵のときだけ参考に出す） */
        val detected: String = "",
        /** 1 打鍵のときは 1 回の提示の長さ、単語のときは 1 打鍵あたりの長さ */
        val intervalMs: Long = DEFAULT_INTERVAL_MS,
        val perStrokeMs: Long = DEFAULT_PER_STROKE_MS,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    val active: Boolean get() = _state.value.phase != Phase.IDLE

    var sessionId = 0L
        private set

    /** 記録中なら試行の通し番号（このモードでは 1 回の収集 = 1 試行） */
    val recordingAttempt: Int?
        get() = 1.takeIf { _state.value.phase == Phase.COUNTDOWN || _state.value.phase == Phase.RUNNING }

    private var job: Job? = null

    fun start(kind: Kind = Kind.SINGLE) {
        if (active) return
        sessionId = System.currentTimeMillis()
        val items = when (kind) {
            Kind.SINGLE -> sequence(DEFAULT_COUNT, random).map { Item(it.label, listOf(it), word = false) }
            Kind.WORDS -> WORDS.shuffled(random).take(DEFAULT_WORDS).map { Item(it, strokesFor(it), word = true) }
        }
        onReset()
        _state.update { State(phase = Phase.COUNTDOWN, kind = kind, total = items.size, next = items.first().label, intervalMs = it.intervalMs, perStrokeMs = it.perStrokeMs) }
        job = scope.launch {
            beat(COUNTDOWN_MS) { _state.update { s -> s.copy(progress = it) } }
            for ((i, item) in items.withIndex()) {
                val t = clock()
                val detector = settings.value.detector
                for ((pos, s) in item.strokes.withIndex()) {
                    onPrompt(Prompt(1, i + 1, t, s.row, s.col, s.dir, s.label, detector, if (item.word) item.label else "", pos))
                }
                _state.update { it.copy(phase = Phase.RUNNING, index = i + 1, current = item.label, next = items.getOrNull(i + 1)?.label ?: "", progress = 0f) }
                val st = _state.value
                val ms = if (item.word) WORD_BASE_MS + st.perStrokeMs * item.strokes.size else st.intervalMs
                beat(ms) { f -> _state.update { it.copy(progress = f) } }
            }
            _state.update { it.copy(phase = Phase.DONE, current = "", next = "", progress = 1f) }
            onFinish()
        }
    }

    /** テンポを変える（次の提示から）。1 打鍵のときは提示の長さ、単語のときは 1 打鍵あたりの長さ */
    fun changeInterval(deltaMs: Long) = _state.update {
        if (it.kind == Kind.WORDS) it.copy(perStrokeMs = (it.perStrokeMs + deltaMs / 2).coerceIn(MIN_PER_STROKE_MS, MAX_PER_STROKE_MS))
        else it.copy(intervalMs = (it.intervalMs + deltaMs).coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS))
    }

    /** 確定した入力を受け取る。収集中なら true（通常のテキストには入れない）。 */
    fun onAction(action: KeyAction): Boolean {
        val phase = _state.value.phase
        if (phase != Phase.COUNTDOWN && phase != Phase.RUNNING) return false
        if (action == KeyAction.NextMode) onReset()
        _state.update { it.copy(detected = SessionLog.encode(action)) }
        return true
    }

    fun cancel() {
        job?.cancel()
        _state.update { State(kind = it.kind, intervalMs = it.intervalMs, perStrokeMs = it.perStrokeMs) }
        onFinish()
    }

    /** DONE の表示を閉じる */
    fun close() = cancel()

    private suspend fun beat(ms: Long, onProgress: (Float) -> Unit) {
        val start = clock()
        while (true) {
            val elapsed = clock() - start
            if (elapsed >= ms) break
            onProgress(elapsed.toFloat() / ms)
            delay(minOf(TICK_MS, ms - elapsed))
        }
    }

    companion object {
        const val DEFAULT_COUNT = 60
        /** 画面を見ながらだと忙しいので長め（提示が変わったら音も鳴らす） */
        const val DEFAULT_INTERVAL_MS = 2000L
        const val MIN_INTERVAL_MS = 600L
        const val MAX_INTERVAL_MS = 3000L
        const val DEFAULT_WORDS = 20
        /** 単語の提示の長さ = WORD_BASE_MS + 1 打鍵あたりの長さ × 打鍵数 */
        const val WORD_BASE_MS = 1500L
        const val DEFAULT_PER_STROKE_MS = 700L
        const val MIN_PER_STROKE_MS = 300L
        const val MAX_PER_STROKE_MS = 1500L
        private const val COUNTDOWN_MS = 3000L
        private const val TICK_MS = 50L
        /** 直前と同じキーを続ける割合 */
        private const val REPEAT_KEY = 0.25
        /** 単押しの割合 */
        private const val TAP = 0.4

        val TARGETS: List<Target> = buildList {
            for ((r, row) in FlickLayout.rows(InputMode.KANA).withIndex()) for ((c, key) in row.withIndex()) {
                if (key.isFunction) {
                    if (key.actionFor(FlickDir.NONE) != KeyAction.NextMode) add(Target(r, c, FlickDir.NONE, key.label))
                } else {
                    for (d in FlickDir.entries) (key.flicks[d] as? KeyAction.Text)?.let { add(Target(r, c, d, it.text)) }
                }
            }
        }

        private val MODIFY: Target = TARGETS.first { FlickLayout.rows(InputMode.KANA)[it.row][it.col].actionFor(FlickDir.NONE) == KeyAction.Modify }

        /**
         * かなと「゛゜小」だけで打てる、2〜5 文字の単語。濁点・半濁点・小書き・長音を含むものも混ぜる。
         * 同じキーが続くもの（かき、うえ、あお、もも）も入れる
         */
        val WORDS: List<String> = listOf(
            "さくら", "ねこ", "いぬ", "そら", "うみ", "やま", "かわ", "はな", "とり", "くも",
            "ほし", "つき", "あめ", "ゆき", "かぜ", "みず", "ひかり", "ことば", "にもつ", "たまご",
            "りんご", "みかん", "すいか", "いちご", "めがね", "かばん", "でんわ", "てがみ", "じかん", "がっこう",
            "きょう", "あした", "きのう", "しゃしん", "ちょっと", "ぱん", "ぴあの", "ぷりん", "ふね", "くるま",
            "えんぴつ", "はさみ", "おちゃ", "ごはん", "さかな", "やさい", "とまと", "ぶどう", "ばなな", "れもん",
            "もも", "かき", "うえ", "あお", "いえ", "こうえん", "えき", "みち", "まち", "よる",
            "あさ", "ひる", "はる", "なつ", "あき", "ふゆ", "らーめん", "こーひー", "ろうそく", "ぬりえ",
            "へや", "せなか", "わたし", "おかし", "けしき", "ゆうひ", "たいよう", "ほんや", "ねむい", "むかし",
        )

        /** 単語を打鍵の並びに分ける。濁点・半濁点・小書きは、元の文字のあとに「゛゜小」を何回か押す */
        fun strokesFor(word: String): List<Target> = word.flatMap { c ->
            TARGETS.firstOrNull { it.label == c.toString() }?.let { return@flatMap listOf(it) }
            for (base in TARGETS) {
                if (base.label.length != 1) continue
                var ch = base.label[0]
                for (k in 1..3) {
                    ch = KanaModifier.modify(ch) ?: break
                    if (ch == base.label[0]) break
                    if (ch == c) return@flatMap listOf(base) + List(k) { MODIFY }
                }
            }
            error("「$c」はかなキーと「゛゜小」で打てない")
        }

        /** 提示する順番。同じキーの連続（⌫ は 2〜4 回続けることもある）と、単押しを多めに混ぜる */
        fun sequence(count: Int, random: Random): List<Target> {
            val out = mutableListOf<Target>()
            while (out.size < count) {
                val prev = out.lastOrNull()
                val sameKey = prev?.let { p -> TARGETS.filter { it.row == p.row && it.col == p.col } }
                if (sameKey != null && random.nextDouble() < REPEAT_KEY) {
                    out += sameKey.random(random)
                    continue
                }
                val key = TARGETS.map { it.row to it.col }.distinct().random(random)
                val onKey = TARGETS.filter { it.row == key.first && it.col == key.second }
                val tap = onKey.first { it.dir == FlickDir.NONE }
                val t = if (onKey.size == 1 || random.nextDouble() < TAP) tap else onKey.filter { it.dir != FlickDir.NONE }.random(random)
                val run = if (t.label == "⌫") random.nextInt(1, 5) else 1
                repeat(run) { out += t }
            }
            return out.take(count)
        }
    }
}
