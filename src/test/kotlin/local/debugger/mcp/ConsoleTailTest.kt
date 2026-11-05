package local.debugger.mcp

import org.junit.Assert.*
import org.junit.Test

class ConsoleTailTest {
    @Test fun `tail preserves stack trace line endings and final newline`() {
        assertEquals(ConsoleTail("error\r\n  at Main.main\r\n", true),
            consoleTail("old\r\nerror\r\n  at Main.main\r\n", 2, 1000))
        assertEquals(ConsoleTail("last", true), consoleTail("first\nlast", 1, 1000))
    }
    @Test fun `character limit bounds a single enormous log line`() {
        val result = consoleTail("x".repeat(100000) + "ERROR", 200, 12)
        assertEquals("xxxxxxxERROR", result.text)
        assertTrue(result.truncated)
    }
    @Test fun `empty and short buffers are complete`() {
        assertEquals(ConsoleTail("", false), consoleTail("", 1, 10))
        assertEquals(ConsoleTail("a\n", false), consoleTail("a\n", 1, 10))
        assertEquals(ConsoleTail("a\n\n", false), consoleTail("a\n\n", 2, 10))
    }
    @Test fun `hard cap does not emit broken unicode or CRLF`() {
        assertEquals(ConsoleTail("end", true), consoleTail("old\uD83D\uDE00end", 10, 4))
        assertEquals(ConsoleTail("end", true), consoleTail("old\r\nend", 10, 4))
    }
}
