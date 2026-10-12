package com.example.viewcast

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** Applique la langue choisie dans les paramètres (français par défaut, anglais en option). */
object LocaleHelper {
    fun wrap(base: Context): Context {
        val locale = Locale.forLanguageTag(Prefs(base).language)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}
