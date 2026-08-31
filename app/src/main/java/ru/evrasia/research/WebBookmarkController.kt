package ru.evrasia.research

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

internal class WebBookmarkController(
    private val activity: AppCompatActivity,
    private val normalizeUrl: (String) -> String,
    private val onOpen: (String) -> Unit
) {
    private val bookmarks = mutableListOf<String>()
    private lateinit var spinner: Spinner
    private lateinit var adapter: ArrayAdapter<String>

    fun bind(target: Spinner) {
        spinner = target
        adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, bookmarks)
        spinner.adapter = adapter
        load()
    }

    fun openSelected() {
        if (bookmarks.isEmpty()) return
        onOpen(bookmarks[spinner.selectedItemPosition])
    }

    fun save(raw: String) {
        val url = normalizeUrl(raw)
        if (!bookmarks.contains(url)) bookmarks.add(url)
        bookmarks.sort()
        adapter.notifyDataSetChanged()
        persist()
        spinner.setSelection(bookmarks.indexOf(url).coerceAtLeast(0))
        Toast.makeText(activity, "Закладка сохранена", Toast.LENGTH_SHORT).show()
    }

    fun deleteSelected() {
        if (bookmarks.isEmpty()) return
        bookmarks.removeAt(spinner.selectedItemPosition)
        adapter.notifyDataSetChanged()
        persist()
    }

    private fun load() {
        bookmarks.clear()
        val saved = activity.getSharedPreferences("web-research", Context.MODE_PRIVATE).getStringSet("bookmarks", emptySet()) ?: emptySet()
        bookmarks.addAll(saved.sorted())
        adapter.notifyDataSetChanged()
    }

    private fun persist() {
        activity.getSharedPreferences("web-research", Context.MODE_PRIVATE).edit().putStringSet("bookmarks", bookmarks.toSet()).apply()
    }
}
