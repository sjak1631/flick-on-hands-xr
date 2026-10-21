package com.flickxr.app

import java.io.File

/** 記録した軌跡を判定に流し直す。 */
object Replay {
    data class Attempt(val trial: SessionLog.Trial, val frames: List<SessionLog.Frame>) {
        /** 記録したときに実機の判定が出したアクション */
        val liveActions: List<String> get() = frames.map { it.action }.filter { it.isNotEmpty() }
    }

    data class Result(val text: String, val actions: List<String>)

    /** 最後まで終えた試行だけを読む（やり直した・中止した試行は trials.csv に行がない）。 */
    fun load(sessionDir: File): List<Attempt> {
        val frames = SessionLog.readFrames(sessionDir).groupBy { it.attempt }
        return SessionLog.readTrials(sessionDir).map { Attempt(it, frames[it.attempt].orEmpty()) }
    }

    /** 試行の始めは実機と同じく、新しい判定（かなモード、押下なし）から始める。 */
    fun run(detector: KeyDetector, frames: List<SessionLog.Frame>): Result {
        val buffer = TextBuffer()
        val actions = mutableListOf<String>()
        for (f in frames) detector.onFrame(f.tip)?.let {
            buffer.apply(it)
            actions += SessionLog.encode(it)
        }
        return Result(buffer.text, actions)
    }

    fun sessions(root: File): List<File> = root.walk().filter { File(it, SessionLog.TRIALS).isFile }.sortedBy { it.name }.toList()
}
