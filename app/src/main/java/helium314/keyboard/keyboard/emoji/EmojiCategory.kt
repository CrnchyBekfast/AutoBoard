/*
 * Copyright (C) 2015 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */
package helium314.keyboard.keyboard.emoji

import android.content.Context
import android.content.res.TypedArray
import android.graphics.Paint
import androidx.core.graphics.PaintCompat
import helium314.keyboard.keyboard.Key
import helium314.keyboard.keyboard.KeyboardElement
import helium314.keyboard.keyboard.KeyboardLayoutSet
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.ResourceUtils
import helium314.keyboard.latin.utils.prefs
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import androidx.core.content.edit

internal class EmojiCategory(private val context: Context, private val layoutSet: KeyboardLayoutSet, emojiPaletteViewAttr: TypedArray) {
    inner class CategoryProperties(val categoryId: Int) {
        var mPageCount = -1

        val pageCount: Int
            get() {
                if (mPageCount < 0) mPageCount = computeCategoryPageCount(categoryId)
                return mPageCount
            }
    }

    private val prefs = context.prefs()
    private val maxRecentsKeyCount = context.resources.getInteger(R.integer.config_emoji_keyboard_max_recents_key_count)

    private val categoryTabIconId = IntArray(categoryTabIconAttr.size) { i ->
        emojiPaletteViewAttr.getResourceId(categoryTabIconAttr[i], 0)
    }

    val shownCategories = listOfNotNull(
        CategoryProperties(ID_RECENTS),
        CategoryProperties(ID_SMILEYS_EMOTION),
        CategoryProperties(ID_PEOPLE_BODY),
        CategoryProperties(ID_ANIMALS_NATURE),
        CategoryProperties(ID_FOOD_DRINK),
        CategoryProperties(ID_TRAVEL_PLACES),
        CategoryProperties(ID_ACTIVITIES),
        CategoryProperties(ID_OBJECTS),
        CategoryProperties(ID_SYMBOLS),
        if (canShowFlagEmoji()) CategoryProperties(ID_FLAGS) else null,
        CategoryProperties(ID_EMOTICONS)
    )

    private val categoryKeyboardMap = ConcurrentHashMap<Long, DynamicGridKeyboard>()

    var currentCategoryId = ID_UNSPECIFIED
        set(value) {
            field = value
            prefs.edit { putInt(Settings.PREF_LAST_SHOWN_EMOJI_CATEGORY_ID, value) }
        }

    var currentCategoryPageId = 0
        set(value) {
            field = value
            prefs.edit { putInt(Settings.PREF_LAST_SHOWN_EMOJI_CATEGORY_PAGE_ID, value) }
        }

    fun initialize() {
        val defaultCategoryId = ID_SMILEYS_EMOTION
        val recentsKbd = getKeyboard(ID_RECENTS, 0)
        currentCategoryId = prefs.getInt(Settings.PREF_LAST_SHOWN_EMOJI_CATEGORY_ID, defaultCategoryId)
        currentCategoryPageId =
            prefs.getInt(Settings.PREF_LAST_SHOWN_EMOJI_CATEGORY_PAGE_ID, Defaults.PREF_LAST_SHOWN_EMOJI_CATEGORY_PAGE_ID)
        if (!isShownCategoryId(currentCategoryId)) {
            currentCategoryId = defaultCategoryId
        } else if (currentCategoryId == ID_RECENTS && recentsKbd.sortedKeys.isEmpty()) {
            currentCategoryId = defaultCategoryId
        }

        if (currentCategoryPageId >= computeCategoryPageCount(currentCategoryId)) {
            currentCategoryPageId = 0
        }
    }

    fun clearKeyboardCache() {
        categoryKeyboardMap.clear()
        for (props in shownCategories) props.mPageCount = -1 // reset page count in case size (number of keys per row) changed
    }

    private fun isShownCategoryId(categoryId: Int): Boolean {
        for (prop in shownCategories) {
            if (prop.categoryId == categoryId) {
                return true
            }
        }
        return false
    }

    fun getCategoryTabIcon(categoryId: Int) = categoryTabIconId[categoryId]

    fun getAccessibilityDescription(categoryId: Int) = context.getString(categoryElement[categoryId].descriptionResId)

    val currentCategoryPageCount get() = getCategoryPageCount(currentCategoryId)

    fun getCategoryPageCount(categoryId: Int): Int {
        for (prop in shownCategories) {
            if (prop.categoryId == categoryId) {
                return prop.pageCount
            }
        }
        Log.w(TAG, "Invalid category id: $categoryId")
        // Should not reach here.
        return 0
    }

    val isInRecentTab get() = currentCategoryId == ID_RECENTS

    fun getTabIdFromCategoryId(categoryId: Int): Int {
        val i = shownCategories.indexOfFirst { it.categoryId == categoryId }
        return if (i == -1) 0 else i
    }

    val recentTabId get() = getTabIdFromCategoryId(ID_RECENTS)

    private fun computeCategoryPageCount(categoryId: Int): Int {
        val keyboard = layoutSet.getKeyboard(categoryElement[categoryId])
        return (keyboard.sortedKeys.size - 1) / computeMaxKeyCountPerPage() + 1
    }

    // Returns a keyboard from the recycler view's adapter position.
    fun getKeyboardFromAdapterPosition(categoryId: Int, position: Int): DynamicGridKeyboard? {
        if (position >= 0 && position < getCategoryPageCount(categoryId)) {
            return getKeyboard(categoryId, position)
        }
        Log.w(TAG, "invalid position for categoryId : $categoryId")
        return null
    }

    fun getKeyboard(categoryId: Int, id: Int): DynamicGridKeyboard {
        synchronized(categoryKeyboardMap) {
            val categoryKeyboardMapKey = getCategoryKeyboardMapKey(categoryId, id)
            categoryKeyboardMap[categoryKeyboardMapKey]?.let { return it }

            val currentWidth = ResourceUtils.getKeyboardWidth(context, Settings.getValues())
            if (categoryId == ID_RECENTS) {
                val kbd = DynamicGridKeyboard.ofKeyCount(
                    prefs,
                    layoutSet.getKeyboard(KeyboardElement.EMOJI_RECENTS),
                    maxRecentsKeyCount, categoryId, currentWidth
                )
                categoryKeyboardMap[categoryKeyboardMapKey] = kbd
                kbd.loadRecentKeys(categoryKeyboardMap.values)
                return kbd
            }

            val keyboard = layoutSet.getKeyboard(categoryElement[categoryId])
            val keyCountPerPage = computeMaxKeyCountPerPage()
            val sortedKeysPages = sortKeysGrouped(keyboard.sortedKeys, keyCountPerPage)
            for (pageId in sortedKeysPages.indices) {
                val tempKeyboard = DynamicGridKeyboard.ofKeyCount(prefs,
                    layoutSet.getKeyboard(KeyboardElement.EMOJI_RECENTS),
                    keyCountPerPage, categoryId, currentWidth)
                for (emojiKey in sortedKeysPages[pageId]) {
                    if (emojiKey == null) {
                        break
                    }
                    tempKeyboard.addKeyLast(emojiKey)
                }
                categoryKeyboardMap[getCategoryKeyboardMapKey(categoryId, pageId)] = tempKeyboard
            }
            return categoryKeyboardMap[categoryKeyboardMapKey]!!
        }
    }

    private fun computeMaxKeyCountPerPage(): Int {
        val tempKeyboard = DynamicGridKeyboard.ofKeyCount(prefs,
            layoutSet.getKeyboard(KeyboardElement.EMOJI_RECENTS),
            0, 0, ResourceUtils.getKeyboardWidth(context, Settings.getValues())
        )
        return MAX_LINE_COUNT_PER_PAGE * tempKeyboard.occupiedColumnCount
    }

    companion object {
        private val TAG = EmojiCategory::class.java.simpleName
        private const val ID_UNSPECIFIED = -1
        const val ID_RECENTS: Int = 0
        private const val ID_SMILEYS_EMOTION = 1
        private const val ID_PEOPLE_BODY = 2
        private const val ID_ANIMALS_NATURE = 3
        private const val ID_FOOD_DRINK = 4
        private const val ID_TRAVEL_PLACES = 5
        private const val ID_ACTIVITIES = 6
        private const val ID_OBJECTS = 7
        private const val ID_SYMBOLS = 8
        private const val ID_FLAGS = 9
        private const val ID_EMOTICONS = 10

        private const val MAX_LINE_COUNT_PER_PAGE = 3

        private val categoryTabIconAttr = intArrayOf(
            R.styleable.EmojiPalettesView_iconEmojiRecentsTab,
            R.styleable.EmojiPalettesView_iconEmojiCategory1Tab,
            R.styleable.EmojiPalettesView_iconEmojiCategory2Tab,
            R.styleable.EmojiPalettesView_iconEmojiCategory3Tab,
            R.styleable.EmojiPalettesView_iconEmojiCategory4Tab,
            R.styleable.EmojiPalettesView_iconEmojiCategory5Tab,
            R.styleable.EmojiPalettesView_iconEmojiCategory6Tab,
            R.styleable.EmojiPalettesView_iconEmojiCategory7Tab,
            R.styleable.EmojiPalettesView_iconEmojiCategory8Tab,
            R.styleable.EmojiPalettesView_iconEmojiCategory9Tab,
            R.styleable.EmojiPalettesView_iconEmojiCategory10Tab
        )

        private val categoryElement = arrayOf(
            KeyboardElement.EMOJI_RECENTS,
            KeyboardElement.EMOJI_SMILEY,
            KeyboardElement.EMOJI_PEOPLE,
            KeyboardElement.EMOJI_NATURE,
            KeyboardElement.EMOJI_FOOD,
            KeyboardElement.EMOJI_TRAVEL_PLACES,
            KeyboardElement.EMOJI_ACTIVITIES,
            KeyboardElement.EMOJI_OBJECTS,
            KeyboardElement.EMOJI_SYMBOLS,
            KeyboardElement.EMOJI_FLAGS,
            KeyboardElement.EMOJI_EMOTICONS
        )

        private fun getCategoryKeyboardMapKey(categoryId: Int, id: Int) =
            ((categoryId.toLong()) shl Integer.SIZE) or id.toLong()

        private val EMOJI_KEY_COMPARATOR = Comparator { lhs: Key, rhs: Key ->
            val lHitBox = lhs.hitBox
            val rHitBox = rhs.hitBox
            if (lHitBox.top < rHitBox.top) {
                return@Comparator -1
            } else if (lHitBox.top > rHitBox.top) {
                return@Comparator 1
            }
            if (lHitBox.left < rHitBox.left) {
                return@Comparator -1
            } else if (lHitBox.left > rHitBox.left) {
                return@Comparator 1
            }
            if (lhs.code == rhs.code) {
                return@Comparator 0
            }
            if (lhs.code < rhs.code) -1 else 1
        }

        private fun sortKeysGrouped(inKeys: MutableList<Key>, maxPageCount: Int): Array<Array<Key?>> {
            val keys = ArrayList(inKeys)
            Collections.sort(keys, EMOJI_KEY_COMPARATOR)
            val pageCount = (keys.size - 1) / maxPageCount + 1
            val retval = Array<Array<Key?>>(pageCount) { arrayOfNulls(maxPageCount) }
            for (i in keys.indices) {
                retval[i / maxPageCount][i % maxPageCount] = keys[i]
            }
            return retval
        }

        private fun canShowFlagEmoji(): Boolean {
            val paint = Paint()
            val switzerland = "\uD83C\uDDE8\uD83C\uDDED" //  U+1F1E8 U+1F1ED Flag for Switzerland
            return PaintCompat.hasGlyph(paint, switzerland)
        }
    }
}
