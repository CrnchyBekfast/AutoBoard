/*
 * Copyright (C) 2015 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */
package helium314.keyboard.keyboard

import android.text.InputType
import android.text.TextUtils
import android.view.inputmethod.EditorInfo
import helium314.keyboard.compat.EditorInfoCompatUtils.imeActionName
import helium314.keyboard.latin.RichInputMethodSubtype
import helium314.keyboard.latin.WordComposer
import helium314.keyboard.latin.utils.InputTypeUtils

/**
 * Unique identifier for each keyboard type.
 */
data class KeyboardId(
    val elementId: Int,
    val subtype: RichInputMethodSubtype,
    val width: Int,
    val height: Int,
    val mode: Int,
    val inputType: Int,
    val imeOptions: Int,
    val imeAction: Int,
    val deviceLocked: Boolean,
    val numberRowEnabled: Boolean,
    val numberRowInSymbols: Boolean,
    val languageSwitchKeyEnabled: Boolean,
    val emojiKeyEnabled: Boolean,
    val customActionLabel: String?,
    val hasShortcutKey: Boolean,
    val isSplitLayout: Boolean,
    val oneHandedModeEnabled: Boolean,
    val internalAction: KeyboardLayoutSet.InternalAction?,
    val emojiSearchAvailable: Boolean
) {
    lateinit var editorInfo: EditorInfo // we don't want it in the data class constructor

    constructor(elemId: Int, params: KeyboardLayoutSet.Params) : this(
        elemId,
        params.subtype,
        params.keyboardWidth,
        params.keyboardHeight,
        params.mode,
        params.editorInfo.inputType,
        params.editorInfo.imeOptions,
        InputTypeUtils.getImeOptionsActionIdFromEditorInfo(params.editorInfo),
        params.deviceLocked,
        params.numberRowEnabled,
        params.numberRowInSymbols,
        params.languageSwitchKeyEnabled,
        params.emojiKeyEnabled,
        params.editorInfo.actionLabel?.toString(),
        params.voiceInputKeyEnabled,
        params.isSplitLayoutEnabled,
        params.oneHandedModeEnabled,
        params.internalAction,
        params.emojiSearchAvailable,
    ) {
        editorInfo = params.editorInfo
    }

    val isAlphaOrSymbolKeyboard get() = elementId <= ELEMENT_SYMBOLS_SHIFTED

    val isAlphabetKeyboard get() = isAlphabetKeyboard(elementId)

    fun navigateNext(): Boolean {
        return (imeOptions and EditorInfo.IME_FLAG_NAVIGATE_NEXT) != 0
            || imeAction == EditorInfo.IME_ACTION_NEXT
    }

    fun navigatePrevious(): Boolean {
        return (imeOptions and EditorInfo.IME_FLAG_NAVIGATE_PREVIOUS) != 0
            || imeAction == EditorInfo.IME_ACTION_PREVIOUS
    }

    val isPasswordInput get() = InputTypeUtils.isAnyPasswordInputType(inputType)

    val isMultiLine get() = (inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0

    val isAlphabetShifted get() = when (elementId) {
        ELEMENT_ALPHABET_SHIFT_LOCKED, ELEMENT_ALPHABET_SHIFT_LOCK_SHIFTED, ELEMENT_ALPHABET_AUTOMATIC_SHIFTED, ELEMENT_ALPHABET_MANUAL_SHIFTED -> true
        else -> false
    }

    val isAlphabetShiftedManually get() = when (elementId) {
        ELEMENT_ALPHABET_SHIFT_LOCKED, ELEMENT_ALPHABET_SHIFT_LOCK_SHIFTED, ELEMENT_ALPHABET_MANUAL_SHIFTED -> true
        else -> false
    }

    val isNumberLayout get() = when (elementId) {
        ELEMENT_NUMBER, ELEMENT_NUMPAD, ELEMENT_PHONE, ELEMENT_PHONE_SYMBOLS -> true
        else -> false
    }

    val isEmojiKeyboard get() = elementId >= ELEMENT_EMOJI_RECENTS && elementId <= ELEMENT_EMOJI_CATEGORY16

    val isEmojiClipBottomRow get() = elementId == ELEMENT_CLIPBOARD_BOTTOM_ROW || elementId == ELEMENT_EMOJI_BOTTOM_ROW

    val locale get() = subtype.locale

    val capsMode: Int
        get() = when (elementId) {
            ELEMENT_ALPHABET_SHIFT_LOCKED, ELEMENT_ALPHABET_SHIFT_LOCK_SHIFTED -> WordComposer.CAPS_MODE_MANUAL_SHIFT_LOCKED
            ELEMENT_ALPHABET_MANUAL_SHIFTED -> WordComposer.CAPS_MODE_MANUAL_SHIFTED
            ELEMENT_ALPHABET_AUTOMATIC_SHIFTED -> WordComposer.CAPS_MODE_AUTO_SHIFTED
            else -> WordComposer.CAPS_MODE_OFF
        }

    companion object {
        const val MODE_TEXT = 0
        const val MODE_URL = 1
        const val MODE_EMAIL = 2
        const val MODE_IM = 3
        const val MODE_PHONE = 4
        const val MODE_NUMBER = 5
        const val MODE_DATE = 6
        const val MODE_TIME = 7
        const val MODE_DATETIME = 8
        const val MODE_NUMPAD = 9

        const val ELEMENT_ALPHABET = 0
        const val ELEMENT_ALPHABET_MANUAL_SHIFTED = 1
        const val ELEMENT_ALPHABET_AUTOMATIC_SHIFTED = 2
        const val ELEMENT_ALPHABET_SHIFT_LOCKED = 3
        const val ELEMENT_ALPHABET_SHIFT_LOCK_SHIFTED = 4
        const val ELEMENT_SYMBOLS = 5
        const val ELEMENT_SYMBOLS_SHIFTED = 6
        const val ELEMENT_PHONE = 7
        const val ELEMENT_PHONE_SYMBOLS = 8
        const val ELEMENT_NUMBER = 9
        const val ELEMENT_EMOJI_RECENTS = 10
        const val ELEMENT_EMOJI_CATEGORY1 = 11
        const val ELEMENT_EMOJI_CATEGORY2 = 12
        const val ELEMENT_EMOJI_CATEGORY3 = 13
        const val ELEMENT_EMOJI_CATEGORY4 = 14
        const val ELEMENT_EMOJI_CATEGORY5 = 15
        const val ELEMENT_EMOJI_CATEGORY6 = 16
        const val ELEMENT_EMOJI_CATEGORY7 = 17
        const val ELEMENT_EMOJI_CATEGORY8 = 18
        const val ELEMENT_EMOJI_CATEGORY9 = 19
        const val ELEMENT_EMOJI_CATEGORY10 = 20
        const val ELEMENT_EMOJI_CATEGORY11 = 21
        const val ELEMENT_EMOJI_CATEGORY12 = 22
        const val ELEMENT_EMOJI_CATEGORY13 = 23
        const val ELEMENT_EMOJI_CATEGORY14 = 24
        const val ELEMENT_EMOJI_CATEGORY15 = 25
        const val ELEMENT_EMOJI_CATEGORY16 = 26 // Emoji search
        const val ELEMENT_CLIPBOARD = 27
        const val ELEMENT_NUMPAD = 28
        const val ELEMENT_EMOJI_BOTTOM_ROW = 29
        const val ELEMENT_CLIPBOARD_BOTTOM_ROW = 30

        private fun isAlphabetKeyboard(elementId: Int): Boolean {
            return elementId < ELEMENT_SYMBOLS
        }

        fun equivalentEditorInfoForKeyboard(a: EditorInfo?, b: EditorInfo?): Boolean {
            if (a == null && b == null) return true
            if (a == null || b == null) return false
            return a.inputType == b.inputType && a.imeOptions == b.imeOptions && TextUtils.equals(a.privateImeOptions, b.privateImeOptions)
        }

        fun actionName(actionId: Int) = if (actionId == InputTypeUtils.IME_ACTION_CUSTOM_LABEL) "actionCustomLabel"
            else imeActionName(actionId)
    }
}
