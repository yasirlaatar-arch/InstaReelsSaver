package com.example.instareels

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

class ExtractException(val kind: Kind, msg: String? = null) : Exception(msg) {
    enum class Kind { INVALID, PRIVATE, NETWORK, FAILED }
}

data class MediaInfo(
    val link: ParsedLink,
    val videoUrl: String,
    val thumbnail: String?,
    val title: String,
    val size: Long?,
) {
    val fileName: String get() = title.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(40) + "_" + link.code + ".mp4"
}

object Extractor {
    const val UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    suspend fun extract(input: ParsedLink): MediaInfo = withContext(Dispatchers.IO) {
        val link = resolveShare(input)
        val backend = Repo.prefs.backendUrl.trim()
        var info: MediaInfo? = null
        var failure: Exception? = null

        // 1) Optional backend (most stable: yt-dlp is updated whenever Instagram changes)
        if (backend.isNotEmpty()) {
            try { info = fromBackend(backend, link) } catch (e: Exception) { failure = e }
        }
        // 2) On-device attempt through the public embed page
        if (info == null) {
            try { info = fromEmbed(link) } catch (e: Exception) { failure = e }
        }
        val result = info ?: throw when (failure) {
            is ExtractException -> failure
            is IOException -> ExtractException(ExtractException.Kind.NETWORK)
            else -> ExtractException(ExtractException.Kind.FAILED)
        }
        val size = runCatching { Net.probe(result.videoUrl).first.takeIf { it > 0 } }.getOrNull()
        result.copy(size = size)
    }

    // New-style share links (/share/reel/XYZ) redirect to the real shortcode
    private fun resolveShare(link: ParsedLink): ParsedLink {
        if (!link.shared) return link
        return runCatching {
            val req = Request.Builder().url(link.raw).header("User-Agent", UA).build()
            Repo.client.newCall(req).execute().use { LinkParser.extract(it.request.url.toString()) }
        }.getOrNull()?.takeIf { !it.shared } ?: link
    }

    private fun fromBackend(base: String, link: ParsedLink): MediaInfo {
        val sep = if (base.contains("?")) "&" else "?"
        val url = base + sep + "url=" + URLEncoder.encode(link.url, "UTF-8")
        Repo.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (r.code == 403 || r.code == 404) throw ExtractException(ExtractException.Kind.PRIVATE)
            if (!r.isSuccessful) throw ExtractException(ExtractException.Kind.FAILED, "backend ${r.code}")
            val o = JSONObject(r.body!!.string())
            return MediaInfo(
                link, o.getString("video_url"), o.optString("thumbnail").ifEmpty { null },
                o.optString("uploader").ifEmpty { "reel" }, null,
            )
        }
    }

    private fun fromEmbed(link: ParsedLink): MediaInfo {
        val req = Request.Builder()
            .url("https://www.instagram.com/${link.kind}/${link.code}/embed/captioned/")
            .header("User-Agent", UA)
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        Repo.client.newCall(req).execute().use { r ->
            if (r.code == 404 || r.code == 403) throw ExtractException(ExtractException.Kind.PRIVATE)
            if (!r.isSuccessful) throw ExtractException(ExtractException.Kind.FAILED, "HTTP ${r.code}")
            val html = r.body!!.string()

            val video = Regex("""video_url\\*"\s*:\s*\\*"(https:.+?)\\*"""").find(html)?.groupValues?.get(1)
                ?: Regex("""<meta property="og:video" content="([^"]+)"""").find(html)?.groupValues?.get(1)
                ?: throw ExtractException(ExtractException.Kind.PRIVATE, "no video_url")

            val thumb = Regex("""<meta property="og:image" content="([^"]+)"""").find(html)?.groupValues?.get(1)
                ?: Regex("""display_url\\*"\s*:\s*\\*"(https:.+?)\\*"""").find(html)?.groupValues?.get(1)
            val owner = Regex("""username\\*"\s*:\s*\\*"([A-Za-z0-9._]+)""").find(html)?.groupValues?.get(1)

            return MediaInfo(link, unescape(video), thumb?.let { unescape(it) }, owner ?: "reel", null)
        }
    }

    private fun unescape(s: String): String {
        var out = s
        repeat(2) {
            out = out.replace("\\/", "/").replace("\\u0026", "&").replace("\\\\", "\\").replace("&amp;", "&")
        }
        return out
    }
}
