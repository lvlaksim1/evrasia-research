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
    private var spinner: Spinner? = null
    private var adapter: ArrayAdapter<String>? = null

    init {
        load()
    }

    fun all(): List<String> = bookmarks.toList()

    fun open(url: String) {
        if (url.isNotBlank()) onOpen(url)
    }

    fun save(raw: String) {
        val url = normalizeUrl(raw)
        if (!bookmarks.contains(url)) bookmarks.add(url)
        bookmarks.sort()
        persist()
        adapter?.notifyDataSetChanged()
        spinner?.setSelection(bookmarks.indexOf(url).coerceAtLeast(0))
        Toast.makeText(activity, "Закладка сохранена", Toast.LENGTH_SHORT).show()
    }

    fun delete(url: String) {
        if (bookmarks.remove(url)) {
            persist()
            adapter?.notifyDataSetChanged()
        }
    }

    fun bind(target: Spinner) {
        spinner = target
        adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, bookmarks)
        target.adapter = adapter
        adapter?.notifyDataSetChanged()
    }

    fun openSelected() {
        val current = spinner ?: return
        if (bookmarks.isEmpty()) return
        val index = current.selectedItemPosition.coerceIn(0, bookmarks.lastIndex)
        open(bookmarks[index])
    }

    fun deleteSelected() {
        val current = spinner ?: return
        if (bookmarks.isEmpty()) return
        val index = current.selectedItemPosition.coerceIn(0, bookmarks.lastIndex)
        delete(bookmarks[index])
    }

    private fun load() {
        bookmarks.clear()
        val saved = activity.getSharedPreferences("web-research", Context.MODE_PRIVATE).getStringSet("bookmarks", emptySet()) ?: emptySet()
        bookmarks.addAll(saved.sorted())
    }

    private fun persist() {
        activity.getSharedPreferences("web-research", Context.MODE_PRIVATE).edit().putStringSet("bookmarks", bookmarks.toSet()).apply()
    }
}
