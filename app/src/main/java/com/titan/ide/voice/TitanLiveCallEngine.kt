package com.titan.ide.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.titan.ide.network.TitanDirectEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * CircularAudioRingBuffer — 1.5-Second In-Memory Audio Ring Buffer
 * Holds trailing 1.5s (48,000 bytes) at 16kHz Mono 16-bit PCM so opening syllables are NEVER clipped.
 */
class CircularAudioRingBuffer(val capacity: Int) {
    private val buffer = ByteArray(capacity)
    private var writePos = 0
    private var isFull = false
    private val lock = Any()

    fun write(data: ByteArray, offset: Int, length: Int) {
        synchronized(lock) {
            for (i in 0 until length) {
                buffer[writePos] = data[offset + i]
                writePos = (writePos + 1) % capacity
                if (writePos == 0) isFull = true
            }
        }
    }

    fun getSnapshot(): ByteArray {
        synchronized(lock) {
            val size = if (isFull) capacity else writePos
            val out = ByteArray(size)
            if (!isFull) {
                System.arraycopy(buffer, 0, out, 0, writePos)
            } else {
                val tail = capacity - writePos
                System.arraycopy(buffer, writePos, out, 0, tail)
                System.arraycopy(buffer, 0, out, tail, writePos)
            }
            return out
        }
    }

    fun clear() {
        synchronized(lock) {
            writePos = 0
            isFull = false
        }
    }
}

/**
 * TitanLiveCallEngine — 100% Zero-Beep Claude-Style Continuous Hardware Phone-Call Engine
 *
 * ZERO system speech recognizer -> ZERO ting-ting beeps EVER, ZERO OS timeouts!
 * Opened ONCE at call start (16kHz Mono 16-bit PCM AudioRecord + 1.5s Ring Buffer +
 * Adaptive Noise-Floor VAD + Echo-Safe Real-Time Barge-In) and stays continuously open
 * until End Call!
 */
object TitanLiveCallEngine {

    private const val TAG = "TitanLiveCallEngine"
    private const val SAMPLE_RATE = 16000
    private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private const val FRAME_MS = 20L
    private const val SAMPLES_PER_FRAME = (SAMPLE_RATE * FRAME_MS / 1000L).toInt()
    private const val BYTES_PER_FRAME = SAMPLES_PER_FRAME * 2
    private const val RING_BUFFER_CAPACITY_BYTES = 48000

    private const val SILENCE_ENDPOINT_MS = 480L
    private const val MIN_SPEECH_DURATION_MS = 240L
    private const val MAX_UTTERANCE_MS = 10_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    enum class LiveCallState {
        IDLE,
        LISTENING,
        THINKING,
        SPEAKING
    }

    enum class LanguageMode(val label: String, val languageTag: String) {
        AUTO("Auto (Global)", "bn-IN"),
        BENGALI("Bengali", "bn-IN"),
        ENGLISH("English", "en-IN")
    }

    private var appContext: Context? = null
    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var recordingJob: Job? = null

    private val ringBuffer = CircularAudioRingBuffer(RING_BUFFER_CAPACITY_BYTES)

    private val _callState = MutableStateFlow(LiveCallState.IDLE)
    val callState: StateFlow<LiveCallState> = _callState.asStateFlow()

    private val _activeLanguage = MutableStateFlow(LanguageMode.AUTO)
    val activeLanguage: StateFlow<LanguageMode> = _activeLanguage.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isSpeakerOn = MutableStateFlow(true)
    val isSpeakerOn: StateFlow<Boolean> = _isSpeakerOn.asStateFlow()

    private val _liveSpokenText = MutableStateFlow("")
    val liveSpokenText: StateFlow<String> = _liveSpokenText.asStateFlow()

    private val _liveResponseText = MutableStateFlow("")
    val liveResponseText: StateFlow<String> = _liveResponseText.asStateFlow()

    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    private var onFinalSpeechCallback: ((String) -> Unit)? = null

    @Volatile private var isCallActive = false
    @Volatile private var speechStartedAtMs = 0L
    @Volatile private var echoGuardUntilMs = 0L

    fun ensureStreamsUnmuted(context: Context) {
        // No-op: pure hardware AudioRecord never mutes any stream!
    }

    fun setCallState(newState: LiveCallState) {
        if (!isCallActive && newState != LiveCallState.IDLE) return
        val prev = _callState.value
        _callState.value = newState
        val now = System.currentTimeMillis()
        if (newState == LiveCallState.SPEAKING && prev != LiveCallState.SPEAKING) {
            speechStartedAtMs = now
        } else if (newState == LiveCallState.LISTENING && prev == LiveCallState.SPEAKING) {
            echoGuardUntilMs = now + 420L
        }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        TitanInstantTtsEngine.preWarm(context)
    }

    fun setLanguageMode(mode: LanguageMode) {
        _activeLanguage.value = mode
    }

    private var audioFocusRequest: Any? = null
    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { }

    private fun requestCallAudioFocus(context: Context) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener(focusChangeListener, mainHandler)
                    .build()
                audioFocusRequest = req
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(focusChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
            }
        } catch (_: Exception) {}
    }

    private fun abandonCallAudioFocus(context: Context) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                (audioFocusRequest as? AudioFocusRequest)?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(focusChangeListener)
            }
            audioFocusRequest = null
        } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    fun startLiveCall(context: Context, onUtteranceReady: (String) -> Unit) {
        val appCtx = context.applicationContext
        appContext = appCtx
        onFinalSpeechCallback = onUtteranceReady
        isCallActive = true
        _isMuted.value = false
        _liveSpokenText.value = ""
        _liveResponseText.value = ""
        _callState.value = LiveCallState.LISTENING

        TitanAudioSessionManager.requestMicOwnership("TitanLiveCallEngine")
        TitanCallForegroundService.startCall(appCtx)
        requestCallAudioFocus(appCtx)
        setSpeakerphone(appCtx, true)
        TitanInstantTtsEngine.preWarm(appCtx)

        engineScope.launch {
            try { TitanDirectEngine.speculativePreWarm(appCtx, "gemini-3.5-flash-lite") } catch (_: Exception) {}
        }

        startContinuousAudioRecordStream(appCtx)
    }

    @SuppressLint("MissingPermission")
    private fun startContinuousAudioRecordStream(context: Context) {
        if (recordingJob?.isActive == true && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            return
        }

        recordingJob?.cancel()
        ringBuffer.clear()

        recordingJob = engineScope.launch(Dispatchers.IO) {
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
                .coerceAtLeast(6400)

            val recorder = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    minBuf * 2
                ).takeIf { it.state == AudioRecord.STATE_INITIALIZED }
                    ?: AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        SAMPLE_RATE,
                        CHANNEL_CONFIG,
                        AUDIO_FORMAT,
                        minBuf * 2
                    )
            } catch (e: Exception) {
                Log.e(TAG, "AudioRecord initialization error: ${e.message}", e)
                null
            }

            if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "Hardware AudioRecord initialization failed!")
                _callState.value = LiveCallState.IDLE
                return@launch
            }

            audioRecord = recorder
            try {
                val sid = recorder.audioSessionId
                if (AcousticEchoCanceler.isAvailable()) {
                    echoCanceler = AcousticEchoCanceler.create(sid)?.apply { enabled = true }
                }
                if (NoiseSuppressor.isAvailable()) {
                    noiseSuppressor = NoiseSuppressor.create(sid)?.apply { enabled = true }
                }
                recorder.startRecording()
                Log.i(TAG, "100% Zero-Beep Claude-Style Continuous AudioRecord Started!")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start AudioRecord: ${e.message}")
                releaseAudioRecord()
                return@launch
            }

            val frameBytes = ByteArray(BYTES_PER_FRAME)
            val utteranceStream = ByteArrayOutputStream()

            var isSpeakingUtterance = false
            var speechDurationMs = 0L
            var silenceDurationMs = 0L
            var bargeInFrames = 0
            var dynamicNoiseFloor = 120.0

            while (isCallActive && isActive) {
                val read = recorder.read(frameBytes, 0, frameBytes.size)
                if (read <= 0) continue

                ringBuffer.write(frameBytes, 0, read)

                var sum = 0.0
                val shortsCount = read / 2
                val bb = ByteBuffer.wrap(frameBytes, 0, read).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until shortsCount) {
                    val s = bb.short.toDouble()
                    sum += s * s
                }
                val rms = if (shortsCount > 0) sqrt(sum / shortsCount) else 0.0
                val normalizedLevel = (rms / 2800.0).toFloat().coerceIn(0.04f, 1f)

                if (_callState.value == LiveCallState.LISTENING && !_isMuted.value) {
                    _audioLevel.value = normalizedLevel
                }

                if (_isMuted.value) continue

                val nowMs = System.currentTimeMillis()
                if (_callState.value == LiveCallState.THINKING || nowMs < echoGuardUntilMs) {
                    bargeInFrames = 0
                    isSpeakingUtterance = false
                    speechDurationMs = 0L
                    silenceDurationMs = 0L
                    continue
                }

                val isAiCurrentlySpeaking = _callState.value == LiveCallState.SPEAKING || TitanInstantTtsEngine.isSpeaking
                if (isAiCurrentlySpeaking) {
                    val pastGrace = (nowMs - speechStartedAtMs) > 450L
                    if (pastGrace && rms > 2400.0) {
                        bargeInFrames++
                        if (bargeInFrames >= 12) {
                            Log.i(TAG, "Barge-in detected ($rms RMS) -> Interrupting AI speech!")
                            mainHandler.post {
                                TitanInstantTtsEngine.stopSpeech()
                                setCallState(LiveCallState.LISTENING)
                            }
                            bargeInFrames = 0
                            isSpeakingUtterance = true
                            utteranceStream.reset()
                            utteranceStream.write(ringBuffer.getSnapshot())
                            speechDurationMs = 200L
                            silenceDurationMs = 0L
                        }
                    } else {
                        bargeInFrames = 0
                    }
                    continue
                }

                if (!isSpeakingUtterance) {
                    dynamicNoiseFloor = (dynamicNoiseFloor * 0.94 + rms * 0.06).coerceIn(45.0, 650.0)
                }

                val voiceThreshold = maxOf(dynamicNoiseFloor * 2.2, 220.0)
                val isVoiceFrame = rms >= voiceThreshold

                if (isVoiceFrame) {
                    if (!isSpeakingUtterance) {
                        isSpeakingUtterance = true
                        utteranceStream.reset()
                        utteranceStream.write(ringBuffer.getSnapshot())
                        speechDurationMs = 0L
                        silenceDurationMs = 0L
                    }
                    utteranceStream.write(frameBytes, 0, read)
                    speechDurationMs += FRAME_MS
                    silenceDurationMs = 0L

                    if (speechDurationMs == 400L) {
                        engineScope.launch {
                            try { TitanDirectEngine.speculativePreWarm(context, "gemini-3.5-flash-lite") } catch (_: Exception) {}
                        }
                    }
                } else if (isSpeakingUtterance) {
                    utteranceStream.write(frameBytes, 0, read)
                    silenceDurationMs += FRAME_MS
                }

                val reachedSilence = isSpeakingUtterance && silenceDurationMs >= SILENCE_ENDPOINT_MS && speechDurationMs >= MIN_SPEECH_DURATION_MS
                val reachedMaxCap = isSpeakingUtterance && speechDurationMs >= MAX_UTTERANCE_MS

                if (reachedSilence || reachedMaxCap) {
                    val capturedPcm = utteranceStream.toByteArray()
                    utteranceStream.reset()
                    isSpeakingUtterance = false
                    speechDurationMs = 0L
                    silenceDurationMs = 0L

                    Log.i(TAG, "Zero-Beep Continuous VAD Endpoint! Captured ${capturedPcm.size} bytes PCM.")
                    engineScope.launch {
                        processCapturedUtterance(context, capturedPcm)
                    }
                }
            }

            releaseAudioRecord()
        }
    }

    private suspend fun processCapturedUtterance(context: Context, pcmBytes: ByteArray) {
        if (pcmBytes.size < 4800) return

        val wavBytes = buildWavBytes(pcmBytes, SAMPLE_RATE)
        _callState.value = LiveCallState.THINKING

        val transcribedText = transcribeAudioWav(context, wavBytes)
        val cleanSpeech = transcribedText.trim()

        if (cleanSpeech.isNotBlank() && isCallActive) {
            _liveSpokenText.value = cleanSpeech
            Log.i(TAG, "Zero-Beep Transcribed: '$cleanSpeech' -> Dispatching to AI")
            onFinalSpeechCallback?.invoke(cleanSpeech)
        } else {
            if (isCallActive && !_isMuted.value) {
                if (!com.titan.ide.network.TitanPsidSettingsManager.hasGeminiPsid(context) &&
                    TitanDirectEngine.getApiKey(context, "gemini").isBlank()
                ) {
                    _liveSpokenText.value = "Sign in to Gemini Session (Settings) once for Zero-Beep Voice"
                }
                setCallState(LiveCallState.LISTENING)
            }
        }
    }

    suspend fun transcribeAudioWav(context: Context, wavBytes: ByteArray): String = withContext(Dispatchers.IO) {
        try {
            val psidText = TitanDirectEngine.uploadWavAndTranscribeViaPsid(context, wavBytes)
            if (psidText.isNotBlank() &&
                !psidText.contains("I cannot", ignoreCase = true) &&
                !psidText.contains("unable to", ignoreCase = true)
            ) {
                return@withContext psidText
            }
        } catch (e: Exception) {
            Log.w(TAG, "Zero-beep audio transcription notice: ${e.message}")
        }
        ""
    }

    fun speakAndAutoResume(context: Context, responseText: String) {
        if (!isCallActive) return
        _liveResponseText.value = responseText
        setCallState(LiveCallState.SPEAKING)

        TitanInstantTtsEngine.speakFullResponse(
            context = context,
            rawText = responseText,
            onStart = { setCallState(LiveCallState.SPEAKING) },
            onDone = {
                if (isCallActive && !_isMuted.value) {
                    setCallState(LiveCallState.LISTENING)
                }
            }
        )
    }

    fun stopSpeakingAndListen() {
        TitanInstantTtsEngine.stopSpeech()
        if (isCallActive && !_isMuted.value) {
            echoGuardUntilMs = 0L
            setCallState(LiveCallState.LISTENING)
        }
    }

    fun toggleMute() {
        val newMute = !_isMuted.value
        _isMuted.value = newMute
        if (newMute) _audioLevel.value = 0f
    }

    fun toggleSpeakerphone(context: Context) {
        setSpeakerphone(context, !_isSpeakerOn.value)
    }

    private fun setSpeakerphone(context: Context, enabled: Boolean) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (enabled) {
                am.mode = AudioManager.MODE_NORMAL
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try { am.clearCommunicationDevice() } catch (_: Exception) {}
                } else {
                    @Suppress("DEPRECATION")
                    am.isSpeakerphoneOn = true
                }
            } else {
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                        ?.let { am.setCommunicationDevice(it) }
                } else {
                    @Suppress("DEPRECATION")
                    am.isSpeakerphoneOn = false
                }
            }
            _isSpeakerOn.value = enabled
        } catch (_: Exception) {}
    }

    fun endLiveCall() {
        if (!isCallActive) return
        isCallActive = false
        _callState.value = LiveCallState.IDLE
        _audioLevel.value = 0f
        TitanInstantTtsEngine.stopSpeech()

        recordingJob?.cancel()
        recordingJob = null
        releaseAudioRecord()

        appContext?.let { ctx ->
            abandonCallAudioFocus(ctx)
            try {
                (ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.mode = AudioManager.MODE_NORMAL
            } catch (_: Exception) {}
            TitanCallForegroundService.endCall(ctx)
        }
        TitanAudioSessionManager.releaseMicOwnership("TitanLiveCallEngine")
        Log.i(TAG, "Live Phone Call Ended -> Hardware AudioRecord released")
    }

    private fun releaseAudioRecord() {
        try { echoCanceler?.release() } catch (_: Exception) {}
        echoCanceler = null
        try { noiseSuppressor?.release() } catch (_: Exception) {}
        noiseSuppressor = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
    }

    fun buildWavBytes(pcmData: ByteArray, sampleRate: Int): ByteArray {
        val totalAudioLen = pcmData.size.toLong()
        val totalDataLen = totalAudioLen + 36
        val byteRate = sampleRate * 2
        val header = ByteArray(44)
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xffL).toByte(); header[5] = (totalDataLen shr 8 and 0xffL).toByte()
        header[6] = (totalDataLen shr 16 and 0xffL).toByte(); header[7] = (totalDataLen shr 24 and 0xffL).toByte()
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        header[16] = 16; header[20] = 1; header[22] = 1
        header[24] = (sampleRate and 0xff).toByte(); header[25] = (sampleRate shr 8 and 0xff).toByte()
        header[26] = (sampleRate shr 16 and 0xff).toByte(); header[27] = (sampleRate shr 24 and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte(); header[29] = (byteRate shr 8 and 0xff).toByte()
        header[30] = (byteRate shr 16 and 0xff).toByte(); header[31] = (byteRate shr 24 and 0xff).toByte()
        header[32] = 2; header[34] = 16
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xffL).toByte(); header[41] = (totalAudioLen shr 8 and 0xffL).toByte()
        header[42] = (totalAudioLen shr 16 and 0xffL).toByte(); header[43] = (totalAudioLen shr 24 and 0xffL).toByte()
        return header + pcmData
    }
}
