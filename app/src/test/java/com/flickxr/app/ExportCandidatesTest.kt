package com.flickxr.app

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 記録から打鍵の候補を切り出し、特徴量と一緒に各セッションの `candidates.csv` に書き出す（学習用、ml/ が読む）。
 * 切り出しと特徴量は端末と同じ Kotlin のコードで計算する。
 *
 * ```
 * ./gradlew testDebugUnitTest --tests '*ExportCandidatesTest*' -PreplayDir=logs
 * ```
 */
class ExportCandidatesTest {
    @Test fun export() {
        val root = System.getProperty("replayDir")?.let(::File)
        assumeTrue("-PreplayDir を指定すると候補を書き出す", root != null && root.isDirectory)
        val header = listOf("attempt", "index", "tStart", "tEnd", "row", "col", "ruleDir", "liveActions") + StrokeFeatures.NAMES
        var total = 0
        for (dir in root!!.listFiles()!!.filter { File(it, SessionLog.FRAMES).isFile }.sortedBy { it.name }) {
            val frames = SessionLog.readFrames(dir)
            val out = StringBuilder(header.joinToString(",") + "\n")
            var n = 0
            for ((attempt, fs) in frames.groupBy { it.attempt }) {
                // 候補の切り出しの数値を試すとき: -PcandMoveRepress=0.008
                val gen = System.getProperty("candMoveRepress")?.let { StrokeCandidates(moveRepressMeters = it.toFloat()) } ?: StrokeCandidates()
                for ((i, f) in fs.withIndex()) {
                    val s = gen.onFrame(f.tip) ?: continue
                    val a = s.points.first()
                    val (row, col) = FlickLayout.cellAt(a.x, a.y)!!
                    val tEnd = s.points.last().tMs
                    // 同じ区間に実機の判定が出したアクション（参考）
                    val live = fs.subList(0, i + 1).filter { it.tMs in a.tMs..tEnd + 30 && it.action.isNotEmpty() }.joinToString(" ") { it.action }
                    val feats = StrokeFeatures.compute(s).joinToString(",") { if (it.isNaN()) "" else it.toString() }
                    out.append(listOf(attempt, ++n, a.tMs, tEnd, row, col, StrokeFeatures.ruleDir(s).name, "\"${live.replace("\"", "\"\"")}\"").joinToString(","))
                        .append(',').append(feats).append('\n')
                }
            }
            File(dir, "candidates.csv").writeText(out.toString())
            // フレーズ入力の記録なら、目標の打鍵の並び（濁点などは元の文字 + ゛゜小）も書き出す（誤りの切り分け用）
            if (File(dir, SessionLog.TRIALS).isFile) {
                val t = StringBuilder("attempt,pos,row,col,dir,label\n")
                for (trial in SessionLog.readTrials(dir)) {
                    val strokes = runCatching { PromptCollection.strokesFor(trial.target) }.getOrNull() ?: continue
                    for ((pos, s) in strokes.withIndex()) t.append("${trial.attempt},$pos,${s.row},${s.col},${s.dir.name},\"${s.label}\"\n")
                }
                File(dir, "targets.csv").writeText(t.toString())
            }
            println("${dir.name}: $n candidates")
            total += n
        }
        println("total $total candidates")
    }
}
