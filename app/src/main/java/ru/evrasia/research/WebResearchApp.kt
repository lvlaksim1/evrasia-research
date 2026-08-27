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
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
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

    private fun addSwitcher(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (content.findViewWithTag<Button>("web-research-screen-switcher") != null) return

        val isDebugger = activity is NetworkDebuggerActivity
        val button = Button(activity).apply {
            tag = "web-research-screen-switcher"
            text = if (isDebugger) "← НАЗАД К БРАУЗЕРУ" else "ОТКРЫТЬ NETWORK DEBUGGER"
            isAllCaps = false
            textSize = 12f
            setTextColor(if (isDebugger) Color.rgb(238, 245, 241) else Color.rgb(8, 18, 14))
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(activity, 16), 0, dp(activity, 16), 0)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(if (isDebugger) Color.rgb(20, 39, 33) else Color.rgb(151, 231, 92))
                cornerRadius = dp(activity, 14).toFloat()
                if (isDebugger) setStroke(dp(activity, 1), Color.rgb(50, 76, 65))
            }
            elevation = dp(activity, 12).toFloat()
            setOnClickListener {
                if (isDebugger) {
                    activity.finish()
                } else {
                    syncNetworkStore()
                    activity.startActivity(Intent(activity, NetworkDebuggerActivity::class.java))
                }
            }
        }

        val params = FrameLayout.LayoutParams(
            if (isDebugger) FrameLayout.LayoutParams.WRAP_CONTENT else FrameLayout.LayoutParams.MATCH_PARENT,
            dp(activity, 48)
        ).apply {
            gravity = if (isDebugger) Gravity.TOP or Gravity.END else Gravity.BOTTOM
            if (isDebugger) {
                topMargin = dp(activity, 42)
                marginEnd = dp(activity, 12)
            } else {
                marginStart = dp(activity, 12)
                marginEnd = dp(activity, 12)
                bottomMargin = dp(activity, 60)
            }
        }
        content.addView(button, params)
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
                addSwitcher(activity)
            }
            is NetworkDebuggerActivity -> {
                syncNetworkStore()
                addSwitcher(activity)
            }
        }
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (activity is WebResearchV10Activity && browserRef.get() === activity) {
            browserRef.clear()
        }
    }

    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
}
