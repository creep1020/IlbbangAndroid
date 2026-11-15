
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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

    private var recognitionJob: Job? = null
    private var recognitionTimeoutRunnable: Runnable? = null

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
                if (restored == null) browser.loadUrl(appUrl)
            }

        } catch (e: Exception) {
            Log.e(TAG, "앱 실행 오류", e)
            showError("앱 실행 오류\n\n${e.message ?: e.toString()}")
        }
    }

    // JavaScript 연결

    private inner class NativeVoiceBridge {

        @JavascriptInterface
        fun startRecording(requestId: String, mode: String) {

            runOnUiThread {

                if (!isTrustedPage()) {
                    sendVoiceError(requestId, "앱 주소 확인 오류")
                    return@runOnUiThread
                }

                if (mode != "sentence" && mode != "review") {
                    sendVoiceError(requestId, "잘못된 녹음 요청입니다.")
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

    // 마이크 권한

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

    // 5초 녹음

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

            sendVoiceStatus(pendingRequestId, "recording")

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

    // 녹음 종료 및 음성인식 시작

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

        if (file == null || !file.exists() || file.length() == 0L) {
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

        sendVoiceStatus(requestId, "preparing")

        recognitionJob = lifecycleScope.launch {

            var audioBase64: String? = null

            try {

                // 문장 모드는 녹음 데이터를 먼저 확보합니다.
                // 시간 초과가 발생해도 재생용 녹음 파일을 전달할 수 있습니다.

                if (mode == "sentence") {
                    audioBase64 = withContext(Dispatchers.IO) {
                        Base64.encodeToString(
                            file.readBytes(),
                            Base64.NO_WRAP
                        )
                    }
                }

                val recordedAudioBase64 = audioBase64

                val transcript = withContext(Dispatchers.IO) {

                    recognizeAudio(file) {

                        // 실제로 음성인식이 시작되는 순간
                        // 화면 상태를 바꾸고 별도 5초 타이머를 시작합니다.

                        runOnUiThread {

                            if (
                                pendingRequestId == requestId &&
                                processingActive
                            ) {

                                sendVoiceStatus(
                                    requestId,
                                    "recognizing"
                                )

                                startRecognitionTimeout(
                                    requestId,
                                    mode,
                                    recordedAudioBase64
                                )
                            }
                        }
                    }
                }

                deliverVoiceResult(
                    requestId,
                    mode,
                    transcript,
                    recordedAudioBase64,
                    ""
                )

            } catch (e: CancellationException) {

                Log.d(TAG, "음성인식 작업 취소")

                throw e

            } catch (e: Exception) {

                Log.e(TAG, "음성인식 오류", e)

                deliverVoiceResult(
                    requestId,
                    mode,
                    "",
                    audioBase64,
                    e.message ?: "음성인식에 실패했습니다."
                )

            } finally {

                file.delete()
            }
        }
    }

    // 핵심: 인식 중 별도 5초 타이머

    private fun startRecognitionTimeout(
        requestId: String,
        mode: String,
        audioBase64: String?
    ) {

        cancelRecognitionTimeout()

        val timeoutTask = Runnable {

            if (
                pendingRequestId == requestId &&
                processingActive
            ) {

                Log.w(TAG, "별도 음성인식 타이머 5초 초과")

                // 먼저 인식 작업 취소를 요청합니다.
                recognitionJob?.cancel()

                // 인식 작업의 종료를 기다리지 않고
                // 즉시 웹페이지에 실패 결과를 전달합니다.

                deliverVoiceResult(
                    requestId,
                    mode,
                    "",
                    audioBase64,
                    "음성 확인이 5초 안에 끝나지 않았어요. 다시 녹음해 주세요."
                )
            }
        }

        recognitionTimeoutRunnable = timeoutTask

        handler.postDelayed(
            timeoutTask,
            RECOGNITION_TIMEOUT
        )
    }

    private fun cancelRecognitionTimeout() {

        recognitionTimeoutRunnable?.let {
            handler.removeCallbacks(it)
        }

        recognitionTimeoutRunnable = null
    }

    // 결과를 한 번만 전달

    private fun deliverVoiceResult(
        requestId: String,
        mode: String,
        transcript: String,
        audioBase64: String?,
        error: String
    ) {

        // 이미 시간 초과 결과를 전달했다면
        // 뒤늦게 도착한 인식 결과는 무시합니다.

        if (pendingRequestId != requestId) return

        cancelRecognitionTimeout()

        sendVoiceResult(
            requestId,
            mode,
            transcript,
            audioBase64,
            error
        )

        pendingRequestId = ""
        pendingMode = ""
        processingActive = false
        recognitionJob = null
    }

    // ML Kit 음성인식

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

            // 모델 상태 확인

            val status = recognizer.checkStatus()

            Log.d(TAG, "음성인식 모델 상태: $status")

            when (status) {

                FeatureStatus.AVAILABLE -> {
                    Log.d(TAG, "모델 준비 완료")
                }

                FeatureStatus.DOWNLOADABLE -> {

                    Log.d(TAG, "모델 다운로드 시작")

                    val result = withTimeout(MODEL_DOWNLOAD_TIMEOUT) {

                        recognizer.download().first {
                            it is DownloadStatus.DownloadCompleted ||
                            it is DownloadStatus.DownloadFailed
                        }
                    }

                    when (result) {

                        is DownloadStatus.DownloadCompleted -> {
                            Log.d(TAG, "모델 다운로드 완료")
                        }

                        is DownloadStatus.DownloadFailed -> {
                            throw Exception(
                                result.e.message ?: "모델 다운로드 실패"
                            )
                        }

                        else -> {
                            throw Exception("모델 준비 실패")
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

            val audioSource = AudioSource.fromFile(file)

            try {

                val request = speechRecognizerRequest {
                    this.audioSource = audioSource
                }

                // 여기서 화면에 음성 확인 중을 표시하고
                // 별도 5초 타이머를 작동시킵니다.

                onRecognitionStarted()

                Log.d(TAG, "실제 음성인식 시작")

                // 최종 결과 또는 오류가 도착하면 즉시 반환합니다.

                val response = recognizer.startRecognition(request).first {

                    it is SpeechRecognizerResponse.FinalTextResponse ||
                    it is SpeechRecognizerResponse.ErrorResponse ||
                    it is SpeechRecognizerResponse.CompletedResponse
                }

                return when (response) {

                    is SpeechRecognizerResponse.FinalTextResponse -> {

                        Log.d(
                            TAG,
                            "인식 결과: ${response.text}"
                        )

                        response.text
                    }

                    is SpeechRecognizerResponse.ErrorResponse -> {

                        throw Exception(
                            response.e.message
                                ?: "음성인식 처리 오류"
                        )
                    }

                    is SpeechRecognizerResponse.CompletedResponse -> {

                        throw Exception(
                            "인식된 문장이 없습니다. 다시 말해 주세요."
                        )
                    }

                    else -> {
                        throw Exception("음성인식 결과 처리 오류")
                    }
                }

            } finally {
                audioSource.close()
            }

        } finally {

            // 취소된 경우에도 정리 작업은 수행합니다.
            withContext(NonCancellable) {
                try {
                    recognizer.stopRecognition()
                } catch (e: Exception) {
                    Log.e(TAG, "인식 중지 오류", e)
                }

                try {
                    recognizer.close()
                } catch (e: Exception) {
                    Log.e(TAG, "인식기 정리 오류", e)
                }
            }
        }
    }

    // 웹페이지에 상태 전달

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

    // 오류 전달

    private fun sendVoiceError(
        requestId: String,
        message: String
    ) {

        val mode =
            if (pendingRequestId == requestId) pendingMode else ""

        deliverVoiceResult(
            requestId,
            mode,
            "",
            null,
            message
        )
    }

    // 인식 결과 전달

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

        val js =
            "window.onIlbbangNativeVoiceResult && " +
            "window.onIlbbangNativeVoiceResult($result);"

        webView?.post {
            webView?.evaluateJavascript(js, null)
        }
    }

    // 녹음기 정리

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

    // 오류 화면

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

    // 화면 상태 저장

    override fun onSaveInstanceState(outState: Bundle) {
        webView?.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    // 뒤로가기

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {

        val browser = webView

        if (browser?.canGoBack() == true) {
            browser.goBack()
        } else {
            super.onBackPressed()
        }
    }

    // 앱 종료

    override fun onDestroy() {

        cancelRecognitionTimeout()

        recognitionJob?.cancel()
        recognitionJob = null

        releaseRecorder()

        webView?.removeJavascriptInterface("IlbbangAndroid")
        webView?.destroy()
        webView = null

        super.onDestroy()
    }
}
