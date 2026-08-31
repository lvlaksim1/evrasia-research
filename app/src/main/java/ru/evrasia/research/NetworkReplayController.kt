package ru.evrasia.research

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

internal class NetworkReplayController(
    private val activity: AppCompatActivity,
    private val backgroundColor: Int,
    private val panelColor: Int,
    private val lineColor: Int,
    private val textColor: Int,
    private val mutedColor: Int
) {
    fun show(event: JSONObject, method: String, headers: String) {
        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(backgroundColor)
        }
        val methodInput = editor("METHOD", method, false)
        val urlInput = editor("URL", event.optString("url", ""), true)
        val headersInput = editor("HEADERS — по одному Name: Value на строку", headers, true)
        val bodyInput = editor("BODY", event.optString("requestBody", ""), true)
        form.addView(methodInput.first)
        form.addView(urlInput.first)
        form.addView(headersInput.first)
        form.addView(bodyInput.first)
        val scroll = ScrollView(activity).apply { addView(form) }
        AlertDialog.Builder(activity)
            .setTitle("Корректировка и повтор запроса")
            .setView(scroll)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Отправить") { _, _ ->
                val parsedHeaders = parseHeaderLines(headersInput.second.text.toString())
                val ok = NetworkRequestActions.replay(activity, event, methodInput.second.text.toString(), urlInput.second.text.toString().trim(), parsedHeaders, bodyInput.second.text.toString())
                Toast.makeText(activity, if (ok) "Запрос отправляется" else "Некорректный URL", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun editor(label: String, value: String, multiline: Boolean): Pair<LinearLayout, EditText> {
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
        box.addView(subtitle(label))
        val input = EditText(activity).apply {
            setText(value); setTextColor(textColor); setHintTextColor(mutedColor); textSize = 10.5f; typeface = Typeface.MONOSPACE
            background = rounded(panelColor, 9f, lineColor); setPadding(dp(9), dp(7), dp(9), dp(7))
            if (multiline) { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; minLines = if (label.startsWith("BODY")) 5 else 3; maxLines = 12; setHorizontallyScrolling(false) } else setSingleLine(true)
        }
        box.addView(input, LinearLayout.LayoutParams(-1, -2))
        return box to input
    }

    private fun parseHeaderLines(raw: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        raw.lines().forEach { line ->
            val separator = line.indexOf(':')
            if (separator > 0) {
                val name = line.substring(0, separator).trim()
                if (name.isNotBlank()) out[name] = line.substring(separator + 1).trim()
            }
        }
        return out
    }

    private fun subtitle(label: String) = TextView(activity).apply {
        text = label; setTextColor(mutedColor); textSize = 9f; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); letterSpacing = .08f; setPadding(dp(3), dp(6), dp(3), dp(4))
    }

    private fun rounded(fill: Int, radius: Float, stroke: Int = Color.TRANSPARENT) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; setColor(fill); cornerRadius = dp(radius.toInt()).toFloat(); if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
}
