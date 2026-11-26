package com.example.instareels

import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

sealed interface UiState {
    data object Idle : UiState
    data object Loading : UiState
    data class Preview(val info: MediaInfo) : UiState
    data class Error(val message: String) : UiState
}

class MainViewModel : ViewModel() {
    val state = MutableStateFlow<UiState>(UiState.Idle)
    var input by mutableStateOf("")
    private var job: Job? = null
    private var lastText = ""
    private var lastImmediate = false

    /** Called when the app receives a Share Intent from Instagram. */
    fun onShared(text: String) {
        input = LinkParser.extract(text)?.url ?: text
        process(text, immediate = Repo.prefs.autoDownload)
    }

    fun retry() = process(lastText, lastImmediate)

    fun process(text: String, immediate: Boolean) {
        lastText = text; lastImmediate = immediate
        val link = LinkParser.extract(text)
        if (link == null) {
            state.value = UiState.Error("الرابط غير صالح. الصق رابط Reel أو منشور من Instagram.")
            return
        }
        state.value = UiState.Loading
        job?.cancel()
        job = viewModelScope.launch {
            try {
                val info = Extractor.extract(link)
                if (immediate) startDownload(info) else state.value = UiState.Preview(info)
            } catch (e: ExtractException) {
                state.value = UiState.Error(
                    when (e.kind) {
                        ExtractException.Kind.INVALID -> "الرابط غير صالح."
                        ExtractException.Kind.PRIVATE -> "الفيديو غير متاح: قد يكون الحساب خاصاً أو المحتوى محذوفاً، أو غيّر Instagram بنية الصفحة. جرّب إعداد خدمة الاستخراج (Backend) من الإعدادات."
                        ExtractException.Kind.NETWORK -> "تعذّر الاتصال. تحقق من الإنترنت وأعد المحاولة."
                        ExtractException.Kind.FAILED -> "فشل استخراج رابط الفيديو. أعد المحاولة لاحقاً."
                    }
                )
            } catch (e: Exception) {
                state.value = UiState.Error("حدث خطأ غير متوقع. أعد المحاولة.")
            }
        }
    }

    fun startDownload(info: MediaInfo) {
        val ctx = Repo.app
        val i = Intent(ctx, DownloadService::class.java)
            .putExtra(DownloadService.EXTRA_URL, info.videoUrl)
            .putExtra(DownloadService.EXTRA_TITLE, "Reel • " + info.title)
            .putExtra(DownloadService.EXTRA_THUMB, info.thumbnail)
            .putExtra(DownloadService.EXTRA_NAME, info.fileName)
            .putExtra(DownloadService.EXTRA_CODE, info.link.code)
        ContextCompat.startForegroundService(ctx, i)
        state.value = UiState.Idle
        input = ""
    }
}
