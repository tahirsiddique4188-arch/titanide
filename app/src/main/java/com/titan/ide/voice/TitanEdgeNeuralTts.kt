package com.titan.ide.voice

import android.content.Context
import android.util.Log
import com.example.data.model.VoicePersonas
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * TitanEdgeNeuralTts — Native Microsoft Edge Neural TTS WebSocket Engine for Android
 *
 * Streams genuine Microsoft Neural Voices (bn-IN-TanishaaNeural, bn-IN-BashkarNeural,
 * bn-BD-NabanitaNeural, bn-BD-PradeepNeural, hi-IN-SwaraNeural, hi-IN-MadhurNeural,
 * en-IN-NeerjaNeural, en-IN-PrabhatNeural, MultilingualNeural, etc.) with per-persona
 * SSML pitch & rate so every character in Live Call, Chat, Video & Podcast sounds distinct.
 */
object TitanEdgeNeuralTts {

    private const val TAG = "TitanEdgeNeuralTts"
    private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val CHROMIUM_FULL_VERSION = "140.0.3485.14"
    private const val SEC_MS_GEC_VERSION = "1-$CHROMIUM_FULL_VERSION"
    private const val EDGE_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$CHROMIUM_FULL_VERSION Safari/537.36 Edg/$CHROMIUM_FULL_VERSION"

    private val ENDPOINTS = listOf(
        "wss://api.msedgeservices.com/tts/cognitiveservices/websocket/v1",
        "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"
    )

    private val wsClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Generates the time-windowed SHA-256 Sec-MS-GEC DRM token required by Microsoft Edge TTS.
     */
    private fun generateSecMsGec(): String {
        val unixSec = System.currentTimeMillis() / 1000L
        var ticks = unixSec + 11644473600L // Windows FileTime epoch offset (1601 -> 1970)
        ticks -= (ticks % 300L)            // Round down to 5-minute window
        ticks *= 10_000_000L               // 100-nanosecond intervals
        val raw = "${ticks}$TRUSTED_CLIENT_TOKEN"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.US_ASCII))
        return digest.joinToString("") { "%02X".format(it) }
    }

    private fun escapeXml(input: String): String {
        return input
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun rfcTimestamp(): String {
        val sdf = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }

    /**
     * Resolves the exact Microsoft Neural voice name & locale for the selected VoicePersona
     * and the detected script of the text (Bengali, Hindi, or English).
     */
    fun resolveEdgeVoice(voiceId: String, text: String, langHint: String = ""): Triple<String, String, Pair<String, String>> {
        val persona = VoicePersonas.getById(voiceId)
        val hasBn = text.any { it in '\u0980'..'\u09FF' }
        val hasHi = text.any { it in '\u0900'..'\u097F' }

        val voiceName = when {
            hasBn || langHint.startsWith("bn", ignoreCase = true) -> {
                // Ensure authentic Bengali pronunciation for all personas
                if (persona.edgeVoiceBn.startsWith("bn-") || persona.edgeVoiceBn.contains("Multilingual")) {
                    persona.edgeVoiceBn
                } else if (persona.gender.equals("Male", ignoreCase = true)) {
                    "bn-IN-BashkarNeural"
                } else {
                    "bn-IN-TanishaaNeural"
                }
            }
            hasHi || langHint.startsWith("hi", ignoreCase = true) -> persona.edgeVoiceHi
            else -> persona.edgeVoiceEn
        }

        val parts = voiceName.split("-")
        val xmlLang = if (parts.size >= 2) "${parts[0]}-${parts[1]}" else "en-US"
        val rate = persona.prosodyRate.ifBlank { "+0%" }
        val pitch = persona.prosodyPitch.ifBlank { "+0Hz" }
        return Triple(voiceName, xmlLang, rate to pitch)
    }

    /**
     * Synthesizes crisp MP3 bytes for the given text and VoicePersona ID.
     * Automatically chunks long text safely so unlimited-length narration works smoothly.
     */
    suspend fun synthesizeMp3(
        context: Context,
        rawText: String,
        voiceId: String = "zara",
        langHint: String = ""
    ): ByteArray? = withContext(Dispatchers.IO) {
        val clean = rawText
            .replace(Regex("```[\\s\\S]*?```"), " ")
            .replace(Regex("https?://\\S+"), " ")
            .replace(Regex("[#*`_~\\[\\]]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (clean.isBlank()) return@withContext null

        val (voiceName, xmlLang, prosody) = resolveEdgeVoice(voiceId, clean, langHint)
        val (rate, pitch) = prosody

        // Split into <= 420 char chunks so every WebSocket turn completes rapidly
        val chunks = splitIntoChunks(clean, 420)
        val combined = ByteArrayOutputStream()

        for (chunk in chunks) {
            val mp3Chunk = synthesizeSingleChunk(chunk, voiceName, xmlLang, rate, pitch)
            if (mp3Chunk != null && mp3Chunk.size > 200) {
                combined.write(mp3Chunk)
            } else {
                // If Edge WebSocket fails on first chunk, return null so caller falls back cleanly
                if (combined.size() == 0) return@withContext null
            }
        }
        val result = combined.toByteArray()
        if (result.size > 200) result else null
    }

    private fun splitIntoChunks(text: String, maxLen: Int): List<String> {
        if (text.length <= maxLen) return listOf(text)
        val out = mutableListOf<String>()
        val sentences = text.split(Regex("(?<=[।.!?])\\s+"))
        val cur = StringBuilder()
        for (s in sentences) {
            if (cur.length + s.length + 1 > maxLen && cur.isNotEmpty()) {
                out.add(cur.toString().trim())
                cur.clear()
            }
            if (s.length > maxLen) {
                val words = s.split(Regex("\\s+"))
                for (w in words) {
                    if (cur.length + w.length + 1 > maxLen && cur.isNotEmpty()) {
                        out.add(cur.toString().trim())
                        cur.clear()
                    }
                    if (cur.isNotEmpty()) cur.append(' ')
                    cur.append(w)
                }
            } else {
                if (cur.isNotEmpty()) cur.append(' ')
                cur.append(s)
            }
        }
        if (cur.isNotEmpty()) out.add(cur.toString().trim())
        return out
    }

    private suspend fun synthesizeSingleChunk(
        text: String,
        voiceName: String,
        xmlLang: String,
        rate: String,
        pitch: String
    ): ByteArray? {
        for (baseEndpoint in ENDPOINTS) {
            val bytes = withTimeoutOrNull(14_000L) {
                runWebSocketSynthesis(baseEndpoint, text, voiceName, xmlLang, rate, pitch)
            }
            if (bytes != null && bytes.size > 200) return bytes
        }
        return null
    }

    private suspend fun runWebSocketSynthesis(
        baseEndpoint: String,
        text: String,
        voiceName: String,
        xmlLang: String,
        rate: String,
        pitch: String
    ): ByteArray? = suspendCancellableCoroutine { cont ->
        val connId = UUID.randomUUID().toString().replace("-", "")
        val reqId = UUID.randomUUID().toString().replace("-", "")
        val muid = UUID.randomUUID().toString().replace("-", "").uppercase(Locale.US)
        val gec = generateSecMsGec()

        val wsUrl = "$baseEndpoint?TrustedClientToken=$TRUSTED_CLIENT_TOKEN&Sec-MS-GEC=$gec&Sec-MS-GEC-Version=$SEC_MS_GEC_VERSION&ConnectionId=$connId"

        val request = Request.Builder()
            .url(wsUrl)
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header("User-Agent", EDGE_UA)
            .header("Accept-Encoding", "gzip, deflate, br")
            .header("Accept-Language", "en-US,en;q=0.9,bn;q=0.8,hi;q=0.8")
            .header("Cookie", "muid=$muid;")
            .build()

        val audioOut = ByteArrayOutputStream()
        var finished = false

        fun finishOnce(ws: WebSocket?, data: ByteArray?) {
            if (!finished) {
                finished = true
                try { ws?.close(1000, "done") } catch (_: Exception) {}
                if (cont.isActive) cont.resume(data)
            }
        }

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val ts = rfcTimestamp()
                val configMsg =
                    "X-Timestamp:$ts\r\n" +
                    "Content-Type:application/json; charset=utf-8\r\n" +
                    "Path:speech.config\r\n\r\n" +
                    """{"context":{"synthesis":{"audio":{"metadataoptions":{"sentenceBoundaryEnabled":"false","wordBoundaryEnabled":"false"},"outputFormat":"audio-24khz-48kbitrate-mono-mp3"}}}}"""
                webSocket.send(configMsg)

                val escaped = escapeXml(text)
                val ssml =
                    "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='$xmlLang'>" +
                    "<voice name='$voiceName'>" +
                    "<prosody pitch='$pitch' rate='$rate' volume='+0%'>$escaped</prosody>" +
                    "</voice></speak>"

                val ssmlMsg =
                    "X-RequestId:$reqId\r\n" +
                    "Content-Type:application/ssml+xml\r\n" +
                    "X-Timestamp:$ts\r\n" +
                    "Path:ssml\r\n\r\n" +
                    ssml
                webSocket.send(ssmlMsg)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val arr = bytes.toByteArray()
                if (arr.size > 2) {
                    val headerLen = ((arr[0].toInt() and 0xFF) shl 8) or (arr[1].toInt() and 0xFF)
                    val audioStart = 2 + headerLen
                    if (audioStart < arr.size) {
                        val headerText = String(arr, 2, headerLen, Charsets.UTF_8)
                        if (headerText.contains("Path:audio")) {
                            audioOut.write(arr, audioStart, arr.size - audioStart)
                        }
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, textMsg: String) {
                if (textMsg.contains("Path:turn.end")) {
                    val data = audioOut.toByteArray()
                    finishOnce(webSocket, if (data.size > 200) data else null)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "Edge TTS WS failure (${response?.code}): ${t.message}")
                val data = audioOut.toByteArray()
                finishOnce(webSocket, if (data.size > 200) data else null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                val data = audioOut.toByteArray()
                finishOnce(webSocket, if (data.size > 200) data else null)
            }
        }

        val ws = wsClient.newWebSocket(request, listener)
        cont.invokeOnCancellation {
            try { ws.cancel() } catch (_: Exception) {}
        }
    }
}
