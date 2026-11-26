package com.example.instareels

data class ParsedLink(val kind: String, val code: String, val shared: Boolean = false, val raw: String = "") {
    val url: String get() = "https://www.instagram.com/$kind/$code/"
}

object LinkParser {
    // Matches /reel/, /reels/, /p/, /tv/ (optionally prefixed by username or "share")
    private val rx = Regex("""https?://(?:www\.|m\.)?instagram\.com/((?:[A-Za-z0-9._]+/)?)(reels?|p|tv)/([A-Za-z0-9_-]+)""")

    /** Extracts a clean link from any text (even with extra words around it). */
    fun extract(text: String): ParsedLink? {
        val m = rx.find(text) ?: return null
        val prefix = m.groupValues[1]
        val kind = if (m.groupValues[2] == "reels") "reel" else m.groupValues[2]
        return ParsedLink(kind, m.groupValues[3], shared = prefix == "share/", raw = m.value)
    }
}
