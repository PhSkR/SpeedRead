package com.speedread.rsvp.engine

enum class TrailingBreak { LINE, PARAGRAPH }

data class RsvpWord(
    val text: String,
    val position: Int,
    val orpIndex: Int = calculateOrp(text),
    val trailingBreak: TrailingBreak? = null,
    val absoluteStartIndex: Int = position,
    val absoluteEndIndex: Int = position
) {
    companion object {
        fun calculateOrp(word: String): Int {
            return when {
                word.length <= 1 -> 0
                word.length <= 5 -> 1
                word.length <= 9 -> 2
                word.length <= 13 -> 3
                else -> 4
            }
        }
    }
}