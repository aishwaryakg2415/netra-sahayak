package com.sih.netrasahayak.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

data class AppLanguage(
    val code: String,
    val displayName: String,
    val nativeName: String,
)

object LanguageManager {
    val supportedLanguages: List<AppLanguage> = listOf(
        AppLanguage("en", "English", "English"),
        AppLanguage("hi", "Hindi", "हिन्दी"),
        AppLanguage("mr", "Marathi", "मराठी"),
        AppLanguage("ta", "Tamil", "தமிழ்"),
        AppLanguage("te", "Telugu", "తెలుగు"),
        AppLanguage("bn", "Bengali", "বাংলা"),
        AppLanguage("gu", "Gujarati", "ગુજરાતી"),
        AppLanguage("es", "Spanish", "Español"),
    )

    fun getCurrentLanguageCode(context: Context): String {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (!locales.isEmpty) {
            val tag = locales.get(0)?.language
            if (!tag.isNullOrBlank()) {
                return tag
            }
        }
        return "en"
    }

    fun getCurrentLanguage(context: Context): AppLanguage {
        val code = getCurrentLanguageCode(context)
        return supportedLanguages.firstOrNull { it.code.equals(code, ignoreCase = true) }
            ?: supportedLanguages.first()
    }

    fun setAppLanguage(languageCode: String) {
        val appLocales = LocaleListCompat.forLanguageTags(languageCode)
        AppCompatDelegate.setApplicationLocales(appLocales)
    }
}
