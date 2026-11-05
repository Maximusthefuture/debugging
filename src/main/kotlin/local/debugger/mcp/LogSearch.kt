package local.debugger.mcp

internal data class LogHit(val line: Int, val matchOffset: Int, val startOffset: Int, val endOffset: Int,
                           val text: String, val clipped: Boolean)
internal data class LogSearchResult(val matches: List<LogHit>, val matchingLines: Int, val scannedChars: Int,
                                   val scanTruncated: Boolean, val outputTruncated: Boolean, val returnedChars: Int)

/** Search a bounded suffix of an immutable console snapshot on a worker thread. */
internal fun searchLogs(text: CharSequence, totalLines: Int, query: String, lowerBound: Int,
                        ignoreCase: Boolean, contextLines: Int, maxMatches: Int, maxChars: Int,
                        maxScanChars: Int, summaryOnly: Boolean, checkCancelled: () -> Unit = {}): LogSearchResult {
    require(query.isNotEmpty() && '\n' !in query && '\r' !in query)
    require(lowerBound in 0..text.length && maxScanChars > 0 && maxChars >= query.length)
    val floor = maxOf(lowerBound, text.length - maxScanChars)
    var end = text.length
    var line = totalLines
    if (end > floor && text[end - 1] == '\n') { end--; line-- }
    val hits = mutableListOf<LogHit>()
    var found = 0
    var returned = 0
    while (end > floor) {
        checkCancelled()
        var start = end
        while (start > floor && text[start - 1] != '\n') {
            start--
            if (start % 4096 == 0) checkCancelled()
        }
        val contentEnd = if (end > start && text[end - 1] == '\r') end - 1 else end
        val relative = text.subSequence(start, contentEnd).toString().indexOf(query, ignoreCase = ignoreCase)
        if (relative >= 0) {
            found++
            val match = start + relative
            val budget = minOf(2000.coerceAtLeast(query.length), maxChars - returned)
            if (!summaryOnly && hits.size < maxMatches && budget >= query.length) {
                // Context is bounded even for a console containing a single enormous line.
                var a = start
                var b = end
                repeat(contextLines) {
                    if (a > lowerBound) {
                        a--
                        while (a > lowerBound && text[a - 1] != '\n' && match - a < budget) a--
                    }
                    if (b < text.length) {
                        b++
                        while (b < text.length && text[b] != '\n' && b - match < budget) b++
                    }
                }
                if (b > a && text[b - 1] == '\r') b--
                val desiredStart = a
                val desiredEnd = b
                if (b - a > budget) {
                    a = maxOf(a, match - minOf(200, (budget - query.length) / 2))
                    b = minOf(b, a + budget)
                }
                if (a > 0 && a < text.length && text[a].isLowSurrogate() && text[a - 1].isHighSurrogate()) a++
                if (b < text.length && b > a && text[b - 1].isHighSurrogate() && text[b].isLowSurrogate()) b--
                val excerpt = text.subSequence(a, b).toString()
                returned += excerpt.length
                hits.add(LogHit(line, match, a, b, excerpt, a != desiredStart || b != desiredEnd))
            }
        }
        if (start == floor) break
        end = start - 1
        line--
    }
    return LogSearchResult(hits, found, text.length - floor, floor > lowerBound,
        !summaryOnly && hits.size < found, returned)
}
