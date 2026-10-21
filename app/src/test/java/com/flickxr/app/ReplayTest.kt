package com.flickxr.app

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 端末から取り出した記録を、今の判定（高さのしきい値）とスライド判定で再生して比べる。
 *
 * ```
 * adb pull /sdcard/Android/data/com.flickxr.app/files/sessions logs/
 * ./gradlew testDebugUnitTest --tests '*ReplayTest*' -PreplayDir=logs
 * ```
 *
 * 結果は標準出力と `<replayDir>/replay-report.txt` に出す。replayDir を指定しないときは何もしない。
 */
class ReplayTest {
    /** 実機で選べる判定と、数値を変えた候補（判定の数値を詰めるときにここへ足す） */
    private val detectors: List<Pair<String, () -> KeyDetector>> =
        DetectorType.entries.map { it.name to it::create } + listOf(
            "SLIDE/before" to { SlideDetector(flickRatio = 0.4f, repressMeters = 0f) },
            "SLIDE/no-repress" to { SlideDetector(repressMeters = 0f) },
            "SLIDE/k0.4" to { SlideDetector(flickRatio = 0.4f) },
            "SLIDE/shallow" to { SlideDetector(shallowTaps = true) },
            "SLIDE/tail60" to { SlideDetector(tailMs = 60) },
            "SLIDE/tail30" to { SlideDetector(tailMs = 30) },
        )

    @Test fun replayRecordedSessions() {
        val root = System.getProperty("replayDir")?.let(::File)
        assumeTrue("-PreplayDir を指定すると記録を再生する", root != null && root.isDirectory)
        root!!

        val out = StringBuilder()
        val chars = IntArray(detectors.size)
        val errors = IntArray(detectors.size)
        val exact = IntArray(detectors.size)
        var attempts = 0
        var liveMismatch = 0

        for (dir in Replay.sessions(root)) {
            out.appendLine("== ${dir.name}")
            out.appendLine(listOf("trial", "target", "live(判定)").plus(detectors.map { it.first }).joinToString("\t"))
            for (a in Replay.load(dir)) {
                attempts++
                val cells = mutableListOf("${a.trial.index}", a.trial.target, "${a.trial.typed}(${a.trial.detector.name})")
                detectors.forEachIndexed { i, (name, make) ->
                    val r = Replay.run(make(), a.frames)
                    val e = PhraseScore.levenshtein(a.trial.target, r.text)
                    chars[i] += a.trial.target.length
                    errors[i] += e
                    if (e == 0) exact[i]++
                    cells += "${r.text}($e)"
                    // 記録したときと同じ判定は、再生しても同じ結果になるはず（ならなければ記録か再生が壊れている）
                    if (name == a.trial.detector.name && r.actions != a.liveActions) liveMismatch++
                }
                out.appendLine(cells.joinToString("\t"))
            }
        }
        out.appendLine()
        out.appendLine("試行 $attempts 回")
        detectors.forEachIndexed { i, (name, _) ->
            val cer = if (chars[i] == 0) 0.0 else errors[i].toDouble() / chars[i]
            out.appendLine("%-12s 文字誤り率 %.1f %%  完全一致 %d / %d".format(name, cer * 100, exact[i], attempts))
        }
        if (liveMismatch > 0) out.appendLine("注意: 記録したときの判定で再生しても、記録と一致しない試行が $liveMismatch 回（記録のあとにその判定を変えたなら当然ずれる）")
        out.appendLine("※ 記録中は実機の判定の結果を見ながら打ち、訂正（⌫）しているので、比較は記録したときの判定に有利")

        println(out)
        File(root, "replay-report.txt").writeText(out.toString())
    }
}
