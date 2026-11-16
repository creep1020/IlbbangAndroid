package com.example.ilbbangandroid

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.util.Locale

class MainActivity : ComponentActivity() {

    private var webView: WebView? = null
    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
    private var pendingTtsText: String? = null
    private var pendingTtsRate = 0.85f
    private val appUrl = "https://creep1020.github.io/I-ll-bang-1/"
    private val handler = Handler(Looper.getMainLooper())

    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var recordingRunnable: Runnable? = null
    private var pendingRequestId = ""
    private var pendingMode = ""
    private var recordingActive = false
    private var processingWatchdog: Runnable? = null

    companion object {
        private const val TAG = "IlbbangVoice"
        private const val MIC_PERMISSION = 1001
        private const val RECORDING_TIME = 5000L
        private const val RECOGNITION_TIMEOUT = 30000L
        private const val MODEL_DOWNLOAD_TIMEOUT = 60000L
        private const val PROCESSING_WATCHDOG_TIMEOUT = 95000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        textToSpeech = TextToSpeech(this) { status ->
            runOnUiThread {
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) {
                    try { textToSpeech?.language = Locale.US } catch (e: Exception) {
                        Log.e(TAG, "영어 TTS 언어 설정 실패", e)
                    }
                    val queued = pendingTtsText
                    if (!queued.isNullOrBlank()) {
                        val rate = pendingTtsRate
                        pendingTtsText = null
                        speakNative(queued, rate)
                    }
                } else {
                    Log.e(TAG, "기기 TTS 엔진 초기화 실패: status=$status")
                }
            }
        }
        try {
            val browser = WebView(this)
            webView = browser
            browser.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                cacheMode = WebSettings.LOAD_DEFAULT
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
            browser.addJavascriptInterface(NativeVoiceBridge(), "IlbbangAndroid")
            browser.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    val isAppPage = uri.scheme == "https" &&
                        uri.host == "creep1020.github.io" &&
                        uri.path?.startsWith("/I-ll-bang-1") == true
                    if (isAppPage) return false
                    if (uri.scheme == "https" || uri.scheme == "http") {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, uri))
                        } catch (e: Exception) {
                            Log.e(TAG, "외부 링크 오류", e)
                        }
                    }
                    return true
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: android.webkit.WebResourceError
                ) {
                    super.onReceivedError(view, request, error)
                    if (request.isForMainFrame) {
                        showError("페이지를 불러오지 못했습니다.\n${error.description}")
                    }
                }
            }
            browser.webChromeClient = WebChromeClient()
            browser.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            setContentView(browser)
            if (savedInstanceState == null) {
                browser.loadUrl(appUrl)
            } else {
                val restored = browser.restoreState(savedInstanceState)
                if (restored == null) browser.loadUrl(appUrl)
            }
        } catch (e: Exception) {
            Log.e(TAG, "앱 실행 오류", e)
            showError("앱 실행 오류\n\n${e.message ?: e.toString()}")
        }
    }

    private inner class NativeVoiceBridge {
        @JavascriptInterface
        fun startRecording(requestId: String, mode: String) {
            runOnUiThread {
                if (!isTrustedPage()) {
                    Log.e(TAG, "신뢰할 수 없는 페이지 주소: ${webView?.url}")
                    sendVoiceError(requestId, "앱 주소 확인 오류: ${webView?.url}")
                    return@runOnUiThread
                }
                if (mode != "sentence" && mode != "review") {
                    sendVoiceError(requestId, "잘못된 녹음 요청입니다.")
                    return@runOnUiThread
                }
                if (recordingActive || pendingRequestId.isNotEmpty()) {
                    sendVoiceError(requestId, "이전 음성 처리가 아직 끝나지 않았습니다. 잠시 후 다시 시도해 주세요.", mode)
                    return@runOnUiThread
                }
                pendingRequestId = requestId
                pendingMode = mode
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC_PERMISSION)
                    return@runOnUiThread
                }
                startNativeRecording()
            }
        }
        @JavascriptInterface
        fun stopRecording(requestId: String) {
            runOnUiThread {
                if (!isTrustedPage() || pendingRequestId != requestId) return@runOnUiThread
                if (recordingActive) {
                    Log.d(TAG, "웹에서 녹음 종료 요청: requestId=$requestId")
                    finishNativeRecording()
                } else {
                    // 녹음은 이미 끝나고 모델 준비/인식 중입니다. 처리 작업은 중단하지 않습니다.
                    sendVoiceStatus(requestId, "processing")
                }
            }
        }

        @JavascriptInterface
        fun speak(text: String, rate: Double) {
            runOnUiThread {
                if (!isTrustedPage()) return@runOnUiThread
                val safeText = text.take(500)
                if (safeText.isBlank()) return@runOnUiThread
                pendingTtsText = safeText
                pendingTtsRate = rate.toFloat().coerceIn(0.5f, 2.0f)
                if (ttsReady) {
                    pendingTtsText = null
                    speakNative(safeText, pendingTtsRate)
                }
            }
        }

        @JavascriptInterface
        fun stopSpeaking() {
            runOnUiThread {
                pendingTtsText = null
                try { textToSpeech?.stop() } catch (e: Exception) {
                    Log.w(TAG, "TTS 중지 실패", e)
                }
            }
        }
    }

    private fun speakNative(text: String, rate: Float) {
        if (!ttsReady) {
            pendingTtsText = text
            pendingTtsRate = rate
            return
        }
        try {
            val engine = textToSpeech ?: return
            engine.language = Locale.US
            engine.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ilbbang_tts_${System.currentTimeMillis()}")
        } catch (e: Exception) {
            Log.e(TAG, "네이티브 영어 음성 재생 실패", e)
        }
    }

    private fun isTrustedPage(): Boolean {
        val uri = Uri.parse(webView?.url ?: "")
        return uri.scheme == "https" &&
            uri.host == "creep1020.github.io" &&
            uri.path?.startsWith("/I-ll-bang-1") == true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == MIC_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startNativeRecording()
            } else {
                sendVoiceError(pendingRequestId, "마이크 권한을 허용해야 녹음할 수 있습니다.")
            }
        }
    }

    private fun startNativeRecording() {
        try {
            val file = File(cacheDir, "ilbbang_${System.currentTimeMillis()}.m4a")
            val mediaRecorder = if (Build.VERSION.SDK_INT >= 31) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            mediaRecorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16000)
                setAudioEncodingBitRate(64000)
                setAudioChannels(1)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = mediaRecorder
            recordingFile = file
            recordingActive = true
            sendVoiceStatus(pendingRequestId, "recording")
            recordingRunnable = Runnable { finishNativeRecording() }
            handler.postDelayed(recordingRunnable!!, RECORDING_TIME)
        } catch (e: Exception) {
            Log.e(TAG, "녹음 시작 실패", e)
            releaseRecorder()
            sendVoiceError(pendingRequestId, "녹음을 시작하지 못했습니다: ${e.message}")
        }
    }

    private fun finishNativeRecording() {
        if (!recordingActive) return
        recordingActive = false
        recordingRunnable?.let { handler.removeCallbacks(it) }
        recordingRunnable = null

        val activeRecorder = recorder
        recorder = null
        try {
            activeRecorder?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "녹음 종료 오류", e)
        } finally {
            try { activeRecorder?.release() } catch (_: Exception) {}
        }

        val file = recordingFile
        recordingFile = null
        if (file == null || !file.exists() || file.length() == 0L) {
            file?.delete()
            sendVoiceError(pendingRequestId, "녹음 파일을 만들지 못했습니다.")
            return
        }

        val requestId = pendingRequestId
        val mode = pendingMode
        Log.d(TAG, "녹음 완료: requestId=$requestId, mode=$mode, bytes=${file.length()}")
        // ML Kit 흐름이 예기치 않게 멈춰도 네이티브 요청 잠금이 영구히 남지 않도록 감시합니다.
        processingWatchdog?.let { handler.removeCallbacks(it) }
        processingWatchdog = Runnable {
            if (pendingRequestId == requestId) {
                Log.e(TAG, "네이티브 처리 감시 시간 초과: requestId=$requestId")
                sendVoiceResult(
                    requestId, mode, "", null,
                    "음성인식 처리가 응답하지 않아 종료했어요. 앱을 다시 열고 한 번 더 시도해 주세요."
                )
                pendingRequestId = ""
                pendingMode = ""
            }
            processingWatchdog = null
        }.also { handler.postDelayed(it, PROCESSING_WATCHDOG_TIMEOUT) }
        // 녹음 직후에는 모델 준비 단계를 별도로 알립니다.
        sendVoiceStatus(requestId, "preparing")

        lifecycleScope.launch {
            var transcript = ""
            var recognitionError = ""
            var audioBase64: String? = null

            try {
                Log.d(TAG, "ML Kit 인식 작업 시작: requestId=$requestId")
                transcript = withContext(Dispatchers.IO) {
                    recognizeAudio(file) {
                        Log.d(TAG, "ML Kit 인식 단계 진입: requestId=$requestId")
                        sendVoiceStatus(requestId, "recognizing")
                    }
                }
                Log.d(TAG, "ML Kit 인식 작업 종료: requestId=$requestId, transcriptLength=${transcript.length}")
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "음성인식 또는 모델 준비 시간 초과", e)
                recognitionError = "음성 확인 시간이 길어졌어요. 다시 한 번 천천히 말해 주세요."
            } catch (e: Exception) {
                Log.e(TAG, "ML Kit 인식 오류", e)
                recognitionError = e.message ?: "음성인식에 실패했습니다."
            }

            if (mode == "sentence") {
                try {
                    audioBase64 = withContext(Dispatchers.IO) {
                        Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "녹음 데이터 변환 실패", e)
                    recognitionError = recognitionError.ifEmpty {
                        "녹음 데이터를 전달하지 못했습니다."
                    }
                }
            }

            file.delete()
            if (transcript.isBlank() && recognitionError.isBlank()) {
                recognitionError = "음성을 인식하지 못했습니다."
            }
            Log.d(TAG, "웹뷰로 최종 결과 전달: requestId=$requestId, error=$recognitionError")
            sendVoiceResult(requestId, mode, transcript, audioBase64, recognitionError)
            if (pendingRequestId == requestId) {
                pendingRequestId = ""
                pendingMode = ""
            }
            processingWatchdog?.let { handler.removeCallbacks(it) }
            processingWatchdog = null
        }
    }

    private suspend fun recognizeAudio(file: File, onRecognitionStarted: () -> Unit): String {
        val options = speechRecognizerOptions {
            locale = Locale.US
            preferredMode = SpeechRecognizerOptions.Mode.MODE_BASIC
        }
        val recognizer = SpeechRecognition.getClient(options)
        try {
            sendVoiceStatus(pendingRequestId, "checking")
            val status = recognizer.checkStatus()
            Log.d(TAG, "ML Kit 모델 상태: $status")
            when (status) {
                FeatureStatus.AVAILABLE -> {
                    Log.d(TAG, "ML Kit 모델이 이미 준비되어 있습니다.")
                    sendVoiceStatus(pendingRequestId, "model-ready")
                }
                FeatureStatus.DOWNLOADABLE -> {
                    sendVoiceStatus(pendingRequestId, "downloading")
                    // 다운로드 Flow가 계속 열려 있을 수 있으므로 완료/실패의 첫 종결 이벤트만 기다립니다.
                    val terminal = withTimeout(MODEL_DOWNLOAD_TIMEOUT) {
                        recognizer.download().first { result ->
                            result is DownloadStatus.DownloadCompleted ||
                                result is DownloadStatus.DownloadFailed
                        }
                    }
                    when (terminal) {
                        is DownloadStatus.DownloadCompleted -> {
                            Log.d(TAG, "ML Kit 모델 다운로드 완료")
                            sendVoiceStatus(pendingRequestId, "model-ready")
                        }
                        is DownloadStatus.DownloadFailed -> {
                            val message = terminal.e.message ?: "모델 다운로드 실패"
                            Log.e(TAG, "ML Kit 모델 다운로드 실패: $message", terminal.e)
                            throw Exception(message)
                        }
                        else -> throw Exception("음성인식 모델 다운로드 상태를 확인하지 못했습니다.")
                    }
                }
                FeatureStatus.DOWNLOADING ->
                    throw Exception("음성인식 모델을 다운로드 중입니다. 잠시 후 다시 시도해 주세요.")
                else ->
                    throw Exception("이 기기에서 영어 음성인식을 사용할 수 없습니다. 모델 상태: $status")
            }

            val audioSource = AudioSource.fromFile(file)
            try {
                val request = speechRecognizerRequest { this.audioSource = audioSource }
                var recognizedText = ""
                onRecognitionStarted()
                // 녹음 파일 처리와 최종 응답까지 충분히 기다립니다.
                withTimeout(RECOGNITION_TIMEOUT) {
                    recognizer.startRecognition(request).collect { response ->
                        Log.d(TAG, "ML Kit 응답: ${response::class.java.simpleName}")
                        when (response) {
                            is SpeechRecognizerResponse.FinalTextResponse ->
                                recognizedText = response.text
                            is SpeechRecognizerResponse.ErrorResponse ->
                                throw Exception(response.e.message ?: "음성인식 처리 오류")
                            is SpeechRecognizerResponse.CompletedResponse ->
                                Log.d(TAG, "ML Kit 인식 완료 이벤트 수신")
                            else -> Unit
                        }
                    }
                }
                return recognizedText
            } finally {
                audioSource.close()
            }
        } finally {
            recognizer.close()
        }
    }

    private fun sendVoiceStatus(requestId: String, status: String) {
        val js = "window.onIlbbangNativeVoiceStatus && " +
            "window.onIlbbangNativeVoiceStatus(" +
            "${JSONObject.quote(requestId)}," +
            "${JSONObject.quote(status)});"
        webView?.post {
            val browser = webView
            if (browser == null) {
                Log.e(TAG, "상태 전달 실패: WebView가 없습니다. status=$status")
            } else {
                browser.evaluateJavascript(js) { value ->
                    Log.d(TAG, "상태 전달 완료: status=$status, jsResult=$value")
                }
            }
        }
    }

    private fun sendVoiceError(
        requestId: String,
        message: String,
        mode: String = if (pendingRequestId == requestId) pendingMode else ""
    ) {
        sendVoiceResult(requestId, mode, "", null, message)
        // 오류 경로에서도 요청 잠금을 반드시 해제해 다음 녹음을 허용합니다.
        if (pendingRequestId == requestId) {
            pendingRequestId = ""
            pendingMode = ""
        }
    }

    private fun sendVoiceResult(
        requestId: String,
        mode: String,
        transcript: String,
        audioBase64: String?,
        error: String
    ) {
        val result = JSONObject().apply {
            put("requestId", requestId)
            put("mode", mode)
            put("transcript", transcript)
            put("audioBase64", audioBase64 ?: JSONObject.NULL)
            put("mimeType", "audio/mp4")
            put("error", error)
        }
        val js = "window.onIlbbangNativeVoiceResult && " +
            "window.onIlbbangNativeVoiceResult($result);"
        webView?.post {
            val browser = webView
            if (browser == null) {
                Log.e(TAG, "최종 결과 전달 실패: WebView가 없습니다. requestId=$requestId")
            } else {
                browser.evaluateJavascript(js) { value ->
                    Log.d(TAG, "최종 결과 JS 실행 결과: requestId=$requestId, jsResult=$value")
                }
            }
        }
    }

    private fun releaseRecorder() {
        recordingRunnable?.let { handler.removeCallbacks(it) }
        recordingRunnable = null
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        recordingActive = false
        recordingFile?.delete()
        recordingFile = null
    }

    private fun showError(message: String) {
        val text = TextView(this).apply {
            this.text = message
            textSize = 16f
            setTextColor(Color.BLACK)
            setPadding(40, 60, 40, 40)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            addView(text)
        }
        setContentView(layout)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView?.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        val browser = webView
        if (browser?.canGoBack() == true) browser.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        processingWatchdog?.let { handler.removeCallbacks(it) }
        processingWatchdog = null
        releaseRecorder()
        webView?.removeJavascriptInterface("IlbbangAndroid")
        webView?.destroy()
        webView = null
        try { textToSpeech?.stop() } catch (_: Exception) {}
        try { textToSpeech?.shutdown() } catch (_: Exception) {}
        textToSpeech = null
        ttsReady = false
        super.onDestroy()
    }
}
