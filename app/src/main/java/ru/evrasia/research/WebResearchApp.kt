package ru.evrasia.research

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import java.lang.ref.WeakReference

class WebResearchApp : Application(), Application.ActivityLifecycleCallbacks {
    private val handler = Handler(Looper.getMainLooper())
    private var browserRef = WeakReference<WebResearchV10Activity>(null)
    private var debuggerRef = WeakReference<NetworkDebuggerActivity>(null)
    private var mirroredCount = 0
    private val mirroredScripts = mutableSetOf<String>()

    private val ink = Color.rgb(5, 7, 12)
    private val surface = Color.rgb(12, 17, 27)
    private val surface2 = Color.rgb(18, 25, 39)
    private val surface3 = Color.rgb(24, 33, 50)
    private val line = Color.rgb(39, 51, 72)
    private val cyan = Color.rgb(55, 226, 255)
    private val violet = Color.rgb(139, 92, 246)
    private val white = Color.rgb(235, 243, 255)
    private val muted = Color.rgb(132, 148, 174)
    private val red = Color.rgb(255, 92, 132)

    private val syncTicker = object : Runnable {
        override fun run() {
            syncNetworkStore()
            debuggerRef.get()?.let { styleDebuggerRows(it) }
            handler.postDelayed(this, 250)
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(this)
        handler.post(syncTicker)
    }

    fun syncNetworkStore() {
        val browser = browserRef.get() ?: return
        try {
            val field = WebResearchV10Activity::class.java.getDeclaredField("archive")
            field.isAccessible = true
            val archive = field.get(browser) as? ResearchArchive ?: return
            synchronized(archive) {
                val length = archive.records.length()
                if (length < mirroredCount) {
                    mirroredCount = 0
                    mirroredScripts.clear()
                    NetworkDebugStore.clear()
                }
                while (mirroredCount < length) {
                    archive.records.optJSONObject(mirroredCount)?.let { NetworkDebugStore.add(it) }
                    mirroredCount++
                }
                archive.scripts.forEach { (url, bytes) ->
                    if (mirroredScripts.add(url)) {
                        NetworkDebugStore.add(org.json.JSONObject()
                            .put("source", "js-file")
                            .put("time", System.currentTimeMillis())
                            .put("method", "GET")
                            .put("url", url)
                            .put("mimeType", "application/javascript")
                            .put("responseSize", bytes.size)
                            .put("responseBody", try { bytes.toString(Charsets.UTF_8) } catch (_: Exception) { "[binary]" }))
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun brandBrowser(activity: WebResearchV10Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val root = content.getChildAt(0) as? LinearLayout ?: return
        if (root.tag == "wr-brand-2026") return
        root.tag = "wr-brand-2026"
        if (root.childCount < 7) return

        root.setBackgroundColor(ink)
        val hero = root.getChildAt(0)
        val navCard = root.getChildAt(1) as? LinearLayout ?: return
        val bookmarkCard = root.getChildAt(2) as? LinearLayout ?: return
        val statsHeader = root.getChildAt(3)
        val statsPanel = root.getChildAt(4)
        val bottomTools = root.getChildAt(6)
        hero.visibility = View.GONE
        statsHeader.visibility = View.GONE
        statsPanel.visibility = View.GONE
        bottomTools.visibility = View.GONE
        bookmarkCard.visibility = View.GONE

        navCard.background = rounded(activity, surface, 16, line)
        navCard.setPadding(dp(activity, 7), dp(activity, 6), dp(activity, 7), dp(activity, 6))
        (navCard.layoutParams as? LinearLayout.LayoutParams)?.apply {
            height = LinearLayout.LayoutParams.WRAP_CONTENT
            setMargins(dp(activity, 7), dp(activity, 5), dp(activity, 7), dp(activity, 5))
            navCard.layoutParams = this
        }

        val nav = navCard.getChildAt(0) as? LinearLayout ?: return
        val address = nav.getChildAt(0) as? EditText ?: return
        val go = nav.getChildAt(1) as? Button ?: return

        address.textSize = 13f
        address.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        address.setTextColor(white)
        address.setHintTextColor(muted)
        address.background = rounded(activity, surface2, 12, line)
        address.setPadding(dp(activity, 12), 0, dp(activity, 12), 0)
        address.layoutParams = LinearLayout.LayoutParams(0, dp(activity, 42), 1f).apply {
            marginStart = dp(activity, 6)
            marginEnd = dp(activity, 6)
        }

        styleIconButton(activity, go, "↗", true)
        go.layoutParams = LinearLayout.LayoutParams(dp(activity, 44), dp(activity, 42))

        if (bookmarkCard.childCount > 0) bookmarkCard.getChildAt(0).visibility = View.GONE
        bookmarkCard.background = rounded(activity, surface, 14, line)
        bookmarkCard.setPadding(dp(activity, 7), dp(activity, 6), dp(activity, 7), dp(activity, 6))
        (bookmarkCard.layoutParams as? LinearLayout.LayoutParams)?.apply {
            setMargins(dp(activity, 7), 0, dp(activity, 7), dp(activity, 5))
            bookmarkCard.layoutParams = this
        }

        val bookmarkRow = if (bookmarkCard.childCount > 1) bookmarkCard.getChildAt(1) as? LinearLayout else null
        bookmarkRow?.gravity = Gravity.CENTER_VERTICAL
        val spinner = bookmarkRow?.let { row ->
            (0 until row.childCount).map { row.getChildAt(it) }.filterIsInstance<Spinner>().firstOrNull()
        }
        spinner?.let { sp ->
            val strings = mutableListOf<String>()
            val old = sp.adapter
            for (i in 0 until (old?.count ?: 0)) strings.add(old?.getItem(i)?.toString().orEmpty())
            sp.adapter = object : ArrayAdapter<String>(activity, android.R.layout.simple_spinner_item, strings) {
                private fun item(position: Int, dropdown: Boolean) = TextView(activity).apply {
                    text = getItem(position)
                    setTextColor(white)
                    textSize = 12f
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(activity, 12), if (dropdown) dp(activity, 11) else 0, dp(activity, 12), if (dropdown) dp(activity, 11) else 0)
                    background = rounded(activity, if (dropdown) surface2 else surface2, 10, if (dropdown) line else line)
                }
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = item(position, false)
                override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View = item(position, true)
            }
        }
        bookmarkRow?.let { row ->
            val buttons = (0 until row.childCount).map { row.getChildAt(it) }.filterIsInstance<Button>()
            buttons.forEach { styleTinyButton(activity, it) }
            buttons.firstOrNull { it.text.toString() == "Открыть" || it.text.toString() == "➜" }?.let { styleIconButton(activity, it, "↗", true) }
        }

        val menu = Button(activity).apply {
            text = "☰"
            isAllCaps = false
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(white)
            minWidth = 0
            minimumWidth = 0
            background = rounded(activity, surface2, 12, line)
            setOnClickListener {
                bookmarkCard.visibility = if (bookmarkCard.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }
        nav.addView(menu, 0, LinearLayout.LayoutParams(dp(activity, 44), dp(activity, 42)))

        val net = Button(activity).apply {
            text = "◎"
            isAllCaps = false
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(cyan)
            minWidth = 0
            minimumWidth = 0
            background = rounded(activity, surface2, 12, cyan)
            setOnClickListener {
                syncNetworkStore()
                activity.startActivity(Intent(activity, NetworkDebuggerActivity::class.java))
            }
        }
        nav.addView(net, LinearLayout.LayoutParams(dp(activity, 44), dp(activity, 42)).apply { marginStart = dp(activity, 6) })
    }

    private fun brandDebugger(activity: NetworkDebuggerActivity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val root = content.getChildAt(0) as? LinearLayout ?: return
        root.setBackgroundColor(ink)

        if (root.childCount >= 5) {
            val header = root.getChildAt(0) as? LinearLayout
            header?.setPadding(dp(activity, 14), dp(activity, 10), dp(activity, 14), dp(activity, 8))
            header?.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(surface, surface2)).apply {
                cornerRadius = 0f
            }
            (header?.getChildAt(0) as? TextView)?.apply {
                text = "TRACE / NETWORK"
                setTextColor(white)
                textSize = 17f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                letterSpacing = .10f
            }
            (header?.getChildAt(1) as? TextView)?.apply {
                text = "LIVE REQUEST INTELLIGENCE"
                setTextColor(cyan)
                textSize = 10f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                letterSpacing = .08f
            }

            val toolbarHolder = root.getChildAt(1)
            toolbarHolder.setBackgroundColor(surface)
            styleTree(activity, toolbarHolder)

            val filterRow = root.getChildAt(2) as? LinearLayout
            filterRow?.setBackgroundColor(ink)
            styleTree(activity, filterRow)

            (root.getChildAt(3) as? TextView)?.apply {
                setTextColor(muted)
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
                textSize = 10f
                setPadding(dp(activity, 14), dp(activity, 7), dp(activity, 14), dp(activity, 7))
            }

            (root.getChildAt(4) as? ListView)?.apply {
                setBackgroundColor(ink)
                divider = null
                setPadding(dp(activity, 7), dp(activity, 4), dp(activity, 7), dp(activity, 6))
                clipToPadding = false
            }
        }

        addDebuggerBack(activity)
        styleDebuggerRows(activity)
    }

    private fun styleTree(activity: Activity, view: View) {
        when (view) {
            is Button -> styleTinyButton(activity, view)
            is EditText -> {
                view.setTextColor(white)
                view.setHintTextColor(muted)
                view.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
                view.background = rounded(activity, surface2, 11, line)
            }
            is Spinner -> view.background = rounded(activity, surface2, 11, line)
            is ViewGroup -> for (i in 0 until view.childCount) styleTree(activity, view.getChildAt(i))
        }
    }

    private fun styleDebuggerRows(activity: NetworkDebuggerActivity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val root = content.getChildAt(0) as? LinearLayout ?: return
        val list = if (root.childCount >= 5) root.getChildAt(4) as? ListView else null
        list?.let {
            for (i in 0 until it.childCount) {
                val row = it.getChildAt(i) as? LinearLayout ?: continue
                row.background = rounded(activity, surface, 12, line)
                row.setPadding(dp(activity, 11), dp(activity, 9), dp(activity, 11), dp(activity, 9))
                (row.getChildAt(0) as? TextView)?.apply {
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                    textSize = 11f
                    if (currentTextColor != red && currentTextColor != cyan) setTextColor(white)
                }
                (row.getChildAt(1) as? TextView)?.apply {
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
                    textSize = 10f
                    setTextColor(muted)
                }
                (row.layoutParams as? android.widget.AbsListView.LayoutParams)?.let { lp -> lp.height = ViewGroup.LayoutParams.WRAP_CONTENT; row.layoutParams = lp }
            }
        }
    }

    private fun addDebuggerBack(activity: NetworkDebuggerActivity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (content.findViewWithTag<Button>("debugger-back") != null) return
        val button = Button(activity).apply {
            tag = "debugger-back"
            text = "↙ SITE"
            isAllCaps = false
            textSize = 10f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(cyan)
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(activity, 11), 0, dp(activity, 11), 0)
            background = rounded(activity, surface2, 11, cyan)
            elevation = dp(activity, 8).toFloat()
            setOnClickListener { activity.finish() }
        }
        content.addView(button, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, dp(activity, 36)).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dp(activity, 42)
            marginEnd = dp(activity, 10)
        })
    }

    private fun styleIconButton(activity: Activity, button: Button, label: String, strong: Boolean) {
        button.text = label
        button.isAllCaps = false
        button.textSize = 19f
        button.typeface = Typeface.DEFAULT_BOLD
        button.setTextColor(if (strong) ink else cyan)
        button.minWidth = 0
        button.minimumWidth = 0
        button.background = rounded(activity, if (strong) cyan else surface2, 12, if (strong) cyan else line)
    }

    private fun styleTinyButton(activity: Activity, button: Button) {
        button.isAllCaps = false
        button.textSize = 10f
        button.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        button.setTextColor(white)
        button.minWidth = 0
        button.minimumWidth = 0
        button.setPadding(dp(activity, 10), 0, dp(activity, 10), 0)
        button.background = rounded(activity, surface2, 10, line)
    }

    private fun rounded(activity: Activity, fill: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = dp(activity, radius).toFloat()
        stroke?.let { setStroke(dp(activity, 1), it) }
    }

    private fun dp(activity: Activity, value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        when (activity) {
            is WebResearchV10Activity -> {
                browserRef = WeakReference(activity)
                mirroredCount = 0
                mirroredScripts.clear()
                NetworkDebugStore.clear()
            }
            is NetworkDebuggerActivity -> debuggerRef = WeakReference(activity)
        }
    }

    override fun onActivityResumed(activity: Activity) {
        when (activity) {
            is WebResearchV10Activity -> {
                browserRef = WeakReference(activity)
                syncNetworkStore()
                brandBrowser(activity)
            }
            is NetworkDebuggerActivity -> {
                debuggerRef = WeakReference(activity)
                syncNetworkStore()
                brandDebugger(activity)
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
}
