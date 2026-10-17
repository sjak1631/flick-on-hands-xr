package com.flickxr.app

/** 「゛゜小」キー：直前の 1 文字を 清音 → 濁音 → 半濁音 / 小書き の順に巡回させる。 */
object KanaModifier {
    private val cycles = listOf(
        "あぁ", "いぃ", "うぅゔ", "えぇ", "おぉ",
        "かが", "きぎ", "くぐ", "けげ", "こご",
        "さざ", "しじ", "すず", "せぜ", "そぞ",
        "ただ", "ちぢ", "つっづ", "てで", "とど",
        "はばぱ", "ひびぴ", "ふぶぷ", "へべぺ", "ほぼぽ",
        "やゃ", "ゆゅ", "よょ", "わゎ",
    )

    private val next: Map<Char, Char> = buildMap {
        for (c in cycles) for (i in c.indices) put(c[i], c[(i + 1) % c.length])
    }

    /** 変換後の文字。対象外なら null。英字は大文字・小文字を入れ替える。 */
    fun modify(c: Char): Char? = next[c] ?: when {
        c.isLowerCase() -> c.uppercaseChar()
        c.isUpperCase() -> c.lowercaseChar()
        else -> null
    }
}
