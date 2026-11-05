package local.debugger.mcp

internal data class ConsoleTail(val text: String, val truncated: Boolean)

/** Scan only the requested suffix; never copy the entire console document. */
internal fun consoleTail(text: CharSequence, maxLines: Int, maxChars: Int): ConsoleTail {
    require(maxLines > 0 && maxChars > 0)
    val end = text.length
    var start = (end - maxChars).coerceAtLeast(0)
    var lines = 0
    var i = end - 1
    if (i >= 0 && text[i] == '\n') i-- // final newline belongs to the last non-empty line
    while (i >= start) {
        if (text[i] == '\n' && ++lines == maxLines) {
            start = i + 1
            break
        }
        i--
    }
    // A hard character cap may cut through a UTF-16 surrogate pair or CRLF.
    if (start > 0 && start < end &&
        (text[start].isLowSurrogate() && text[start - 1].isHighSurrogate() ||
            text[start] == '\n' && text[start - 1] == '\r')) start++
    return ConsoleTail(text.subSequence(start, end).toString(), start > 0)
}
