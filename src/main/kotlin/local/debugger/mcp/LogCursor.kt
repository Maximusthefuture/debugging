package local.debugger.mcp

import java.util.UUID

/** One tracker per console document. Non-append edits invalidate every older cursor. */
internal class LogCursor {
    private val epoch = UUID.randomUUID().toString()
    private var revision = 0L

    fun beforeChange(offset: Int, oldLength: Int, documentLength: Int) {
        if (offset != documentLength || oldLength != 0) revision++
    }

    fun capture(length: Int, scope: String): String = "$epoch:$revision:$length:$scope"

    fun read(text: CharSequence, cursor: String, scope: String, maxChars: Int): LogDelta {
        require(maxChars > 0)
        val start = offset(cursor, scope, text.length)
        var end = (start.toLong() + maxChars).coerceAtMost(text.length.toLong()).toInt()
        if (end < text.length && end > start && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        // Leave a split surrogate for the next page, rather than silently dropping it.
        require(end > start || start == text.length) { "maxChars is too small for the next Unicode character; use at least 2." }
        return LogDelta(text.subSequence(start, end).toString(), capture(end, scope), end < text.length)
    }

    fun offset(cursor: String, scope: String, length: Int): Int {
        val parts = cursor.split(':', limit = 4)
        require(parts.size == 4 && parts[0] == epoch && parts[1].toLongOrNull() == revision && parts[3] == scope) {
            "Cursor is invalid or console was cleared, edited, rotated, or restarted. Capture a new cursor; previous output may have been lost."
        }
        val start = parts[2].toIntOrNull()
        require(start != null && start in 0..length) { "Invalid cursor offset. Capture a new cursor." }
        return start
    }
}

internal data class LogDelta(val text: String, val cursor: String, val truncated: Boolean)
