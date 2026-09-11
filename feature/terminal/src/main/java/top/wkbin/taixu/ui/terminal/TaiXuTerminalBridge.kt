package top.wkbin.taixu.ui.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import top.wkbin.taixu.feature.terminal.R
import top.wkbin.taixu.runtime.terminal.TerminalSessionClientRouter
import java.nio.charset.StandardCharsets

/**
 * Bridges Termux [TerminalView] / [TerminalSession] to TaiXu UI.
 *
 * Behaviour mirrors Android-PRoot-Engine's TerminalBridge, with one Compose-specific
 * difference: [shouldEnforceCharBasedInput] is true so soft keyboards use
 * InputConnection.commitText (TYPE_NULL KeyEvents are often swallowed by Compose).
 */
class TaiXuTerminalBridge(
    private val context: Context,
    private val router: TerminalSessionClientRouter,
) : TerminalSessionClient, TerminalViewClient {

    var terminalView: TerminalView? = null
        set(value) {
            field = value
            value?.setTerminalViewClient(this)
        }

    var onFontScale: ((increase: Boolean) -> Unit)? = null

    /** Compose host should focus its IME proxy when the user taps the terminal. */
    var onRequestIme: (() -> Unit)? = null

    private var virtualControlActive = false
    private var virtualAltActive = false

    fun attachToRouter() = router.attach(this)

    fun detachFromRouter() {
        router.attach(null)
        terminalView = null
    }

    fun toggleControlKey() {
        virtualControlActive = !virtualControlActive
    }

    fun isControlKeyActive(): Boolean = virtualControlActive

    fun toggleAltKey() {
        virtualAltActive = !virtualAltActive
    }

    fun currentSession(): TerminalSession? = terminalView?.currentSession

    fun sendBytes(session: TerminalSession?, bytes: ByteArray) {
        if (session == null || bytes.isEmpty()) return
        // write() itself no-ops until the shell pid is live; don't gate on isRunning
        // here so the first keystrokes after attach aren't dropped on a race.
        session.write(bytes, 0, bytes.size)
    }

    fun sendString(session: TerminalSession?, text: String) {
        if (text.isNotEmpty()) {
            sendBytes(session, text.toByteArray(StandardCharsets.UTF_8))
        }
    }

    fun sendString(text: String) = sendString(currentSession(), text)

    /** Request focus and show IME. Call only from explicit user gestures. */
    fun showSoftKeyboard() {
        val view = terminalView ?: return
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        if (!view.hasFocus()) {
            view.requestFocus()
        }
        // Prefer the view's context (Activity); application context often fails silently.
        val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return
        view.post {
            // Rebind InputConnection after focus so char-based inputType takes effect under Compose.
            imm.restartInput(view)
            imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    // region TerminalSessionClient
    override fun onTextChanged(changedSession: TerminalSession) {
        val view = terminalView ?: return
        if (view.currentSession === changedSession || view.currentSession == null) {
            view.onScreenUpdated()
        }
    }

    override fun onTitleChanged(changedSession: TerminalSession) = Unit

    override fun onSessionFinished(finishedSession: TerminalSession) {
        Log.w("TaiXuTerminal", "session finished pid=${finishedSession.pid} exit=${finishedSession.exitStatus}")
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
        if (text.isNullOrEmpty()) return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.terminal_clipboard_label), text))
        Toast.makeText(context, context.getString(R.string.terminal_copied), Toast.LENGTH_SHORT).show()
    }

    override fun onPasteTextFromClipboard(session: TerminalSession) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        sendString(session, text)
    }

    override fun onBell(session: TerminalSession) = Unit

    override fun onColorsChanged(session: TerminalSession) {
        terminalView?.onScreenUpdated()
    }

    override fun onTerminalCursorStateChange(state: Boolean) = Unit

    override fun getTerminalCursorStyle(): Int? = null

    override fun logError(tag: String, message: String) {
        Log.e(tag, message)
    }

    override fun logWarn(tag: String, message: String) {
        Log.w(tag, message)
    }

    override fun logInfo(tag: String, message: String) {
        Log.i(tag, message)
    }

    override fun logDebug(tag: String, message: String) {
        Log.d(tag, message)
    }

    override fun logVerbose(tag: String, message: String) {
        Log.v(tag, message)
    }

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        Log.e(tag, message, e)
    }

    override fun logStackTrace(tag: String, e: Exception) {
        Log.e(tag, "TerminalSession", e)
    }
    // endregion

    // region TerminalViewClient
    override fun onScale(scale: Float): Float {
        if (scale < 0.92f || scale > 1.08f) {
            onFontScale?.invoke(scale > 1.0f)
            return 1.0f
        }
        return scale
    }

    override fun onSingleTapUp(e: MotionEvent) {
        // Prefer Compose IME proxy when available (Compose swallows TYPE_NULL KeyEvents).
        // Fall back to Termux TerminalView InputConnection for View-hosted layouts.
        val proxy = onRequestIme
        if (proxy != null) {
            proxy.invoke()
        } else {
            showSoftKeyboard()
        }
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    /**
     * Compose often drops TYPE_NULL KeyEvents before they reach [TerminalView].
     * Char-based inputType forces IME → commitText (see termux#686 / Compose hosts).
     */
    override fun shouldEnforceCharBasedInput(): Boolean = true

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) = Unit
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
    override fun onLongPress(event: MotionEvent): Boolean = false

    override fun readControlKey(): Boolean {
        val active = virtualControlActive
        virtualControlActive = false
        return active
    }

    override fun readAltKey(): Boolean {
        val active = virtualAltActive
        virtualAltActive = false
        return active
    }

    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false

    override fun onEmulatorSet() {
        val view = terminalView
        val session = view?.currentSession
        Log.i(
            "TaiXuTerminal",
            "onEmulatorSet pid=${session?.pid} cols=${session?.emulator?.mColumns} rows=${session?.emulator?.mRows}",
        )
        view?.onScreenUpdated()
    }
    // endregion
}
