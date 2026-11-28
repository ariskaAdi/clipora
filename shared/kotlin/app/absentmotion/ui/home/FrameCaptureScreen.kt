package app.absentmotion.ui.home

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.i18n.Ltr
import app.absentmotion.platform.LocalContext
import app.absentmotion.platform.rememberStorageAccess
import app.absentmotion.tools.FrameFormat
import app.absentmotion.tools.clock
import app.absentmotion.tools.frameSide
import app.absentmotion.tools.frameStepUs
import app.absentmotion.tools.saveFrames
import app.absentmotion.tools.videoStills
import app.absentmotion.ui.export.ExportIcons
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private class Shot(val timeUs: Long, val thumb: ImageBitmap?)

private const val STRIP_FRAMES = 8

@Composable
internal fun FrameCaptureScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val storage = rememberStorageAccess()
    val shots = remember { mutableStateListOf<Shot>() }
    var asking by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var format by remember { mutableStateOf(FrameFormat.Jpg) }
    var at by remember { mutableStateOf(0L) }
    val pick = rememberVideoPick(null) { shots.clear(); at = 0L }
    val picked = pick.picked
    val source = picked?.source
    val duration = source?.durationUs ?: 0L
    val step = frameStepUs(source?.fps ?: 30f)

    fun leave() {
        when {
            saving != null -> job?.cancel()
            shots.isNotEmpty() -> asking = true
            else -> onClose()
        }
    }

    fun capture() {
        val media = picked ?: return
        val time = at.coerceIn(0, (duration - 1).coerceAtLeast(0))
        if (shots.any { it.timeUs == time }) return
        scope.launch {
            val thumb = videoStills(context, media.uri, listOf(time), 240, exact = true).firstOrNull()
            val index = shots.indexOfFirst { it.timeUs > time }.let { if (it < 0) shots.size else it }
            if (shots.none { it.timeUs == time }) shots.add(index, Shot(time, thumb))
        }
    }

    fun saveAll() {
        val media = picked ?: return
        val src = source ?: return
        val times = shots.map { it.timeUs }
        if (times.isEmpty()) return
        fun run() {
            job = scope.launch {
                saving = 0 to times.size
                val count = try {
                    saveFrames(context, media.uri, times, frameSide(src.width, src.height), format) { done -> saving = done to times.size }
                } finally {
                    saving = null
                }
                if (count > 0) shots.clear()
                Toast.makeText(context, context.resources.getQuantityString(R.plurals.framecap_saved, count, count), Toast.LENGTH_SHORT).show()
            }
        }
        if (storage.ready { run() }) run()
    }

    if (asking) AlertDialog(
        onDismissRequest = { asking = false },
        title = { Text(stringResource(R.string.framecap_discard_title, shots.size), color = Am.Navy) },
        dismissButton = { TextButton({ asking = false }) { Text(stringResource(R.string.cancel), color = Am.Navy) } },
        confirmButton = { TextButton({ asking = false; shots.clear(); onClose() }) { Text(stringResource(R.string.framecap_discard), color = Am.Navy) } },
        containerColor = Am.Card,
    )

    val busy = saving != null
    ToolSheet(stringResource(R.string.framecap_title), { leave() }, bottom = {
        Column(Modifier.fillMaxWidth().background(Am.Card).padding(16.dp)) {
            val status = saving?.let { (done, total) -> stringResource(R.string.framecap_saving, done, total) }
                ?: when {
                    picked == null -> stringResource(R.string.tools_choose_first)
                    shots.isEmpty() -> stringResource(R.string.framecap_hint)
                    else -> stringResource(R.string.framecap_count, shots.size)
                }
            Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.CenterStart) { BarText(status, Am.Navy) }
            Spacer(Modifier.height(10.dp))
            if (busy) BigButton(stringResource(R.string.cancel), Modifier.fillMaxWidth(), primary = false) { job?.cancel() }
            else BigButton(stringResource(R.string.framecap_save_all, shots.size), Modifier.fillMaxWidth(), HomeIcons.Download, enabled = shots.isNotEmpty()) { saveAll() }
        }
    }, side = {
        Label(stringResource(R.string.framecap_format))
        Chips(FrameFormat.entries.map { it.label }, format.ordinal, !busy) { format = FrameFormat.entries[it] }
        Spacer(Modifier.height(24.dp))
    }) {
        Spacer(Modifier.height(20.dp))
        PickCard(picked, pick.problem != null, !busy && shots.isEmpty(), R.string.tools_choose_video, pick.problem ?: R.string.compress_unreadable) { pick.launch() }
        if (picked != null && source != null && duration > 0) {
            Spacer(Modifier.height(16.dp))
            FrameScrubber(picked.uri, duration, at, shots.map { it.timeUs }, !busy) { at = it }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                StepButton(ExportIcons.Prev, stringResource(R.string.framecap_prev), !busy && at > 0) { at = (at - step).coerceAtLeast(0) }
                Row(
                    Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(28.dp)).background(if (busy) Am.HomeLine else Am.Accent)
                        .clickable(enabled = !busy) { capture() },
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(22.dp).clip(CircleShape).border(3.dp, Am.OnAccent, CircleShape).padding(5.dp).clip(CircleShape).background(Am.OnAccent))
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.framecap_capture), color = Am.OnAccent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                StepButton(ExportIcons.Next, stringResource(R.string.framecap_next), !busy && at < duration - step) { at = (at + step).coerceAtMost((duration - 1).coerceAtLeast(0)) }
            }
            SectionTitle(stringResource(R.string.framecap_count, shots.size))
            if (shots.isEmpty()) Text(stringResource(R.string.framecap_none), Modifier.padding(bottom = 16.dp), color = Am.HomeDim, fontSize = 14.sp)
            else ShotRow(shots, at, !busy, onPick = { at = it.timeUs }, onRemove = { shots.remove(it) })
        }
    }
}

@Composable
private fun StepButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(56.dp).alpha(if (enabled) 1f else 0.4f).clip(CircleShape).background(Am.Card).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, Modifier.size(20.dp), tint = Am.Navy)
    }
}

@Composable
private fun FrameScrubber(uri: Uri, durationUs: Long, at: Long, marks: List<Long>, enabled: Boolean, onSeek: (Long) -> Unit) = key(uri) {
    val context = LocalContext.current
    val seek by rememberUpdatedState(onSeek)
    val strip by produceState<List<ImageBitmap?>>(emptyList(), uri, durationUs) {
        value = videoStills(context, uri, List(STRIP_FRAMES) { ((it + 0.5) * durationUs / STRIP_FRAMES).toLong() }, 160)
    }
    var still by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(at) {
        delay(90)
        videoStills(context, uri, listOf(at), 720, exact = true).firstOrNull()?.let { still = it }
    }
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(12.dp)).background(Am.Scrim), contentAlignment = Alignment.Center) {
            still?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            Text(
                clock(at), Modifier.align(Alignment.BottomStart).padding(10.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 8.dp, vertical = 4.dp),
                color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(10.dp)).background(Am.Card).alpha(if (enabled) 1f else 0.5f)
                .pointerInput(durationUs, enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val width = size.width.toFloat().coerceAtLeast(1f)
                        fun place(x: Float) = seek((x / width * durationUs).toLong().coerceIn(0, (durationUs - 1).coerceAtLeast(0)))
                        place(down.position.x)
                        drag(down.id) { change ->
                            place(change.position.x)
                            change.consume()
                        }
                    }
                },
        ) {
            Ltr {
                Row(Modifier.fillMaxSize()) {
                    repeat(STRIP_FRAMES) { i ->
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            strip.getOrNull(i)?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                        }
                    }
                }
                Canvas(Modifier.fillMaxSize()) {
                    for (mark in marks) {
                        val x = mark.toFloat() / durationUs * size.width
                        drawCircle(Am.Accent, 4.dp.toPx(), Offset(x, size.height - 6.dp.toPx()))
                    }
                    val x = at.toFloat() / durationUs * size.width
                    val line = 4.dp.toPx()
                    drawRoundRect(Color.Black.copy(alpha = 0.35f), Offset(x - line / 2 - 1.dp.toPx(), 0f), Size(line + 2.dp.toPx(), size.height), CornerRadius(3.dp.toPx()))
                    drawRoundRect(Color.White, Offset(x - line / 2, 0f), Size(line, size.height), CornerRadius(2.dp.toPx()))
                }
            }
        }
    }
}

@Composable
private fun ShotRow(shots: List<Shot>, at: Long, enabled: Boolean, onPick: (Shot) -> Unit, onRemove: (Shot) -> Unit) {
    val list = rememberLazyListState()
    LaunchedEffect(shots.size) { if (shots.isNotEmpty()) list.animateScrollToItem(shots.lastIndex) }
    LazyRow(state = list, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        itemsIndexed(shots, key = { _, shot -> shot.timeUs }) { i, shot ->
            Column(Modifier.width(96.dp)) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(Am.Scrim)
                        .then(if (shot.timeUs == at) Modifier.border(2.dp, Am.Accent, RoundedCornerShape(10.dp)) else Modifier)
                        .clickable(enabled = enabled) { onPick(shot) },
                ) {
                    shot.thumb?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    Text(
                        "${i + 1}", Modifier.align(Alignment.TopStart).padding(5.dp).size(22.dp).clip(CircleShape).background(Am.Accent).padding(top = 2.dp),
                        color = Am.OnAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(4.dp).size(26.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f))
                            .clickable(enabled = enabled) { onRemove(shot) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(HomeIcons.Close, stringResource(R.string.gallery_remove), Modifier.size(14.dp), tint = Color.White)
                    }
                }
                Text(clock(shot.timeUs), Modifier.padding(top = 4.dp), color = Am.HomeMid, fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}
