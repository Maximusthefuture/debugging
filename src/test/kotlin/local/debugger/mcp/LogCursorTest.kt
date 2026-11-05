package local.debugger.mcp

import org.junit.Assert.*
import org.junit.Test

class LogCursorTest {
    @Test fun `cursor excludes old messages and reads async appends`() {
        val tracker = LogCursor()
        val text = StringBuilder("old\n")
        val cursor = tracker.capture(text.length, "run")
        assertEquals("", tracker.read(text, cursor, "run", 100).text)
        tracker.beforeChange(text.length, 0, text.length)
        text.append("method started\n")
        assertEquals("method started\n", tracker.read(text, cursor, "run", 100).text)
        tracker.beforeChange(text.length, 0, text.length)
        text.append("method done\n")
        assertTrue(tracker.read(text, cursor, "run", 100).text.contains("method done"))
    }

    @Test fun `pagination never skips or repeats text`() {
        val tracker = LogCursor()
        val cursor = tracker.capture(3, "run")
        val first = tracker.read("old123456", cursor, "run", 4)
        assertEquals("1234", first.text)
        assertTrue(first.truncated)
        val second = tracker.read("old123456", first.cursor, "run", 4)
        assertEquals("56", second.text)
        assertFalse(second.truncated)
        assertEquals("", tracker.read("old123456", second.cursor, "run", 4).text)
    }

    @Test fun `clear and regrowth beyond old offset invalidates cursor`() {
        val tracker = LogCursor()
        val cursor = tracker.capture(3, "run")
        tracker.beforeChange(0, 3, 3)
        tracker.beforeChange(0, 0, 0)
        assertThrows(IllegalArgumentException::class.java) { tracker.read("new longer output", cursor, "run", 100) }
    }

    @Test fun `cyclic eviction and overwrite invalidate cursors`() {
        for ((offset, oldLength) in listOf(0 to 3, 2 to 1)) {
            val tracker = LogCursor()
            val cursor = tracker.capture(6, "run")
            tracker.beforeChange(offset, oldLength, 6)
            assertThrows(IllegalArgumentException::class.java) { tracker.read("abcdefghi", cursor, "run", 100) }
        }
    }

    @Test fun `cursor cannot cross consoles or restarted executions`() {
        val tracker = LogCursor()
        val cursor = tracker.capture(0, "console:1")
        assertThrows(IllegalArgumentException::class.java) { tracker.read("log", cursor, "console:2", 100) }
        assertThrows(IllegalArgumentException::class.java) { LogCursor().read("log", cursor, "console:1", 100) }
        assertThrows(IllegalArgumentException::class.java) { tracker.read("log", "malformed", "console:1", 100) }
    }

    @Test fun `unicode character remains intact across page boundary`() {
        val tracker = LogCursor()
        val text = "a\uD83D\uDE00b"
        val first = tracker.read(text, tracker.capture(0, "run"), "run", 2)
        assertEquals("a", first.text)
        val second = tracker.read(text, first.cursor, "run", 2)
        assertEquals("\uD83D\uDE00", second.text)
        assertEquals("b", tracker.read(text, second.cursor, "run", 2).text)
    }
}
