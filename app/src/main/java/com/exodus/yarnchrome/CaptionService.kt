package com.exodus.yarnchrome

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.io.IOException
import java.io.OutputStream
import kotlin.concurrent.thread

/** 字幕在各个浏览器窗口之间的分发。只在主线程使用。 */
object CaptionBus {
    interface Listener {
        fun onCaption(original: String, translated: String)
        fun onCaptionState(running: Boolean)
    }

    private val listeners = mutableSetOf<Listener>()
    var running = false
        private set

    fun add(listener: Listener) {
        listeners.add(listener)
    }

    fun remove(listener: Listener) {
        listeners.remove(listener)
    }

    fun post(original: String, translated: String) {
        listeners.toList().forEach { it.onCaption(original, translated) }
    }

    fun setRunning(value: Boolean) {
        running = value
        listeners.toList().forEach { it.onCaptionState(value) }
    }
}

/**
 * 实时字幕：通过 MediaProjection 抓取应用正在播放的声音（不用麦克风），
 * 交给系统语音识别转成日文，再用 ML Kit 离线翻译成中文，经 CaptionBus 显示在网页上。
 */
@RequiresApi(33)
class CaptionService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null
    @Volatile private var running = false
    @Volatile private var sink: OutputStream? = null
    private var sessionRead: ParcelFileDescriptor? = null

    private var recognizer: SpeechRecognizer? = null
    private var onDevice = true
    private var session = 0
    private var failures = 0
    private val restartTick = Runnable { startSession() }

    private var translator: Translator? = null
    private var translatorReady = false
    private var currentOriginal = ""
    private var currentTranslated = ""
    private var utterance = 0          // 每句识别结束后加一，丢弃上一句迟到的翻译
    private var originalUtterance = -1
    private var translateSeq = 0
    private var appliedSeq = 0
    private var lastPartialTranslateAt = 0L
    private val clearTick = Runnable {
        currentOriginal = ""
        currentTranslated = ""
        publish()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        onDevice = intent.getBooleanExtra(EXTRA_ON_DEVICE, true)

        // 必须先进入前台，才能拿 MediaProjection
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        val mp = try {
            getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
        } catch (e: Exception) {
            null
        }
        if (mp == null) {
            toast(R.string.caption_start_failed)
            stopSelf()
            return START_NOT_STICKY
        }
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, main)

        if (!startCapture(mp)) {
            toast(R.string.caption_start_failed)
            stopSelf()
            return START_NOT_STICKY
        }
        startTranslator()
        recognizer = if (onDevice) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        } else {
            SpeechRecognizer.createSpeechRecognizer(this)
        }
        CaptionBus.setRunning(true)
        startSession()
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacks(restartTick)
        main.removeCallbacks(clearTick)
        endSession(cancel = true)
        recognizer?.destroy()
        recognizer = null
        record?.let {
            try {
                it.stop()
            } catch (_: IllegalStateException) {
            }
            it.release()
        }
        record = null
        projection?.stop()
        projection = null
        translator?.close()
        translator = null
        CaptionBus.post("", "")
        CaptionBus.setRunning(false)
        super.onDestroy()
    }

    // ---- 抓取声音 ----

    @SuppressLint("MissingPermission") // 开启前已在界面上申请录音权限
    private fun startCapture(mp: MediaProjection): Boolean {
        val config = AudioPlaybackCaptureConfiguration.Builder(mp)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val rec = try {
            AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBuf * 2, SAMPLE_RATE))
                .setAudioPlaybackCaptureConfig(config)
                .build()
        } catch (e: Exception) {
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        try {
            rec.startRecording()
        } catch (e: IllegalStateException) {
            rec.release()
            return false
        }
        record = rec
        running = true
        // 把声音源源不断写进当前识别会话的管道；没有会话时直接丢弃
        thread(name = "caption-audio", isDaemon = true) {
            val buf = ByteArray(SAMPLE_RATE / 10 * 2) // 100 ms
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) {
                    if (n < 0) SystemClock.sleep(50)
                    continue
                }
                val out = sink ?: continue
                try {
                    out.write(buf, 0, n)
                } catch (e: IOException) {
                    if (sink === out) sink = null
                }
            }
        }
        return true
    }

    // ---- 语音识别 ----

    private fun startSession() {
        val rec = recognizer ?: return
        if (!running) return
        endSession(cancel = false)
        val pipe = try {
            ParcelFileDescriptor.createPipe()
        } catch (e: IOException) {
            scheduleRestart(1000)
            return
        }
        sessionRead = pipe[0]
        sink = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
        val id = ++session
        rec.setRecognitionListener(Listener(id))
        val intent = recognizerIntent()
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE)
            // 连续分段识别：音频不断，会话就不结束，每句话通过 onSegmentResults 返回
            .putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        try {
            rec.startListening(intent)
        } catch (e: Exception) {
            scheduleRestart(1000)
        }
    }

    /** 结束当前会话：先关读端让写线程立刻失败返回，再关写端。 */
    private fun endSession(cancel: Boolean) {
        if (cancel) {
            try {
                recognizer?.cancel()
            } catch (_: Exception) {
            }
        }
        val out = sink
        sink = null
        try {
            sessionRead?.close()
        } catch (_: IOException) {
        }
        sessionRead = null
        try {
            out?.close()
        } catch (_: IOException) {
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        main.removeCallbacks(restartTick)
        if (running) main.postDelayed(restartTick, delayMs)
    }

    private inner class Listener(private val id: Int) : RecognitionListener {
        private fun stale() = id != session || !running

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            if (stale()) return
            firstText(partialResults)?.let { onText(it, final = false) }
        }

        override fun onSegmentResults(segmentResults: Bundle) {
            if (stale()) return
            failures = 0
            firstText(segmentResults)?.let { onText(it, final = true) }
        }

        override fun onEndOfSegmentedSession() {
            if (stale()) return
            scheduleRestart(RESTART_MS)
        }

        override fun onResults(results: Bundle?) {
            if (stale()) return
            failures = 0
            firstText(results)?.let { onText(it, final = true) }
            scheduleRestart(RESTART_MS)
        }

        override fun onError(error: Int) {
            if (stale()) return
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> scheduleRestart(RESTART_MS)
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                    toast(R.string.caption_language_unavailable)
                    stopSelf()
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    toast(R.string.caption_permission_denied)
                    stopSelf()
                }
                else -> {
                    failures++
                    if (failures >= MAX_FAILURES) {
                        toast(getString(R.string.caption_recognizer_error, error))
                        stopSelf()
                    } else {
                        scheduleRestart(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1500 else 800)
                    }
                }
            }
        }
    }

    private fun firstText(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    // ---- 翻译与显示 ----

    private fun startTranslator() {
        val t = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.JAPANESE)
                .setTargetLanguage(TranslateLanguage.CHINESE)
                .build(),
        )
        translator = t
        t.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener { translatorReady = true }
            .addOnFailureListener { toast(R.string.caption_translate_model_failed) }
    }

    private fun onText(text: String, final: Boolean) {
        main.removeCallbacks(clearTick)
        if (originalUtterance != utterance) {
            originalUtterance = utterance
            currentTranslated = ""
        }
        currentOriginal = text
        publish()
        val now = SystemClock.elapsedRealtime()
        if (final || now - lastPartialTranslateAt >= PARTIAL_TRANSLATE_MS) {
            lastPartialTranslateAt = now
            translate(text, utterance)
        }
        if (final) {
            utterance++
            main.postDelayed(clearTick, CLEAR_MS)
        }
    }

    private fun translate(text: String, forUtterance: Int) {
        val t = translator ?: return
        if (!translatorReady) return
        val seq = ++translateSeq
        t.translate(text).addOnSuccessListener { result ->
            // 只接受当前显示这句的、比已显示更新的翻译
            if (!running || forUtterance != originalUtterance || seq <= appliedSeq) return@addOnSuccessListener
            appliedSeq = seq
            currentTranslated = result
            publish()
        }
    }

    private fun publish() {
        CaptionBus.post(currentOriginal, currentTranslated)
    }

    // ---- 通知与提示 ----

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.caption_title), NotificationManager.IMPORTANCE_LOW),
        )
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, CaptionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_caption)
            .setContentTitle(getString(R.string.caption_title))
            .setContentText(getString(R.string.caption_notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_caption), getString(R.string.caption_stop), stop).build())
            .build()
    }

    private fun toast(resId: Int) = toast(getString(resId))

    private fun toast(text: String) {
        main.post { Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show() }
    }

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_ON_DEVICE = "on_device"
        const val ACTION_STOP = "com.exodus.yarnchrome.CAPTION_STOP"
        const val LANGUAGE = "ja-JP"
        private const val CHANNEL_ID = "captions"
        private const val NOTIFICATION_ID = 1001
        private const val SAMPLE_RATE = 16_000
        private const val RESTART_MS = 200L
        private const val MAX_FAILURES = 5
        private const val PARTIAL_TRANSLATE_MS = 500L
        private const val CLEAR_MS = 6_000L

        fun recognizerIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)

        fun stop(context: Context) {
            context.startService(Intent(context, CaptionService::class.java).setAction(ACTION_STOP))
        }
    }
}
