package local.debugger.mcp

import com.intellij.execution.console.ConsoleViewWrapperBase
import com.intellij.execution.ui.ConsoleView
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class ConsoleResolverTest {
    private fun leaf(): ConsoleView = Proxy.newProxyInstance(ConsoleView::class.java.classLoader,
        arrayOf(ConsoleView::class.java)) { _, _, _ -> null } as ConsoleView

    @Test fun `standard IDE wrappers expose the underlying output console`() {
        val text = leaf()
        val wrapped = ConsoleViewWrapperBase(ConsoleViewWrapperBase(text))
        assertSame(text, resolveConsole(wrapped) { it === text })
    }

    @Test fun `unknown console is reported unsupported`() {
        assertNull(resolveConsole(ConsoleViewWrapperBase(leaf())) { false })
        assertNull(resolveConsole(null) { true })
    }

    @Test fun `malformed wrapper cycle does not overflow stack`() {
        val cycle = object : ConsoleViewWrapperBase(leaf()) {
            override fun getDelegate(): ConsoleView = this
        }
        assertNull(resolveConsole(cycle) { false })
    }
}
