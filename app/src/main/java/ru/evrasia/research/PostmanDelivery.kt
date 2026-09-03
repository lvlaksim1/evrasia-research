package ru.evrasia.research

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object PostmanDelivery {
    private const val PREFS = "web-research-postman"
    private const val KEY_MODE = "mode"

    enum class Mode(val key: String, val label: String) {
        CLIPBOARD("clipboard", "Буфер обмена"),
        FILE("file", "Файл")
    }

    fun mode(context: Context): Mode {
        val key = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODE, Mode.CLIPBOARD.key)
        return Mode.entries.firstOrNull { it.key == key } ?: Mode.CLIPBOARD
    }

    fun modeLabel(context: Context): String = mode(context).label

    fun saveMode(context: Context, mode: Mode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MODE, mode.key).apply()
    }

    fun deliver(activity: AppCompatActivity, json: String, sourceUrl: String = "") {
        if (mode(activity) == Mode.CLIPBOARD) {
            val manager = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            manager.setPrimaryClip(ClipData.newPlainText("POSTMAN JSON", json))
            Toast.makeText(activity, "POSTMAN JSON скопирован", Toast.LENGTH_SHORT).show()
            return
        }
        val prepared = prepareTempFile(activity, json, sourceUrl)
        if (prepared == null) {
            Toast.makeText(activity, "Не удалось сформировать JSON-файл", Toast.LENGTH_LONG).show()
            return
        }
        showFileChoice(activity, prepared)
    }

    private fun prepareTempFile(activity: AppCompatActivity, json: String, sourceUrl: String): File? {
        return try {
            val folder = File(activity.cacheDir, "postman").apply { mkdirs() }
            val host = try { java.net.URL(sourceUrl).host.replace(Regex("[^A-Za-z0-9._-]"), "-") } catch (_: Exception) { "request" }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            File(folder, "postman-$host-$stamp.json").apply { writeText(json, Charsets.UTF_8) }
        } catch (_: Exception) {
            null
        }
    }

    private fun showFileChoice(activity: AppCompatActivity, file: File) {
        val palette = WebUiTheme.palette(activity)
        val dialog = Dialog(activity)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setCancelable(true)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 8), dp(activity, 8), dp(activity, 8), dp(activity, 12))
            background = rounded(activity, palette.card, 20f, palette.divider)
        }
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(squareButton(activity, palette, "×") { dialog.dismiss() }, LinearLayout.LayoutParams(dp(activity, 40), dp(activity, 40)))
        header.addView(TextView(activity).apply {
            text = "POSTMAN JSON"
            setTextColor(palette.text)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 9), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, dp(activity, 40), 1f))
        root.addView(header)
        root.addView(TextView(activity).apply {
            text = file.name
            setTextColor(palette.secondary)
            textSize = 11.5f
            setPadding(dp(activity, 8), dp(activity, 8), dp(activity, 8), dp(activity, 12))
        })
        root.addView(actionButton(activity, palette, "Сохранить в Загрузки", true) {
            val uri = saveToDownloads(activity, file)
            if (uri != null) {
                Toast.makeText(activity, "Файл сохранён в Загрузки", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }, LinearLayout.LayoutParams(-1, dp(activity, 48)).apply { setMargins(dp(activity, 6), 0, dp(activity, 6), dp(activity, 6)) })
        root.addView(actionButton(activity, palette, "Отправить в другое приложение", false) {
            share(activity, file)
            dialog.dismiss()
        }, LinearLayout.LayoutParams(-1, dp(activity, 48)).apply { setMargins(dp(activity, 6), 0, dp(activity, 6), 0) })

        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setOnShowListener {
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout((activity.resources.displayMetrics.widthPixels * 0.90f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
                setGravity(Gravity.CENTER)
            }
        }
        dialog.show()
    }

    private fun saveToDownloads(activity: AppCompatActivity, file: File): android.net.Uri? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/json")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = activity.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
                resolver.openOutputStream(uri)?.use { output -> file.inputStream().use { input -> input.copyTo(output) } }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri
            } else {
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/json"
                    putExtra(Intent.EXTRA_TITLE, file.name)
                }
                activity.startActivity(intent)
                null
            }
        } catch (_: Exception) {
            Toast.makeText(activity, "Не удалось сохранить файл", Toast.LENGTH_LONG).show()
            null
        }
    }

    private fun share(activity: AppCompatActivity, file: File) {
        try {
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(send, "Отправить POSTMAN JSON"))
        } catch (_: Exception) {
            Toast.makeText(activity, "Не удалось открыть меню отправки", Toast.LENGTH_LONG).show()
        }
    }

    private fun squareButton(activity: AppCompatActivity, palette: WebUiTheme.Palette, symbol: String, click: () -> Unit) = Button(activity).apply {
        text = symbol
        setTextColor(palette.accent)
        textSize = 19f
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(0, 0, 0, 0)
        background = rounded(activity, palette.address, 11f, palette.divider)
        setOnClickListener { click() }
    }

    private fun actionButton(activity: AppCompatActivity, palette: WebUiTheme.Palette, label: String, primary: Boolean, click: () -> Unit) = Button(activity).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        typeface = if (primary) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        setTextColor(if (primary) WebUiTheme.contrastText(palette.accent) else palette.text)
        background = rounded(activity, if (primary) palette.accent else palette.address, 14f, if (primary) palette.accent else palette.divider)
        setOnClickListener { click() }
    }

    private fun rounded(activity: AppCompatActivity, fill: Int, radius: Float, stroke: Int = Color.TRANSPARENT) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = dp(activity, radius.toInt()).toFloat()
        if (stroke != Color.TRANSPARENT) setStroke(dp(activity, 1), stroke)
    }

    private fun dp(activity: AppCompatActivity, value: Int) = (value * activity.resources.displayMetrics.density).toInt()
}
