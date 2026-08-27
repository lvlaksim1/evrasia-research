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
import android.widget.Spinner
import android.widget.TextView
import java.lang.ref.WeakReference

class WebResearchApp : Application(), Application.ActivityLifecycleCallbacks {
    private val handler = Handler(Looper.getMainLooper())
    private var browserRef = WeakReference<WebResearchV10Activity>(null)
    private var mirroredCount = 0
    private val mirroredScripts = mutableSetOf<String>()

    private val syncTicker = object : Runnable {
        override fun run() {
            syncNetworkStore()
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

    private fun compactBrowserUi(activity: WebResearchV10Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val root = content.getChildAt(0) as? LinearLayout ?: return
        if (root.tag == "compact-browser-v2") return
        root.tag = "compact-browser-v2"
        if (root.childCount < 7) return

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

        val nav = navCard.getChildAt(0) as? LinearLayout ?: return
        val address = nav.getChildAt(0) as? EditText ?: return
        val go = nav.getChildAt(1) as? Button ?: return
        navCard.setPadding(dp(activity, 6), dp(activity, 5), dp(activity, 6), dp(activity, 5))
        (navCard.layoutParams as? LinearLayout.LayoutParams)?.apply {
            height = LinearLayout.LayoutParams.WRAP_CONTENT
            setMargins(dp(activity, 6), dp(activity, 4), dp(activity, 6), dp(activity, 4))
            navCard.layoutParams = this
        }
        address.textSize = 13f
        address.setPadding(dp(activity, 10), 0, dp(activity, 10), 0)
        address.layoutParams = LinearLayout.LayoutParams(0, dp(activity, 40), 1f).apply { marginStart = dp(activity, 5); marginEnd = dp(activity, 5) }
        go.text = "➜"
        go.textSize = 21f
        go.typeface = Typeface.DEFAULT_BOLD
        go.setTextColor(Color.rgb(6,14,12))
        go.background = rounded(activity, Color.rgb(151,231,92), 12)
        go.layoutParams = LinearLayout.LayoutParams(dp(activity, 42), dp(activity, 40))

        // Make bookmark drawer compact and dark-styled.
        if (bookmarkCard.childCount > 0) bookmarkCard.getChildAt(0).visibility = View.GONE
        bookmarkCard.setPadding(dp(activity, 6), dp(activity, 4), dp(activity, 6), dp(activity, 4))
        (bookmarkCard.layoutParams as? LinearLayout.LayoutParams)?.apply { setMargins(dp(activity,6),0,dp(activity,6),dp(activity,4)); bookmarkCard.layoutParams=this }
        val bookmarkRow = if (bookmarkCard.childCount > 1) bookmarkCard.getChildAt(1) as? LinearLayout else null
        bookmarkRow?.layoutParams = bookmarkRow?.layoutParams?.apply { height = dp(activity, 40) }
        val spinner = bookmarkRow?.let { row -> (0 until row.childCount).map { row.getChildAt(it) }.filterIsInstance<Spinner>().firstOrNull() }
        spinner?.let { sp ->
            val strings = mutableListOf<String>()
            val old = sp.adapter
            for (i in 0 until (old?.count ?: 0)) strings.add(old?.getItem(i)?.toString().orEmpty())
            sp.adapter = object : ArrayAdapter<String>(activity, android.R.layout.simple_spinner_item, strings) {
                private fun row(position: Int, dropdown: Boolean) = TextView(activity).apply {
                    text = getItem(position)
                    setTextColor(Color.rgb(238,245,241)); textSize = 13f; gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(activity,10), if (dropdown) dp(activity,10) else 0, dp(activity,10), if (dropdown) dp(activity,10) else 0)
                    background = rounded(activity, if (dropdown) Color.rgb(10,22,18) else Color.rgb(20,39,33), 10, Color.rgb(50,76,65))
                }
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = row(position,false)
                override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View = row(position,true)
            }
        }
        bookmarkRow?.let { row ->
            val buttons = (0 until row.childCount).map { row.getChildAt(it) }.filterIsInstance<Button>()
            buttons.firstOrNull { it.text.toString() == "Открыть" }?.apply {
                text = "➜"; textSize = 19f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(6,14,12)); background = rounded(activity, Color.rgb(151,231,92), 11)
            }
        }

        val menu = compactButton(activity, "☰", false) { bookmarkCard.visibility = if (bookmarkCard.visibility == View.VISIBLE) View.GONE else View.VISIBLE }.apply { textSize = 20f }
        nav.addView(menu, 0, LinearLayout.LayoutParams(dp(activity, 42), dp(activity, 40)))
        val net = compactButton(activity, "⌁", false) {
            syncNetworkStore(); activity.startActivity(Intent(activity, NetworkDebuggerActivity::class.java))
        }.apply { textSize = 22f; typeface = Typeface.DEFAULT_BOLD }
        nav.addView(net, LinearLayout.LayoutParams(dp(activity,42),dp(activity,40)).apply { marginStart = dp(activity,5) })
    }

    private fun addDebuggerBack(activity: NetworkDebuggerActivity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (content.findViewWithTag<Button>("debugger-back") != null) return
        val button = compactButton(activity, "← САЙТ", false) { activity.finish() }.apply { tag = "debugger-back"; elevation = dp(activity,8).toFloat() }
        content.addView(button, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, dp(activity,38)).apply { gravity = Gravity.TOP or Gravity.END; topMargin = dp(activity,38); marginEnd = dp(activity,8) })
    }

    private fun rounded(activity: Activity, fill: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; setColor(fill); cornerRadius = dp(activity,radius).toFloat(); stroke?.let { setStroke(dp(activity,1), it) }
    }
    private fun compactButton(activity: Activity, label: String, strong: Boolean, click: () -> Unit) = Button(activity).apply {
        text = label; isAllCaps = false; textSize = 11f; minWidth = 0; minimumWidth = 0; setPadding(dp(activity,8),0,dp(activity,8),0)
        setTextColor(if (strong) Color.rgb(8,18,14) else Color.rgb(238,245,241))
        background = rounded(activity, if (strong) Color.rgb(151,231,92) else Color.rgb(20,39,33), 11, if (strong) null else Color.rgb(50,76,65))
        setOnClickListener { click() }
    }
    private fun dp(activity: Activity, value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (activity is WebResearchV10Activity) { browserRef = WeakReference(activity); mirroredCount = 0; mirroredScripts.clear(); NetworkDebugStore.clear() }
    }
    override fun onActivityResumed(activity: Activity) {
        when (activity) {
            is WebResearchV10Activity -> { browserRef = WeakReference(activity); syncNetworkStore(); compactBrowserUi(activity) }
            is NetworkDebuggerActivity -> { syncNetworkStore(); addDebuggerBack(activity) }
        }
    }
    override fun onActivityDestroyed(activity: Activity) { if (activity is WebResearchV10Activity && browserRef.get() === activity) browserRef.clear() }
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
}
