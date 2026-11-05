package local.debugger.mcp

import com.google.gson.Gson
import com.intellij.debugger.ui.breakpoints.JavaLineBreakpointType
import com.intellij.execution.RunManager
import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.ui.ExecutionConsole
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.mcpserver.McpExpectedError
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.project
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.util.Key
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.SimpleColoredText
import com.intellij.ui.SimpleTextAttributes
import com.intellij.xdebugger.*
import com.intellij.xdebugger.breakpoints.*
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.*
import com.intellij.xdebugger.frame.presentation.XValuePresentation
import kotlinx.coroutines.*
import java.nio.file.Path
import java.util.Collections
import java.util.WeakHashMap
import java.util.UUID
import javax.swing.Icon
import kotlin.coroutines.coroutineContext

/** Extends IDEA's MCP server; never opens a second network listener. */
class DebuggerTools : McpToolset {
    companion object {
        private val LOG_CURSOR = Key.create<LogCursor>("local.debugger.mcp.logCursor")
    }
    private val gson = Gson()
    private val ids = Collections.synchronizedMap(WeakHashMap<XDebugSession, String>())
    private val consoleIds = WeakHashMap<RunContentDescriptor, String>() // accessed only on EDT
    private fun consoleId(d: RunContentDescriptor): String = consoleIds.getOrPut(d) { UUID.randomUUID().toString() }
    private fun id(s: XDebugSession): String = ids.getOrPut(s) { UUID.randomUUID().toString() }
    private fun json(value: Any?): String = gson.toJson(value)
    private fun fail(message: String): Nothing = throw McpExpectedError(message)

    private suspend fun <T> onEdt(block: suspend () -> T): T = withContext(Dispatchers.EDT) { block() }
    private suspend fun <T> debuggerReply(block: suspend CoroutineScope.() -> T): T = try {
        withTimeout(10_000, block)
    } catch (_: TimeoutCancellationException) {
        fail("Debugger did not reply within 10 seconds. Check session state. An evaluation may still be running; do not automatically retry expressions with side effects.")
    }
    private fun manager(p: Project) = XDebuggerManager.getInstance(p)
    private fun session(p: Project, sessionId: String?): XDebugSession {
        val sessions = manager(p).debugSessions.filterNot { it.isStopped }
        if (sessionId != null) return sessions.find { id(it) == sessionId }
            ?: fail("Unknown or stopped session. Call jdebug_sessions again.")
        if (sessions.size != 1) fail("Expected exactly one live debug session; call jdebug_sessions and pass sessionId.")
        return sessions.single()
    }

    private fun status(s: XDebugSession): Map<String, Any?> = mapOf(
        "sessionId" to id(s), "name" to s.sessionName,
        "state" to when { s.isStopped -> "stopped"; s.isSuspended -> "suspended"; else -> "running" },
        "position" to position(s.currentPosition)
    )
    private fun position(p: XSourcePosition?): Map<String, Any>? = p?.let {
        mapOf("file" to it.file.path, "line" to it.line + 1)
    }

    @McpTool
    @McpDescription("Start an existing run configuration in Debug by its exact name. Returns when launch is submitted, not when a session or breakpoint is reached. Poll jdebug_sessions, then jdebug_status. Do not automatically retry a pending launch. Does not restart an existing live debug session with the same name.")
    suspend fun jdebug_start(configurationName: String): String {
        val p = coroutineContext.project
        return onEdt {
            if (configurationName.isBlank()) fail("configurationName must not be blank.")
            val matches = RunManager.getInstance(p).allSettings.filter { it.name == configurationName }
            if (matches.isEmpty()) fail("Unknown run configuration '$configurationName'. Call get_run_configurations first.")
            if (matches.size != 1) fail("Multiple run configurations named '$configurationName'. Rename them in the IDE to make the name unique.")
            if (manager(p).debugSessions.any { !it.isStopped && it.sessionName == configurationName })
                fail("A live debug session named '$configurationName' already exists. Call jdebug_sessions to use it or stop it explicitly.")
            val settings = matches.single()
            val executor = DefaultDebugExecutor.getDebugExecutorInstance()
            val runner = ProgramRunner.getRunner(executor.id, settings.configuration)
                ?: fail("Run configuration '$configurationName' does not support Debug.")
            settings.checkSettings()
            FileDocumentManager.getInstance().saveAllDocuments()
            val environment = ExecutionEnvironmentBuilder.create(executor, settings).build()
            runner.execute(environment)
            json(mapOf("submitted" to "debug", "configurationName" to configurationName))
        }
    }

    @McpTool
    @McpDescription("List live IDE debug sessions and their IDs. Use jdebug_start to launch a run configuration in Debug. Pass projectPath when multiple projects are open.")
    suspend fun jdebug_sessions(): String {
        val p = coroutineContext.project
        return onEdt { json(manager(p).debugSessions.filterNot { it.isStopped }.map(::status)) }
    }

    @McpTool
    @McpDescription("Read the current state of a debug session. Omit sessionId only when exactly one session exists.")
    suspend fun jdebug_status(sessionId: String? = null): String {
        val p = coroutineContext.project
        return onEdt { json(status(session(p, sessionId))) }
    }

    private fun textConsole(console: ExecutionConsole?): ConsoleViewImpl? =
        resolveConsole(console) { it is ConsoleViewImpl } as? ConsoleViewImpl

    private fun logDescriptor(p: Project, consoleId: String?, sessionId: String?): RunContentDescriptor {
        if (consoleId != null && sessionId != null) fail("Pass consoleId or sessionId, not both.")
        val descriptors = RunContentManager.getInstance(p).allDescriptors
        return when {
            sessionId != null -> session(p, sessionId).runContentDescriptor
            consoleId != null -> descriptors.find { consoleId(it) == consoleId }
                ?: fail("Console tab not found. Call jdebug_consoles again.")
            else -> {
                val readable = descriptors.filter { textConsole(it.executionConsole) != null }
                if (readable.size != 1) fail("Expected one readable console. Call jdebug_consoles and pass consoleId.")
                readable.single()
            }
        }
    }

    private fun logDocument(d: RunContentDescriptor): Pair<ConsoleViewImpl, Document> {
        val console = textConsole(d.executionConsole)
            ?: fail("Unsupported console type: ${d.executionConsole?.javaClass?.name ?: "none"}. Call jdebug_consoles to inspect consoleType.")
        console.flushDeferredText()
        val document = console.editor?.document ?: fail("Console editor is not initialized. Open the console tab in IDEA and retry.")
        return console to document
    }

    private fun logTracker(document: Document): LogCursor {
        document.getUserData(LOG_CURSOR)?.let { return it }
        val tracker = LogCursor()
        document.putUserData(LOG_CURSOR, tracker)
        // The listener and tracker belong to the document and retain no project or toolset.
        document.addDocumentListener(object : DocumentListener {
            override fun beforeDocumentChange(event: DocumentEvent) {
                tracker.beforeChange(event.offset, event.oldLength, event.document.textLength)
            }
        })
        return tracker
    }

    private fun logScope(d: RunContentDescriptor) = "${consoleId(d)}:${d.executionId}"

    @McpTool
    @McpDescription("Search application logs INSIDE the IDE; prefer this over dumping logs into agent context. Literal single-line query, newest matching lines first, case-insensitive by default. Returns only bounded excerpts and counts of matching lines in the scanned range. summaryOnly returns counts without log text. Optional afterCursor restricts search to output after a captured cursor. maxMatches 1..100, maxChars 256..20000 (default 4000), contextLines 0..5. Scans only the newest maxScanChars (default 1000000, max 5000000); scanTruncated means older text was not searched. Does not count all occurrences within a matching line. Not regex. Snapshot offsets and line numbers may change as the IDE buffer changes.")
    suspend fun jdebug_search_logs(query: String, consoleId: String? = null, sessionId: String? = null,
                                   afterCursor: String? = null, ignoreCase: Boolean = true, contextLines: Int = 1,
                                   maxMatches: Int = 20, maxChars: Int = 4000, maxScanChars: Int = 1000000,
                                   summaryOnly: Boolean = false): String {
        if (query.isEmpty() || query.length > 256 || '\n' in query || '\r' in query) fail("query must contain 1..256 characters on a single line.")
        if (contextLines !in 0..5 || maxMatches !in 1..100 || maxChars !in 256..20000 || maxScanChars !in 1..5000000)
            fail("Use contextLines 0..5, maxMatches 1..100, maxChars 256..20000, maxScanChars 1..5000000.")
        val p = coroutineContext.project
        data class SearchSnapshot(val text: CharSequence, val lines: Int, val floor: Int, val id: String, val stamp: Long, val pending: Boolean)
        val snapshot = onEdt {
            val d = logDescriptor(p, consoleId, sessionId)
            val (console, document) = logDocument(d)
            val floor = if (afterCursor == null) 0 else try { logTracker(document).offset(afterCursor, logScope(d), document.textLength) }
                catch (e: IllegalArgumentException) { fail(e.message ?: "Invalid cursor.") }
            SearchSnapshot(document.immutableCharSequence, document.lineCount, floor, consoleId(d), document.modificationStamp, console.hasDeferredOutput())
        }
        val result = withContext(Dispatchers.Default) {
            val context = coroutineContext
            searchLogs(snapshot.text, snapshot.lines, query, snapshot.floor, ignoreCase, contextLines, maxMatches,
                maxChars, maxScanChars, summaryOnly) { context.ensureActive() }
        }
        return json(mapOf("consoleId" to snapshot.id, "snapshotStamp" to snapshot.stamp,
            "matchingLines" to result.matchingLines, "matches" to result.matches,
            "scannedChars" to result.scannedChars, "scanTruncated" to result.scanTruncated,
            "outputTruncated" to result.outputTruncated, "returnedChars" to result.returnedChars,
            "summaryOnly" to summaryOnly, "pendingOutput" to snapshot.pending))
    }

    @McpTool
    @McpDescription("Capture the current END of an IDE Run/Debug console before invoking a method. Pass the returned cursor and consoleId to jdebug_logs_since to read only subsequent output. Capturing flushes pending console text first. Not a guarantee that later messages were caused by that method.")
    suspend fun jdebug_log_cursor(consoleId: String? = null, sessionId: String? = null): String {
        val p = coroutineContext.project
        return onEdt {
            val d = logDescriptor(p, consoleId, sessionId)
            val (_, document) = logDocument(d)
            json(mapOf("consoleId" to consoleId(d), "cursor" to logTracker(document).capture(document.textLength, logScope(d))))
        }
    }

    @McpTool
    @McpDescription("Read only output after a cursor from jdebug_log_cursor or a previous jdebug_logs_since. Always reuse returned consoleId and cursor for subsequent reads. maxChars: 1..100000. waitMs: 0..30000; optionally wait for a literal case-sensitive contains substring, otherwise wait for any new output. Waiting does not block IDE UI. Returns on match, timeout, or output limit. Cursor advances only through returned text. Console edits/clear/buffer eviction/restart invalidate cursors explicitly. No automatic retry of the Java method is needed.")
    suspend fun jdebug_logs_since(cursor: String, consoleId: String? = null, sessionId: String? = null,
                                  maxChars: Int = 20000, waitMs: Int = 0, contains: String? = null): String {
        if (maxChars !in 1..100000) fail("maxChars must be 1..100000.")
        if (waitMs !in 0..30000) fail("waitMs must be 0..30000.")
        if (contains != null && (contains.isEmpty() || contains.length > maxChars)) fail("contains must contain 1..maxChars characters.")
        val p = coroutineContext.project
        val targetId = onEdt { consoleId(logDescriptor(p, consoleId, sessionId)) }
        val started = System.nanoTime()
        while (true) {
            coroutineContext.ensureActive()
            val snapshot = onEdt {
                val d = logDescriptor(p, targetId, null)
                val (console, document) = logDocument(d)
                val delta = try { logTracker(document).read(document.charsSequence, cursor, logScope(d), maxChars) }
                    catch (e: IllegalArgumentException) { fail(e.message ?: "Invalid cursor.") }
                Triple(delta, console.hasDeferredOutput(), d.displayName)
            }
            val (delta, pending, name) = snapshot
            val matched = contains?.let { delta.text.contains(it) } ?: delta.text.isNotEmpty()
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            val timedOut = waitMs > 0 && elapsedMs >= waitMs && !matched && !delta.truncated
            if (matched || delta.truncated || waitMs == 0 || timedOut) {
                return json(mapOf("consoleId" to targetId, "name" to name,
                    "text" to delta.text, "cursor" to delta.cursor, "truncated" to delta.truncated,
                    "matched" to matched, "timedOut" to timedOut, "pendingOutput" to pending,
                    "reason" to when { matched -> if (contains == null) "output" else "matched"; delta.truncated -> "limit"; timedOut -> "timeout"; delta.text.isEmpty() -> "empty"; else -> "notMatched" }))
            }
            delay(minOf(100L, (waitMs - elapsedMs).coerceAtLeast(1)))
        }
    }

    @McpTool
    @McpDescription("List open Run/Debug console tabs, including terminated processes whose tabs remain open. Use consoleId with jdebug_logs. readable indicates a supported text console. This reads application output, not idea.log or separate log-file tabs.")
    suspend fun jdebug_consoles(): String {
        val p = coroutineContext.project
        return onEdt {
            json(RunContentManager.getInstance(p).allDescriptors.map { d ->
                val s = manager(p).debugSessions.find { it.runContentDescriptor === d }
                mapOf("consoleId" to consoleId(d), "name" to d.displayName,
                    "terminated" to d.processHandler?.isProcessTerminated,
                    "sessionId" to s?.let(::id), "configurationName" to d.runConfigurationName,
                    "consoleType" to d.executionConsole?.javaClass?.name,
                    "textConsoleType" to textConsole(d.executionConsole)?.javaClass?.name,
                    "readable" to (textConsole(d.executionConsole) != null))
            })
        }
    }

    @McpTool
    @McpDescription("Read the latest application log/output text from an open IDE Run/Debug console, without requiring a breakpoint. Choose consoleId from jdebug_consoles (also works after process termination) or a live sessionId from jdebug_sessions. Omit both only when one readable console exists. maxLines: 1..2000; maxChars: 1..100000. Returns a bounded snapshot of the IDE buffer; cleared/evicted output is unavailable. Does not read idea.log or log files.")
    suspend fun jdebug_logs(consoleId: String? = null, sessionId: String? = null, maxLines: Int = 200, maxChars: Int = 20000): String {
        if (maxLines !in 1..2000) fail("maxLines must be 1..2000.")
        if (maxChars !in 1..100000) fail("maxChars must be 1..100000.")
        if (consoleId != null && sessionId != null) fail("Pass consoleId or sessionId, not both.")
        val p = coroutineContext.project
        return onEdt {
            val d = logDescriptor(p, consoleId, sessionId)
            val (console, document) = logDocument(d)
            val tail = consoleTail(document.charsSequence, maxLines, maxChars)
            json(mapOf("consoleId" to consoleId(d), "name" to d.displayName,
                "terminated" to d.processHandler?.isProcessTerminated,
                "text" to tail.text, "truncated" to tail.truncated,
                "bufferChars" to document.textLength, "returnedChars" to tail.text.length,
                "pendingOutput" to console.hasDeferredOutput()))
        }
    }

    @McpTool
    @McpDescription("Control an existing debug session: resume, pause, stepOver, stepInto, stepOut, stop. Returns once the command is submitted, NOT once a breakpoint is reached. Poll jdebug_status before reading frames. Stop terminates the session.")
    suspend fun jdebug_control(action: String, sessionId: String? = null): String {
        val p = coroutineContext.project
        return onEdt {
            val s = session(p, sessionId)
            if (action in listOf("resume", "stepOver", "stepInto", "stepOut") && !s.isSuspended)
                fail("Session must be suspended for $action.")
            when (action) {
                "resume" -> s.resume()
                "pause" -> s.pause()
                "stepOver" -> s.stepOver(false)
                "stepInto" -> s.stepInto()
                "stepOut" -> s.stepOut()
                "stop" -> s.stop()
                else -> fail("Unknown action. Use resume, pause, stepOver, stepInto, stepOut, stop.")
            }
            json(mapOf("submitted" to action, "sessionId" to id(s)))
        }
    }

    private data class Snapshot(val session: XDebugSession, val context: XSuspendContext, val stack: XExecutionStack)
    private suspend fun snapshot(p: Project, sessionId: String?): Snapshot = onEdt {
        val s = session(p, sessionId)
        if (!s.isSuspended) fail("Session is not suspended. Wait for a breakpoint or pause it.")
        val context = s.suspendContext ?: fail("Suspend context is not available.")
        Snapshot(s, context, context.activeExecutionStack ?: fail("No active thread."))
    }
    private suspend fun check(snapshot: Snapshot) = onEdt {
        if (snapshot.session.isStopped || !snapshot.session.isSuspended || snapshot.session.suspendContext !== snapshot.context)
            fail("Debug session changed during inspection. Fetch the stack again.")
    }

    private suspend fun frames(s: Snapshot, limit: Int): List<XStackFrame> = onEdt {
        debuggerReply {
            suspendCancellableCoroutine { c ->
                val result = mutableListOf<XStackFrame>()
                s.stack.computeStackFrames(0, object : XExecutionStack.XStackFrameContainer {
                    override fun isObsolete() = !c.isActive
                    override fun errorOccurred(errorMessage: String) { if (c.isActive) c.resumeWith(Result.failure(McpExpectedError(errorMessage))) }
                    override fun addStackFrames(stackFrames: List<XStackFrame>, last: Boolean) {
                        if (!c.isActive) return
                        result.addAll(stackFrames.take((limit - result.size).coerceAtLeast(0)))
                        if (last || result.size >= limit) c.resumeWith(Result.success(result.toList()))
                    }
                })
            }
        }
    }

    @McpTool
    @McpDescription("Get frames of the active suspended thread. Frame indexes are zero-based and valid only until execution resumes. Other threads can be selected in the IDE. maxFrames: 1..200.")
    suspend fun jdebug_stack(sessionId: String? = null, maxFrames: Int = 50): String {
        require(maxFrames in 1..200) { "maxFrames must be 1..200" }
        val s = snapshot(coroutineContext.project, sessionId)
        val frames = frames(s, maxFrames)
        check(s)
        return onEdt {
            json(mapOf("sessionId" to id(s.session), "thread" to s.stack.displayName,
                "limitReached" to (frames.size == maxFrames), "frames" to frames.mapIndexed { i, f ->
                    val text = SimpleColoredText()
                    f.customizeTextPresentation(text)
                    mapOf("frameIndex" to i, "description" to text.toString(), "position" to position(f.sourcePosition))
                }))
        }
    }

    private suspend fun frame(s: Snapshot, index: Int): XStackFrame {
        if (index !in 0..199) fail("frameIndex must be 0..199")
        val f = frames(s, index + 1).getOrNull(index) ?: fail("Frame index is out of range.")
        check(s)
        return f
    }

    private data class Children(val values: List<Pair<String, XValue>>, val groups: List<String>, val truncated: Boolean)
    private suspend fun children(container: XValueContainer, limit: Int): Children = onEdt {
        debuggerReply {
            suspendCancellableCoroutine { c ->
                val values = mutableListOf<Pair<String, XValue>>()
                val groups = mutableListOf<String>()
                fun finish(truncated: Boolean) {
                    if (c.isActive) c.resumeWith(Result.success(Children(values.toList(), groups.toList(), truncated)))
                }
                container.computeChildren(object : XCompositeNode {
                    override fun isObsolete() = !c.isActive
                    override fun addChildren(children: XValueChildrenList, last: Boolean) {
                        if (!c.isActive) return
                        val all = children.topValues.map { it.name to it } +
                            (0 until children.size()).map { children.getName(it) to children.getValue(it) }
                        val available = limit - values.size
                        values.addAll(all.take(available))
                        groups.addAll((children.topGroups + children.bottomGroups).map { it.name })
                        if (all.size > available || !last && values.size == limit) finish(true)
                        else if (last) finish(false)
                    }
                    @Suppress("OVERRIDE_DEPRECATION")
                    override fun tooManyChildren(remaining: Int) = finish(true)
                    override fun setAlreadySorted(alreadySorted: Boolean) {}
                    override fun setErrorMessage(errorMessage: String) {
                        if (c.isActive) c.resumeWith(Result.failure(McpExpectedError(errorMessage)))
                    }
                    override fun setErrorMessage(errorMessage: String, link: XDebuggerTreeNodeHyperlink?) = setErrorMessage(errorMessage)
                    override fun setMessage(message: String, icon: Icon?, attributes: SimpleTextAttributes, link: XDebuggerTreeNodeHyperlink?) {
                        // Informational tree messages are not completion signals.
                    }
                })
            }
        }
    }

    private suspend fun value(v: XValue): Map<String, Any?> = onEdt {
        debuggerReply {
            suspendCancellableCoroutine { c ->
                v.computePresentation(object : XValueNode {
                    override fun isObsolete() = !c.isActive
                    override fun setFullValueEvaluator(fullValueEvaluator: XFullValueEvaluator) {}
                    override fun setPresentation(icon: Icon?, type: String?, value: String, hasChildren: Boolean) {
                        if (c.isActive) c.resumeWith(Result.success(mapOf("type" to type, "value" to value.take(4000),
                            "hasChildren" to hasChildren, "textTruncated" to (value.length > 4000))))
                    }
                    override fun setPresentation(icon: Icon?, presentation: XValuePresentation, hasChildren: Boolean) {
                        val text = PlainValueRenderer()
                        presentation.renderValue(text)
                        setPresentation(icon, presentation.type, text.toString(), hasChildren)
                    }
                }, XValuePlace.TREE)
            }
        }
    }

    @McpTool
    @McpDescription("Read local variables of a frame in the active suspended thread. frameIndex defaults to the top frame. maxValues: 1..100. Grouped values are reported by group name; use jdebug_evaluate to inspect fields or expressions.")
    suspend fun jdebug_variables(sessionId: String? = null, frameIndex: Int = 0, maxValues: Int = 50): String {
        require(maxValues in 1..100) { "maxValues must be 1..100" }
        val s = snapshot(coroutineContext.project, sessionId)
        val children = children(frame(s, frameIndex), maxValues)
        val values = coroutineScope { children.values.map { (name, v) -> async { mapOf("name" to name) + value(v) } }.awaitAll() }
        check(s)
        return json(mapOf("frameIndex" to frameIndex, "values" to values, "groupsNotExpanded" to children.groups,
            "truncated" to children.truncated))
    }

    @McpTool
    @McpDescription("Evaluate a Java expression in a suspended frame. Expressions CAN invoke methods and change application state. Returns the debugger-rendered value and optionally direct fields/array elements. maxChildren: 0..100. A timeout does not undo an evaluation.")
    suspend fun jdebug_evaluate(expression: String, sessionId: String? = null, frameIndex: Int = 0, maxChildren: Int = 0): String {
        require(expression.isNotBlank() && expression.length <= 10_000) { "Expression must contain 1..10000 characters" }
        require(maxChildren in 0..100) { "maxChildren must be 0..100" }
        val s = snapshot(coroutineContext.project, sessionId)
        val f = frame(s, frameIndex)
        val result = onEdt {
            debuggerReply {
                suspendCancellableCoroutine<XValue> { c ->
                    val evaluator = f.evaluator ?: fail("Evaluation is unavailable for this frame.")
                    evaluator.evaluate(expression, object : XDebuggerEvaluator.XEvaluationCallback {
                        override fun evaluated(result: XValue) { if (c.isActive) c.resumeWith(Result.success(result)) }
                        override fun errorOccurred(errorMessage: String) { if (c.isActive) c.resumeWith(Result.failure(McpExpectedError(errorMessage))) }
                    }, f.sourcePosition)
                }
            }
        }
        val rendered = value(result)
        val expanded = if (maxChildren > 0 && rendered["hasChildren"] == true) {
            val ch = children(result, maxChildren)
            val rows = coroutineScope { ch.values.map { (name, v) -> async { mapOf("name" to name) + value(v) } }.awaitAll() }
            mapOf("children" to rows, "truncated" to ch.truncated, "groupsNotExpanded" to ch.groups)
        } else emptyMap()
        check(s)
        return json(rendered + expanded)
    }

    private fun source(p: Project, filePath: String): com.intellij.openapi.vfs.VirtualFile {
        val base = p.basePath ?: fail("Project has no base directory.")
        val path = Path.of(filePath).let { if (it.isAbsolute) it else Path.of(base).resolve(it) }.normalize()
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: fail("File not found: $path")
        if (file.extension != "java") fail("Only .java source files are supported for breakpoints.")
        return file
    }

    @McpTool
    @McpDescription("List Java line breakpoints in this project. Paths are absolute and lines are one-based.")
    suspend fun jdebug_breakpoints(): String {
        val p = coroutineContext.project
        return onEdt {
            json(manager(p).breakpointManager.allBreakpoints.filterIsInstance<XLineBreakpoint<*>>()
                .filter { it.type is JavaLineBreakpointType }.map {
                    mapOf("fileUrl" to it.fileUrl, "line" to it.line + 1, "enabled" to it.isEnabled)
                })
        }
    }

    @McpTool
    @McpDescription("Set or remove a Java line breakpoint. filePath is absolute or project-relative; line is one-based. Existing breakpoints are preserved when setting. Setting is a request; use the IDE to check whether it binds to executable code.")
    suspend fun jdebug_breakpoint(filePath: String, line: Int, remove: Boolean = false): String {
        val p = coroutineContext.project
        return onEdt {
            val file = source(p, filePath)
            val document = FileDocumentManager.getInstance().getDocument(file) ?: fail("No source document.")
            if (line !in 1..document.lineCount) fail("Line is outside the file (1..${document.lineCount}).")
            var removed = 0
            ApplicationManager.getApplication().runWriteAction {
                val bm = manager(p).breakpointManager
                val existing = bm.allBreakpoints.filterIsInstance<XLineBreakpoint<*>>()
                    .filter { it.type is JavaLineBreakpointType && it.fileUrl == file.url && it.line == line - 1 }
                if (remove) {
                    existing.forEach { bm.removeBreakpoint(it); removed++ }
                } else if (existing.isEmpty()) {
                    val type = XBreakpointType.EXTENSION_POINT_NAME.extensionList
                        .filterIsInstance<JavaLineBreakpointType>()
                        .singleOrNull { it.javaClass == JavaLineBreakpointType::class.java }
                        ?: fail("Standard Java line breakpoint type is not available.")
                    bm.addLineBreakpoint(type, file.url, line - 1, type.createBreakpointProperties(file, line - 1))
                }
            }
            json(mapOf("file" to file.path, "line" to line, "action" to if (remove) "removed" else "set", "removedCount" to removed))
        }
    }
}

internal class PlainValueRenderer : XValuePresentation.XValueTextRenderer {
    private val text = StringBuilder()
    override fun renderValue(value: String) { text.append(value) }
    override fun renderStringValue(value: String) { text.append('"').append(value).append('"') }
    override fun renderNumericValue(value: String) = renderValue(value)
    override fun renderKeywordValue(value: String) = renderValue(value)
    override fun renderValue(value: String, key: TextAttributesKey) = renderValue(value)
    override fun renderStringValue(value: String, additionalSpecialCharsToHighlight: String?, maxLength: Int) = renderStringValue(value.take(maxLength.coerceAtLeast(0)))
    override fun renderComment(comment: String) = renderValue(comment)
    override fun renderSpecialSymbol(symbol: String) = renderValue(symbol)
    override fun renderError(error: String) = renderValue(error)
    override fun toString() = text.toString()
}
