package com.example.viewcast

import android.content.Context

/** Réglages persistants (stockés uniquement sur le téléphone). */
class Prefs(context: Context) {
    private val sp = (context.applicationContext ?: context).getSharedPreferences("viewcast", Context.MODE_PRIVATE)

    /** "fr" (langue principale) ou "en". */
    var language: String
        get() = sp.getString("lang", "fr") ?: "fr"
        set(v) = sp.edit().putString("lang", v).apply()

    var quality: String
        get() = sp.getString("quality", "720p") ?: "720p"
        set(v) = sp.edit().putString("quality", v).apply()

    var fps: Int
        get() = sp.getInt("fps", 30)
        set(v) = sp.edit().putInt("fps", v).apply()

    var bitrateMbps: Int
        get() = sp.getInt("bitrate", 4)
        set(v) = sp.edit().putInt("bitrate", v).apply()

    var lowLatency: Boolean
        get() = sp.getBoolean("low_latency", true)
        set(v) = sp.edit().putBoolean("low_latency", v).apply()

    var audio: Boolean
        get() = sp.getBoolean("audio", true)
        set(v) = sp.edit().putBoolean("audio", v).apply()

    /** Protection par code PIN + chiffrement (recommandé, activé par défaut). */
    var secure: Boolean
        get() = sp.getBoolean("secure", true)
        set(v) = sp.edit().putBoolean("secure", v).apply()

    /** Dernière adresse saisie pour recevoir (le code PIN n'est JAMAIS enregistré). */
    var rxAddress: String
        get() = sp.getString("rx_address", "") ?: ""
        set(v) = sp.edit().putString("rx_address", v).apply()
}
