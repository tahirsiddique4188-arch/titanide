package com.titan.ide.agent

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import com.example.services.TitanAccessibilityService
import com.titan.ide.network.TitanDirectEngine
import com.titan.ide.network.TitanWebSearchGrounding
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.TimeUnit

/**
 * TitanBackgroundOrchestrator — Antigravity-Style Non-Blocking Multi-Step Background Task Engine
 *
 * Runs complex long tasks in the background while the user continues talking freely in Live Voice:
 * 1. Generate AI Image + Share/Send to WhatsApp Friend (with Accessibility auto-select & send).
 * 2. Live Breaking News / Weather Fetch & Spoken Bengali/Hindi/English Briefing.
 * 3. Live status streaming via StateFlow so the Compact Floating Live Bar shows real-time progress.
 */
object TitanBackgroundOrchestrator {

    private const val TAG = "TitanBgOrchestrator"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val _taskStatus = MutableStateFlow("")
    val taskStatus: StateFlow<String> = _taskStatus.asStateFlow()

    @Volatile
    private var activeJob: Job? = null

    fun stopActiveTask() {
        try { activeJob?.cancel() } catch (_: Exception) {}
        activeJob = null
        TitanDynamicAgentLoop.stopAssistantMode()
        _taskStatus.value = ""
    }

    /**
     * Multi-Step Background Task:
     * Step 1: Generate AI image using TitanDirectEngine
     * Step 2: Save image cleanly to cache & Gallery
     * Step 3: Launch WhatsApp image share Intent + dispatch Accessibility sub-agent to select recipient & send!
     */
    fun runImageAndWhatsAppShare(
        context: Context,
        imagePrompt: String,
        recipientName: String,
        onChatPost: (String) -> Unit = {},
        onSpokenUpdate: (String) -> Unit = {}
    ) {
        val appCtx = context.applicationContext
        activeJob?.cancel()
        activeJob = scope.launch {
            try {
                _taskStatus.value = "Step 1/3: Generating AI image..."
                val imageUrl = TitanDirectEngine.generateImageDirect(appCtx, imagePrompt)
                if (imageUrl.isNullOrBlank()) {
                    _taskStatus.value = ""
                    val failMsg = "ইমেজ তৈরি করা যায়নি, আবার চেষ্টা করো।"
                    onChatPost("⚠️ Image generation failed for: $imagePrompt")
                    onSpokenUpdate(failMsg)
                    return@launch
                }

                _taskStatus.value = "Step 2/3: Downloading & saving image..."
                val imageFile = downloadImageToFile(appCtx, imageUrl)
                if (imageFile == null || !imageFile.exists()) {
                    _taskStatus.value = ""
                    onChatPost("![Generated Image]($imageUrl)\n\n⚠️ Could not download image locally for WhatsApp share.")
                    onSpokenUpdate("ইমেজটা চ্যাটে তৈরি হয়েছে, কিন্তু শেয়ারের জন্য ডাউনলোড করা যায়নি।")
                    return@launch
                }

                saveImageToGallery(appCtx, imageFile)
                onChatPost("![Generated Image]($imageUrl)\n\n✅ **Image ready** — opening WhatsApp${if (recipientName.isNotBlank()) " for **$recipientName**" else ""}...")

                _taskStatus.value = if (recipientName.isNotBlank()) {
                    "Step 3/3: Sending to $recipientName on WhatsApp..."
                } else {
                    "Step 3/3: Opening WhatsApp share..."
                }

                val shareUri = FileProvider.getUriForFile(
                    appCtx,
                    "${appCtx.packageName}.provider",
                    imageFile
                )

                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, shareUri)
                    setPackage("com.whatsapp")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                try {
                    appCtx.startActivity(sendIntent)
                } catch (_: Exception) {
                    // Fallback to generic chooser if WhatsApp is not installed
                    val chooser = Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "image/jpeg"
                            putExtra(Intent.EXTRA_STREAM, shareUri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        },
                        "Share Image"
                    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    appCtx.startActivity(chooser)
                }

                // If Accessibility is active and user named a specific friend, auto-search & send!
                if (recipientName.isNotBlank() && TitanAccessibilityService.isServiceRunning()) {
                    delay(1400L)
                    TitanDynamicAgentLoop.dispatchBackgroundSubAgent(
                        context = appCtx,
                        userGoal = "On the open WhatsApp share screen, tap Search, type \"$recipientName\", tap the matching contact \"$recipientName\", then tap the green Send arrow button, and finally tap the Send button on the image preview.",
                        onCompleted = { summary ->
                            _taskStatus.value = ""
                            onSpokenUpdate("হয়ে গেছে ভাই! $recipientName-কে ইমেজটা WhatsApp-এ পাঠিয়ে দিয়েছি।")
                            onChatPost("✅ WhatsApp send status: $summary")
                        }
                    )
                } else {
                    delay(1200L)
                    _taskStatus.value = ""
                    val msg = if (recipientName.isNotBlank()) {
                        "ইমেজটা বানিয়ে WhatsApp ওপেন করে দিয়েছি—$recipientName-কে সিলেক্ট করে Send চেপে দাও।"
                    } else {
                        "ইমেজটা বানিয়ে WhatsApp শেয়ার ওপেন করে দিয়েছি!"
                    }
                    onSpokenUpdate(msg)
                }
            } catch (e: Exception) {
                Log.e(TAG, "runImageAndWhatsAppShare error", e)
                _taskStatus.value = ""
                onSpokenUpdate("ব্যাকগ্রাউন্ড টাস্কে একটু সমস্যা হয়েছে।")
            }
        }
    }

    /**
     * Background Live News & Weather Briefing Task:
     * Fetches real-time RSS headlines via TitanWebSearchGrounding, summarizes in user's language,
     * posts to chat, and speaks smoothly in Live Voice.
     */
    fun runLiveNewsOrWeatherBriefing(
        context: Context,
        topicQuery: String,
        preferBengali: Boolean,
        onChatPost: (String) -> Unit = {},
        onSpokenUpdate: (String) -> Unit = {}
    ) {
        val appCtx = context.applicationContext
        activeJob?.cancel()
        activeJob = scope.launch {
            try {
                _taskStatus.value = "Fetching live news: ${topicQuery.take(28)}..."
                val sources = TitanWebSearchGrounding.search(topicQuery, maxResults = 5)
                val liveWebData = TitanWebSearchGrounding.buildGroundingPromptBlock(sources)
                if (liveWebData.isBlank()) {
                    _taskStatus.value = ""
                    onSpokenUpdate(if (preferBengali) "এখন লাইভ নিউজ পাওয়া যাচ্ছে না, একটু পরে আবার বলো।" else "Could not fetch live news right now.")
                    return@launch
                }

                _taskStatus.value = "Summarizing latest headlines..."
                val langRule = if (preferBengali) {
                    "Summarize the top 3-4 most important live news items in natural, crisp Bengali (no markdown asterisks, max 5 short spoken sentences)."
                } else {
                    "Summarize the top 3-4 most important live news items in natural, crisp spoken English/Hindi matching the query (no markdown asterisks, max 5 short sentences)."
                }
                val summary = TitanDirectEngine.executePrompt(
                    context = appCtx,
                    prompt = "Query: $topicQuery\n\nLive Web Results:\n$liveWebData\n\n$langRule"
                ).trim()

                _taskStatus.value = ""
                if (summary.isNotBlank()) {
                    onChatPost("📰 **Live News Briefing**\n\n$summary")
                    onSpokenUpdate(summary)
                }
            } catch (e: Exception) {
                Log.e(TAG, "runLiveNewsOrWeatherBriefing error", e)
                _taskStatus.value = ""
            }
        }
    }

    private fun downloadImageToFile(context: Context, url: String): File? {
        return try {
            val req = Request.Builder().url(url).get().build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val bytes = resp.body?.bytes() ?: return null
                if (bytes.size < 1000) return null
                val out = File(context.cacheDir, "titan_wa_img_${System.currentTimeMillis()}.jpg")
                out.writeBytes(bytes)
                out
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun saveImageToGallery(context: Context, src: File) {
        try {
            val fileName = "Titan_AI_${System.currentTimeMillis()}.jpg"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/TitanIDE")
                }
                val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(src).use { it.copyTo(out) }
                }
            }
        } catch (_: Exception) {}
    }
}
