package com.linan.barezen_drive.i18n

import androidx.compose.runtime.staticCompositionLocalOf

/** Languages the client ships translations for. */
enum class Language(val tag: String) {
    EN("en"),
    ZH("zh"),
    ;

    companion object {
        /**
         * Maps a BCP-47 tag such as "zh-CN", "zh-Hant-TW" or "en-US" onto a
         * supported language. Anything that is not Chinese resolves to [EN],
         * which is also the fallback for a null or blank tag.
         */
        fun fromTag(tag: String?): Language =
            if (tag?.trim()?.startsWith("zh", ignoreCase = true) == true) ZH else EN

        /** Preference encoding: 0 = follow the system, 1 = Chinese, 2 = English. */
        fun fromMode(mode: Int): Language? = when (mode) {
            1 -> ZH
            2 -> EN
            else -> null
        }

        fun modeOf(language: Language): Int = when (language) {
            ZH -> 1
            EN -> 2
        }
    }
}

/**
 * Process-wide holder for the active language.
 *
 * Compose code reads [LocalStrings] so that switching the language recomposes
 * the tree. Code that cannot observe composition - the API layer, upload
 * progress strings, date formatting, coroutine callbacks - reads
 * [I18n.strings] instead. Both always resolve to the same instance.
 */
object I18n {
    var language: Language = Language.EN
        private set

    val strings: Strings
        get() = stringsFor(language)

    /** Updates the process-wide language. Called whenever the preference changes. */
    fun set(language: Language) {
        this.language = language
    }
}

/** Returns the implementation that matches [language]. */
fun stringsFor(language: Language): Strings = when (language) {
    Language.EN -> StringsEn
    Language.ZH -> StringsZh
}

/**
 * The strings used by the current composition. Provided once at the app root
 * from a snapshot state, so changing the language recomposes every screen.
 */
val LocalStrings = staticCompositionLocalOf<Strings> { StringsEn }
