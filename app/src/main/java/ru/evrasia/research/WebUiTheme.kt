package ru.evrasia.research

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import androidx.appcompat.app.AppCompatDelegate
import java.util.Locale

internal object WebUiTheme {
    const val PREFS = "web-research-ui"
    private const val KEY_THEME = "theme"
    private const val KEY_ACCENT = "accent"
    private const val KEY_ACCENT_COLOR = "accent-color"

    enum class Mode(val key: String, val label: String) {
        SYSTEM("system", "Системная"),
        LIGHT("light", "Светлая"),
        DARK("dark", "Тёмная")
    }

    enum class Accent(
        val key: String,
        val label: String,
        private val lightColor: Int,
        private val darkColor: Int
    ) {
        CYAN("cyan", "Бирюзовый", Color.rgb(0, 159, 181), Color.rgb(0, 226, 239)),
        BLUE("blue", "Синий", Color.rgb(38, 105, 205), Color.rgb(79, 143, 247)),
        INDIGO("indigo", "Индиго", Color.rgb(75, 76, 189), Color.rgb(114, 120, 245)),
        PURPLE("purple", "Фиолетовый", Color.rgb(126, 63, 194), Color.rgb(160, 90, 214)),
        PINK("pink", "Розовый", Color.rgb(190, 45, 105), Color.rgb(217, 79, 139)),
        RED("red", "Красный", Color.rgb(190, 55, 55), Color.rgb(232, 91, 91)),
        ORANGE("orange", "Оранжевый", Color.rgb(197, 94, 24), Color.rgb(229, 122, 50)),
        AMBER("amber", "Янтарный", Color.rgb(166, 111, 13), Color.rgb(199, 139, 40)),
        GREEN("green", "Зелёный", Color.rgb(45, 130, 76), Color.rgb(74, 166, 107)),
        TEAL("teal", "Тёмно-бирюзовый", Color.rgb(0, 123, 111), Color.rgb(46, 166, 154)),
        SLATE("slate", "Серо-синий", Color.rgb(78, 91, 110), Color.rgb(119, 142, 177)),
        GRAPHITE("graphite", "Графитовый", Color.rgb(82, 86, 92), Color.rgb(126, 132, 141));

        fun color(dark: Boolean): Int = if (dark) darkColor else lightColor
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

    fun savedAccent(context: Context): Accent {
        val key = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ACCENT, Accent.CYAN.key)
        return Accent.entries.firstOrNull { it.key == key } ?: Accent.CYAN
    }

    fun savedAccentColor(context: Context): Int {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (preferences.contains(KEY_ACCENT_COLOR)) return preferences.getInt(KEY_ACCENT_COLOR, defaultAccent(context))
        return accentColor(context, savedAccent(context))
    }

    fun accentLabel(context: Context): String = colorLabel(savedAccentColor(context))

    fun colorLabel(color: Int): String = String.format(Locale.US, "#%06X", color and 0xFFFFFF)

    fun applySaved(context: Context) {
        applyMode(savedMode(context))
    }

    fun save(context: Context, mode: Mode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_THEME, mode.key).apply()
        applyMode(mode)
    }

    fun saveAccent(context: Context, accent: Accent) {
        val color = accentColor(context, accent)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ACCENT, accent.key)
            .putInt(KEY_ACCENT_COLOR, color)
            .apply()
    }

    fun saveAccentColor(context: Context, color: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_ACCENT_COLOR, color or 0xFF000000.toInt())
            .apply()
    }

    fun accentColor(context: Context, accent: Accent = savedAccent(context)): Int {
        val dark = isDark(context)
        return accent.color(dark)
    }

    fun contrastText(color: Int): Int {
        val r = Color.red(color) / 255.0
        val g = Color.green(color) / 255.0
        val b = Color.blue(color) / 255.0
        val luminance = 0.299 * r + 0.587 * g + 0.114 * b
        return if (luminance > 0.62) Color.rgb(20, 20, 22) else Color.WHITE
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

    private fun isDark(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun defaultAccent(context: Context): Int = if (isDark(context)) Color.rgb(0, 226, 239) else Color.rgb(0, 159, 181)

    fun palette(context: Context): Palette {
        val dark = isDark(context)
        val accent = savedAccentColor(context)
        return if (dark) {
            Palette(
                background = Color.rgb(16, 17, 19),
                card = Color.rgb(28, 29, 32),
                address = Color.rgb(37, 38, 42),
                text = Color.rgb(244, 244, 245),
                secondary = Color.rgb(154, 154, 160),
                divider = Color.rgb(50, 51, 56),
                accent = accent,
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
                accent = accent,
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
