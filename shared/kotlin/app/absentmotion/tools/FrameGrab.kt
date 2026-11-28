package app.absentmotion.tools

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import app.absentmotion.export.MediaStoreExport
import app.absentmotion.library.openableUri
import app.absentmotion.platform.scaledFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class FrameFormat(val label: String, val mime: String, val extension: String, val compress: Bitmap.CompressFormat, val quality: Int) {
    Jpg("JPG", "image/jpeg", "jpg", Bitmap.CompressFormat.JPEG, 95),
    Png("PNG", "image/png", "png", Bitmap.CompressFormat.PNG, 100),
}

fun frameStepUs(fps: Float): Long = (1_000_000.0 / fps.takeIf { it in 1f..240f }.let { it ?: 30f }).toLong().coerceAtLeast(1)

fun frameSide(width: Int, height: Int): Int = maxOf(width, height, 1).coerceAtMost(memorySide().coerceAtLeast(720))

suspend fun saveFrames(
    context: Context, uri: Uri, timesUs: List<Long>, side: Int, format: FrameFormat, progress: (Int) -> Unit,
): Int = withContext(Dispatchers.IO) {
    val gallery = MediaStoreExport(context)
    val stamp = SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.ROOT).format(Date())
    val retriever = MediaMetadataRetriever()
    var saved = 0
    try {
        retriever.setDataSource(context, Uri.parse(openableUri(uri.toString())))
        for ((i, time) in timesUs.withIndex()) {
            currentCoroutineContext().ensureActive()
            withContext(Dispatchers.Main) { progress(i + 1) }
            val frame = runCatching { retriever.scaledFrame(time, MediaMetadataRetriever.OPTION_CLOSEST, side, side) }.getOrNull() ?: continue
            try {
                gallery.save(null, "Frame $stamp-${i + 1}.${format.extension}", format.mime) { out -> frame.compress(format.compress, format.quality, out) }
                saved++
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
            } finally {
                frame.recycle()
            }
        }
    } finally {
        retriever.release()
    }
    saved
}
