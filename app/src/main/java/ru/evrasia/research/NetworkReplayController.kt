package ru.evrasia.research

import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
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
        val palette = WebUiTheme.palette(activity)
        val dialog = Dialog(activity)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setCancelable(true)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = rounded(backgroundColor, 18f, lineColor)
        }
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(panelColor, 13f, lineColor)
            setPadding(dp(6), dp(5), dp(8), dp(5))
        }
        header.addView(Button(activity).apply {
            text = "×"
            setTextColor(palette.accent)
            textSize = 19f
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(0, 0, 0, 0)
            background = rounded(panelColor, 10f, lineColor)
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        header.addView(TextView(activity).apply {
            text = "Корректировка и повтор запроса"
            setTextColor(textColor)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(9), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, dp(40), 1f))
        root.addView(header)

        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(8))
        }
        val methodInput = editor("METHOD", method, false)
        val urlInput = editor("URL", event.optString("url", ""), true)
        val headersInput = editor("HEADERS — по одному Name: Value на строку", headers, true)
        val bodyInput = editor("BODY", event.optString("requestBody", ""), true)
        form.addView(methodInput.first)
        form.addView(urlInput.first)
        form.addView(headersInput.first)
        form.addView(bodyInput.first)
        root.addView(ScrollView(activity).apply { addView(form) }, LinearLayout.LayoutParams(-1, 0, 1f))

        root.addView(Button(activity).apply {
            text = "Отправить"
            isAllCaps = false
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(WebUiTheme.contrastText(palette.accent))
            background = rounded(palette.accent, 13f)
            setOnClickListener {
                val parsedHeaders = parseHeaderLines(headersInput.second.text.toString())
                val ok = NetworkRequestActions.replay(activity, event, methodInput.second.text.toString(), urlInput.second.text.toString().trim(), parsedHeaders, bodyInput.second.text.toString())
                Toast.makeText(activity, if (ok) "Запрос отправляется" else "Некорректный URL", Toast.LENGTH_SHORT).show()
                if (ok) dialog.dismiss()
            }
        }, LinearLayout.LayoutParams(-1, dp(46)).apply { setMargins(dp(8), dp(6), dp(8), dp(4)) })

        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setOnShowListener {
            val dm = activity.resources.displayMetrics
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout((dm.widthPixels * 0.96f).toInt(), (dm.heightPixels * 0.90f).toInt())
                setGravity(Gravity.CENTER)
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
        }
        dialog.show()
    }

    private fun editor(label: String, value: String, multiline: Boolean): Pair<LinearLayout, EditText> {
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
        box.addView(subtitle(label))
        val input = EditText(activity).apply {
            setText(value)
            setTextColor(textColor)
            setHintTextColor(mutedColor)
            textSize = 10.5f
            typeface = Typeface.MONOSPACE
            background = rounded(panelColor, 9f, lineColor)
            setPadding(dp(9), dp(7), dp(9), dp(7))
            if (multiline) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = if (label.startsWith("BODY")) 5 else 3
                maxLines = 12
                setHorizontallyScrolling(false)
            } else setSingleLine(true)
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
        text = label
        setTextColor(mutedColor)
        textSize = 9f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        letterSpacing = .08f
        setPadding(dp(3), dp(6), dp(3), dp(4))
    }

    private fun rounded(fill: Int, radius: Float, stroke: Int = Color.TRANSPARENT) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = dp(radius.toInt()).toFloat()
        if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
}
