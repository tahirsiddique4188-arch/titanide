package com.example.ui.components

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.model.VoicePersona
import com.example.data.model.VoicePersonas
import com.titan.ide.media.video.TitanRemotionExporter
import com.titan.ide.media.video.TitanStoryDirectorEngine
import com.titan.ide.media.video.TitanStoryDirectorEngine.StoryVideoResult
import com.titan.ide.media.video.TitanStoryDirectorEngine.VideoAspect
import com.titan.ide.media.video.TitanStoryDirectorEngine.VideoTheme
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TitanVideoStudioScreen(
    defaultVoicePersona: VoicePersona = VoicePersonas.DEFAULT,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var storyInput by remember { mutableStateOf("") }
    var selectedAspect by remember { mutableStateOf(VideoAspect.PORTRAIT_9_16) }
    var selectedTheme by remember { mutableStateOf(VideoTheme.OBSIDIAN) }
    var selectedVoice by remember { mutableStateOf(defaultVoicePersona) }
    var showVoiceDropdown by remember { mutableStateOf(false) }

    var isGenerating by remember { mutableStateOf(false) }
    var progressText by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }
    var videoResult by remember { mutableStateOf<StoryVideoResult?>(null) }
    var isExportingRemotion by remember { mutableStateOf(false) }
    var remotionSavedPath by remember { mutableStateOf<String?>(null) }
    var videoViewRef by remember { mutableStateOf<VideoView?>(null) }
    var isPlaying by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(listOf(Color(0xFFEC4899), Color(0xFF8B5CF6)))
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Videocam,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Make Storytelling Video",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "Paste ChatGPT/Gemini script -> Auto Character + Diagrams",
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 1. Prompt / Script Paste Box
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "STORY OR PROMPT",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TextButton(
                                    onClick = {
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                        val clipText = cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                                        if (clipText.isNotBlank()) {
                                            storyInput = clipText
                                        } else {
                                            Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Paste", fontSize = 12.sp)
                                }
                                TextButton(
                                    onClick = {
                                        storyInput = "আপনি কি জানেন গুগল ম্যাপ কীভাবে ২ কিমি আগেই ট্র্যাফিক জ্যাম বুঝে ফেলে? প্রথমে হাজার হাজার ফোনের GPS সিগন্যাল সার্ভারে যায়। তারপর AI গাড়ির স্পিড তুলনা করে রাস্তা সবুজ, হলুদ নাকি লাল তা হিসাব করে! মাত্র কয়েক সেকেন্ডেই আপনি সবচেয়ে ফাস্ট রুট পেয়ে যান।"
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text("Sample", fontSize = 12.sp)
                                }
                                if (storyInput.isNotBlank()) {
                                    TextButton(
                                        onClick = { storyInput = "" },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Text("Clear", fontSize = 12.sp, color = Color(0xFFEF4444))
                                    }
                                }
                            }
                        }

                        OutlinedTextField(
                            value = storyInput,
                            onValueChange = { storyInput = it },
                            placeholder = {
                                Text(
                                    "ChatGPT বা Gemini থেকে বানানো গল্প, স্ক্রিপ্ট বা টপিক এখানে পেস্ট করে Send চাপুন...",
                                    fontSize = 13.5.sp
                                )
                            },
                            minLines = 5,
                            maxLines = 10,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // 2. Format & Theme Chips
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "FORMAT & STYLE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        VideoAspect.values().forEach { aspect ->
                            val selected = selectedAspect == aspect
                            FilterChip(
                                selected = selected,
                                onClick = { selectedAspect = aspect },
                                label = { Text(aspect.label, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        VideoTheme.values().forEach { theme ->
                            FilterChip(
                                selected = selectedTheme == theme,
                                onClick = { selectedTheme = theme },
                                label = { Text(theme.label, fontSize = 12.sp) }
                            )
                        }
                    }

                    // Voice Persona Selector
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { showVoiceDropdown = true },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Voice: ${selectedVoice.name} (${selectedVoice.gender})",
                                fontSize = 13.sp,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                        }
                        DropdownMenu(
                            expanded = showVoiceDropdown,
                            onDismissRequest = { showVoiceDropdown = false }
                        ) {
                            VoicePersonas.ALL_PERSONAS.forEach { persona ->
                                DropdownMenuItem(
                                    text = { Text("${persona.name} • ${persona.characterTag}", fontSize = 13.sp) },
                                    onClick = {
                                        selectedVoice = persona
                                        showVoiceDropdown = false
                                    }
                                )
                            }
                        }
                    }
                }

                // 3. Primary Send & Generate Button
                Button(
                    onClick = {
                        val trimmed = storyInput.trim()
                        if (trimmed.isBlank()) {
                            Toast.makeText(context, "আগে গল্প বা প্রম্পট পেস্ট করুন", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        isGenerating = true
                        errorText = null
                        remotionSavedPath = null
                        videoResult = null
                        progressText = "Starting Titan Video Director..."
                        scope.launch {
                            try {
                                val res = TitanStoryDirectorEngine.generateStoryExplainerVideo(
                                    context = context,
                                    rawInput = trimmed,
                                    aspect = selectedAspect,
                                    theme = selectedTheme,
                                    voiceId = selectedVoice.id,
                                    onProgress = { msg -> progressText = msg }
                                )
                                videoResult = res
                                progressText = "Video Ready (${res.scenes.size} scenes)"
                            } catch (e: Exception) {
                                errorText = "Video generation failed: ${e.message}"
                            } finally {
                                isGenerating = false
                            }
                        }
                    },
                    enabled = !isGenerating,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEC4899)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    if (isGenerating) {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 2.5.dp,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(progressText.ifBlank { "Generating..." }, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    } else {
                        Icon(Icons.Default.Send, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Send & Make Video", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }

                if (errorText != null) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFFEF4444).copy(alpha = 0.14f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = errorText ?: "",
                            color = Color(0xFFEF4444),
                            fontSize = 12.5.sp,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                // 4. Generated Video Player + Save MP4 + Export Remotion ZIP
                videoResult?.let { result ->
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, Color(0xFF8B5CF6).copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "VIDEO PREVIEW (${result.scenes.size} SCENES)",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF8B5CF6)
                                )
                                Text(
                                    text = "${result.totalDurationMs / 1000}s • ${result.aspect.label}",
                                    fontSize = 11.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            val playerHeight = if (result.aspect == VideoAspect.LANDSCAPE_16_9) 225.dp else 430.dp
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(playerHeight)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color.Black),
                                contentAlignment = Alignment.Center
                            ) {
                                AndroidView(
                                    factory = { ctx ->
                                        VideoView(ctx).apply {
                                            val mc = MediaController(ctx)
                                            mc.setAnchorView(this)
                                            setMediaController(mc)
                                            setVideoURI(Uri.fromFile(result.videoFile))
                                            setOnPreparedListener { mp ->
                                                mp.isLooping = false
                                                start()
                                                isPlaying = true
                                            }
                                            setOnCompletionListener {
                                                isPlaying = false
                                            }
                                            videoViewRef = this
                                        }
                                    },
                                    update = { vv ->
                                        videoViewRef = vv
                                    },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }

                            // Playback controls row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        val vv = videoViewRef
                                        if (vv != null) {
                                            if (vv.isPlaying) {
                                                vv.pause()
                                                isPlaying = false
                                            } else {
                                                vv.start()
                                                isPlaying = true
                                            }
                                        }
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        modifier = Modifier.size(17.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(if (isPlaying) "Pause" else "Play", fontSize = 13.sp)
                                }

                                OutlinedButton(
                                    onClick = {
                                        videoViewRef?.let { vv ->
                                            vv.seekTo(0)
                                            vv.start()
                                            isPlaying = true
                                        }
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Replay, contentDescription = null, modifier = Modifier.size(17.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Replay", fontSize = 13.sp)
                                }
                            }

                            // Save MP4 & Export Remotion ZIP (Option #1 + Option #2!)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        val saved = TitanStoryDirectorEngine.saveVideoToGallery(context, result.videoFile.absolutePath)
                                        if (saved != null) {
                                            Toast.makeText(context, "Saved to Movies/TitanIDE/$saved", Toast.LENGTH_LONG).show()
                                        } else {
                                            Toast.makeText(context, "Save failed", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Save MP4", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                }

                                Button(
                                    onClick = {
                                        isExportingRemotion = true
                                        scope.launch {
                                            try {
                                                val exp = TitanRemotionExporter.exportRemotionProjectZip(
                                                    context = context,
                                                    scenes = result.scenes,
                                                    aspect = result.aspect,
                                                    theme = result.theme
                                                )
                                                remotionSavedPath = exp.savedDisplayName
                                                Toast.makeText(context, "Remotion ZIP saved: ${exp.savedDisplayName}", Toast.LENGTH_LONG).show()
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                            } finally {
                                                isExportingRemotion = false
                                            }
                                        }
                                    },
                                    enabled = !isExportingRemotion,
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Code, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        if (isExportingRemotion) "Exporting..." else "Remotion ZIP",
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                            }

                            if (remotionSavedPath != null) {
                                Text(
                                    text = "Remotion Project Saved: $remotionSavedPath",
                                    fontSize = 11.5.sp,
                                    color = Color(0xFF38BDF8),
                                    fontWeight = FontWeight.Medium
                                )
                            }

                            // Scene Breakdown Preview
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                            Text(
                                text = "AI SCENE & MOOD BREAKDOWN",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            result.scenes.forEach { sc ->
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.background,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = "${sc.index + 1}. ${sc.title}",
                                                fontSize = 12.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onBackground,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Text(
                                                text = "${sc.mood.name} • ${sc.diagramType.name}",
                                                fontSize = 10.5.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color(sc.accentColor)
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(3.dp))
                                        Text(
                                            text = sc.narration,
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
