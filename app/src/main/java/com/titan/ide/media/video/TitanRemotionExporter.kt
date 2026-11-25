package com.titan.ide.media.video

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * TitanRemotionExporter — 1-Tap Remotion Pro Project ZIP Exporter
 *
 * Exports a complete, ready-to-render Remotion (React + TypeScript + SVG) project ZIP
 * with synced SRT subtitles, AI-directed scenes.json, animated robot mascot & diagram components,
 * saved directly to Downloads/TitanIDE/ for 1080p60 PC or GitHub Actions rendering.
 */
object TitanRemotionExporter {

    data class ExportResult(
        val zipFile: File,
        val savedDisplayName: String
    )

    suspend fun exportRemotionProjectZip(
        context: Context,
        scenes: List<TitanStoryDirectorEngine.StoryScene>,
        aspect: TitanStoryDirectorEngine.VideoAspect,
        theme: TitanStoryDirectorEngine.VideoTheme
    ): ExportResult = withContext(Dispatchers.IO) {
        val fps = 30
        val width = if (aspect == TitanStoryDirectorEngine.VideoAspect.LANDSCAPE_16_9) 1920 else 1080
        val height = if (aspect == TitanStoryDirectorEngine.VideoAspect.LANDSCAPE_16_9) 1080 else 1920
        val totalDurationMs = scenes.sumOf { it.durationMs }.coerceAtLeast(3000L)
        val totalFrames = ((totalDurationMs / 1000.0) * fps).toInt().coerceAtLeast(90)

        val stamp = System.currentTimeMillis()
        val fileName = "Titan_Remotion_Project_$stamp.zip"
        val cacheZip = File(context.cacheDir, fileName)
        if (cacheZip.exists()) cacheZip.delete()

        ZipOutputStream(FileOutputStream(cacheZip)).use { zos ->
            fun addTextFile(path: String, content: String) {
                zos.putNextEntry(ZipEntry(path))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }

            addTextFile("package.json", buildPackageJson())
            addTextFile("tsconfig.json", buildTsConfigJson())
            addTextFile("remotion.config.ts", buildRemotionConfig())
            addTextFile("src/index.ts", "import { registerRoot } from 'remotion';\nimport { RemotionRoot } from './Root';\nregisterRoot(RemotionRoot);\n")
            addTextFile("src/Root.tsx", buildRootTsx(width, height, fps, totalFrames))
            addTextFile("src/scenes.json", buildScenesJson(scenes, theme))
            addTextFile("src/ExplainerVideo.tsx", buildExplainerVideoTsx(theme))
            addTextFile("public/subtitles.srt", buildSrtSubtitles(scenes))
            addTextFile("README.md", buildReadme(fileName, width, height, fps, scenes.size))
        }

        val savedName = saveZipToDownloads(context, cacheZip, fileName) ?: fileName
        ExportResult(cacheZip, savedName)
    }

    private fun saveZipToDownloads(context: Context, srcZip: File, fileName: String): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/TitanIDE")
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(srcZip).use { it.copyTo(out) }
                }
                "Downloads/TitanIDE/$fileName"
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "TitanIDE")
                dir.mkdirs()
                val dst = File(dir, fileName)
                srcZip.copyTo(dst, overwrite = true)
                "Downloads/TitanIDE/$fileName"
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun buildSrtSubtitles(scenes: List<TitanStoryDirectorEngine.StoryScene>): String {
        val sb = StringBuilder()
        var cursorMs = 0L
        scenes.forEachIndexed { idx, scene ->
            val start = formatSrtTime(cursorMs)
            val end = formatSrtTime(cursorMs + scene.durationMs)
            sb.append("${idx + 1}\n$start --> $end\n${scene.narration}\n\n")
            cursorMs += scene.durationMs
        }
        return sb.toString()
    }

    private fun formatSrtTime(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        val rem = ms % 1000
        return String.format("%02d:%02d:%02d,%03d", h, m, s, rem)
    }

    private fun buildScenesJson(
        scenes: List<TitanStoryDirectorEngine.StoryScene>,
        theme: TitanStoryDirectorEngine.VideoTheme
    ): String {
        val arr = JSONArray()
        for (s in scenes) {
            val obj = JSONObject().apply {
                put("index", s.index)
                put("badge", s.badge)
                put("title", s.title)
                put("narration", s.narration)
                put("mood", s.mood.name)
                put("diagramType", s.diagramType.name)
                put("diagramItems", JSONArray(s.diagramItems))
                put("statValue", s.statValue)
                put("accentHex", String.format("#%06X", 0xFFFFFF and s.accentColor))
                put("durationMs", s.durationMs)
            }
            arr.put(obj)
        }
        val root = JSONObject().apply {
            put("theme", theme.label)
            put("scenes", arr)
        }
        return root.toString(2)
    }

    private fun buildPackageJson(): String = """
{
  "name": "titan-remotion-explainer",
  "version": "1.0.0",
  "private": true,
  "scripts": {
    "dev": "remotion studio",
    "render": "remotion render ExplainerVideo out/titan-explainer.mp4"
  },
  "dependencies": {
    "react": "^18.3.1",
    "react-dom": "^18.3.1",
    "remotion": "^4.0.220",
    "@remotion/cli": "^4.0.220"
  },
  "devDependencies": {
    "@types/react": "^18.3.5",
    "typescript": "^5.5.4"
  }
}
""".trimIndent()

    private fun buildTsConfigJson(): String = """
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "ESNext",
    "moduleResolution": "Bundler",
    "jsx": "react-jsx",
    "strict": true,
    "resolveJsonModule": true,
    "esModuleInterop": true,
    "skipLibCheck": true
  },
  "include": ["src"]
}
""".trimIndent()

    private fun buildRemotionConfig(): String = """
import { Config } from '@remotion/cli/config';
Config.setVideoImageFormat('jpeg');
Config.setOverwriteOutput(true);
""".trimIndent()

    private fun buildRootTsx(width: Int, height: Int, fps: Int, totalFrames: Int): String = """
import React from 'react';
import { Composition } from 'remotion';
import { ExplainerVideo } from './ExplainerVideo';

export const RemotionRoot: React.FC = () => {
  return (
    <Composition
      id="ExplainerVideo"
      component={ExplainerVideo}
      durationInFrames={$totalFrames}
      fps={$fps}
      width={$width}
      height={$height}
    />
  );
};
""".trimIndent()

    private fun buildExplainerVideoTsx(theme: TitanStoryDirectorEngine.VideoTheme): String {
        val bg = String.format("#%06X", 0xFFFFFF and theme.bgMid)
        val txt = String.format("#%06X", 0xFFFFFF and theme.primaryText)
        return """
import React from 'react';
import { AbsoluteFill, Sequence, interpolate, spring, useCurrentFrame, useVideoConfig } from 'remotion';
import data from './scenes.json';

type SceneItem = {
  index: number;
  badge: string;
  title: string;
  narration: string;
  mood: string;
  diagramType: string;
  diagramItems: string[];
  statValue: string;
  accentHex: string;
  durationMs: number;
};

const SceneCanvas: React.FC<{ scene: SceneItem }> = ({ scene }) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const pop = spring({ frame, fps, config: { damping: 14, stiffness: 120 } });
  const bobY = Math.sin(frame / 8) * 10;
  const mouthH = 8 + Math.abs(Math.sin(frame / 3)) * 14;
  const words = scene.narration.split(/\s+/);
  const revealed = Math.max(1, Math.floor(interpolate(frame, [4, Math.max(20, (scene.durationMs / 1000) * fps * 0.85)], [1, words.length], { extrapolateRight: 'clamp' })));

  return (
    <AbsoluteFill style={{ backgroundColor: '$bg', color: '$txt', fontFamily: 'Noto Sans Bengali, Inter, sans-serif', padding: 64, justifyContent: 'space-between' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <div style={{ padding: '10px 22px', borderRadius: 999, border: `2px solid ${'$'}{scene.accentHex}`, color: scene.accentHex, fontWeight: 700, fontSize: 22 }}>
          {scene.badge} • {scene.mood}
        </div>
        <div style={{ opacity: 0.65, fontSize: 24 }}>TITAN REMOTION STUDIO</div>
      </div>

      <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 28, transform: `scale(${'$'}{0.92 + pop * 0.08})` }}>
        <svg width="210" height="210" viewBox="-100 -110 200 210" style={{ transform: `translateY(${'$'}{bobY}px)` }}>
          <circle cx="0" cy="0" r="88" fill={scene.accentHex} opacity="0.18" />
          <line x1="0" y1="-64" x2="0" y2="-92" stroke={scene.accentHex} strokeWidth="6" strokeLinecap="round" />
          <circle cx="0" cy="-96" r="10" fill="#ffffff" />
          <rect x="-70" y="-62" width="140" height="128" rx="36" fill={scene.accentHex} />
          <rect x="-52" y="-36" width="104" height="72" rx="22" fill="#0F172A" />
          <circle cx="-22" cy="-12" r="10" fill="#ffffff" />
          <circle cx="22" cy="-12" r="10" fill="#ffffff" />
          <rect x="-16" y={14 - mouthH / 2} width="32" height={mouthH} rx="8" fill={scene.accentHex} />
        </svg>

        <div style={{ width: '100%', padding: 36, borderRadius: 28, background: 'rgba(255,255,255,0.06)', border: `2px solid ${'$'}{scene.accentHex}66` }}>
          <div style={{ color: scene.accentHex, fontSize: 30, fontWeight: 800, marginBottom: 16 }}>{scene.title}</div>
          <div style={{ display: 'flex', gap: 18, flexWrap: 'wrap', marginBottom: 20 }}>
            {scene.diagramItems.map((it, idx) => (
              <div key={idx} style={{ flex: 1, minWidth: 160, padding: '18px 20px', borderRadius: 16, background: `${'$'}{scene.accentHex}22`, border: `1.5px solid ${'$'}{scene.accentHex}`, fontSize: 22, fontWeight: 700, textAlign: 'center' }}>
                {idx + 1}. {it}
              </div>
            ))}
          </div>
          <div style={{ fontSize: 38, fontWeight: 700, lineHeight: 1.38 }}>{words.slice(0, revealed).join(' ')}</div>
        </div>
      </div>

      <div style={{ fontSize: 20, opacity: 0.5, textAlign: 'center' }}>Generated by Titan IDE Storytelling Studio</div>
    </AbsoluteFill>
  );
};

export const ExplainerVideo: React.FC = () => {
  const { fps } = useVideoConfig();
  let offset = 0;
  return (
    <AbsoluteFill>
      {(data.scenes as SceneItem[]).map((scene) => {
        const durFrames = Math.max(30, Math.round((scene.durationMs / 1000) * fps));
        const from = offset;
        offset += durFrames;
        return (
          <Sequence key={scene.index} from={from} durationInFrames={durFrames}>
            <SceneCanvas scene={scene} />
          </Sequence>
        );
      })}
    </AbsoluteFill>
  );
};
""".trimIndent()
    }

    private fun buildReadme(zipName: String, width: Int, height: Int, fps: Int, sceneCount: Int): String = """
# Titan IDE — Remotion Pro Explainer Project

- **Package**: `$zipName`
- **Resolution**: `${width}x${height}` @ `${fps} FPS`
- **Scenes**: `$sceneCount` AI-directed scenes + synced `public/subtitles.srt`

## Quick Render (PC / GitHub Actions)
```bash
npm install
npm run dev      # Preview in Remotion Studio
npm run render   # Render 1080p MP4 to out/titan-explainer.mp4
```
""".trimIndent()
}
