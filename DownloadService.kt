package com.example.instareels

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nm by lazy { getSystemService(NOTIFICATION_SERVICE) as NotificationManager }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_THUMB = "thumb"
        const val EXTRA_NAME = "name"
        const val EXTRA_CODE = "code"
        private const val CHANNEL = "downloads"
        private const val FG_ID = 1
        private val mutex = Mutex()          // one download at a time, the rest wait in the queue
        private val pending = AtomicInteger(0)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(EXTRA_URL)
        if (url == null) { stopSelf(startId); return START_NOT_STICKY }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Reel"
        val thumb = intent.getStringExtra(EXTRA_THUMB)
        val name = intent.getStringExtra(EXTRA_NAME) ?: "reel_${System.currentTimeMillis()}.mp4"
        val code = intent.getStringExtra(EXTRA_CODE) ?: name

        ensureChannel()
        pending.incrementAndGet()
        ServiceCompat.startForeground(
            this, FG_ID, progressNotif(title, -1f),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )

        scope.launch {
            mutex.withLock {
                Repo.active.value = ActiveDownload(title, thumb, 0, -1)
                val tmp = File(cacheDir, "$code.part")
                try {
                    Net.download(url, tmp) { done, total ->
                        Repo.active.value = ActiveDownload(title, thumb, done, total)
                        val p = if (total > 0) done.toFloat() / total else -1f
                        nm.notify(FG_ID, progressNotif(title, p))
                    }
                    val uri = saveToGallery(tmp, name)
                    Repo.addHistory(HistoryItem(code, uri.toString(), title, tmp.length(), System.currentTimeMillis()))
                    notifyResult(title, uri, ok = true)
                } catch (e: Exception) {
                    notifyResult(title, null, ok = false)
                } finally {
                    tmp.delete()
                    Repo.active.value = null
                }
            }
            if (pending.decrementAndGet() == 0) {
                ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel(); super.onDestroy() }

    // ---- Saving: appears instantly in Gallery (Movies/InstaReelsSaver) ----
    private fun saveToGallery(src: File, name: String): Uri {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/InstaReelsSaver")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw java.io.IOException("MediaStore insert failed")
            contentResolver.openOutputStream(uri)!!.use { out -> src.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            return uri
        }
        // Android 9 and below (needs WRITE_EXTERNAL_STORAGE)
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "InstaReelsSaver")
        dir.mkdirs()
        val f = File(dir, name)
        src.copyTo(f, overwrite = true)
        MediaScannerConnection.scanFile(this, arrayOf(f.absolutePath), arrayOf("video/mp4"), null)
        return Uri.fromFile(f)
    }

    // ---- Notifications ----
    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "التحميلات", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun progressNotif(title: String, p: Float): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("جارٍ التحميل")
            .setContentText(if (p >= 0) "$title • ${(p * 100).toInt()}%" else title)
            .setProgress(100, (p.coerceAtLeast(0f) * 100).toInt(), p < 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun notifyResult(title: String, uri: Uri?, ok: Boolean) {
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(if (ok) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(if (ok) "اكتمل التحميل" else "فشل التحميل")
            .setContentText(title)
            .setAutoCancel(true)
        if (ok && uri != null) {
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            b.setContentIntent(PendingIntent.getActivity(this, 0, view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        nm.notify((System.currentTimeMillis() % 100000).toInt() + 10, b.build())
    }
}
