package com.flickxr.app

import java.io.BufferedWriter
import java.io.File

/**
 * 自動調整 1 回分（セッション）の記録。後から判定を JVM で再生して比べるために、フレームごとの指先も残す。
 *
 * - `frames.csv`：フレーズ入力中の 1 フレーム 1 行。attempt は試行ごと（やり直しも別）の通し番号
 * - `trials.csv`：最後まで終えた試行の結果。やり直した・中止した試行は行がない
 */
class SessionLog(val dir: File) {
    private val frames: BufferedWriter
    private val trials: BufferedWriter

    init {
        dir.mkdirs()
        frames = File(dir, FRAMES).bufferedWriter()
        trials = File(dir, TRIALS).bufferedWriter()
        frames.write(FRAME_HEADER + "\n")
        trials.write(TRIAL_HEADER + "\n")
    }

    fun frame(attempt: Int, tMs: Long, tip: TipFrame?, touching: Boolean, action: KeyAction?) {
        frames.write(formatFrame(attempt, tMs, tip, touching, action) + "\n")
    }

    fun trial(r: TrialResult, palmSurface: Float?) {
        trials.write(formatTrial(r, palmSurface) + "\n")
        trials.flush()
        frames.flush()
    }

    fun close() {
        frames.close()
        trials.close()
    }

    /** 記録から読み戻した 1 フレーム。tip == null は指先を見失ったか、キーボードが非表示。 */
    data class Frame(val attempt: Int, val tMs: Long, val tip: TipFrame?, val touching: Boolean, val action: String)

    data class Trial(
        val attempt: Int,
        val index: Int,
        val target: String,
        val typed: String,
        val score: Double,
        /** 記録したときに実機で使っていた判定（判定を選べるようになる前の記録は THRESHOLD） */
        val detector: DetectorType,
    )

    companion object {
        const val FRAMES = "frames.csv"
        const val TRIALS = "trials.csv"
        private const val FRAME_HEADER = "attempt,t,tracked,x,y,h,width,height,touchMeters,touching,action"
        private const val TRIAL_HEADER =
            "attempt,trial,target,typed,seconds,errors,score,offsetX,offsetY,offsetZ,widthScale,heightScale,roll,tilt,yaw,touchMeters,palmSurface,detector"

        fun encode(a: KeyAction?): String = when (a) {
            null -> ""
            is KeyAction.Text -> a.text
            KeyAction.Backspace -> "<BS>"
            KeyAction.Space -> "<SP>"
            KeyAction.Enter -> "<EN>"
            KeyAction.NextMode -> "<MODE>"
            KeyAction.Modify -> "<MOD>"
        }

        fun formatFrame(attempt: Int, tMs: Long, tip: TipFrame?, touching: Boolean, action: KeyAction?): String {
            val f = tip?.let { listOf(1, it.x, it.y, it.h, it.widthMeters, it.heightMeters, it.touchMeters) } ?: listOf(0, "", "", "", "", "", "")
            return (listOf(attempt, tMs) + f + listOf(if (touching) 1 else 0, quote(encode(action)))).joinToString(",")
        }

        fun formatTrial(r: TrialResult, palmSurface: Float?): String {
            val s = r.settings
            return listOf(
                r.attempt, r.index, quote(r.target), quote(r.typed), r.seconds, r.errors, r.score,
                s.offsetX, s.offsetY, s.offsetZ, s.widthScale, s.heightScale, s.rollDegrees, s.tiltDegrees, s.yawDegrees, s.touchMeters,
                palmSurface ?: "", s.detector.name,
            ).joinToString(",")
        }

        fun parseFrame(line: String): Frame {
            val c = splitCsv(line)
            val tMs = c[1].toLong()
            val tip = if (c[2] == "1") {
                TipFrame(tMs, c[3].toFloat(), c[4].toFloat(), c[5].toFloat(), c[6].toFloat(), c[7].toFloat(), c[8].toFloat())
            } else null
            return Frame(c[0].toInt(), tMs, tip, c[9] == "1", c[10])
        }

        fun parseTrial(line: String): Trial {
            val c = splitCsv(line)
            val detector = DetectorType.entries.find { it.name == c.getOrNull(17) } ?: DetectorType.THRESHOLD
            return Trial(c[0].toInt(), c[1].toInt(), c[2], c[3], c[6].toDouble(), detector)
        }

        fun readFrames(dir: File): List<Frame> = File(dir, FRAMES).readLines().drop(1).filter { it.isNotBlank() }.map(::parseFrame)
        fun readTrials(dir: File): List<Trial> = File(dir, TRIALS).readLines().drop(1).filter { it.isNotBlank() }.map(::parseTrial)

        private fun quote(v: String) = "\"" + v.replace("\"", "\"\"").replace("\n", "\\n") + "\""

        /** ダブルクォートで囲んだ値（"" はエスケープ）に対応した 1 行の分割。 */
        internal fun splitCsv(line: String): List<String> {
            val out = mutableListOf<String>()
            val sb = StringBuilder()
            var quoted = false
            var i = 0
            while (i < line.length) {
                val ch = line[i]
                when {
                    quoted && ch == '"' && line.getOrNull(i + 1) == '"' -> { sb.append('"'); i++ }
                    ch == '"' -> quoted = !quoted
                    ch == ',' && !quoted -> { out += sb.toString(); sb.clear() }
                    else -> sb.append(ch)
                }
                i++
            }
            out += sb.toString()
            return out.map { it.replace("\\n", "\n") }
        }
    }
}
