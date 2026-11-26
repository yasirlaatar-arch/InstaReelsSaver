package com.example.instareels

import android.app.Application
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
    }
}

data class ActiveDownload(val title: String, val thumb: String?, val done: Long, val total: Long) {
    val progress: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else -1f
}

data class HistoryItem(val id: String, val uri: String, val title: String, val size: Long, val time: Long)

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    var autoDownload: Boolean
        get() = sp.getBoolean("auto", false)
        set(v) = sp.edit().putBoolean("auto", v).apply()

    /** Optional backend (yt-dlp) URL: GET <url>?url=<instagram link> -> {video_url, thumbnail, title, uploader} */
    var backendUrl: String
        get() = sp.getString("backend", "") ?: ""
        set(v) = sp.edit().putString("backend", v).apply()

    fun loadHistory(): List<HistoryItem> = runCatching {
        val arr = JSONArray(sp.getString("history", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            HistoryItem(o.getString("id"), o.getString("uri"), o.getString("title"), o.getLong("size"), o.getLong("time"))
        }
    }.getOrDefault(emptyList())

    fun saveHistory(list: List<HistoryItem>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("uri", it.uri).put("title", it.title)
                .put("size", it.size).put("time", it.time))
        }
        sp.edit().putString("history", arr.toString()).apply()
    }
}

object Repo {
    lateinit var app: Application
    lateinit var prefs: Prefs
    val active = MutableStateFlow<ActiveDownload?>(null)
    val history = MutableStateFlow<List<HistoryItem>>(emptyList())

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    fun init(a: Application) {
        app = a
        prefs = Prefs(a)
        history.value = prefs.loadHistory()
    }

    fun addHistory(i: HistoryItem) {
        history.update { listOf(i) + it }
        prefs.saveHistory(history.value)
    }

    fun removeHistory(id: String) {
        history.update { l -> l.filter { it.id != id } }
        prefs.saveHistory(history.value)
    }
}
