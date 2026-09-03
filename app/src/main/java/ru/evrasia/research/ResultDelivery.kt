package ru.evrasia.research

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
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
import androidx.core.content.FileProvider
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.WeakHashMap

internal object ResultDelivery {
    private const val REQUEST_SAVE = 7901

    private data class Prepared(
        val title: String,
        val file: File,
        val mime: String,
        val clipboardText: String?
    )

    private val pendingSave = WeakHashMap<Activity, Prepared>()

    fun deliverText(
        activity: Activity,
        title: String,
        text: String,
        fileName: String = defaultFileName(title, text),
        mime: String = guessMime(title, text)
    ) {
        val prepared = prepare(activity, title, fileName, mime, text) { output ->
            output.write(text.toByteArray(Charsets.UTF_8))
        } ?: return
        showChoice(activity, prepared)
    }

    fun deliverBytes(activity: Activity, title: String, bytes: ByteArray, fileName: String, mime: String) {
        val prepared = prepare(activity, title, fileName, mime.ifBlank { "application/octet-stream" }, null) { output ->
            output.write(bytes)
        } ?: return
        showChoice(activity, prepared)
    }

    fun deliverGeneratedFile(
        activity: Activity,
        title: String,
        fileName: String,
        mime: String,
        writer: (OutputStream) -> Unit
    ) {
        val prepared = prepare(activity, title, fileName, mime, null, writer) ?: return
        showChoice(activity, prepared)
    }

    fun defaultFileName(label: String, value: String = ""): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val base = label.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9а-яё._-]+", RegexOption.IGNORE_CASE), "-")
            .trim('-').ifBlank { "result" }
        val ext = when {
            label.contains("postman", true) || label.contains("json", true) || value.trimStart().startsWith("{") || value.trimStart().startsWith("[") -> "json"
            label.contains("javascript", true) || label.contains("js setter", true) -> "js"
            label.contains("curl", true) -> "sh"
            else -> "txt"
        }
        return "$base-$stamp.$ext"
    }

    fun handleActivityResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST_SAVE) return false
        val prepared = synchronized(pendingSave) { pendingSave.remove(activity) } ?: return true
        if (resultCode != Activity.RESULT_OK) return true
        val uri = data?.data ?: return true
        return try {
            activity.contentResolver.openOutputStream(uri)?.use { output -> prepared.file.inputStream().use { input -> input.copyTo(output) } }
            Toast.makeText(activity, "Файл сохранён", Toast.LENGTH_SHORT).show()
            true
        } catch (_: Exception) {
            Toast.makeText(activity, "Не удалось сохранить файл", Toast.LENGTH_LONG).show()
            true
        }
    }

    private fun prepare(
        activity: Activity,
        title: String,
        fileName: String,
        mime: String,
        clipboardText: String?,
        writer: (OutputStream) -> Unit
    ): Prepared? {
        return try {
            val folder = File(activity.cacheDir, "exports").apply { mkdirs() }
            val safeName = sanitizeFileName(fileName)
            val file = File(folder, safeName)
            file.outputStream().use(writer)
            Prepared(title, file, mime.ifBlank { "application/octet-stream" }, clipboardText)
        } catch (_: Exception) {
            Toast.makeText(activity, "Не удалось подготовить результат", Toast.LENGTH_LONG).show()
            null
        }
    }

    private fun showChoice(activity: Activity, prepared: Prepared) {
        val palette = WebUiTheme.palette(activity)
        val dialog = Dialog(activity)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setCancelable(true)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 10), dp(activity, 10), dp(activity, 10), dp(activity, 12))
            background = rounded(activity, palette.card, 20f, palette.divider)
        }
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(squareButton(activity, palette, "×") { dialog.dismiss() }, LinearLayout.LayoutParams(dp(activity, 40), dp(activity, 40)))
        header.addView(TextView(activity).apply {
            text = prepared.title
            setTextColor(palette.text)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 9), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, dp(activity, 40), 1f))
        root.addView(header)
        root.addView(TextView(activity).apply {
            text = prepared.file.name
            setTextColor(palette.secondary)
            textSize = 11.5f
            setPadding(dp(activity, 8), dp(activity, 8), dp(activity, 8), dp(activity, 12))
        })

        root.addView(actionButton(activity, palette, "Скачать файл", true) {
            if (download(activity, prepared)) dialog.dismiss()
        }, LinearLayout.LayoutParams(-1, dp(activity, 48)).apply { setMargins(dp(activity, 6), 0, dp(activity, 6), dp(activity, 6)) })

        root.addView(actionButton(activity, palette, "Отправить в другое приложение", false) {
            share(activity, prepared)
            dialog.dismiss()
        }, LinearLayout.LayoutParams(-1, dp(activity, 48)).apply { setMargins(dp(activity, 6), 0, dp(activity, 6), dp(activity, 6)) })

        root.addView(actionButton(activity, palette, "Копировать в буфер", false) {
            copy(activity, prepared)
            dialog.dismiss()
        }, LinearLayout.LayoutParams(-1, dp(activity, 48)).apply { setMargins(dp(activity, 6), 0, dp(activity, 6), 0) })

        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setOnShowListener {
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout((activity.resources.displayMetrics.widthPixels * 0.92f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
                setGravity(Gravity.CENTER)
            }
        }
        dialog.show()
    }

    private fun download(activity: Activity, prepared: Prepared): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            synchronized(pendingSave) { pendingSave[activity] = prepared }
            activity.startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = prepared.mime
                putExtra(Intent.EXTRA_TITLE, prepared.file.name)
            }, REQUEST_SAVE)
            return true
        }
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, prepared.file.name)
                put(MediaStore.Downloads.MIME_TYPE, prepared.mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = activity.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
            resolver.openOutputStream(uri)?.use { output -> prepared.file.inputStream().use { input -> input.copyTo(output) } }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            Toast.makeText(activity, "Файл сохранён в Загрузки", Toast.LENGTH_SHORT).show()
            true
        } catch (_: Exception) {
            Toast.makeText(activity, "Не удалось сохранить файл", Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun share(activity: Activity, prepared: Prepared) {
        try {
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", prepared.file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = prepared.mime
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(activity.contentResolver, prepared.title, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(send, "Отправить ${prepared.title}"))
        } catch (_: Exception) {
            Toast.makeText(activity, "Не удалось открыть меню отправки", Toast.LENGTH_LONG).show()
        }
    }

    private fun copy(activity: Activity, prepared: Prepared) {
        val manager = activity.getSystemService(Activity.CLIPBOARD_SERVICE) as ClipboardManager
        if (prepared.clipboardText != null) {
            manager.setPrimaryClip(ClipData.newPlainText(prepared.title, prepared.clipboardText))
            Toast.makeText(activity, "${prepared.title} скопирован", Toast.LENGTH_SHORT).show()
        } else {
            try {
                val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", prepared.file)
                manager.setPrimaryClip(ClipData.newUri(activity.contentResolver, prepared.title, uri))
                Toast.makeText(activity, "Файл скопирован в буфер", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(activity, "Не удалось скопировать файл", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun guessMime(label: String, value: String): String = when {
        label.contains("postman", true) || label.contains("json", true) || value.trimStart().startsWith("{") || value.trimStart().startsWith("[") -> "application/json"
        label.contains("javascript", true) || label.contains("js setter", true) -> "application/javascript"
        else -> "text/plain"
    }

    private fun sanitizeFileName(name: String): String {
        val clean = name.replace(Regex("[\\/:*?\"<>|]+"), "-").trim().trim('.')
        return clean.ifBlank { defaultFileName("result") }.take(180)
    }

    private fun squareButton(activity: Activity, palette: WebUiTheme.Palette, symbol: String, click: () -> Unit) = Button(activity).apply {
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

    private fun actionButton(activity: Activity, palette: WebUiTheme.Palette, label: String, primary: Boolean, click: () -> Unit) = Button(activity).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        typeface = if (primary) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        setTextColor(if (primary) WebUiTheme.contrastText(palette.accent) else palette.text)
        background = rounded(activity, if (primary) palette.accent else palette.address, 14f, if (primary) palette.accent else palette.divider)
        setOnClickListener { click() }
    }

    private fun rounded(activity: Activity, fill: Int, radius: Float, stroke: Int = Color.TRANSPARENT) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = dp(activity, radius.toInt()).toFloat()
        if (stroke != Color.TRANSPARENT) setStroke(dp(activity, 1), stroke)
    }

    private fun dp(activity: Activity, value: Int) = (value * activity.resources.displayMetrics.density).toInt()
}
