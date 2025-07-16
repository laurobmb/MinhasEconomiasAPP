// Arquivo: ServerUrlManager.kt
package com.example.minhaseconomias

import android.content.Context
import android.content.SharedPreferences

object ServerUrlManager {

    private const val PREFS_NAME = "MinhasEconomiasPrefs"
    private const val KEY_SERVER_URL = "server_url"
    // Este será o valor padrão, que aparecerá pré-preenchido
    private const val DEFAULT_URL = "http://192.168.0.221:8080"

    private lateinit var prefs: SharedPreferences

    // Esta função precisa ser chamada uma vez quando o app inicia
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun setUrl(url: String) {
        // Garante que a URL não termine com uma barra para evitar problemas no Retrofit
        val finalUrl = if (url.endsWith("/")) url.dropLast(1) else url
        prefs.edit().putString(KEY_SERVER_URL, finalUrl).apply()
    }

    fun getUrl(): String {
        return prefs.getString(KEY_SERVER_URL, DEFAULT_URL) ?: DEFAULT_URL
    }
}