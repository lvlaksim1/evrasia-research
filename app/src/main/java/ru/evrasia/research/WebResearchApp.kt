package ru.evrasia.research

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import java.lang.ref.WeakReference

class WebResearchApp : Application(), Application.ActivityLifecycleCallbacks {
    private val handler = Handler(Looper.getMainLooper())
    private var browserRef = WeakReference<WebResearchV10Activity>(null)
    private var mirroredCount = 0

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
                    NetworkDebugStore.clear()
                }
                while (mirroredCount < length) {
                    archive.records.optJSONObject(mirroredCount)?.let { NetworkDebugStore.add(it) }
                    mirroredCount++
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun compactBrowserUi(activity: WebResearchV10Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val root = content.getChildAt(0) as? LinearLayout ?: return
        if (root.tag == "compact-browser-ready") return
        root.tag = "compact-browser-ready"

        // Existing layout order: hero, navigation, bookmarks, stats header, stats panel, WebView, bottom tools.
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
        address.layoutParams = LinearLayout.LayoutParams(0, dp(activity, 40), 1f).apply {
            marginStart = dp(activity, 5)
            marginEnd = dp(activity, 5)
        }

        go.text = "➤"
        go.textSize = 18f
        go.layoutParams = LinearLayout.LayoutParams(dp(activity, 42), dp(activity, 40))

        val menu = compactButton(activity, "☰", strong = false) {
            bookmarkCard.visibility = if (bookmarkCard.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }.apply { textSize = 20f }
        nav.addView(menu, 0, LinearLayout.LayoutParams(dp(activity, 42), dp(activity, 40)))

        val net = compactButton(activity, "NET", strong = true) {
            syncNetworkStore()
            activity.startActivity(Intent(activity, NetworkDebuggerActivity::class.java))
        }
        nav.addView(net, LinearLayout.LayoutParams(dp(activity, 50), dp(activity, 40)).apply { marginStart = dp(activity, 5) })

        (bookmarkCard.layoutParams as? LinearLayout.LayoutParams)?.apply {
            setMargins(dp(activity, 6), 0, dp(activity, 6), dp(activity, 4))
            bookmarkCard.layoutParams = this
        }
        bookmarkCard.setPadding(dp(activity, 8), dp(activity, 6), dp(activity, 8), dp(activity, 6))
    }

    private fun addDebuggerBack(activity: NetworkDebuggerActivity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (content.findViewWithTag<Button>("debugger-back") != null) return
        val button = compactButton(activity, "← САЙТ", strong = false) { activity.finish() }.apply {
            tag = "debugger-back"
            elevation = dp(activity, 8).toFloat()
        }
        val params = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, dp(activity, 38)).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dp(activity, 38)
            marginEnd = dp(activity, 8)
        }
        content.addView(button, params)
    }

    private fun compactButton(activity: Activity, label: String, strong: Boolean, click: () -> Unit) = Button(activity).apply {
        text = label
        isAllCaps = false
        textSize = 11f
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(activity, 8), 0, dp(activity, 8), 0)
        setTextColor(if (strong) Color.rgb(8, 18, 14) else Color.rgb(238, 245, 241))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(if (strong) Color.rgb(151, 231, 92) else Color.rgb(20, 39, 33))
            cornerRadius = dp(activity, 11).toFloat()
            if (!strong) setStroke(dp(activity, 1), Color.rgb(50, 76, 65))
        }
        setOnClickListener { click() }
    }

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (activity is WebResearchV10Activity) {
            browserRef = WeakReference(activity)
            mirroredCount = 0
            NetworkDebugStore.clear()
        }
    }

    override fun onActivityResumed(activity: Activity) {
        when (activity) {
            is WebResearchV10Activity -> {
                browserRef = WeakReference(activity)
                syncNetworkStore()
                compactBrowserUi(activity)
            }
            is NetworkDebuggerActivity -> {
                syncNetworkStore()
                addDebuggerBack(activity)
            }
        }
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (activity is WebResearchV10Activity && browserRef.get() === activity) browserRef.clear()
    }

    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
}
