package com.example.instareels

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLong

object Net {
    private const val PARTS = 4
    private const val MIN_PARALLEL = 2L * 1024 * 1024

    /** Returns (total size or -1, supports range requests). Uses Range 0-0 because some CDNs reject HEAD. */
    fun probe(url: String): Pair<Long, Boolean> {
        val req = Request.Builder().url(url).header("User-Agent", Extractor.UA).header("Range", "bytes=0-0").build()
        Repo.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            if (r.code == 206) {
                val total = r.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1L
                return total to (total > 0)
            }
            return (r.header("Content-Length")?.toLongOrNull() ?: -1L) to false
        }
    }

    /** Parallel ranged download when supported (4 connections), otherwise a single stream. */
    suspend fun download(url: String, dest: File, onProgress: (done: Long, total: Long) -> Unit) = coroutineScope {
        val (total, ranged) = probe(url)
        val done = AtomicLong(0)
        val lock = Any()
        var last = 0L
        fun report(force: Boolean = false) {
            val now = SystemClock.uptimeMillis()
            synchronized(lock) {
                if (force || now - last > 250) { last = now; onProgress(done.get(), total) }
            }
        }

        if (ranged && total > MIN_PARALLEL) {
            RandomAccessFile(dest, "rw").use { it.setLength(total) }
            val chunk = total / PARTS
            (0 until PARTS).map { i ->
                async(Dispatchers.IO) {
                    val s = i * chunk
                    val e = if (i == PARTS - 1) total - 1 else s + chunk - 1
                    val req = Request.Builder().url(url).header("User-Agent", Extractor.UA)
                        .header("Range", "bytes=$s-$e").build()
                    Repo.client.newCall(req).execute().use { r ->
                        if (r.code != 206) throw IOException("Range not honored (${r.code})")
                        RandomAccessFile(dest, "rw").use { raf ->
                            raf.seek(s)
                            val src = r.body!!.byteStream()
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = src.read(buf)
                                if (n < 0) break
                                raf.write(buf, 0, n)
                                done.addAndGet(n.toLong()); report()
                            }
                        }
                    }
                }
            }.awaitAll()
        } else {
            val req = Request.Builder().url(url).header("User-Agent", Extractor.UA).build()
            Repo.client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                FileOutputStream(dest).use { out ->
                    val src = r.body!!.byteStream()
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = src.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done.addAndGet(n.toLong()); report()
                    }
                }
            }
        }
        report(force = true)
    }
}
