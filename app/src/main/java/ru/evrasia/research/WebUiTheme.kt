package ru.evrasia.research

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import androidx.appcompat.app.AppCompatDelegate

internal object WebUiTheme {
    const val PREFS = "web-research-ui"
    private const val KEY_THEME = "theme"

    enum class Mode(val key: String, val label: String) {
        SYSTEM("system", "Системная"),
        LIGHT("light", "Светлая"),
        DARK("dark", "Тёмная")
    }

    data class Palette(
        val background: Int,
        val card: Int,
        val address: Int,
        val text: Int,
        val secondary: Int,
        val divider: Int,
        val accent: Int,
        val green: Int,
        val blue: Int,
        val orange: Int,
        val red: Int,
        val pending: Int,
        val dark: Boolean
    )

    fun savedMode(context: Context): Mode {
        val key = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_THEME, Mode.SYSTEM.key)
        return Mode.entries.firstOrNull { it.key == key } ?: Mode.SYSTEM
    }

    fun applySaved(context: Context) {
        applyMode(savedMode(context))
    }

    fun save(context: Context, mode: Mode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_THEME, mode.key).apply()
        applyMode(mode)
    }

    private fun applyMode(mode: Mode) {
        AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                Mode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                Mode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                Mode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            }
        )
    }

    fun palette(context: Context): Palette {
        val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return if (dark) {
            Palette(
                background = Color.rgb(16, 17, 19),
                card = Color.rgb(28, 29, 32),
                address = Color.rgb(37, 38, 42),
                text = Color.rgb(244, 244, 245),
                secondary = Color.rgb(154, 154, 160),
                divider = Color.rgb(50, 51, 56),
                accent = Color.rgb(0, 226, 239),
                green = Color.rgb(92, 184, 122),
                blue = Color.rgb(96, 160, 224),
                orange = Color.rgb(224, 154, 76),
                red = Color.rgb(224, 94, 94),
                pending = Color.rgb(132, 134, 140),
                dark = true
            )
        } else {
            Palette(
                background = Color.rgb(245, 245, 247),
                card = Color.WHITE,
                address = Color.rgb(236, 236, 239),
                text = Color.rgb(21, 21, 21),
                secondary = Color.rgb(109, 109, 114),
                divider = Color.rgb(224, 224, 228),
                accent = Color.rgb(0, 159, 181),
                green = Color.rgb(52, 146, 84),
                blue = Color.rgb(57, 116, 190),
                orange = Color.rgb(190, 112, 35),
                red = Color.rgb(196, 64, 64),
                pending = Color.rgb(132, 132, 138),
                dark = false
            )
        }
    }
}
