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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.util.Locale

class MainActivity : ComponentActivity() {

    private var webView: WebView? = null
    private val appUrl = "https://creep1020.github.io/I-ll-bang-1/"
    private val handler = Handler(Looper.getMainLooper())

    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var recordingRunnable: Runnable? = null

    private var pendingRequestId = ""
    private var pendingMode = ""

    private var recordingActive = false
    private var processingActive = false

    companion object {
        private const val TAG = "IlbbangVoice"
        private const val MIC_PERMISSION = 1001

        private const val RECORDING_TIME = 5000L
        private const val RECOGNITION_TIMEOUT = 5000L
        private const val MODEL_DOWNLOAD_TIMEOUT = 60000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

            browser.addJavascriptInterface(
                NativeVoiceBridge(),
                "IlbbangAndroid"
            )

            browser.webViewClient = object : WebViewClient() {

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {

                    val uri = request.url

                    val isAppPage =
                        uri.scheme == "https" &&
                        uri.host == "creep1020.github.io" &&
                        uri.path?.startsWith("/I-ll-bang-1") == true

                    if (isAppPage) return false

                    if (uri.scheme == "https" || uri.scheme == "http") {
                        try {
                            startActivity(
                                Intent(Intent.ACTION_VIEW, uri)
                            )
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
                        showError(
                            "페이지를 불러오지 못했습니다.\n${error.description}"
                        )
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
                if (restored == null) {
                    browser.loadUrl(appUrl)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "앱 실행 오류", e)
            showError("앱 실행 오류\n\n${e.message ?: e.toString()}")
        }
    }

    // --------------------------------------------------
    // JavaScript 연결
    // --------------------------------------------------

    private inner class NativeVoiceBridge {

        @JavascriptInterface
        fun startRecording(requestId: String, mode: String) {

            runOnUiThread {

                if (!isTrustedPage()) {
                    Log.e(TAG, "신뢰할 수 없는 페이지: ${webView?.url}")
                    sendVoiceError(
                        requestId,
                        "앱 주소 확인 오류"
                    )
                    return@runOnUiThread
                }

                if (mode != "sentence" && mode != "review") {
                    sendVoiceError(
                        requestId,
                        "잘못된 녹음 요청입니다."
                    )
                    return@runOnUiThread
                }

                if (recordingActive || processingActive) {
                    sendVoiceError(
                        requestId,
                        "이전 음성 확인이 끝난 뒤 다시 시도해 주세요."
                    )
                    return@runOnUiThread
                }

                pendingRequestId = requestId
                pendingMode = mode

                if (
                    checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissions(
                        arrayOf(Manifest.permission.RECORD_AUDIO),
                        MIC_PERMISSION
                    )
                    return@runOnUiThread
                }

                startNativeRecording()
            }
        }
    }

    private fun isTrustedPage(): Boolean {
        val uri = Uri.parse(webView?.url ?: "")

        return uri.scheme == "https" &&
            uri.host == "creep1020.github.io" &&
            uri.path?.startsWith("/I-ll-bang-1") == true
    }

    // --------------------------------------------------
    // 마이크 권한
    // --------------------------------------------------

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode == MIC_PERMISSION) {

            if (
                grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                startNativeRecording()
            } else {
                sendVoiceError(
                    pendingRequestId,
                    "마이크 권한을 허용해야 녹음할 수 있습니다."
                )
            }
        }
    }

    // --------------------------------------------------
    // 5초 녹음
    // --------------------------------------------------

    private fun startNativeRecording() {

        try {
            val file = File(
                cacheDir,
                "ilbbang_${System.currentTimeMillis()}.m4a"
            )

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

            sendVoiceStatus(
                pendingRequestId,
                "recording"
            )

            recordingRunnable = Runnable {
                finishNativeRecording()
            }

            handler.postDelayed(
                recordingRunnable!!,
                RECORDING_TIME
            )

        } catch (e: Exception) {

            Log.e(TAG, "녹음 시작 실패", e)

            releaseRecorder()

            sendVoiceError(
                pendingRequestId,
                "녹음을 시작하지 못했습니다: ${e.message}"
            )
        }
    }

    // --------------------------------------------------
    // 녹음 종료 후 음성인식 시작
    // --------------------------------------------------

    private fun finishNativeRecording() {

        if (!recordingActive) return

        recordingActive = false

        recordingRunnable?.let {
            handler.removeCallbacks(it)
        }

        recordingRunnable = null

        val activeRecorder = recorder
        recorder = null

        try {
            activeRecorder?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "녹음 종료 오류", e)
        } finally {
            try {
                activeRecorder?.release()
            } catch (_: Exception) {
            }
        }

        val file = recordingFile
        recordingFile = null

        if (
            file == null ||
            !file.exists() ||
            file.length() == 0L
        ) {
            file?.delete()

            sendVoiceError(
                pendingRequestId,
                "녹음 파일을 만들지 못했습니다."
            )
            return
        }

        val requestId = pendingRequestId
        val mode = pendingMode

        processingActive = true

        sendVoiceStatus(
            requestId,
            "preparing"
        )

        lifecycleScope.launch {

            var transcript = ""
            var recognitionError = ""
            var audioBase64: String? = null

            try {

                transcript = withContext(Dispatchers.IO) {
                    recognizeAudio(file) {

                        sendVoiceStatus(
                            requestId,
                            "recognizing"
                        )
                    }
                }

            } catch (e: Exception) {

                Log.e(TAG, "음성인식 오류", e)

                recognitionError =
                    e.message ?: "음성인식에 실패했습니다."
            }

            // 문장 학습에서는 기존 녹음 재생 기능을 위해
            // 녹음 파일을 Base64로 전달합니다.

            if (mode == "sentence") {

                try {
                    audioBase64 = withContext(Dispatchers.IO) {
                        Base64.encodeToString(
                            file.readBytes(),
                            Base64.NO_WRAP
                        )
                    }

                } catch (e: Exception) {

                    Log.e(TAG, "녹음 데이터 변환 실패", e)

                    if (recognitionError.isBlank()) {
                        recognitionError =
                            "녹음 데이터를 전달하지 못했습니다."
                    }
                }
            }

            file.delete()

            if (
                transcript.isBlank() &&
                recognitionError.isBlank()
            ) {
                recognitionError = "음성을 인식하지 못했습니다."
            }

            sendVoiceResult(
                requestId,
                mode,
                transcript,
                audioBase64,
                recognitionError
            )

            if (pendingRequestId == requestId) {
                pendingRequestId = ""
                pendingMode = ""
            }

            processingActive = false
        }
    }

    // --------------------------------------------------
    // ML Kit 음성인식
    // --------------------------------------------------

    private suspend fun recognizeAudio(
        file: File,
        onRecognitionStarted: () -> Unit
    ): String {

        val options = speechRecognizerOptions {
            locale = Locale.US
            preferredMode =
                SpeechRecognizerOptions.Mode.MODE_BASIC
        }

        val recognizer = SpeechRecognition.getClient(options)

        try {

            // 1. 모델 상태 확인

            val status = recognizer.checkStatus()

            Log.d(TAG, "음성인식 모델 상태: $status")

            when (status) {

                FeatureStatus.AVAILABLE -> {
                    Log.d(TAG, "음성인식 모델 준비 완료")
                }

                FeatureStatus.DOWNLOADABLE -> {

                    Log.d(TAG, "음성인식 모델 다운로드 시작")

                    val downloadResult = try {

                        withTimeout(MODEL_DOWNLOAD_TIMEOUT) {

                            recognizer.download().first {
                                it is DownloadStatus.DownloadCompleted ||
                                it is DownloadStatus.DownloadFailed
                            }
                        }

                    } catch (e: TimeoutCancellationException) {

                        throw Exception(
                            "음성인식 모델 다운로드가 60초 안에 끝나지 않았습니다. 인터넷 연결을 확인하고 다시 시도해 주세요."
                        )
                    }

                    when (downloadResult) {

                        is DownloadStatus.DownloadCompleted -> {
                            Log.d(TAG, "모델 다운로드 완료")
                        }

                        is DownloadStatus.DownloadFailed -> {
                            throw Exception(
                                downloadResult.e.message
                                    ?: "음성인식 모델 다운로드 실패"
                            )
                        }

                        else -> {
                            throw Exception(
                                "음성인식 모델을 준비하지 못했습니다."
                            )
                        }
                    }
                }

                FeatureStatus.DOWNLOADING -> {

                    throw Exception(
                        "음성인식 모델을 다운로드 중입니다. 잠시 후 다시 시도해 주세요."
                    )
                }

                else -> {

                    throw Exception(
                        "이 기기에서 영어 음성인식을 사용할 수 없습니다."
                    )
                }
            }

            // 2. 녹음 파일 불러오기

            val audioSource = AudioSource.fromFile(file)

            try {

                val request = speechRecognizerRequest {
                    this.audioSource = audioSource
                }

                onRecognitionStarted()

                Log.d(TAG, "실제 음성인식 시작")

                // 3. 최종 결과 또는 오류가 도착하면 즉시 종료
                // CompletedResponse까지 무작정 기다리지 않습니다.

                val response = try {

                    withTimeout(RECOGNITION_TIMEOUT) {

                        recognizer.startRecognition(request).first {

                            it is SpeechRecognizerResponse.FinalTextResponse ||
                            it is SpeechRecognizerResponse.ErrorResponse ||
                            it is SpeechRecognizerResponse.CompletedResponse
                        }
                    }

                } catch (e: TimeoutCancellationException) {

                    Log.w(TAG, "음성인식 제한시간 초과")

                    try {
                        recognizer.stopRecognition()
                    } catch (stopError: Exception) {
                        Log.e(
                            TAG,
                            "음성인식 중지 오류",
                            stopError
                        )
                    }

                    throw Exception(
                        "음성 확인이 5초 안에 끝나지 않았어요. 조금 천천히 다시 말해 주세요."
                    )
                }

                // 4. 응답 처리

                when (response) {

                    is SpeechRecognizerResponse.FinalTextResponse -> {

                        Log.d(
                            TAG,
                            "인식 결과: ${response.text}"
                        )

                        return response.text
                    }

                    is SpeechRecognizerResponse.ErrorResponse -> {

                        Log.e(
                            TAG,
                            "ML Kit 오류: ${response.e.message}"
                        )

                        throw Exception(
                            response.e.message
                                ?: "음성인식 처리 중 오류가 발생했습니다."
                        )
                    }

                    is SpeechRecognizerResponse.CompletedResponse -> {

                        Log.d(
                            TAG,
                            "음성인식 완료 신호 수신"
                        )

                        throw Exception(
                            "인식된 문장이 없습니다. 다시 말해 주세요."
                        )
                    }

                    else -> {

                        throw Exception(
                            "음성인식 결과를 처리하지 못했습니다."
                        )
                    }
                }

            } finally {

                audioSource.close()
            }

        } finally {

            recognizer.close()
        }
    }

    // --------------------------------------------------
    // 웹페이지로 상태 전달
    // --------------------------------------------------

    private fun sendVoiceStatus(
        requestId: String,
        status: String
    ) {

        val js =
            "window.onIlbbangNativeVoiceStatus && " +
            "window.onIlbbangNativeVoiceStatus(" +
            "${JSONObject.quote(requestId)}," +
            "${JSONObject.quote(status)});"

        webView?.post {
            webView?.evaluateJavascript(js, null)
        }
    }

    // --------------------------------------------------
    // 오류 전달
    // --------------------------------------------------

    private fun sendVoiceError(
        requestId: String,
        message: String
    ) {

        val mode =
            if (pendingRequestId == requestId) {
                pendingMode
            } else {
                ""
            }

        sendVoiceResult(
            requestId,
            mode,
            "",
            null,
            message
        )

        if (pendingRequestId == requestId) {
            pendingRequestId = ""
            pendingMode = ""
        }
    }

    // --------------------------------------------------
    // 인식 결과 전달
    // --------------------------------------------------

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

            put(
                "audioBase64",
                audioBase64 ?: JSONObject.NULL
            )

            put("mimeType", "audio/mp4")
            put("error", error)
        }

        val js =
            "window.onIlbbangNativeVoiceResult && " +
            "window.onIlbbangNativeVoiceResult($result);"

        webView?.post {
            webView?.evaluateJavascript(js, null)
        }
    }

    // --------------------------------------------------
    // 녹음기 정리
    // --------------------------------------------------

    private fun releaseRecorder() {

        recordingRunnable?.let {
            handler.removeCallbacks(it)
        }

        recordingRunnable = null

        try {
            recorder?.release()
        } catch (_: Exception) {
        }

        recorder = null
        recordingActive = false

        recordingFile?.delete()
        recordingFile = null
    }

    // --------------------------------------------------
    // 오류 화면
    // --------------------------------------------------

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

    // --------------------------------------------------
    // 화면 상태 저장
    // --------------------------------------------------

    override fun onSaveInstanceState(outState: Bundle) {

        webView?.saveState(outState)

        super.onSaveInstanceState(outState)
    }

    // --------------------------------------------------
    // 뒤로가기
    // --------------------------------------------------

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {

        val browser = webView

        if (browser?.canGoBack() == true) {
            browser.goBack()
        } else {
            super.onBackPressed()
        }
    }

    // --------------------------------------------------
    // 앱 종료
    // --------------------------------------------------

    override fun onDestroy() {

        releaseRecorder()

        webView?.removeJavascriptInterface(
            "IlbbangAndroid"
        )

        webView?.destroy()
        webView = null

        super.onDestroy()
    }
}
