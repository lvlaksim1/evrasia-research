package ru.evrasia.research

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import java.util.WeakHashMap

internal class AuthUiProvider : ContentProvider(), Application.ActivityLifecycleCallbacks {
    private val controllers = WeakHashMap<WebResearchV10Activity, AuthSessionController>()
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? Application ?: return true
        app.registerActivityLifecycleCallbacks(this)
        return true
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        attach(activity)
    }

    override fun onActivityResumed(activity: Activity) {
        attach(activity)
    }

    override fun onActivityDestroyed(activity: Activity) {
        val browser = activity as? WebResearchV10Activity ?: return
        controllers.remove(browser)?.cancel()
    }

    private fun attach(activity: Activity) {
        val browser = activity as? WebResearchV10Activity ?: return
        val toolbar = browser.window.decorView.findViewWithTag<View>("browser-toolbar") as? LinearLayout
        if (toolbar == null) {
            handler.postDelayed({ if (!browser.isFinishing) attach(browser) }, 150L)
            return
        }
        val existing = toolbar.findViewWithTag<Button>(BUTTON_TAG)
        val controller = controllers.getOrPut(browser) {
            val web = browser.researchWebView() ?: return
            AuthSessionController(browser, web)
        }
        if (existing != null) {
            render(browser, existing, controller)
            return
        }

        val button = Button(browser).apply {
            tag = BUTTON_TAG
            isAllCaps = false
            textSize = 9.5f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(browser, 4), 0, dp(browser, 4), 0)
            setOnClickListener {
                val finishing = controller.isActive()
                controller.toggle()
                render(browser, this, controller)
                if (finishing) refreshUntilSettled(browser, this, controller)
                else handler.postDelayed({ if (!browser.isFinishing) render(browser, this, controller) }, 250L)
            }
        }
        render(browser, button, controller)
        val params = LinearLayout.LayoutParams(dp(browser, 54), dp(browser, 46)).apply {
            marginStart = dp(browser, 4)
        }
        val networkIndex = (0 until toolbar.childCount).firstOrNull { index ->
            toolbar.getChildAt(index).tag == "browser-network"
        } ?: toolbar.childCount
        toolbar.addView(button, networkIndex, params)
    }

    private fun refreshUntilSettled(activity: WebResearchV10Activity, button: Button, controller: AuthSessionController) {
        handler.postDelayed({
            if (activity.isFinishing || activity.isDestroyed) return@postDelayed
            render(activity, button, controller)
            if (controller.isActive() || controller.isProcessing()) refreshUntilSettled(activity, button, controller)
        }, 250L)
    }

    private fun render(activity: WebResearchV10Activity, button: Button, controller: AuthSessionController) {
        val palette = WebUiTheme.palette(activity)
        val active = controller.isActive()
        val processing = controller.isProcessing()
        val highlighted = active || processing
        button.text = when {
            processing -> "AUTH…"
            active -> "AUTH●"
            else -> "AUTH"
        }
        button.contentDescription = when {
            processing -> "Обработка результатов авторизации"
            active -> "Завершить анализ авторизации"
            else -> "Начать анализ авторизации"
        }
        button.setTextColor(if (highlighted) WebUiTheme.contrastText(palette.accent) else palette.accent)
        button.background = rounded(
            activity,
            if (highlighted) palette.accent else palette.card,
            13f,
            if (highlighted) palette.accent else palette.divider
        )
    }

    private fun rounded(activity: Activity, fill: Int, radius: Float, stroke: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = dp(activity, radius.toInt()).toFloat()
        if (stroke != Color.TRANSPARENT) setStroke(dp(activity, 1), stroke)
    }

    private fun dp(activity: Activity, value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val BUTTON_TAG = "auth-analyzer-button"
    }
}
