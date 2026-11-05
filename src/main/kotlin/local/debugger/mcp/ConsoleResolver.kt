package local.debugger.mcp

import com.intellij.execution.console.ConsoleViewWrapperBase
import com.intellij.execution.console.DuplexConsoleView
import com.intellij.execution.ui.ExecutionConsole
import java.util.Collections
import java.util.IdentityHashMap

internal fun resolveConsole(console: ExecutionConsole?, accept: (ExecutionConsole) -> Boolean): ExecutionConsole? {
    val visited = Collections.newSetFromMap(IdentityHashMap<ExecutionConsole, Boolean>())
    fun visit(current: ExecutionConsole?): ExecutionConsole? {
        if (current == null || !visited.add(current)) return null
        if (accept(current)) return current
        return when (current) {
            is ConsoleViewWrapperBase -> visit(current.delegate)
            is DuplexConsoleView<*, *> -> visit(current.primaryConsoleView) ?: visit(current.secondaryConsoleView)
            else -> null
        }
    }
    return visit(console)
}
