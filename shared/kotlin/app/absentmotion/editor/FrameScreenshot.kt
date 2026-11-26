package app.absentmotion.editor

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import app.absentmotion.export.MediaStoreExport
import app.absentmotion.export.stillFits
import app.absentmotion.library.MediaKeeper
import app.absentmotion.media.mediaDeclaration
import app.absentmotion.model.Layer
import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val SCREENSHOT_UNDO_MS = 10_000L

private val RawParts = setOf("speedMap", "timeRemap")
private val Dropped = setOf("blending", "parent", "clippingMask", "hidden", "pathLayer", "link")

data class RawFrame(val scene: Scene, val width: Int, val height: Int)

fun Scene.rawFrame(layer: Layer, media: MediaDeclaration, fits: (Int, Int) -> Boolean = { w, h -> stillFits(w, h) }): RawFrame? {
    val turned = media.orientation == 90 || media.orientation == 270
    var w = if (turned) media.height else media.width
    var h = if (turned) media.width else media.height
    if (w <= 0 || h <= 0) return null
    while (!fits(w, h) && w > 1 && h > 1) { w = (w / 2).coerceAtLeast(1); h = (h / 2).coerceAtLeast(1) }
    val blank = Scene(Scene.create("frame", w, h, fps, 0).node.with("totalTime", "$totalTime"))
    val placed = blank.videoLayer(media, 0) ?: return null
    val own = layer.node
    val attrs = LinkedHashMap(own.attrs.filterKeys { it !in Dropped })
    attrs["mediaFillMode"] = "fill"
    attrs["s"] = ".rect"
    val node = own.copy(attrs = attrs, children = placed.node.children + own.children.filter { it.name in RawParts })
    return RawFrame(blank.add(Layer(node), listOf(media)), w, h)
}

fun EditorState.canScreenshot(layer: Layer?): Boolean =
    layer?.mediaFill?.video == true && timeMs >= layer.startTime && timeMs < layer.endTime

suspend fun EditorState.screenshotFrame(context: Context, scope: CoroutineScope): Boolean {
    val layer = selected?.takeIf { canScreenshot(it) } ?: return false
    val media = scene.media.firstOrNull { it.uri == layer.mediaFill?.uri } ?: return false
    val raw = scene.rawFrame(layer, media) ?: return false
    val png = File(context.cacheDir, "screenshot-${System.nanoTime()}.png")
    try {
        if (!engine.stillPng(raw.scene.toXml(), timeMs, raw.width, raw.height, png)) return false
        val name = "Screenshot " + SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.ROOT).format(Date()) + ".png"
        val gallery = MediaStoreExport(context)
        val saved = withContext(Dispatchers.IO) { gallery.save(null, name, "image/png") { out -> png.inputStream().use { it.copyTo(out) } } }
        val picture = mediaDeclaration(context, saved) ?: run { withContext(Dispatchers.IO) { gallery.delete(saved) }; return false }
        val own = withContext(Dispatchers.IO) {
            runCatching { MediaKeeper(store, { uri -> context.contentResolver.openInputStream(Uri.parse(uri)) }).keep(projectId, picture, scene) }.getOrNull()
        }
        val kept = own?.takeIf { store.inMediaFolder(it.uri) } ?: keepCopy(context, picture)
        addImage(kept)
        val added = selectedId ?: return true
        if (kept.uri != saved.toString()) scope.launch { dropIfRemoved(added, saved, gallery) }
        return true
    } finally {
        withContext(Dispatchers.IO) { png.delete() }
    }
}

private suspend fun EditorState.dropIfRemoved(id: Long, saved: Uri, store: MediaStoreExport) {
    val gone = withTimeoutOrNull(SCREENSHOT_UNDO_MS) { snapshotFlow { !project.node.holdsLayer(id) }.first { it } } == true
    if (gone) withContext(Dispatchers.IO) { runCatching { store.delete(saved) } }
}

internal fun XmlNode.holdsLayer(id: Long): Boolean = children.any { it.name in Scene.LAYER_TAGS && it["id"]?.toLongOrNull() == id || it.holdsLayer(id) }

/** One frame grabbed in frame-capture mode, waiting in the tray until the person saves them all. */
class FrameShot(val file: File, val timeMs: Int, val thumb: android.graphics.Bitmap?)

/**
 * Frame-capture mode: grab several frames from a video one by one, review them in a tray,
 * then save them all at once. Saving adds them to the timeline back to back in a single undo step.
 */
class FrameCaptures {
    val shots = androidx.compose.runtime.mutableStateListOf<FrameShot>()
    var open by androidx.compose.runtime.mutableStateOf(false)
    var busy by androidx.compose.runtime.mutableStateOf(false)

    fun remove(shot: FrameShot) {
        shots.remove(shot)
        shot.file.delete()
    }

    fun discard() {
        shots.forEach { it.file.delete() }
        shots.clear()
    }
}

/** Renders the selected video's frame at the playhead into a cache file and puts it in the tray. */
suspend fun EditorState.captureFrame(context: Context): FrameShot? {
    val layer = selected?.takeIf { canScreenshot(it) } ?: return null
    val media = scene.media.firstOrNull { it.uri == layer.mediaFill?.uri } ?: return null
    val raw = scene.rawFrame(layer, media) ?: return null
    val png = File(context.cacheDir, "framecap-${System.nanoTime()}.png")
    val at = timeMs
    if (!engine.stillPng(raw.scene.toXml(), at, raw.width, raw.height, png)) {
        withContext(Dispatchers.IO) { png.delete() }
        return null
    }
    val thumb = withContext(Dispatchers.IO) {
        runCatching {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(png.path, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 160) sample *= 2
            android.graphics.BitmapFactory.decodeFile(png.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
    }
    return FrameShot(png, at, thumb).also { frameCaptures.shots += it }
}

/**
 * Saves every shot to the gallery and adds them to the project one after another from the playhead,
 * as one edit. Returns how many were added; the tray is emptied of the ones that were saved.
 */
suspend fun EditorState.saveFrameShots(context: Context): Int {
    val shots = frameCaptures.shots.toList()
    if (shots.isEmpty()) return 0
    val gallery = MediaStoreExport(context)
    val stamp = SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.ROOT).format(Date())
    val kept = mutableListOf<MediaDeclaration>()
    for ((i, shot) in shots.withIndex()) {
        val name = "Frame $stamp-${i + 1}.png"
        val saved = runCatching {
            withContext(Dispatchers.IO) { gallery.save(null, name, "image/png") { out -> shot.file.inputStream().use { it.copyTo(out) } } }
        }.getOrNull() ?: continue
        val picture = mediaDeclaration(context, saved)
        if (picture == null) {
            withContext(Dispatchers.IO) { runCatching { gallery.delete(saved) } }
            continue
        }
        val own = withContext(Dispatchers.IO) {
            runCatching { MediaKeeper(store, { uri -> context.contentResolver.openInputStream(Uri.parse(uri)) }).keep(projectId, picture, scene) }.getOrNull()
        }
        kept += own?.takeIf { store.inMediaFolder(it.uri) } ?: keepCopy(context, picture)
        frameCaptures.remove(shot)
    }
    if (kept.isEmpty()) return 0
    val seconds = settings.defaultLayerDuration
    val placed = mutableListOf<Pair<Layer, MediaDeclaration>>()
    var cursor = frame
    for (media in kept) {
        val layer = scene.imageLayer(media, cursor, seconds) ?: continue
        placed += layer to media
        cursor = scene.frameAt(layer.endTime) + 1
    }
    if (placed.isEmpty()) return 0
    edit { current -> placed.fold(current) { acc, (layer, media) -> acc.add(layer, listOf(media)) } }
    selectedId = placed.first().first.id
    panel = EditorPanel.Tools
    return placed.size
}
