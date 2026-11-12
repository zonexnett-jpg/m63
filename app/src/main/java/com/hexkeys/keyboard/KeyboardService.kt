package com.hexkeys.keyboard

import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo

class KeyboardService : InputMethodService(), KeyboardListener {

    private var keyboardView: HexKeyboardView? = null

    override fun onCreateInputView(): View {
        val v = HexKeyboardView(this)
        v.listener = this
        keyboardView = v
        return v
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (!restarting) keyboardView?.showLetters()
    }

    override fun onEvaluateFullscreenMode() = false

    override fun onCommit(text: String) {
        currentInputConnection?.commitText(text, 1)
    }

    /** Deletes one character (or the selection) and returns what was removed. */
    override fun onDeleteOne(): String? {
        val ic = currentInputConnection ?: return null
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
            return selected.toString()
        }
        val before = ic.getTextBeforeCursor(2, 0)
        if (before == null) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            return ""
        }
        val s = before.toString()
        if (s.isEmpty()) return null
        var n = 1
        if (s.length >= 2 && Character.isLowSurrogate(s[s.length - 1]) &&
            Character.isHighSurrogate(s[s.length - 2])
        ) n = 2
        ic.deleteSurroundingText(n, 0)
        return s.substring(s.length - n)
    }

    override fun onRestore(text: String) {
        if (text.isNotEmpty()) currentInputConnection?.commitText(text, 1)
    }

    override fun onEnter() {
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        if (info != null) {
            val multiLine = info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
            val noAction = info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
            val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
            if (!multiLine && !noAction &&
                action != EditorInfo.IME_ACTION_NONE &&
                action != EditorInfo.IME_ACTION_UNSPECIFIED
            ) {
                ic.performEditorAction(action)
                return
            }
        }
        sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
    }
}
