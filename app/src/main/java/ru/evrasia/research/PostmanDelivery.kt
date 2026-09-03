package ru.evrasia.research

import android.app.Activity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object PostmanDelivery {
    fun deliver(activity: Activity, json: String, sourceUrl: String = "") {
        val host = try {
            java.net.URL(sourceUrl).host.replace(Regex("[^A-Za-z0-9._-]"), "-").ifBlank { "request" }
        } catch (_: Exception) {
            "request"
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        ResultDelivery.deliverText(activity, "POSTMAN JSON", json, "postman-$host-$stamp.json", "application/json")
    }
}
