package com.ialocalbridge.utils

/** Locate tool tags while ignoring literal tag text inside JSON strings. */
object ToolTagScanner {
    data class Tag(val start: Int, val end: Int, val body: String)
    data class Scan(val tags: List<Tag>, val incompleteStart: Int?)
    private val open = Regex("<tool(?:_call)?>", RegexOption.IGNORE_CASE)
    private val close = Regex("</tool(?:_call)?>", RegexOption.IGNORE_CASE)

    fun scan(text: String): Scan {
        val tags = mutableListOf<Tag>()
        var cursor = 0
        while (cursor < text.length) {
            val match = open.find(text, cursor) ?: break
            val start = match.range.first
            val bodyStart = match.range.last + 1
            val first = text.indexOfFirstNonWhitespace(bodyStart)
            val isJson = first < text.length && (text[first] == '{' || text[first] == '[')
            var inString = false
            var escaped = false
            var pos = bodyStart
            var closing: MatchResult? = null
            while (pos < text.length) {
                val ch = text[pos]
                if (isJson && inString) {
                    if (escaped) escaped = false else if (ch == '\\') escaped = true else if (ch == '"') inString = false
                    pos++
                    continue
                }
                if (isJson && ch == '"') { inString = true; pos++; continue }
                if (ch == '<') {
                    val found = close.matchAt(text, pos)
                    if (found != null && found.range.first == pos) { closing = found; break }
                }
                pos++
            }
            if (closing == null) return Scan(tags, start)
            val end = closing.range.last + 1
            tags.add(Tag(start, end, text.substring(bodyStart, closing.range.first)))
            cursor = end
        }
        return Scan(tags, null)
    }
    private fun String.indexOfFirstNonWhitespace(from: Int): Int {
        var i = from
        while (i < length && this[i].isWhitespace()) i++
        return i
    }
}
