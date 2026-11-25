package com.example.voice

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.data.model.ChatMessage
import com.example.data.model.SenderRole
import com.titan.ide.voice.TitanAudioSessionManager
import com.titan.ide.voice.TitanInstantTtsEngine
import com.titan.ide.voice.TitanLiveCallEngine
import com.titan.ide.voice.TitanLiveSessionManager
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * TitanWakeWordService — 100% Zero-Beep Pure Hardware AudioRecord Wake Word Service
 * ZERO system speech recognizer -> ZERO ting-ting beeps EVER!
 */
class TitanWakeWordService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var vadJob: Job? = null
    private var cpuWakeLock: PowerManager.WakeLock? = null

    @Volatile private var isWakeActive = false
    @Volatile private var cachedSensitivity = 0.55f
    private val isVerifying = AtomicBoolean(false)
    @Volatile private var lastWakeTriggeredAtMs = 0L

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == "wake_word_sensitivity") {
            cachedSensitivity = prefs.getFloat(key, 0.55f)
        } else if (key == "custom_wake_word" || key == "assistant_custom_name" || key == "assistant_app_language") {
            updateNotification()
        }
    }

    companion object {
        const val CHANNEL_ID = "TitanWakeWordChannel"
        const val NOTIF_ID = 2002
        const val ACTION_START = "ACTION_START_WAKE_WORD"
        const val ACTION_STOP = "ACTION_STOP_WAKE_WORD"
        const val TAG = "TitanWakeWordService"

        var isServiceRunning = false
            private set

        @Volatile
        var instance: TitanWakeWordService? = null
            private set

        fun restartWithNewName(context: Context) {
            instance?.updateNotification()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        val notif = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notif)
        }

        acquireCpuWakeLock()
        val prefs = getSharedPreferences("titan_prefs", Context.MODE_PRIVATE)
        cachedSensitivity = prefs.getFloat("wake_word_sensitivity", 0.55f)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        getSharedPreferences("titan_assistant_prefs", Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(prefListener)

        isServiceRunning = true
        isWakeActive = true
        if (!TitanLiveSessionManager.isSessionActive()) {
            startPureAudioRecordWakeLoop()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        isWakeActive = true
        acquireCpuWakeLock()
        updateNotification()
        if (!TitanLiveSessionManager.isSessionActive() && vadJob?.isActive != true) {
            startPureAudioRecordWakeLoop()
        }
        return START_STICKY
    }

    fun pauseListening() {
        vadJob?.cancel()
        vadJob = null
        releaseAudioRecord()
        TitanAudioSessionManager.releaseMicOwnership("TitanWakeWordService")
    }

    fun resumeListeningIfIdle() {
        if (!isServiceRunning || !isWakeActive) return
        if (TitanLiveSessionManager.isSessionActive() || TitanAudioSessionManager.isMicInUse()) return
        acquireCpuWakeLock()
        handler.postDelayed({
            if (isServiceRunning && isWakeActive && !TitanLiveSessionManager.isSessionActive() && !TitanAudioSessionManager.isMicInUse()) {
                startPureAudioRecordWakeLoop()
            }
        }, 450L)
    }

    @SuppressLint("MissingPermission")
    private fun startPureAudioRecordWakeLoop() {
        if (!isServiceRunning || !isWakeActive || TitanLiveSessionManager.isSessionActive()) return
        if (vadJob?.isActive == true) return
        if (!TitanAudioSessionManager.requestMicOwnership("TitanWakeWordService")) return

        vadJob = serviceScope.launch(Dispatchers.IO) {
            val sr = 16000
            val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(3200)
            val recorder = try {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2)
                    .takeIf { it.state == AudioRecord.STATE_INITIALIZED }
                    ?: AudioRecord(MediaRecorder.AudioSource.MIC, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2)
            } catch (_: Exception) { null }

            if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
                TitanAudioSessionManager.releaseMicOwnership("TitanWakeWordService")
                return@launch
            }

            audioRecord = recorder
            try {
                val sid = recorder.audioSessionId
                if (AcousticEchoCanceler.isAvailable()) echoCanceler = AcousticEchoCanceler.create(sid)?.apply { enabled = true }
                if (NoiseSuppressor.isAvailable()) noiseSuppressor = NoiseSuppressor.create(sid)?.apply { enabled = true }
                recorder.startRecording()
            } catch (_: Exception) {
                releaseAudioRecord()
                TitanAudioSessionManager.releaseMicOwnership("TitanWakeWordService")
                return@launch
            }

            val buffer = ByteArray(1280)
            val preRoll = ArrayDeque<ByteArray>(10)
            val voiceBuffer = ByteArrayOutputStream()
            var voiceFrames = 0
            var silenceFrames = 0
            var collecting = false
            var noiseFloor = 100.0

            while (isServiceRunning && isWakeActive && isActive && !TitanLiveSessionManager.isSessionActive() && TitanAudioSessionManager.currentOwner == "TitanWakeWordService") {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) continue

                var sum = 0.0
                val count = read / 2
                val bb = ByteBuffer.wrap(buffer, 0, read).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until count) {
                    val s = bb.short.toDouble()
                    sum += s * s
                }
                val rms = if (count > 0) sqrt(sum / count) else 0.0
                val copy = buffer.copyOf(read)

                val sensFactor = (1.55 - cachedSensitivity.coerceIn(0.1f, 1.0f) * 0.75)
                val threshold = (maxOf(160.0, noiseFloor * 2.1) * sensFactor).coerceIn(140.0, 900.0)

                if (rms > threshold) {
                    if (!collecting) {
                        collecting = true
                        voiceBuffer.reset()
                        for (pre in preRoll) voiceBuffer.write(pre)
                        preRoll.clear()
                        voiceFrames = 0
                        silenceFrames = 0
                    }
                    voiceBuffer.write(copy)
                    voiceFrames++
                    silenceFrames = 0
                } else if (collecting) {
                    voiceBuffer.write(copy)
                    silenceFrames++
                    if (silenceFrames >= 10 || voiceFrames >= 75) {
                        val pcm = voiceBuffer.toByteArray()
                        collecting = false
                        voiceBuffer.reset()
                        silenceFrames = 0
                        if (voiceFrames >= 5 && pcm.size >= 8000 && isVerifying.compareAndSet(false, true)) {
                            serviceScope.launch(Dispatchers.IO) {
                                try { verifyWakePcm(pcm) } finally { isVerifying.set(false) }
                            }
                        }
                        voiceFrames = 0
                    }
                } else {
                    noiseFloor = (noiseFloor * 0.93 + rms * 0.07).coerceIn(40.0, 300.0)
                    if (preRoll.size >= 10) preRoll.removeFirst()
                    preRoll.addLast(copy)
                }
            }
            releaseAudioRecord()
            TitanAudioSessionManager.releaseMicOwnership("TitanWakeWordService")
        }
    }

    private suspend fun verifyWakePcm(pcm: ByteArray) {
        val now = System.currentTimeMillis()
        if (now - lastWakeTriggeredAtMs < 3500L || TitanLiveSessionManager.isSessionActive()) return
        val wav = TitanLiveCallEngine.buildWavBytes(pcm, 16000)
        val recognized = TitanLiveCallEngine.transcribeAudioWav(this, wav).lowercase(Locale.ROOT).trim()
        if (recognized.isBlank()) return

        val wakeWords = TitanAssistantConfig.getWakeWordList(this)
        val matchedKw = wakeWords.firstOrNull { kw -> kw.isNotBlank() && recognized.contains(kw) } ?: return

        lastWakeTriggeredAtMs = System.currentTimeMillis()
        pauseListening()
        wakeScreenBriefly()
        handler.post { playWakeChime() }

        val trailing = recognized.substringAfter(matchedKw).trim(' ', ',', '.', '?', '!')
        if (trailing.length >= 4) {
            try {
                val botName = TitanAssistantConfig.getAssistantName(this)
                val selectedLang = TitanAssistantConfig.getSelectedLanguage(this)
                val langRule = if (selectedLang.promptDirective.isNotBlank()) selectedLang.promptDirective else "Reply in the user's exact language."
                val prompt = "SYSTEM: Your name is $botName. Reply concisely in 1-2 spoken sentences (no markdown). $langRule If the user asks to perform a phone task, append [TITAN_GOAL: <task>].\nUser said: $trailing"
                val reply = com.titan.ide.network.TitanDirectEngine.executeChat(
                    context = this,
                    modelId = "gemini-3.5-flash-lite",
                    messages = listOf(ChatMessage(sender = SenderRole.USER, content = prompt))
                ).trim()
                val spoken = reply
                    .replace(Regex("""\[TITAN_(?:GOAL|ACTION|BUILD):.*?\]""", RegexOption.DOT_MATCHES_ALL), "")
                    .replace(Regex("\\s*⚠️\\s*Network.*$", RegexOption.DOT_MATCHES_ALL), "")
                    .trim()
                    .ifBlank { TitanAssistantConfig.getAwakenGreeting(this) }
                handler.post {
                    TitanInstantTtsEngine.speakFullResponse(this, spoken) { resumeListeningIfIdle() }
                }
            } catch (_: Exception) {
                resumeListeningIfIdle()
            }
        } else {
            val launchIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("EXTRA_START_LIVE_VOICE", true)
            }
            try { startActivity(launchIntent) } catch (_: Exception) { resumeListeningIfIdle() }
        }
    }

    private fun releaseAudioRecord() {
        try { echoCanceler?.release() } catch (_: Exception) {}
        echoCanceler = null
        try { noiseSuppressor?.release() } catch (_: Exception) {}
        noiseSuppressor = null
        try { audioRecord?.stop(); audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
    }

    private fun playWakeChime() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 65)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            handler.postDelayed({ try { tg.release() } catch (_: Exception) {} }, 260L)
        } catch (_: Exception) {}
    }

    private fun acquireCpuWakeLock() {
        try {
            if (cpuWakeLock?.isHeld == true) return
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            cpuWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TitanIDE::WakeWordLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {}
    }

    private fun releaseCpuWakeLock() {
        try { if (cpuWakeLock?.isHeld == true) cpuWakeLock?.release() } catch (_: Exception) {}
        cpuWakeLock = null
    }

    @Suppress("DEPRECATION")
    private fun wakeScreenBriefly() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            if (!pm.isInteractive) {
                pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "TitanIDE::WakeScreenPulse")
                    .acquire(4500L)
            }
        } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Titan Always-On Wake Word", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply { putExtra("EXTRA_START_LIVE_VOICE", true) }
        val pendingIntent = PendingIntent.getActivity(this, 0, launchIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val assistantName = TitanAssistantConfig.getAssistantName(this)
        val langLabel = TitanAssistantConfig.getSelectedLanguage(this).displayName
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("$assistantName Wake Word Active ($langLabel)")
            .setContentText("Say 'Hey $assistantName' or '$assistantName' anytime")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        try { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, createNotification()) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        isWakeActive = false
        isServiceRunning = false
        if (instance == this) instance = null
        try {
            getSharedPreferences("titan_prefs", Context.MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(prefListener)
            getSharedPreferences("titan_assistant_prefs", Context.MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(prefListener)
        } catch (_: Exception) {}
        vadJob?.cancel()
        releaseAudioRecord()
        TitanAudioSessionManager.releaseMicOwnership("TitanWakeWordService")
        releaseCpuWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }
}
