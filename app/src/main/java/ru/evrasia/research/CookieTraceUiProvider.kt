package ru.evrasia.research

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CookieTraceUiProvider : ContentProvider(), Application.ActivityLifecycleCallbacks {
    private val handler = Handler(Looper.getMainLooper())
    private var browserRef = WeakReference<WebResearchV10Activity>(null)
    private var debuggerRef = WeakReference<NetworkDebuggerActivity>(null)

    private val ink = Color.rgb(3, 10, 15)
    private val surface = Color.rgb(7, 18, 25)
    private val surface2 = Color.rgb(10, 25, 34)
    private val line = Color.rgb(21, 57, 69)
    private val cyan = Color.rgb(0, 226, 239)
    private val white = Color.rgb(232, 244, 248)
    private val muted = Color.rgb(113, 139, 151)
    private val accent = Color.rgb(151, 231, 92)

    override fun onCreate(): Boolean {
        (context?.applicationContext as? Application)?.registerActivityLifecycleCallbacks(this)
        return true
    }

    private fun webOf(activity: WebResearchV10Activity): WebView? = activity.researchWebView()

    private fun archiveOf(activity: WebResearchV10Activity): ResearchArchive? = activity.researchArchive()

    private fun currentPage(): String {
        val browser = browserRef.get() ?: return ""
        return webOf(browser)?.url.orEmpty()
    }

    private fun traceEvents(): List<JSONObject> {
        val browser = browserRef.get() ?: return emptyList()
        val archive = archiveOf(browser) ?: return emptyList()
        val bytes = archive.extraArtifacts["cookie-trace.json"] ?: return emptyList()
        return try {
            val arr = JSONObject(bytes.toString(Charsets.UTF_8)).optJSONArray("events") ?: JSONArray()
            buildList {
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { add(JSONObject(it.toString())) }
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun parseCookieHeader(raw: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        raw.split(';').map { it.trim() }.filter { it.isNotBlank() }.forEach { part ->
            val eq = part.indexOf('=')
            if (eq > 0) out[part.substring(0, eq).trim()] = part.substring(eq + 1)
        }
        return out
    }

    private fun hostOf(raw: String): String = try {
        URL(raw).host.lowercase(Locale.US)
    } catch (_: Exception) { "" }

    private fun eventDomain(event: JSONObject): String {
        val explicit = event.optString("domain", "").trim().trimStart('.').lowercase(Locale.US)
        if (explicit.isNotBlank()) return explicit
        val location = event.optString("page", event.optString("url", ""))
        return hostOf(location)
    }

    private fun eventsForDomain(domain: String, all: List<JSONObject>): List<JSONObject> =
        all.filter { eventDomain(it) == domain }.sortedBy { it.optLong("time", 0L) }

    private fun representativeUrls(domain: String, page: String, domainEvents: List<JSONObject>): List<String> {
        val out = linkedSetOf<String>()
        if (hostOf(page) == domain && page.startsWith("http")) out.add(page)
        domainEvents.asReversed().forEach { event ->
            val url = event.optString("url", event.optString("page", ""))
            if (url.startsWith("http://") || url.startsWith("https://")) out.add(url)
        }
        out.add("https://$domain/")
        out.add("http://$domain/")
        return out.toList()
    }

    private fun activeCookies(domain: String, page: String, domainEvents: List<JSONObject>): Map<String, String> {
        val out = linkedMapOf<String, String>()
        representativeUrls(domain, page, domainEvents).forEach { url ->
            val raw = try { CookieManager.getInstance().getCookie(url).orEmpty() } catch (_: Exception) { "" }
            parseCookieHeader(raw).forEach { (name, value) -> if (!out.containsKey(name)) out[name] = value }
        }
        return out
    }

    private fun allDomains(page: String, all: List<JSONObject>): List<String> {
        val domains = linkedSetOf<String>()
        hostOf(page).takeIf { it.isNotBlank() }?.let { domains.add(it) }
        all.map { eventDomain(it) }.filter { it.isNotBlank() }.forEach { domains.add(it) }
        val current = hostOf(page)
        return domains.sortedWith(compareBy<String> { if (it == current) 0 else 1 }.thenBy { it })
    }

    private fun currentOrigin(history: List<JSONObject>, currentValue: String?): JSONObject? {
        if (currentValue != null) {
            history.asReversed().firstOrNull {
                it.optString("action") != "DELETE" &&
                    it.optString("value") == currentValue &&
                    it.optString("confidence") != "UNKNOWN"
            }?.let { return it }
        }
        return history.asReversed().firstOrNull { it.optString("action") != "DELETE" }
    }

    private fun birthEvent(history: List<JSONObject>): JSONObject? {
        history.firstOrNull { it.optString("action") in setOf("CREATE", "SET") && it.optString("confidence") == "EXACT" }?.let { return it }
        history.firstOrNull { it.optString("action") in setOf("CREATE", "SET", "OBSERVED") }?.let { return it }
        return history.firstOrNull()
    }

    private fun regenerationEvent(history: List<JSONObject>, currentValue: String?): JSONObject? {
        val current = currentOrigin(history, currentValue)
        if (current != null && current.optString("origin") in setOf("HTTP_RESPONSE", "LIKELY_HTTP_RESPONSE", "JAVASCRIPT")) return current
        return birthEvent(history)
    }

    private fun sourceShort(event: JSONObject?): String {
        if (event == null) return "источник неизвестен"
        val source = when (event.optString("origin", "")) {
            "HTTP_RESPONSE" -> "HTTP Set-Cookie"
            "JAVASCRIPT" -> event.optString("mechanism", "JavaScript")
            "LIKELY_HTTP_RESPONSE" -> "вероятно HTTP"
            else -> event.optString("mechanism", "неизвестно")
        }
        return "$source · ${confidenceRu(event.optString("confidence", "UNKNOWN"))}"
    }

    private fun confidenceRu(value: String) = when (value) {
        "EXACT" -> "точно"
        "MEDIUM" -> "вероятно"
        "LOW" -> "предположение"
        else -> "неизвестно"
    }

    private fun showDomainList(activity: NetworkDebuggerActivity) {
        val page = currentPage()
        val all = traceEvents()
        val domains = allDomains(page, all)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 8), dp(activity, 6), dp(activity, 8), dp(activity, 10))
            setBackgroundColor(ink)
        }
        root.addView(TextView(activity).apply {
            text = "Страница: ${page.ifBlank { "—" }}\nДомены с cookies: ${domains.size}"
            setTextColor(muted)
            textSize = 9.5f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(activity, 6), dp(activity, 4), dp(activity, 6), dp(activity, 8))
        })

        var dialog: AlertDialog? = null
        if (domains.isEmpty()) root.addView(sectionText(activity, "Cookies пока не обнаружены"))
        domains.forEach { domain ->
            val domainEvents = eventsForDomain(domain, all)
            val active = activeCookies(domain, page, domainEvents)
            val names = domainEvents.map { it.optString("name", "") }.filter { it.isNotBlank() }.distinct()
            val historyOnly = names.count { it !in active }
            root.addView(domainRow(activity, domain, active.size, historyOnly, hostOf(page) == domain) {
                dialog?.dismiss()
                showDomainCookies(activity, domain)
            })
        }

        val scroll = ScrollView(activity).apply { setBackgroundColor(ink); addView(root) }
        dialog = AlertDialog.Builder(activity).setTitle("Куки · домены").setView(scroll).setNegativeButton("Закрыть", null).create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(round(activity, ink, 16, line))
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(cyan)
        }
        dialog.show()
    }

    private fun domainRow(activity: Activity, domain: String, activeCount: Int, historyCount: Int, current: Boolean, click: () -> Unit): View {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            background = round(activity, if (current) surface else surface2, 10, line)
            setPadding(dp(activity, 11), dp(activity, 10), dp(activity, 11), dp(activity, 10))
            addView(TextView(activity).apply {
                text = domain
                setTextColor(if (current) white else cyan)
                textSize = 11.5f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            })
            addView(TextView(activity).apply {
                text = buildString {
                    append("активных: ").append(activeCount)
                    if (historyCount > 0) append(" · в истории: ").append(historyCount)
                    if (current) append(" · текущий домен")
                }
                setTextColor(if (activeCount > 0) accent else muted)
                textSize = 9f
                typeface = Typeface.MONOSPACE
                setPadding(0, dp(activity, 4), 0, 0)
            })
            setOnClickListener { click() }
        }.also { view ->
            view.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(activity, 6)) }
        }
    }

    private fun showDomainCookies(activity: NetworkDebuggerActivity, domain: String) {
        val page = currentPage()
        val all = traceEvents()
        val domainEvents = eventsForDomain(domain, all)
        val active = activeCookies(domain, page, domainEvents)
        val inactive = domainEvents.map { it.optString("name", "") }.filter { it.isNotBlank() && it !in active }.distinct().sorted()

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 8), dp(activity, 6), dp(activity, 8), dp(activity, 10))
            setBackgroundColor(ink)
        }
        root.addView(TextView(activity).apply {
            text = "$domain\nАктивных куки: ${active.size}"
            setTextColor(cyan)
            textSize = 10f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextIsSelectable(true)
            setPadding(dp(activity, 6), dp(activity, 4), dp(activity, 6), dp(activity, 8))
        })

        var dialog: AlertDialog? = null
        if (active.isEmpty()) root.addView(sectionText(activity, "Активных куки нет"))
        active.toSortedMap().forEach { (name, value) ->
            val history = domainEvents.filter { it.optString("name") == name }
            val origin = currentOrigin(history, value)
            root.addView(cookieRow(activity, name, value, sourceShort(origin), true) {
                dialog?.dismiss()
                showCookieDetails(activity, domain, name, value, true)
            })
        }

        if (inactive.isNotEmpty()) {
            root.addView(sectionText(activity, "ИСТОРИЯ / НЕАКТИВНЫЕ"))
            inactive.forEach { name ->
                val history = domainEvents.filter { it.optString("name") == name }
                val latest = history.lastOrNull()
                root.addView(cookieRow(activity, name, latest?.optString("value", "").orEmpty(), sourceShort(latest), false) {
                    dialog?.dismiss()
                    showCookieDetails(activity, domain, name, null, false)
                })
            }
        }

        val scroll = ScrollView(activity).apply { setBackgroundColor(ink); addView(root) }
        dialog = AlertDialog.Builder(activity).setTitle("Куки · $domain").setView(scroll).setNegativeButton("Назад", null).create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(round(activity, ink, 16, line))
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
                setTextColor(cyan)
                setOnClickListener { dialog.dismiss(); showDomainList(activity) }
            }
        }
        dialog.show()
    }

    private fun cookieRow(activity: Activity, name: String, value: String, source: String, active: Boolean, click: () -> Unit): View {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            background = round(activity, if (active) surface else surface2, 10, line)
            setPadding(dp(activity, 11), dp(activity, 9), dp(activity, 11), dp(activity, 9))
            addView(TextView(activity).apply {
                text = name
                setTextColor(if (active) white else muted)
                textSize = 11f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            })
            addView(TextView(activity).apply {
                val preview = if (value.length > 80) value.take(80) + "…" else value
                text = "${if (active) preview else "неактивна"}\n$source"
                setTextColor(if (active) accent else muted)
                textSize = 9f
                typeface = Typeface.MONOSPACE
                setPadding(0, dp(activity, 4), 0, 0)
            })
            setOnClickListener { click() }
        }.also { view ->
            view.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(activity, 6)) }
        }
    }

    private fun showCookieDetails(activity: NetworkDebuggerActivity, domain: String, name: String, currentValue: String?, active: Boolean) {
        val history = eventsForDomain(domain, traceEvents()).filter { it.optString("name") == name }
        val birth = birthEvent(history)
        val origin = currentOrigin(history, currentValue)
        val regen = regenerationEvent(history, currentValue)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 10), dp(activity, 8), dp(activity, 10), dp(activity, 12))
            setBackgroundColor(ink)
        }

        root.addView(block(activity, buildString {
            append("Домен: ").append(domain).append('\n')
            append("Cookie: ").append(name)
            append(if (active) "\n\nТЕКУЩЕЕ ЗНАЧЕНИЕ\n${currentValue.orEmpty()}" else "\n\nСейчас cookie не активна")
        }, true))

        root.addView(sectionText(activity, "КАК ОНА РОДИЛАСЬ"))
        root.addView(block(activity, if (birth == null) "Рождение не зафиксировано. Cookie могла существовать до запуска трассировки." else formatOrigin(birth), false))

        if (origin != null && origin !== birth) {
            root.addView(sectionText(activity, "ИСТОЧНИК ТЕКУЩЕГО ЗНАЧЕНИЯ"))
            root.addView(block(activity, formatOrigin(origin), false))
        }

        root.addView(sectionText(activity, "КАК ПОЛУЧИТЬ ЕЁ СНОВА"))
        root.addView(block(activity, regenerationRecipe(regen), false))

        if (regen != null) {
            when (regen.optString("origin", "")) {
                "HTTP_RESPONSE", "LIKELY_HTTP_RESPONSE" -> {
                    val url = regen.optString("url", "")
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        root.addView(actionButton(activity, if (regen.optString("confidence") == "EXACT") "ПОВТОРИТЬ ИСХОДНЫЙ ЗАПРОС" else "ПОВТОРИТЬ ВЕРОЯТНЫЙ ЗАПРОС") {
                            val headers = headersMap(regen.optJSONObject("requestHeaders"))
                            val original = JSONObject().put("url", url).put("method", regen.optString("method", "GET"))
                            if (regen.has("_storeId")) original.put("_storeId", regen.optLong("_storeId"))
                            val ok = NetworkRequestActions.replay(activity, original, regen.optString("method", "GET"), url, headers, regen.optString("requestBody", ""))
                            Toast.makeText(activity, if (ok) "Запрос отправляется" else "Не удалось повторить запрос", Toast.LENGTH_SHORT).show()
                        })
                        root.addView(actionButton(activity, "КОПИРОВАТЬ cURL") { copyText(activity, "cURL", curlFor(regen)) })
                    }
                }
                "JAVASCRIPT" -> {
                    if (regen.optString("raw", "").isNotBlank()) root.addView(actionButton(activity, "КОПИРОВАТЬ JS SETTER") { copyText(activity, "JS setter", jsSetter(regen)) })
                }
            }
        }

        root.addView(sectionText(activity, "ИСТОРИЯ"))
        if (history.isEmpty()) root.addView(block(activity, "История пока отсутствует.", false))
        history.asReversed().forEach { root.addView(block(activity, formatHistory(it), false)) }

        val scroll = ScrollView(activity).apply { setBackgroundColor(ink); addView(root) }
        val dialog = AlertDialog.Builder(activity).setTitle("COOKIE · $name").setView(scroll).setNegativeButton("Назад", null).create()
        dialog.setOnShowListener {
            dialog.window?.setLayout((activity.resources.displayMetrics.widthPixels * 0.97).toInt(), (activity.resources.displayMetrics.heightPixels * 0.92).toInt())
            dialog.window?.setBackgroundDrawable(round(activity, ink, 16, line))
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
                setTextColor(cyan)
                setOnClickListener { dialog.dismiss(); showDomainCookies(activity, domain) }
            }
        }
        dialog.show()
    }

    private fun formatOrigin(event: JSONObject): String = buildString {
        val time = event.optLong("time", 0L)
        if (time > 0L) append("Время: ").append(timeText(time)).append('\n')
        append("Механизм: ").append(event.optString("mechanism", "—")).append('\n')
        append("Точность: ").append(confidenceRu(event.optString("confidence", "UNKNOWN"))).append('\n')
        val url = event.optString("url", "")
        if (url.isNotBlank()) {
            append("Запрос: ").append(event.optString("method", "GET"))
            val status = event.optInt("status", 0)
            if (status > 0) append("  ").append(status)
            append('\n').append(url).append('\n')
        } else event.optString("page", "").takeIf { it.isNotBlank() }?.let { append("Страница: ").append(it).append('\n') }
        event.optString("raw", "").takeIf { it.isNotBlank() }?.let { append("Set/Raw: ").append(it).append('\n') }
        event.optString("stack", "").takeIf { it.isNotBlank() }?.let { append("JS stack:\n").append(it.take(3000)).append('\n') }
        if (event.has("deltaMs")) append("Cookie изменилась через ").append(event.optLong("deltaMs")).append(" мс после этого запроса\n")
    }.trimEnd()

    private fun regenerationRecipe(event: JSONObject?): String {
        if (event == null) return "Недостаточно данных, чтобы предложить способ воспроизведения. Нужно поймать момент создания cookie после запуска приложения."
        return when (event.optString("origin", "")) {
            "HTTP_RESPONSE" -> buildString {
                append("Cookie пришла в ответе Set-Cookie. Чтобы сервер сгенерировал её снова, повторите исходный HTTP-запрос.\n\n")
                append(requestRecipe(event))
                append("\n\nНиже есть кнопка повторения запроса. Новый ответ может выдать новое значение cookie, если серверная логика допускает повторную генерацию.")
            }
            "LIKELY_HTTP_RESPONSE" -> buildString {
                append("Cookie появилась сразу после этого запроса, но Set-Cookie в исходном ответе перехватить не удалось. Поэтому источник вероятный, а не доказанный.\n\n")
                append(requestRecipe(event))
                append("\n\nМожно повторить этот запрос и проверить, создастся ли cookie снова.")
            }
            "JAVASCRIPT" -> buildString {
                append("Cookie записана JavaScript через ").append(event.optString("mechanism", "JavaScript")).append(".\n")
                if (event.optString("stack", "").isNotBlank()) append("Главный ориентир — JS stack ниже: он показывает код, который выполнил запись.\n")
                append("\nДля генерации нового значения нужно повторить действие сайта, приведшее к этому вызову. Простое выполнение setter-а обычно только запишет уже известное значение.\n\n")
                append("Setter для проверки:\n").append(jsSetter(event))
            }
            else -> "Источник рождения пока не доказан. Трассировка видит факт появления/изменения cookie, но не может гарантированно назвать код или HTTP-ответ, создавший её."
        }
    }

    private fun requestRecipe(event: JSONObject): String = buildString {
        append(event.optString("method", "GET")).append(' ').append(event.optString("url", "—"))
        val headers = event.optJSONObject("requestHeaders")
        if (headers != null && headers.length() > 0) {
            append("\n\nHEADERS\n")
            val keys = headers.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                append(key).append(": ").append(headers.opt(key)).append('\n')
            }
        }
        val body = event.optString("requestBody", "")
        if (body.isNotBlank()) append("\nBODY\n").append(body)
    }.trimEnd()

    private fun formatHistory(event: JSONObject): String = buildString {
        append(timeText(event.optLong("time", 0L))).append("  ").append(event.optString("action", "EVENT")).append('\n')
        append(sourceShort(event)).append('\n')
        val url = event.optString("url", "")
        if (url.isNotBlank()) append(event.optString("method", "GET")).append(' ').append(url).append('\n')
        if (event.has("deltaMs")) append("Δ ").append(event.optLong("deltaMs")).append(" ms\n")
        val raw = event.optString("raw", "")
        if (raw.isNotBlank()) append(raw)
    }.trimEnd()

    private fun jsSetter(event: JSONObject): String {
        val raw = event.optString("raw", "")
        return if (event.optString("mechanism", "").startsWith("CookieStore")) {
            "// исходная запись была через ${event.optString("mechanism")}\n// raw: $raw"
        } else "document.cookie = ${jsQuote(raw)};"
    }

    private fun jsQuote(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private fun headersMap(obj: JSONObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val out = linkedMapOf<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            out[key] = obj.opt(key)?.toString().orEmpty()
        }
        return out
    }

    private fun curlFor(event: JSONObject): String = buildString {
        val method = event.optString("method", "GET")
        val url = event.optString("url", "")
        append("curl -X ").append(shellQuote(method)).append(' ').append(shellQuote(url))
        headersMap(event.optJSONObject("requestHeaders")).forEach { (name, value) -> append(" \\\n  -H ").append(shellQuote("$name: $value")) }
        val body = event.optString("requestBody", "")
        if (body.isNotBlank()) append(" \\\n  --data-raw ").append(shellQuote(body))
    }

    private fun shellQuote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"

    private fun copyText(activity: Activity, label: String, value: String) {
        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(activity, "$label скопирован", Toast.LENGTH_SHORT).show()
    }

    private fun block(activity: Activity, textValue: String, strong: Boolean): TextView = TextView(activity).apply {
        text = textValue
        setTextColor(white)
        textSize = if (strong) 11f else 10f
        typeface = if (strong) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.MONOSPACE
        setTextIsSelectable(true)
        isFocusable = true
        isFocusableInTouchMode = true
        setPadding(dp(activity, 10), dp(activity, 9), dp(activity, 10), dp(activity, 9))
        background = round(activity, surface2, 9, line)
    }

    private fun sectionText(activity: Activity, value: String): TextView = TextView(activity).apply {
        text = value
        setTextColor(cyan)
        textSize = 9f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        letterSpacing = .08f
        setPadding(dp(activity, 4), dp(activity, 10), dp(activity, 4), dp(activity, 5))
    }

    private fun actionButton(activity: Activity, label: String, click: () -> Unit): Button = Button(activity).apply {
        text = label
        isAllCaps = false
        setTextColor(cyan)
        textSize = 9.5f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(activity, 9), 0, dp(activity, 9), 0)
        background = round(activity, surface, 9, line)
        setOnClickListener { click() }
    }

    private fun timeText(ms: Long): String = if (ms > 0L) SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(ms)) else "—"

    private fun rewireCookieButton(activity: NetworkDebuggerActivity) {
        val root = (activity.findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0) as? LinearLayout) ?: return
        val toolbarScroll = root.getChildAt(0) as? HorizontalScrollView ?: return
        val toolbar = toolbarScroll.getChildAt(0) as? LinearLayout ?: return
        val button = toolbar.findViewWithTag<Button>("debugger-cookies") ?: return
        button.text = "Куки"
        button.setOnClickListener { showDomainList(activity) }
    }

    private fun dp(activity: Activity, value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private fun round(activity: Activity, fill: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = dp(activity, radius).toFloat()
        stroke?.let { setStroke(dp(activity, 1), it) }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        when (activity) {
            is WebResearchV10Activity -> browserRef = WeakReference(activity)
            is NetworkDebuggerActivity -> debuggerRef = WeakReference(activity)
        }
    }

    override fun onActivityResumed(activity: Activity) {
        when (activity) {
            is WebResearchV10Activity -> browserRef = WeakReference(activity)
            is NetworkDebuggerActivity -> {
                debuggerRef = WeakReference(activity)
                handler.postDelayed({ rewireCookieButton(activity) }, 1350)
                handler.postDelayed({ rewireCookieButton(activity) }, 1700)
            }
        }
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (activity is WebResearchV10Activity && browserRef.get() === activity) browserRef.clear()
        if (activity is NetworkDebuggerActivity && debuggerRef.get() === activity) debuggerRef.clear()
    }

    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
