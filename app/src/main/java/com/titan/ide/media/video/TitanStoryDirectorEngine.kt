package com.titan.ide.media.video

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.media.*
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import com.titan.ide.network.TitanDirectEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * TitanStoryDirectorEngine — AI-Directed Storytelling & Animated Diagram Explainer Video Engine
 *
 * Capabilities:
 * 1. Understands any pasted ChatGPT / Gemini story, script, or prompt and breaks it into
 *    structured visual scenes (AI-directed with instant offline analyzer fallback).
 * 2. Face-Aware Expressive Robot Mascot: 6 moods (EXPLAINING, HAPPY, THINKING, SURPRISED,
 *    SERIOUS, CELEBRATING) with dynamic eyes, brows, lip-synced mouth, arms, and mood FX.
 * 3. Animated Vector Diagrams per scene: FLOWCHART, BAR_CHART, COMPARISON, MAP_ROUTE,
 *    STAT_COUNTER, and STORY_CARD rendered at 30 FPS in 9:16 Reels or 16:9 YouTube.
 * 4. 100% Locked Per-Scene Audio-Video Sync (PCM per scene + deterministic H.264 PTS).
 */
object TitanStoryDirectorEngine {

    private const val TAG = "TitanStoryDirector"
    private const val FPS = 30
    private const val BIT_RATE = 4_800_000
    private const val TARGET_SR = 24000
    private const val TARGET_CH = 1

    enum class CharacterMood {
        EXPLAINING, HAPPY, THINKING, SURPRISED, SERIOUS, CELEBRATING
    }

    enum class DiagramType {
        STORY_CARD, FLOWCHART, BAR_CHART, COMPARISON, MAP_ROUTE, STAT_COUNTER
    }

    enum class VideoAspect(val label: String, val width: Int, val height: Int) {
        PORTRAIT_9_16("9:16 Reels", 720, 1280),
        LANDSCAPE_16_9("16:9 YouTube", 1280, 720)
    }

    enum class VideoTheme(
        val label: String,
        val bgTop: Int,
        val bgMid: Int,
        val bgBot: Int,
        val cardBg: Int,
        val primaryText: Int,
        val mutedText: Int,
        val isLight: Boolean = false
    ) {
        OBSIDIAN(
            "Obsidian",
            0xFF0A0B12.toInt(), 0xFF131829.toInt(), 0xFF07090F.toInt(),
            0xDD151B2E.toInt(), 0xFFF8FAFC.toInt(), 0xFF94A3B8.toInt()
        ),
        NEON_CYBER(
            "Neon Cyber",
            0xFF090418.toInt(), 0xFF1A0B2E.toInt(), 0xFF060814.toInt(),
            0xDD1E1138.toInt(), 0xFFF8FAFC.toInt(), 0xFFC4B5FD.toInt()
        ),
        WARM_STORY(
            "Warm Story",
            0xFF16110E.toInt(), 0xFF231A15.toInt(), 0xFF0F0B09.toInt(),
            0xDD281E18.toInt(), 0xFFFFFBEB.toInt(), 0xFFD6C3B3.toInt()
        ),
        CLEAN_LIGHT(
            "Clean Light",
            0xFFF8FAFC.toInt(), 0xFFEEF2FF.toInt(), 0xFFE2E8F0.toInt(),
            0xEEFFFFFF.toInt(), 0xFF0F172A.toInt(), 0xFF475569.toInt(),
            isLight = true
        )
    }

    data class StoryScene(
        val index: Int,
        val badge: String,
        val title: String,
        val narration: String,
        val words: List<String>,
        val mood: CharacterMood,
        val diagramType: DiagramType,
        val diagramItems: List<String>,
        val statValue: String,
        val accentColor: Int,
        val durationMs: Long,
        val pcmAudio: ByteArray = ByteArray(0)
    )

    data class StoryVideoResult(
        val videoFile: File,
        val scenes: List<StoryScene>,
        val totalDurationMs: Long,
        val aspect: VideoAspect,
        val theme: VideoTheme
    )

    private val ACCENT_PALETTE = intArrayOf(
        0xFFD97757.toInt(), // Warm Terracotta
        0xFF38BDF8.toInt(), // Electric Sky
        0xFFA855F7.toInt(), // Neon Violet
        0xFF10B981.toInt(), // Emerald Mint
        0xFFF59E0B.toInt(), // Amber Gold
        0xFFEC4899.toInt()  // Rose Pink
    )

    suspend fun generateStoryExplainerVideo(
        context: Context,
        rawInput: String,
        aspect: VideoAspect = VideoAspect.PORTRAIT_9_16,
        theme: VideoTheme = VideoTheme.OBSIDIAN,
        voiceId: String = "zara",
        onProgress: (String) -> Unit = {}
    ): StoryVideoResult = withContext(Dispatchers.IO) {
        onProgress("AI story & mood analysis running...")
        val plannedScenes = planScenes(context, rawInput, onProgress)

        // 1. Synthesize & decode PCM per scene for 100% locked audio-video sync
        val syncedScenes = mutableListOf<StoryScene>()
        val masterPcm = ByteArrayOutputStream()
        var detectedSr = TARGET_SR
        var detectedCh = TARGET_CH
        var anyAudio = false

        for ((idx, scene) in plannedScenes.withIndex()) {
            onProgress("Generating voice ${idx + 1}/${plannedScenes.size}...")
            val lang = when {
                scene.narration.any { it in '\u0980'..'\u09FF' } -> "bn"
                scene.narration.any { it in '\u0900'..'\u097F' } -> "hi"
                else -> "en"
            }
            var scenePcm = ByteArray(0)
            try {
                val mp3Bytes = TitanDirectEngine.synthesizeVoiceDirect(context, scene.narration, voiceId, lang)
                if (mp3Bytes != null && mp3Bytes.size > 400) {
                    val tmpMp3 = File(context.cacheDir, "story_s_${idx}_${System.currentTimeMillis()}.mp3")
                    tmpMp3.writeBytes(mp3Bytes)
                    val decoded = decodeAudioToPcm(tmpMp3)
                    try { tmpMp3.delete() } catch (_: Exception) {}
                    if (decoded != null && decoded.first.isNotEmpty()) {
                        if (!anyAudio) {
                            detectedSr = decoded.second
                            detectedCh = decoded.third
                            anyAudio = true
                        }
                        scenePcm = if (decoded.second == detectedSr && decoded.third == detectedCh) {
                            decoded.first
                        } else {
                            resamplePcm(decoded.first, decoded.second, decoded.third, detectedSr, detectedCh)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Scene $idx TTS fallback: ${e.message}")
            }

            val bytesPerMs = (detectedSr * 2 * detectedCh) / 1000.0
            val pauseMs = 300L
            val pauseBytes = ByteArray((pauseMs * bytesPerMs).toInt() and 0xFFFFFFFE.toInt())

            val exactDurationMs = if (scenePcm.isNotEmpty() && bytesPerMs > 0) {
                val speechMs = (scenePcm.size / bytesPerMs).toLong().coerceAtLeast(1100L)
                masterPcm.write(scenePcm)
                masterPcm.write(pauseBytes)
                speechMs + pauseMs
            } else {
                val fallbackMs = scene.durationMs
                if (anyAudio) {
                    val silBytes = ByteArray((fallbackMs * bytesPerMs).toInt() and 0xFFFFFFFE.toInt())
                    masterPcm.write(silBytes)
                }
                fallbackMs
            }
            syncedScenes.add(scene.copy(durationMs = exactDurationMs, pcmAudio = scenePcm))
        }

        val totalDurationMs = syncedScenes.sumOf { it.durationMs }.coerceAtLeast(3000L)
        val totalFrames = ((totalDurationMs / 1000.0) * FPS).toInt().coerceAtLeast(FPS * 3)

        // 2. Render 30 FPS animated character + diagram video track
        onProgress("Rendering 30 FPS character & diagram scenes...")
        val videoOnlyFile = File(context.cacheDir, "titan_story_vid_${System.currentTimeMillis()}.mp4")
        renderStoryVideoTrack(videoOnlyFile, syncedScenes, totalFrames, aspect, theme, onProgress)

        // 3. Encode master PCM -> AAC & Mux into final MP4
        val outFile = File(context.cacheDir, "titan_story_${System.currentTimeMillis()}.mp4")
        if (outFile.exists()) outFile.delete()

        val allPcm = masterPcm.toByteArray()
        if (anyAudio && allPcm.isNotEmpty()) {
            onProgress("Syncing audio & finalizing MP4...")
            val aacFile = File(context.cacheDir, "titan_story_aac_${System.currentTimeMillis()}.m4a")
            val aacOk = encodePcmToM4a(allPcm, detectedSr, detectedCh, aacFile)
            val muxOk = if (aacOk && aacFile.exists()) {
                muxVideoAndAac(videoOnlyFile, aacFile, outFile)
            } else false
            try { aacFile.delete() } catch (_: Exception) {}
            if (muxOk && outFile.exists() && outFile.length() > 20_000L) {
                try { videoOnlyFile.delete() } catch (_: Exception) {}
                return@withContext StoryVideoResult(outFile, syncedScenes, totalDurationMs, aspect, theme)
            }
        }

        if (videoOnlyFile.exists()) {
            videoOnlyFile.copyTo(outFile, overwrite = true)
            try { videoOnlyFile.delete() } catch (_: Exception) {}
        }
        StoryVideoResult(outFile, syncedScenes, totalDurationMs, aspect, theme)
    }

    /**
     * Uses Titan AI to structure any pasted ChatGPT/Gemini prompt or script into visual scenes
     * with character mood + diagram type. Falls back seamlessly to local smart analyzer if offline
     * or if the script is huge and needs exact 100% verbatim preservation.
     */
    suspend fun planScenes(
        context: Context,
        rawInput: String,
        onProgress: (String) -> Unit = {}
    ): List<StoryScene> {
        val cleaned = rawInput.trim()
        if (cleaned.isBlank()) {
            return parseScenesLocally("Welcome to Titan Storytelling Studio! Paste any script to generate an animated explainer video.")
        }

        // If user pasted a short prompt/topic (< 1800 chars), let AI enrich & direct the scenes!
        if (cleaned.length <= 1800) {
            try {
                onProgress("Titan AI directing scenes & character moods...")
                val sysPrompt = """
                    You are a visual video director for animated character explainer videos.
                    Convert the user's pasted story/prompt into a JSON array of 4 to 8 crisp scenes in the SAME language as the user's input (Bengali, Hindi, or English).
                    If the user gave a short topic/prompt, write a compelling storytelling script. If the user pasted a full script, preserve every key part faithfully across scenes.
                    Return ONLY a valid JSON array (no markdown fences, no extra prose) with objects having:
                    - "title": short 2-5 word headline
                    - "narration": natural spoken voiceover sentence(s), 12-28 words per scene
                    - "mood": one of EXPLAINING, HAPPY, THINKING, SURPRISED, SERIOUS, CELEBRATING
                    - "diagramType": one of STORY_CARD, FLOWCHART, BAR_CHART, COMPARISON, MAP_ROUTE, STAT_COUNTER
                    - "diagramItems": array of 2 to 4 very short visual labels (2-4 words each)
                    - "statValue": short metric string like "2 km", "95%", "3x", or ""
                """.trimIndent()

                val aiReply = TitanDirectEngine.executePrompt(
                    context = context,
                    prompt = cleaned,
                    systemInstruction = sysPrompt
                )
                val parsed = parseAiJsonScenes(aiReply)
                if (parsed.isNotEmpty()) {
                    return parsed
                }
            } catch (e: Exception) {
                Log.w(TAG, "AI director fallback to local parser: ${e.message}")
            }
        }

        onProgress("Smart local scene & mood analyzer active...")
        return parseScenesLocally(cleaned)
    }

    private fun parseAiJsonScenes(rawReply: String): List<StoryScene> {
        val start = rawReply.indexOf('[')
        val end = rawReply.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val jsonStr = rawReply.substring(start, end + 1)
        return try {
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<StoryScene>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val narration = obj.optString("narration", "").trim()
                if (narration.length < 3) continue
                val title = obj.optString("title", "").trim().ifBlank { "SCENE ${i + 1}" }
                val mood = runCatching {
                    CharacterMood.valueOf(obj.optString("mood", "EXPLAINING").uppercase().trim())
                }.getOrDefault(detectMood(narration))
                val diagramType = runCatching {
                    DiagramType.valueOf(obj.optString("diagramType", "STORY_CARD").uppercase().trim())
                }.getOrDefault(detectDiagramType(narration, i))
                val itemsArr = obj.optJSONArray("diagramItems")
                val items = mutableListOf<String>()
                if (itemsArr != null) {
                    for (j in 0 until min(itemsArr.length(), 4)) {
                        val s = itemsArr.optString(j, "").trim()
                        if (s.isNotBlank()) items.add(s)
                    }
                }
                val statValue = obj.optString("statValue", "").trim().ifBlank { extractStat(narration) }
                val words = narration.split(Regex("\\s+")).filter { it.isNotBlank() }
                val badge = when {
                    i == 0 -> "HOOK"
                    i == arr.length() - 1 -> "TAKEAWAY"
                    else -> "SCENE ${(i + 1).toString().padStart(2, '0')}"
                }
                val fallbackDur = (2000L + words.size * 280L).coerceIn(2600L, 7000L)
                list.add(
                    StoryScene(
                        index = i,
                        badge = badge,
                        title = title,
                        narration = narration,
                        words = words,
                        mood = mood,
                        diagramType = diagramType,
                        diagramItems = if (items.isNotEmpty()) items else buildDefaultDiagramItems(narration, diagramType),
                        statValue = statValue,
                        accentColor = ACCENT_PALETTE[i % ACCENT_PALETTE.size],
                        durationMs = fallbackDur
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseScenesLocally(rawText: String): List<StoryScene> {
        val cleaned = rawText
            .replace(Regex("```[\\s\\S]*?```"), "")
            .replace(Regex("[#*`_~]"), "")
            .trim()

        val rawLines = cleaned.lines()
            .map { it.trim().trimStart('-', '•', '*').trim() }
            .filter { it.length >= 3 }

        val sourceUnits = if (rawLines.size >= 2) {
            rawLines
        } else {
            cleaned.split(Regex("(?<=[।.!?])\\s+")).map { it.trim() }.filter { it.length >= 3 }
        }

        val chunks = mutableListOf<String>()
        for (unit in sourceUnits) {
            if (unit.length <= 165) {
                chunks.add(unit)
            } else {
                val words = unit.split(Regex("\\s+"))
                val sb = StringBuilder()
                for (w in words) {
                    if (sb.length + w.length + 1 > 155 && sb.isNotEmpty()) {
                        chunks.add(sb.toString().trim())
                        sb.clear()
                    }
                    if (sb.isNotEmpty()) sb.append(" ")
                    sb.append(w)
                }
                if (sb.isNotEmpty()) chunks.add(sb.toString().trim())
            }
        }
        if (chunks.isEmpty()) chunks.add(cleaned.ifBlank { "Titan Storytelling Video" }.take(150))

        return chunks.mapIndexed { idx, chunk ->
            val hasColon = chunk.contains(":") && chunk.indexOf(':') in 2..36
            val title = if (hasColon) chunk.substringBefore(":").trim() else when (idx) {
                0 -> "THE STORY BEGINS"
                chunks.lastIndex -> "FINAL TAKEAWAY"
                else -> "PART ${(idx + 1).toString().padStart(2, '0')}"
            }
            val narration = if (hasColon) chunk.substringAfter(":").trim().ifBlank { chunk } else chunk
            val mood = detectMood(narration)
            val diagramType = detectDiagramType(narration, idx)
            val words = narration.split(Regex("\\s+")).filter { it.isNotBlank() }
            val badge = when (idx) {
                0 -> "HOOK"
                chunks.lastIndex -> "TAKEAWAY"
                else -> "SCENE ${(idx + 1).toString().padStart(2, '0')}"
            }
            val fallbackDur = (2000L + words.size * 280L).coerceIn(2600L, 7000L)
            StoryScene(
                index = idx,
                badge = badge,
                title = title,
                narration = narration,
                words = words,
                mood = mood,
                diagramType = diagramType,
                diagramItems = buildDefaultDiagramItems(narration, diagramType),
                statValue = extractStat(narration),
                accentColor = ACCENT_PALETTE[idx % ACCENT_PALETTE.size],
                durationMs = fallbackDur
            )
        }
    }

    private fun detectMood(text: String): CharacterMood {
        val t = text.lowercase()
        return when {
            t.contains("?") || t.contains("কেন") || t.contains("কিভাবে") || t.contains("কীভাবে") ||
                t.contains("why") || t.contains("how") || t.contains("wonder") || t.contains("ভাব") -> CharacterMood.THINKING
            t.contains("!") || t.contains("অবাক") || t.contains("চমক") || t.contains("হঠাৎ") ||
                t.contains("wow") || t.contains("shock") || t.contains("secret") || t.contains("গোপন") -> CharacterMood.SURPRISED
            t.contains("বিপদ") || t.contains("সাবধান") || t.contains("সমস্যা") || t.contains("ভুল") ||
                t.contains("danger") || t.contains("warning") || t.contains("problem") || t.contains("fail") || t.contains("crash") -> CharacterMood.SERIOUS
            t.contains("সাফল্য") || t.contains("জিতে") || t.contains("অসাধারণ") || t.contains("সুপার") ||
                t.contains("success") || t.contains("win") || t.contains("achieve") || t.contains("congrat") -> CharacterMood.CELEBRATING
            t.contains("খুশি") || t.contains("ভালো") || t.contains("মজা") || t.contains("আনন্দ") ||
                t.contains("happy") || t.contains("great") || t.contains("love") || t.contains("fun") -> CharacterMood.HAPPY
            else -> CharacterMood.EXPLAINING
        }
    }

    private fun detectDiagramType(text: String, idx: Int): DiagramType {
        val t = text.lowercase()
        return when {
            t.contains("ম্যাপ") || t.contains("ট্র্যাফিক") || t.contains("রাস্তা") || t.contains("গাড়ি") ||
                t.contains("প্লেন") || t.contains("map") || t.contains("gps") || t.contains("route") ||
                t.contains("traffic") || t.contains("flight") || t.contains("journey") -> DiagramType.MAP_ROUTE
            t.contains("বনাম") || t.contains("পার্থক্য") || t.contains("তুলনা") ||
                t.contains(" vs ") || t.contains("versus") || t.contains("compare") || t.contains("before") -> DiagramType.COMPARISON
            t.contains("ধাপ") || t.contains("প্রথমে") || t.contains("তারপর") || t.contains("প্রসেস") ||
                t.contains("step") || t.contains("process") || t.contains("flow") || t.contains("->") -> DiagramType.FLOWCHART
            Regex("\\d+\\s*(%|x|km|মি|কোটি|লাখ|k|m)", RegexOption.IGNORE_CASE).containsMatchIn(t) -> {
                if (idx % 2 == 0) DiagramType.STAT_COUNTER else DiagramType.BAR_CHART
            }
            idx % 4 == 1 -> DiagramType.FLOWCHART
            idx % 4 == 2 -> DiagramType.BAR_CHART
            else -> DiagramType.STORY_CARD
        }
    }

    private fun extractStat(text: String): String {
        val m = Regex("(\\d+[.,]?\\d*\\s*(%|x|X|km|KM|K|M|\\+|মি|টি|বছর)?)").find(text)
        return m?.value?.trim()?.take(10) ?: ""
    }

    private fun buildDefaultDiagramItems(text: String, type: DiagramType): List<String> {
        val words = text.split(Regex("\\s+")).filter { it.length >= 2 }
        return when (type) {
            DiagramType.FLOWCHART -> listOf(
                words.take(2).joinToString(" ").ifBlank { "Step 1" },
                words.drop(words.size / 3).take(2).joinToString(" ").ifBlank { "Process" },
                words.takeLast(2).joinToString(" ").ifBlank { "Result" }
            )
            DiagramType.COMPARISON -> listOf(
                words.take(2).joinToString(" ").ifBlank { "Before" },
                words.takeLast(2).joinToString(" ").ifBlank { "After" }
            )
            DiagramType.BAR_CHART -> listOf("Phase 1", "Phase 2", "Peak")
            DiagramType.MAP_ROUTE -> listOf("Start", "Live Signal", "Target")
            else -> listOf(words.take(3).joinToString(" ").ifBlank { "Key Insight" })
        }
    }

    // ==================== VIDEO RENDERER ====================

    private fun renderStoryVideoTrack(
        outFile: File,
        scenes: List<StoryScene>,
        totalFrames: Int,
        aspect: VideoAspect,
        theme: VideoTheme,
        onProgress: (String) -> Unit
    ) {
        val width = aspect.width
        val height = aspect.height
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = encoder.createInputSurface()
        encoder.start()

        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1
        var muxerStarted = false
        val bufferInfo = MediaCodec.BufferInfo()
        var writtenFrameCount = 0L

        fun drainEncoder(endOfStream: Boolean) {
            if (endOfStream) encoder.signalEndOfInputStream()
            while (true) {
                val outIdx = encoder.dequeueOutputBuffer(bufferInfo, 10_000L)
                when {
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) break
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            trackIndex = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                    outIdx >= 0 -> {
                        val encodedData = encoder.getOutputBuffer(outIdx)
                        if (encodedData != null && bufferInfo.size > 0 && muxerStarted) {
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                bufferInfo.presentationTimeUs = (writtenFrameCount * 1_000_000L) / FPS
                                writtenFrameCount++
                                encodedData.position(bufferInfo.offset)
                                encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                            }
                        }
                        encoder.releaseOutputBuffer(outIdx, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                    }
                }
            }
        }

        val sceneStarts = LongArray(scenes.size)
        var cursorMs = 0L
        for (i in scenes.indices) {
            sceneStarts[i] = cursorMs
            cursorMs += scenes[i].durationMs
        }
        val totalMs = cursorMs.coerceAtLeast(1L)

        try {
            for (frameIdx in 0 until totalFrames) {
                val tMs = (frameIdx * 1000L) / FPS
                var activeIdx = scenes.lastIndex
                for (i in scenes.indices) {
                    if (tMs < sceneStarts[i] + scenes[i].durationMs) {
                        activeIdx = i
                        break
                    }
                }
                val scene = scenes[activeIdx]
                val localMs = (tMs - sceneStarts[activeIdx]).coerceAtLeast(0L)
                val sceneProgress = (localMs.toFloat() / scene.durationMs.toFloat()).coerceIn(0f, 1f)
                val globalProgress = (tMs.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f)

                val canvas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    inputSurface.lockHardwareCanvas()
                } else {
                    inputSurface.lockCanvas(null)
                }
                try {
                    drawStoryFrame(canvas, width.toFloat(), height.toFloat(), scene, scenes.size, sceneProgress, globalProgress, tMs, theme)
                } finally {
                    inputSurface.unlockCanvasAndPost(canvas)
                }

                drainEncoder(false)

                if (frameIdx % 30 == 0) {
                    val pct = ((frameIdx * 100) / totalFrames).coerceIn(1, 99)
                    onProgress("Rendering animated scenes... $pct%")
                }
            }
            drainEncoder(true)
        } finally {
            try { encoder.stop(); encoder.release() } catch (_: Exception) {}
            try { inputSurface.release() } catch (_: Exception) {}
            try { if (muxerStarted) muxer.stop(); muxer.release() } catch (_: Exception) {}
        }
    }

    private fun easeOutCubic(x: Float): Float {
        val inv = 1f - x.coerceIn(0f, 1f)
        return 1f - inv * inv * inv
    }

    private fun drawStoryFrame(
        canvas: Canvas,
        w: Float,
        h: Float,
        scene: StoryScene,
        totalScenes: Int,
        sceneProgress: Float,
        globalProgress: Float,
        tMs: Long,
        theme: VideoTheme
    ) {
        val isLandscape = w > h
        val tSec = tMs / 1000f
        val accentR = Color.red(scene.accentColor)
        val accentG = Color.green(scene.accentColor)
        val accentB = Color.blue(scene.accentColor)

        // 1. Background Gradient
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, w, h,
                intArrayOf(theme.bgTop, theme.bgMid, theme.bgBot),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // 2. Ambient Moving Glow
        val glowX = w * (0.5f + 0.16f * sin(tSec * 0.65f))
        val glowY = h * (0.28f + 0.06f * cos(tSec * 0.55f))
        val glowR = min(w, h) * 0.72f
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                glowX, glowY, glowR,
                Color.argb(if (theme.isLight) 42 else 68, accentR, accentG, accentB),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(glowX, glowY, glowR, glowPaint)

        // 3. Subtle Blueprint Dot Grid
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (theme.isLight) Color.argb(26, 15, 23, 42) else Color.argb(22, 255, 255, 255)
        }
        var gx = 32f
        while (gx < w) {
            var gy = 32f
            while (gy < h) {
                canvas.drawCircle(gx, gy, 1.5f, dotPaint)
                gy += 52f
            }
            gx += 52f
        }

        val enterT = easeOutCubic((sceneProgress / 0.15f).coerceIn(0f, 1f))
        val exitT = if (sceneProgress > 0.93f) ((1f - sceneProgress) / 0.07f).coerceIn(0f, 1f) else 1f
        val alphaFactor = (enterT * exitT).coerceIn(0f, 1f)
        val isTalking = sceneProgress in 0.04f..0.90f

        // 4. Top Header Bar: Scene Badge + Mood Pill + Counter
        val topPad = if (isLandscape) 34f else 54f
        val sidePad = if (isLandscape) 44f else 42f

        val badgeRect = RectF(sidePad, topPad, sidePad + 225f, topPad + 44f)
        canvas.drawRoundRect(badgeRect, 22f, 22f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb((48 * alphaFactor).toInt(), accentR, accentG, accentB)
        })
        canvas.drawRoundRect(badgeRect, 22f, 22f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb((160 * alphaFactor).toInt(), accentR, accentG, accentB)
            style = Paint.Style.STROKE
            strokeWidth = 2f
        })
        canvas.drawText(
            "${scene.badge} • ${scene.mood.name}",
            sidePad + 18f,
            topPad + 28f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb((255 * alphaFactor).toInt(), accentR, accentG, accentB)
                textSize = 15.5f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                letterSpacing = 0.08f
            }
        )

        val counterStr = "${(scene.index + 1).toString().padStart(2, '0')} / ${totalScenes.toString().padStart(2, '0')}"
        canvas.drawText(
            counterStr,
            w - sidePad,
            topPad + 29f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = theme.mutedText
                textSize = 20f
                typeface = Typeface.MONOSPACE
                textAlign = Paint.Align.RIGHT
            }
        )

        // 5. Layout Regions (Responsive for 9:16 Portrait vs 16:9 Landscape!)
        val mascotCx: Float
        val mascotCy: Float
        val mascotScale: Float
        val diagramRect: RectF
        val captionRect: RectF

        if (isLandscape) {
            mascotCx = w * 0.20f
            mascotCy = h * 0.44f
            mascotScale = 1.05f
            diagramRect = RectF(w * 0.37f, 102f, w - sidePad, h * 0.61f)
            captionRect = RectF(sidePad, h * 0.66f, w - sidePad, h - 66f)
        } else {
            mascotCx = w * 0.50f
            mascotCy = 232f
            mascotScale = 1.02f
            diagramRect = RectF(sidePad, 355f, w - sidePad, 720f)
            captionRect = RectF(sidePad, 745f, w - sidePad, 1135f)
        }

        // 6. Draw Face-Aware Expressive Character
        drawExpressiveCharacter(canvas, mascotCx, mascotCy, mascotScale, tSec, isTalking, scene.mood, scene.accentColor)

        // 7. Draw Animated Diagram Stage
        val slideShift = (1f - enterT) * 32f
        val animatedDiagramRect = RectF(
            diagramRect.left,
            diagramRect.top + slideShift,
            diagramRect.right,
            diagramRect.bottom + slideShift
        )
        drawAnimatedDiagramStage(canvas, animatedDiagramRect, scene, sceneProgress, tSec, alphaFactor, theme)

        // 8. Draw Kinetic Subtitle & Narration Card
        drawCaptionCard(canvas, captionRect, scene, sceneProgress, alphaFactor, theme, isLandscape)

        // 9. Global Bottom Progress Bar + Signature
        val barY = h - 38f
        canvas.drawRoundRect(
            RectF(sidePad, barY, w - sidePad, barY + 7f),
            3.5f, 3.5f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (theme.isLight) Color.argb(45, 15, 23, 42) else Color.argb(55, 255, 255, 255)
            }
        )
        canvas.drawRoundRect(
            RectF(sidePad, barY, sidePad + (w - sidePad * 2f) * globalProgress, barY + 7f),
            3.5f, 3.5f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = scene.accentColor }
        )
    }

    /**
     * Face-Aware Animated Robot Character:
     * Dynamically changes eye shape, eyebrows, mouth, head tilt, arm gestures, and floating mood FX
     * based on the detected mood of the current scene!
     */
    private fun drawExpressiveCharacter(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        scale: Float,
        tSec: Float,
        isTalking: Boolean,
        mood: CharacterMood,
        accentColor: Int
    ) {
        val bobSpeed = when (mood) {
            CharacterMood.CELEBRATING, CharacterMood.HAPPY -> 5.2f
            CharacterMood.SERIOUS -> 2.0f
            else -> 3.3f
        }
        val bobAmp = when (mood) {
            CharacterMood.CELEBRATING -> 12f
            CharacterMood.HAPPY -> 10f
            else -> 7f
        }
        val bobY = sin(tSec * bobSpeed) * bobAmp
        val tiltDeg = when (mood) {
            CharacterMood.THINKING -> -9f + sin(tSec * 1.6f) * 3f
            CharacterMood.CELEBRATING -> sin(tSec * 5f) * 7f
            CharacterMood.SERIOUS -> 0f
            else -> sin(tSec * 2.1f) * 4.5f
        }

        val r = Color.red(accentColor)
        val g = Color.green(accentColor)
        val b = Color.blue(accentColor)

        canvas.save()
        canvas.translate(cx, cy + bobY)
        canvas.scale(scale, scale)
        canvas.rotate(tiltDeg)

        // Pulsing Halo Aura
        val pulse = 1f + 0.08f * sin(tSec * 5f)
        val haloColor = if (mood == CharacterMood.SERIOUS) 0xFFEF4444.toInt() else accentColor
        val hr = Color.red(haloColor)
        val hg = Color.green(haloColor)
        val hb = Color.blue(haloColor)
        canvas.drawCircle(
            0f, 0f, 114f * pulse,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    0f, 0f, 114f * pulse,
                    Color.argb(95, hr, hg, hb),
                    Color.TRANSPARENT,
                    Shader.TileMode.CLAMP
                )
            }
        )

        // Floating shadow underneath
        canvas.drawOval(
            RectF(-56f, 84f - bobY * 0.45f, 56f, 98f - bobY * 0.45f),
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(72, 0, 0, 0) }
        )

        // Antenna Crown
        canvas.drawLine(
            0f, -64f, 0f, -88f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = accentColor
                strokeWidth = 6f
                strokeCap = Paint.Cap.ROUND
            }
        )
        canvas.drawCircle(0f, -92f, 10f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        canvas.drawCircle(0f, -92f, 6f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = haloColor })

        // Dynamic Arms based on Mood
        val armPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentColor }
        val leftArmAngle = when (mood) {
            CharacterMood.CELEBRATING -> -140f + sin(tSec * 8f) * 14f
            CharacterMood.HAPPY -> -110f + sin(tSec * 7f) * 16f
            CharacterMood.THINKING -> -35f
            else -> -22f + (if (isTalking) sin(tSec * 6.5f) * 16f else sin(tSec * 2.5f) * 5f)
        }
        val rightArmAngle = when (mood) {
            CharacterMood.CELEBRATING -> 140f - sin(tSec * 8f) * 14f
            CharacterMood.EXPLAINING -> 68f + sin(tSec * 5f) * 12f // Pointing toward diagram!
            CharacterMood.THINKING -> 120f // Hand near chin
            else -> 22f - (if (isTalking) sin(tSec * 6.5f) * 16f else sin(tSec * 2.5f) * 5f)
        }

        canvas.save()
        canvas.translate(-72f, 8f)
        canvas.rotate(leftArmAngle)
        canvas.drawRoundRect(RectF(-15f, -10f, 10f, 36f), 12f, 12f, armPaint)
        canvas.restore()

        canvas.save()
        canvas.translate(72f, 8f)
        canvas.rotate(rightArmAngle)
        canvas.drawRoundRect(RectF(-10f, -10f, 15f, 36f), 12f, 12f, armPaint)
        canvas.restore()

        // Squircle Body
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                -72f, -64f, 72f, 66f,
                intArrayOf(accentColor, Color.rgb((r * 0.7f).toInt(), (g * 0.7f).toInt(), (b * 0.7f).toInt())),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(RectF(-72f, -64f, 72f, 66f), 38f, 38f, bodyPaint)

        // Inner Dark Face Visor
        canvas.drawRoundRect(
            RectF(-53f, -38f, 53f, 36f),
            24f, 24f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0F172A.toInt() }
        )

        // Expressive Eyes & Eyebrows per Mood
        val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val strokeEyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 5.5f
            strokeCap = Paint.Cap.ROUND
        }
        val blinkPhase = tSec % 3.4f
        val isBlinking = blinkPhase > 3.24f

        when (mood) {
            CharacterMood.HAPPY, CharacterMood.CELEBRATING -> {
                // Joyful curved ^ ^ eyes
                canvas.drawArc(RectF(-35f, -22f, -11f, -2f), 190f, 160f, false, strokeEyePaint)
                canvas.drawArc(RectF(11f, -22f, 35f, -2f), 190f, 160f, false, strokeEyePaint)
            }
            CharacterMood.SURPRISED -> {
                // Big wide shocked eyes
                canvas.drawCircle(-23f, -12f, 12.5f, eyePaint)
                canvas.drawCircle(23f, -12f, 12.5f, eyePaint)
                val pupil = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentColor }
                canvas.drawCircle(-23f, -12f, 4f, pupil)
                canvas.drawCircle(23f, -12f, 4f, pupil)
            }
            CharacterMood.SERIOUS -> {
                // Focused narrowed eyes + angled serious brows
                val eh = if (isBlinking) 3f else 12f
                canvas.drawRoundRect(RectF(-34f, -11f - eh / 2f, -12f, -11f + eh / 2f), 6f, 6f, eyePaint)
                canvas.drawRoundRect(RectF(12f, -11f - eh / 2f, 34f, -11f + eh / 2f), 6f, 6f, eyePaint)
                val browPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFFEF4444.toInt()
                    strokeWidth = 4.5f
                    strokeCap = Paint.Cap.ROUND
                }
                canvas.drawLine(-36f, -24f, -10f, -18f, browPaint)
                canvas.drawLine(36f, -24f, 10f, -18f, browPaint)
            }
            CharacterMood.THINKING -> {
                // Eyes looking slightly upward-right + one raised brow
                val eh = if (isBlinking) 3f else 15f
                canvas.drawRoundRect(RectF(-34f, -13f - eh / 2f, -12f, -13f + eh / 2f), 7f, 7f, eyePaint)
                canvas.drawRoundRect(RectF(12f, -13f - eh / 2f, 34f, -13f + eh / 2f), 7f, 7f, eyePaint)
                if (!isBlinking) {
                    val pupil = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentColor }
                    canvas.drawCircle(-20f, -16f, 4.5f, pupil)
                    canvas.drawCircle(26f, -16f, 4.5f, pupil)
                }
                val browPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = accentColor
                    strokeWidth = 4f
                    strokeCap = Paint.Cap.ROUND
                }
                canvas.drawLine(-34f, -25f, -12f, -25f, browPaint)
                canvas.drawLine(12f, -29f, 34f, -23f, browPaint)
            }
            CharacterMood.EXPLAINING -> {
                val eh = if (isBlinking) 3f else 16f
                canvas.drawRoundRect(RectF(-34f, -13f - eh / 2f, -12f, -13f + eh / 2f), 8f, 8f, eyePaint)
                canvas.drawRoundRect(RectF(12f, -13f - eh / 2f, 34f, -13f + eh / 2f), 8f, 8f, eyePaint)
                if (!isBlinking) {
                    val lookX = sin(tSec * 1.4f) * 3f
                    val pupil = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentColor }
                    canvas.drawCircle(-23f + lookX, -13f, 4.5f, pupil)
                    canvas.drawCircle(23f + lookX, -13f, 4.5f, pupil)
                }
            }
        }

        // Expressive Mouth synced to speech + mood
        val mouthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = haloColor }
        when (mood) {
            CharacterMood.SURPRISED -> {
                val rMouth = if (isTalking) 8f + abs(sin(tSec * 10f)) * 5f else 9f
                canvas.drawCircle(0f, 15f, rMouth, mouthPaint)
            }
            CharacterMood.HAPPY, CharacterMood.CELEBRATING -> {
                val open = if (isTalking) 10f + abs(sin(tSec * 11f)) * 8f else 10f
                canvas.drawArc(RectF(-18f, 6f, 18f, 6f + open * 1.4f), 0f, 180f, true, mouthPaint)
            }
            CharacterMood.SERIOUS -> {
                val mh = if (isTalking) 4f + abs(sin(tSec * 11f)) * 7f else 4f
                canvas.drawRoundRect(RectF(-15f, 15f - mh / 2f, 15f, 15f + mh / 2f), 4f, 4f, mouthPaint)
            }
            else -> {
                val mh = if (isTalking) 6f + abs(sin(tSec * 11.5f)) * 12f else 4.5f
                canvas.drawRoundRect(RectF(-16f, 14f - mh / 2f, 16f, 14f + mh / 2f), 8f, 8f, mouthPaint)
            }
        }

        // Little Feet
        val footPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, r, g, b) }
        canvas.drawRoundRect(RectF(-38f, 62f, -14f, 77f), 8f, 8f, footPaint)
        canvas.drawRoundRect(RectF(14f, 62f, 38f, 77f), 8f, 8f, footPaint)

        // Floating Mood FX Badges (?, !, Sparkles)
        when (mood) {
            CharacterMood.THINKING -> {
                val fy = -72f + sin(tSec * 4f) * 6f
                canvas.drawText("?", 78f, fy, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = accentColor
                    textSize = 42f
                    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                })
            }
            CharacterMood.SURPRISED -> {
                val fy = -70f + sin(tSec * 7f) * 5f
                canvas.drawText("!", 76f, fy, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFFF59E0B.toInt()
                    textSize = 44f
                    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                })
            }
            CharacterMood.CELEBRATING -> {
                val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG)
                val colors = intArrayOf(0xFFF59E0B.toInt(), 0xFF38BDF8.toInt(), 0xFF10B981.toInt(), 0xFFEC4899.toInt())
                for (k in 0 until 6) {
                    val ang = (k * 60f + tSec * 45f) * (PI / 180.0)
                    val sx = (cos(ang) * 95f).toFloat()
                    val sy = (sin(ang) * 78f).toFloat()
                    sparkPaint.color = colors[k % colors.size]
                    canvas.drawCircle(sx, sy, 5.5f, sparkPaint)
                }
            }
            else -> {}
        }

        canvas.restore()
    }

    /**
     * Draws the Visual Diagram Stage (Flowchart, Bar Chart, Map/Route, Comparison, Stat Counter, or Story Card)
     */
    private fun drawAnimatedDiagramStage(
        canvas: Canvas,
        rect: RectF,
        scene: StoryScene,
        progress: Float,
        tSec: Float,
        alphaFactor: Float,
        theme: VideoTheme
    ) {
        val r = Color.red(scene.accentColor)
        val g = Color.green(scene.accentColor)
        val b = Color.blue(scene.accentColor)

        // Stage Card Shell
        canvas.drawRoundRect(rect, 28f, 28f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.cardBg
            alpha = (Color.alpha(theme.cardBg) * alphaFactor).toInt()
        })
        canvas.drawRoundRect(rect, 28f, 28f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb((125 * alphaFactor).toInt(), r, g, b)
            style = Paint.Style.STROKE
            strokeWidth = 2.2f
        })

        // Scene Title at top of diagram card
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb((255 * alphaFactor).toInt(), r, g, b)
            textSize = 25f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        canvas.drawText(scene.title.take(36), rect.left + 26f, rect.top + 42f, titlePaint)

        val inner = RectF(rect.left + 24f, rect.top + 62f, rect.right - 24f, rect.bottom - 22f)
        val anim = easeOutCubic((progress / 0.45f).coerceIn(0f, 1f))

        when (scene.diagramType) {
            DiagramType.FLOWCHART -> drawFlowchartDiagram(canvas, inner, scene, anim, tSec, theme)
            DiagramType.BAR_CHART -> drawBarChartDiagram(canvas, inner, scene, anim, theme)
            DiagramType.COMPARISON -> drawComparisonDiagram(canvas, inner, scene, anim, theme)
            DiagramType.MAP_ROUTE -> drawMapRouteDiagram(canvas, inner, scene, progress, tSec, theme)
            DiagramType.STAT_COUNTER -> drawStatCounterDiagram(canvas, inner, scene, anim, theme)
            DiagramType.STORY_CARD -> drawStoryVisualCard(canvas, inner, scene, anim, tSec, theme)
        }
    }

    private fun drawFlowchartDiagram(canvas: Canvas, r: RectF, scene: StoryScene, anim: Float, tSec: Float, theme: VideoTheme) {
        val items = scene.diagramItems.take(3).ifEmpty { listOf("Input", "Process", "Output") }
        val count = items.size
        val boxW = (r.width() - (count - 1) * 44f) / count
        val boxH = min(124f, r.height() * 0.62f)
        val cy = r.centerY()
        val accent = scene.accentColor

        for (i in 0 until count) {
            val stepReveal = ((anim * count) - i).coerceIn(0f, 1f)
            val bx = r.left + i * (boxW + 44f)
            val boxRect = RectF(bx, cy - boxH / 2f, bx + boxW, cy + boxH / 2f)

            canvas.drawRoundRect(boxRect, 18f, 18f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb((42 * stepReveal).toInt(), Color.red(accent), Color.green(accent), Color.blue(accent))
            })
            canvas.drawRoundRect(boxRect, 18f, 18f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb((210 * stepReveal).toInt(), Color.red(accent), Color.green(accent), Color.blue(accent))
                style = Paint.Style.STROKE
                strokeWidth = 2.5f
            })

            // Step number circle
            canvas.drawCircle(bx + 24f, boxRect.top + 24f, 13f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent })
            canvas.drawText("${i + 1}", bx + 24f, boxRect.top + 29f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
            })

            // Step label
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = theme.primaryText
                textSize = 20f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            }
            val sl = StaticLayout.Builder.obtain(items[i], 0, items[i].length, tp, (boxW - 22f).toInt().coerceAtLeast(40))
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setMaxLines(2)
                .build()
            canvas.save()
            canvas.translate(bx + 11f, boxRect.top + 48f)
            sl.draw(canvas)
            canvas.restore()

            // Animated connecting arrow + moving signal dot
            if (i < count - 1) {
                val ax1 = boxRect.right + 6f
                val ax2 = bx + boxW + 38f
                canvas.drawLine(ax1, cy, ax2, cy, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = accent
                    strokeWidth = 4f
                    strokeCap = Paint.Cap.ROUND
                })
                val dotT = (tSec * 1.4f + i * 0.3f) % 1f
                val dotX = ax1 + (ax2 - ax1) * dotT
                canvas.drawCircle(dotX, cy, 6f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
            }
        }
    }

    private fun drawBarChartDiagram(canvas: Canvas, r: RectF, scene: StoryScene, anim: Float, theme: VideoTheme) {
        val items = scene.diagramItems.take(4).ifEmpty { listOf("Start", "Growth", "Peak") }
        val count = items.size
        val gap = 26f
        val barW = ((r.width() - gap * (count + 1)) / count).coerceAtMost(110f)
        val totalW = count * barW + (count - 1) * gap
        val startX = r.centerX() - totalW / 2f
        val baseY = r.bottom - 34f
        val maxH = (r.height() - 62f).coerceAtLeast(60f)
        val heights = floatArrayOf(0.45f, 0.72f, 0.96f, 0.65f)

        // Baseline
        canvas.drawLine(r.left + 12f, baseY, r.right - 12f, baseY, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.mutedText
            strokeWidth = 2.5f
        })

        for (i in 0 until count) {
            val frac = heights[i % heights.size] * anim
            val bh = maxH * frac
            val bx = startX + i * (barW + gap)
            val barColor = ACCENT_PALETTE[(scene.index + i) % ACCENT_PALETTE.size]
            canvas.drawRoundRect(
                RectF(bx, baseY - bh, bx + barW, baseY),
                12f, 12f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = barColor }
            )
            val pctVal = "${(heights[i % heights.size] * 100 * anim).toInt()}%"
            canvas.drawText(pctVal, bx + barW / 2f, baseY - bh - 10f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = theme.primaryText
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
            })
            canvas.drawText(items[i].take(12), bx + barW / 2f, baseY + 24f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = theme.mutedText
                textSize = 16f
                textAlign = Paint.Align.CENTER
            })
        }
    }

    private fun drawComparisonDiagram(canvas: Canvas, r: RectF, scene: StoryScene, anim: Float, theme: VideoTheme) {
        val leftLabel = scene.diagramItems.getOrNull(0) ?: "Option A"
        val rightLabel = scene.diagramItems.getOrNull(1) ?: "Option B"
        val halfW = (r.width() - 36f) / 2f
        val leftRect = RectF(r.left, r.top + 12f, r.left + halfW, r.bottom - 12f)
        val rightRect = RectF(r.right - halfW, r.top + 12f, r.right, r.bottom - 12f)

        val c1 = 0xFF38BDF8.toInt()
        val c2 = scene.accentColor

        for ((box, label, col) in listOf(Triple(leftRect, leftLabel, c1), Triple(rightRect, rightLabel, c2))) {
            canvas.drawRoundRect(box, 20f, 20f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb((38 * anim).toInt(), Color.red(col), Color.green(col), Color.blue(col))
            })
            canvas.drawRoundRect(box, 20f, 20f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = col
                style = Paint.Style.STROKE
                strokeWidth = 2.5f
            })
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = theme.primaryText
                textSize = 23f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            }
            val sl = StaticLayout.Builder.obtain(label, 0, label.length, tp, (box.width() - 28f).toInt().coerceAtLeast(40))
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setMaxLines(3)
                .build()
            canvas.save()
            canvas.translate(box.left + 14f, box.centerY() - sl.height / 2f)
            sl.draw(canvas)
            canvas.restore()
        }

        // Center VS Badge
        canvas.drawCircle(r.centerX(), r.centerY(), 26f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = scene.accentColor })
        canvas.drawText("VS", r.centerX(), r.centerY() + 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        })
    }

    private fun drawMapRouteDiagram(canvas: Canvas, r: RectF, scene: StoryScene, progress: Float, tSec: Float, theme: VideoTheme) {
        val y = r.centerY()
        val x1 = r.left + 44f
        val x2 = r.centerX()
        val x3 = r.right - 44f

        // Road track background
        canvas.drawLine(x1, y, x3, y, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(60, 148, 163, 184)
            strokeWidth = 18f
            strokeCap = Paint.Cap.ROUND
        })
        // Green -> Amber -> Red traffic/route segments
        canvas.drawLine(x1, y, x2, y, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF10B981.toInt()
            strokeWidth = 10f
            strokeCap = Paint.Cap.ROUND
        })
        canvas.drawLine(x2, y, x2 + (x3 - x2) * 0.55f, y, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF59E0B.toInt()
            strokeWidth = 10f
            strokeCap = Paint.Cap.ROUND
        })
        canvas.drawLine(x2 + (x3 - x2) * 0.55f, y, x3, y, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFEF4444.toInt()
            strokeWidth = 10f
            strokeCap = Paint.Cap.ROUND
        })

        // Moving vehicle / signal dot along the route!
        val dotX = x1 + (x3 - x1) * progress.coerceIn(0.04f, 0.96f)
        val pulseR = 16f + 6f * sin(tSec * 7f)
        canvas.drawCircle(dotX, y, pulseR, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(95, 56, 189, 248)
        })
        canvas.drawCircle(dotX, y, 10f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })

        // 3 GPS Pins & Labels
        val labels = scene.diagramItems.take(3).ifEmpty { listOf("Start", "Signal", "Goal") }
        val xs = floatArrayOf(x1, x2, x3)
        val pinColors = intArrayOf(0xFF10B981.toInt(), 0xFFF59E0B.toInt(), 0xFFEF4444.toInt())
        for (i in 0 until min(3, labels.size)) {
            canvas.drawCircle(xs[i], y - 34f, 11f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pinColors[i] })
            canvas.drawText(labels[i].take(14), xs[i], y + 48f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = theme.primaryText
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
            })
        }
    }

    private fun drawStatCounterDiagram(canvas: Canvas, r: RectF, scene: StoryScene, anim: Float, theme: VideoTheme) {
        val cx = r.centerX()
        val cy = r.centerY()
        val radius = min(r.width(), r.height()) * 0.38f
        val oval = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

        canvas.drawCircle(cx, cy, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(45, 148, 163, 184)
            style = Paint.Style.STROKE
            strokeWidth = 14f
        })
        canvas.drawArc(oval, -90f, 300f * anim, false, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = scene.accentColor
            style = Paint.Style.STROKE
            strokeWidth = 14f
            strokeCap = Paint.Cap.ROUND
        })

        val heroStat = scene.statValue.ifBlank { "${(anim * 100).toInt()}%" }
        canvas.drawText(heroStat, cx, cy + 14f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.primaryText
            textSize = 44f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        })
        val sub = scene.diagramItems.firstOrNull().orEmpty().take(24)
        if (sub.isNotBlank()) {
            canvas.drawText(sub, cx, cy + radius + 24f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = theme.mutedText
                textSize = 18f
                textAlign = Paint.Align.CENTER
            })
        }
    }

    private fun drawStoryVisualCard(canvas: Canvas, r: RectF, scene: StoryScene, anim: Float, tSec: Float, theme: VideoTheme) {
        val items = scene.diagramItems.take(3).ifEmpty { listOf(scene.title) }
        val rowH = (r.height() / items.size.coerceAtLeast(1)).coerceAtMost(72f)
        var curY = r.top + 14f
        for ((i, item) in items.withIndex()) {
            val reveal = ((anim * items.size) - i).coerceIn(0f, 1f)
            val pill = RectF(r.left + 8f, curY, r.right - 8f, curY + rowH - 12f)
            canvas.drawRoundRect(pill, 16f, 16f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb((34 * reveal).toInt(), Color.red(scene.accentColor), Color.green(scene.accentColor), Color.blue(scene.accentColor))
            })
            val dotR = 7f + sin(tSec * 4f + i) * 1.5f
            canvas.drawCircle(pill.left + 24f, pill.centerY(), dotR, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = scene.accentColor
            })
            canvas.drawText(item.take(38), pill.left + 48f, pill.centerY() + 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = theme.primaryText
                textSize = 22f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            })
            curY += rowH
        }
    }

    private fun drawCaptionCard(
        canvas: Canvas,
        rect: RectF,
        scene: StoryScene,
        sceneProgress: Float,
        alphaFactor: Float,
        theme: VideoTheme,
        isLandscape: Boolean
    ) {
        val r = Color.red(scene.accentColor)
        val g = Color.green(scene.accentColor)
        val b = Color.blue(scene.accentColor)

        canvas.drawRoundRect(rect, 26f, 26f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.cardBg
            alpha = (Color.alpha(theme.cardBg) * alphaFactor).toInt()
        })
        canvas.drawRoundRect(rect, 26f, 26f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb((95 * alphaFactor).toInt(), r, g, b)
            style = Paint.Style.STROKE
            strokeWidth = 2f
        })

        // Left animated accent bar
        val barH = (rect.height() - 40f) * easeOutCubic((sceneProgress / 0.25f).coerceIn(0f, 1f))
        canvas.drawRoundRect(
            RectF(rect.left + 20f, rect.top + 20f, rect.left + 27f, rect.top + 20f + barH),
            3.5f, 3.5f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = scene.accentColor }
        )

        val wordCount = scene.words.size.coerceAtLeast(1)
        val revealProgress = ((sceneProgress - 0.03f) / 0.85f).coerceIn(0f, 1f)
        val revealedCount = (revealProgress * wordCount).toInt().coerceIn(1, wordCount)
        val visibleText = scene.words.take(revealedCount).joinToString(" ")

        val textSize = when {
            isLandscape && scene.narration.length > 110 -> 25f
            isLandscape -> 30f
            scene.narration.length < 60 -> 38f
            scene.narration.length < 115 -> 33f
            else -> 28f
        }
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.primaryText
            this.textSize = textSize
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val layout = StaticLayout.Builder
            .obtain(visibleText, 0, visibleText.length, tp, (rect.width() - 68f).toInt().coerceAtLeast(100))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(10f, 1.12f)
            .build()

        canvas.save()
        canvas.translate(rect.left + 44f, rect.top + 26f)
        layout.draw(canvas)
        canvas.restore()
    }

    // ==================== AUDIO UTILITIES & GALLERY SAVE ====================

    private fun resamplePcm(src: ByteArray, srcSr: Int, srcCh: Int, dstSr: Int, dstCh: Int): ByteArray {
        val srcSamples = src.size / (2 * srcCh)
        if (srcSamples <= 0) return ByteArray(0)
        val dstSamples = ((srcSamples.toLong() * dstSr) / srcSr).toInt().coerceAtLeast(1)
        val out = ByteArray(dstSamples * 2 * dstCh)
        val srcBb = ByteBuffer.wrap(src).order(ByteOrder.LITTLE_ENDIAN)
        val dstBb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        val ratio = srcSamples.toDouble() / dstSamples.toDouble()
        for (i in 0 until dstSamples) {
            val srcIdx = (i * ratio).toInt().coerceIn(0, srcSamples - 1)
            val s = srcBb.getShort(srcIdx * 2 * srcCh)
            for (c in 0 until dstCh) dstBb.putShort(s)
        }
        return out
    }

    private fun decodeAudioToPcm(audioFile: File): Triple<ByteArray, Int, Int>? {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        return try {
            extractor.setDataSource(audioFile.absolutePath)
            var trackIdx = -1
            var fmt: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIdx = i; fmt = f; break
                }
            }
            if (trackIdx < 0 || fmt == null) return null
            extractor.selectTrack(trackIdx)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: return null
            val sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(fmt, null, null, 0)
            decoder.start()

            val out = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false
            while (!outDone) {
                if (!inDone) {
                    val inIdx = decoder.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val inBuf = decoder.getInputBuffer(inIdx)!!
                        val sz = extractor.readSampleData(inBuf, 0)
                        if (sz < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, sz, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = decoder.dequeueOutputBuffer(info, 10_000L)
                if (outIdx >= 0) {
                    val outBuf = decoder.getOutputBuffer(outIdx)
                    if (outBuf != null && info.size > 0) {
                        val arr = ByteArray(info.size)
                        outBuf.position(info.offset)
                        outBuf.get(arr)
                        out.write(arr)
                    }
                    decoder.releaseOutputBuffer(outIdx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true
                }
            }
            Triple(out.toByteArray(), sr, ch)
        } catch (_: Exception) {
            null
        } finally {
            try { decoder?.stop(); decoder?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    private fun encodePcmToM4a(pcm: ByteArray, sampleRate: Int, channelCount: Int, outFile: File): Boolean {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var aTrack = -1
        var started = false
        val info = MediaCodec.BufferInfo()
        var offset = 0
        val bytesPerSample = 2 * channelCount
        var totalSamples = 0L
        var inDone = false
        var outDone = false
        return try {
            while (!outDone) {
                if (!inDone) {
                    val inIdx = encoder.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val inBuf = encoder.getInputBuffer(inIdx)!!
                        inBuf.clear()
                        val remaining = pcm.size - offset
                        if (remaining <= 0) {
                            encoder.queueInputBuffer(inIdx, 0, 0, (totalSamples * 1_000_000L) / sampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            val toWrite = minOf(remaining, inBuf.capacity())
                            inBuf.put(pcm, offset, toWrite)
                            val pts = (totalSamples * 1_000_000L) / sampleRate
                            encoder.queueInputBuffer(inIdx, 0, toWrite, pts, 0)
                            offset += toWrite
                            totalSamples += toWrite / bytesPerSample
                        }
                    }
                }
                val outIdx = encoder.dequeueOutputBuffer(info, 10_000L)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!started) {
                            aTrack = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            started = true
                        }
                    }
                    outIdx >= 0 -> {
                        val outBuf = encoder.getOutputBuffer(outIdx)
                        if (outBuf != null && info.size > 0 && started && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            muxer.writeSampleData(aTrack, outBuf, info)
                        }
                        encoder.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true
                    }
                }
            }
            true
        } catch (_: Exception) {
            false
        } finally {
            try { encoder.stop(); encoder.release() } catch (_: Exception) {}
            try { if (started) muxer.stop(); muxer.release() } catch (_: Exception) {}
        }
    }

    private fun muxVideoAndAac(videoFile: File, aacFile: File, outFile: File): Boolean {
        val vExtractor = MediaExtractor()
        val aExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        return try {
            vExtractor.setDataSource(videoFile.absolutePath)
            var vTrack = -1
            for (i in 0 until vExtractor.trackCount) {
                if (vExtractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    vTrack = i; break
                }
            }
            aExtractor.setDataSource(aacFile.absolutePath)
            var aTrack = -1
            for (i in 0 until aExtractor.trackCount) {
                if (aExtractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    aTrack = i; break
                }
            }
            if (vTrack < 0 || aTrack < 0) return false
            vExtractor.selectTrack(vTrack)
            aExtractor.selectTrack(aTrack)

            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outV = muxer.addTrack(vExtractor.getTrackFormat(vTrack))
            val outA = muxer.addTrack(aExtractor.getTrackFormat(aTrack))
            muxer.start()

            val buf = ByteBuffer.allocate(256 * 1024)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val sz = vExtractor.readSampleData(buf, 0)
                if (sz < 0) break
                info.offset = 0; info.size = sz
                info.presentationTimeUs = vExtractor.sampleTime
                info.flags = vExtractor.sampleFlags
                muxer.writeSampleData(outV, buf, info)
                vExtractor.advance()
            }
            while (true) {
                val sz = aExtractor.readSampleData(buf, 0)
                if (sz < 0) break
                info.offset = 0; info.size = sz
                info.presentationTimeUs = aExtractor.sampleTime
                info.flags = aExtractor.sampleFlags
                muxer.writeSampleData(outA, buf, info)
                aExtractor.advance()
            }
            true
        } catch (_: Exception) {
            false
        } finally {
            try { vExtractor.release() } catch (_: Exception) {}
            try { aExtractor.release() } catch (_: Exception) {}
            try { muxer?.stop(); muxer?.release() } catch (_: Exception) {}
        }
    }

    fun saveVideoToGallery(context: Context, videoPath: String): String? {
        return try {
            val src = File(videoPath)
            if (!src.exists()) return null
            val fileName = "Titan_Story_${System.currentTimeMillis()}.mp4"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/TitanIDE")
                }
                val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(src).use { it.copyTo(out) }
                }
                fileName
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "TitanIDE")
                dir.mkdirs()
                val dst = File(dir, fileName)
                src.copyTo(dst, overwrite = true)
                fileName
            }
        } catch (_: Exception) {
            null
        }
    }
}
