package app.absentmotion.ui.editor

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.canScreenshot
import app.absentmotion.editor.captureFrame
import app.absentmotion.editor.saveFrameShots
import app.absentmotion.platform.BackHandler
import app.absentmotion.ui.home.Cap
import app.absentmotion.ui.home.HomeIcons
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The frame-capture tray that replaces the clip tools while capturing: move the playhead, tap the
 * shutter as many times as you like, check the frames in the strip, then save them all in one go.
 */
@Composable
internal fun FrameCaptureTray(state: EditorState, modifier: Modifier) {
    val context = LocalContext.current
    val scope = LocalEditorScope.current
    val caps = state.frameCaptures
    val storage = app.absentmotion.platform.rememberStorageAccess()
    var confirmDiscard by remember { mutableStateOf(false) }
    val canShoot by remember(state) { derivedStateOf { state.canScreenshot(state.selected) } }
    val failed = stringResource(R.string.screenshot_failed)
    val close = { if (caps.shots.isEmpty()) caps.open = false else confirmDiscard = true }
    BackHandler(onBack = close)

    fun shoot() {
        if (caps.busy || !canShoot) return
        state.playing = false
        caps.busy = true
        scope.launch {
            val shot = try { state.captureFrame(context) } catch (e: CancellationException) { throw e } catch (_: Exception) { null } finally { caps.busy = false }
            if (shot == null) Toast.makeText(context, failed, Toast.LENGTH_SHORT).show()
        }
    }

    fun save() {
        if (caps.busy || caps.shots.isEmpty()) return
        caps.busy = true
        scope.launch {
            val added = try { state.saveFrameShots(context) } catch (e: CancellationException) { throw e } catch (_: Exception) { 0 } finally { caps.busy = false }
            Toast.makeText(context, if (added > 0) context.resources.getQuantityString(R.plurals.framecap_saved, added, added) else failed, Toast.LENGTH_SHORT).show()
            if (caps.shots.isEmpty()) caps.open = false
        }
    }

    Column(modifier.background(Am.Panel).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = close), contentAlignment = Alignment.Center) {
                Icon(HomeIcons.Close, stringResource(R.string.cancel), Modifier.size(22.dp), tint = Am.OnDark)
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.framecap_title), color = Am.OnDark, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.framecap_count, caps.shots.size), color = Am.OnDarkDim, fontSize = 12.sp)
            }
            val ready = caps.shots.isNotEmpty() && !caps.busy
            Box(
                Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(if (ready) Cap.Accent else Am.Well)
                    .clickable(enabled = ready) { if (storage.ready { save() }) save() }.padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.framecap_save_all, caps.shots.size), color = if (ready) Cap.OnAccent else Am.OnDarkDim, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(12.dp))
        val strip = rememberLazyListState()
        LaunchedEffect(caps.shots.size) { if (caps.shots.isNotEmpty()) strip.animateScrollToItem(caps.shots.lastIndex) }
        Box(Modifier.fillMaxWidth().height(84.dp)) {
            if (caps.shots.isEmpty()) Text(
                stringResource(R.string.framecap_hint), Modifier.align(Alignment.Center).padding(horizontal = 12.dp),
                color = Am.OnDarkDim, fontSize = 13.sp, lineHeight = 17.sp, textAlign = TextAlign.Center,
            )
            else LazyRow(Modifier.fillMaxSize(), strip, contentPadding = PaddingValues(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(caps.shots, key = { _, it -> it.file.path }) { i, shot ->
                    Box(Modifier.size(width = 72.dp, height = 78.dp)) {
                        Column(Modifier.align(Alignment.BottomStart)) {
                            Box(Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black)) {
                                shot.thumb?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                                Text("${i + 1}", Modifier.align(Alignment.TopStart).background(Color(0x99000000), RoundedCornerShape(bottomEnd = 6.dp)).padding(horizontal = 5.dp),
                                    color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                            Text(timecode(shot.timeMs), Modifier.width(64.dp), color = Am.OnDarkDim, fontSize = 10.sp, textAlign = TextAlign.Center, maxLines = 1)
                        }
                        val remove = stringResource(R.string.gallery_remove)
                        Box(
                            Modifier.align(Alignment.TopEnd).offset(y = (-2).dp).size(20.dp).clip(CircleShape).background(Color(0xE6000000))
                                .semantics { contentDescription = remove }.clickable(enabled = !caps.busy) { caps.remove(shot) },
                            contentAlignment = Alignment.Center,
                        ) { Icon(HomeIcons.Close, null, Modifier.size(12.dp), tint = Color.White) }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        val capture = stringResource(R.string.framecap_capture)
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(64.dp).alpha(if (canShoot && !caps.busy) 1f else 0.4f).clip(CircleShape).border(3.dp, Color.White, CircleShape).padding(6.dp)
                    .clip(CircleShape).background(Color.White).semantics { contentDescription = capture }
                    .clickable(enabled = canShoot && !caps.busy) { shoot() },
                contentAlignment = Alignment.Center,
            ) { Icon(PanelIcons.Screenshot, null, Modifier.size(24.dp), tint = Color.Black) }
            Spacer(Modifier.height(6.dp))
            Text(if (canShoot) capture else stringResource(R.string.framecap_not_video), color = Am.OnDarkDim, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(8.dp))
    }

    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false },
        title = { Text(stringResource(R.string.framecap_discard_title, caps.shots.size), color = Am.OnDark) },
        confirmButton = {
            TextButton({ confirmDiscard = false; caps.discard(); caps.open = false }) { Text(stringResource(R.string.framecap_discard), color = Am.Accent) }
        },
        dismissButton = { TextButton({ confirmDiscard = false }) { Text(stringResource(R.string.cancel), color = Am.Accent) } },
        containerColor = Am.Field,
    )
}

private fun timecode(ms: Int): String {
    val s = ms / 1000
    val f = (ms % 1000) / 10
    return "%d:%02d.%02d".format(s / 60, s % 60, f)
}
