package local.debugger.mcp

import org.junit.Assert.*
import org.junit.Test

class LogSearchTest {
    private fun search(text: String, query: String = "error", floor: Int = 0, context: Int = 0,
                       matches: Int = 20, chars: Int = 4000, scan: Int = 1000000, summary: Boolean = false,
                       ignoreCase: Boolean = true): LogSearchResult = searchLogs(text, text.count { it == '\n' } + 1,
        query, floor, ignoreCase, context, matches, chars, scan, summary)

    @Test fun `results are newest first with source line numbers and bounded context`() {
        val result = search("start\nERROR first\ncontext\nerror last\ndone\n", context = 1)
        assertEquals(2, result.matchingLines)
        assertEquals(listOf(4, 2), result.matches.map { it.line })
        assertEquals("context\nerror last\ndone", result.matches.first().text)
        assertFalse(result.scanTruncated)
        assertFalse(result.outputTruncated)
    }

    @Test fun `summary counts matching lines without returning text`() {
        val result = search("error error\nnormal\nERROR\n", summary = true)
        assertEquals(2, result.matchingLines)
        assertTrue(result.matches.isEmpty())
        assertEquals(0, result.returnedChars)
        assertFalse(result.outputTruncated)
    }

    @Test fun `cursor excludes old output including earlier text on same line`() {
        val text = "old error\nold errornew error\n"
        val result = search(text, floor = text.indexOf("new"), context = 5)
        assertEquals(1, result.matchingLines)
        assertEquals(2, result.matches.single().line)
        assertFalse(result.matches.single().text.contains("old"))
    }

    @Test fun `limits report omitted hits and unsearched buffer separately`() {
        val result = search("error old\nerror newer\nerror newest", matches = 1, scan = 24)
        assertTrue(result.scanTruncated)
        assertEquals(2, result.matchingLines)
        assertEquals(1, result.matches.size)
        assertTrue(result.outputTruncated)
        assertEquals("error newest", result.matches.single().text)
    }

    @Test fun `huge line returns compact excerpt around actual match`() {
        val result = search("x".repeat(100000) + "error" + "y".repeat(100000), chars = 256)
        assertEquals(1, result.matchingLines)
        assertTrue(result.matches.single().text.contains("error"))
        assertTrue(result.matches.single().clipped)
        assertTrue(result.returnedChars <= 256)
    }

    @Test fun `literal search handles CRLF case sensitivity and no results`() {
        assertEquals(0, search("ERROR\r\n", ignoreCase = false).matchingLines)
        assertEquals("ERROR", search("ERROR\r\n").matches.single().text)
        assertEquals(1, search("a.*b\naxb", query = ".*").matchingLines)
        assertEquals(0, search("").matchingLines)
    }

    @Test fun `search observes cancellation before scanning more data`() {
        assertThrows(InterruptedException::class.java) {
            searchLogs("error", 1, "error", 0, true, 0, 20, 4000, 1000, false) { throw InterruptedException() }
        }
    }

    @Test fun `total response budget holds across many matching lines`() {
        val result = search(("error " + "x".repeat(300) + "\n").repeat(100), chars = 256)
        assertEquals(100, result.matchingLines)
        assertTrue(result.outputTruncated)
        assertTrue(result.matches.sumOf { it.text.length } <= 256)
        assertEquals(result.returnedChars, result.matches.sumOf { it.text.length })
    }
}
